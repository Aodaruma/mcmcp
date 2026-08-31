package dev.aod.mcmcp.routine;

import dev.aod.mcmcp.mixin.client.BlockItemPlacementInvoker;
import dev.aod.mcmcp.observation.MinecraftObservationService;
import dev.aod.mcmcp.runtime.ClientPredictionSignals;
import dev.aod.mcmcp.runtime.ClientReconciliationSignals;
import dev.aod.mcmcp.runtime.WorldSessionTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Minecraft 26.2 adapter for one exact, centered jump-and-place operation. */
public final class MinecraftPillarUpPort implements PillarUpPort {
    private static final float MIN_SAFE_HEALTH = 10.0F;
    private static final double THREAT_RADIUS = 8.0D;
    private static final double CENTER_EPSILON = 0.15D;
    private static final double Y_EPSILON = 1.0e-4D;
    private static final double TARGET_CLEAR_EPSILON = 1.0e-4D;
    private static final float CONTROL_DRIFT_EPSILON = 0.25F;
    private static final float AIM_EPSILON = 0.25F;
    private static final float MAX_TURN_PER_TICK = 8.0F;

    private final Supplier<Minecraft> minecraftSupplier;
    private final Supplier<WorldSessionTracker.Snapshot> sessionSupplier;
    private final MinecraftObservationService observations;
    private final ClientPredictionSignals predictions;
    private final ClientReconciliationSignals reconciliations;
    private final Map<Handle, State> active = new IdentityHashMap<>();

    public MinecraftPillarUpPort(
            Supplier<Minecraft> minecraftSupplier,
            Supplier<WorldSessionTracker.Snapshot> sessionSupplier,
            MinecraftObservationService observations,
            ClientPredictionSignals predictions,
            ClientReconciliationSignals reconciliations) {
        this.minecraftSupplier = Objects.requireNonNull(minecraftSupplier, "minecraftSupplier");
        this.sessionSupplier = Objects.requireNonNull(sessionSupplier, "sessionSupplier");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.predictions = Objects.requireNonNull(predictions, "predictions");
        this.reconciliations = Objects.requireNonNull(reconciliations, "reconciliations");
    }

    @Override
    public Handle begin(KnownPillarUpRequest request, long leaseExpiresAtClientTick) {
        Minecraft minecraft = assertClientThread();
        Objects.requireNonNull(request, "request");
        WorldSessionTracker.Snapshot session = requireSession();
        if (leaseExpiresAtClientTick <= session.clientTick()) {
            throw new IllegalArgumentException("pillar lease has already expired");
        }
        LocalPlayer player = Objects.requireNonNull(minecraft.player);
        ClientLevel level = Objects.requireNonNull(minecraft.level);
        BlockPos support = blockPos(request.support());
        BlockPos target = support.above();
        BlockPos landingHead = target.above(2);
        if (!request.support().dimension().equals(session.dimension())
                || !request.support().dimension().equals(
                        level.dimension().identifier().toString())
                || !level.isInsideBuildHeight(support.getY())
                || !level.isInsideBuildHeight(landingHead.getY())
                || !allLoaded(level, support, target, target.above(), landingHead)
                || !allWithinBorder(level, support, target, target.above(), landingHead)) {
            throw new IllegalStateException("pillar cells are unknown or outside bounds");
        }
        SafeConstructionBlockPolicy.requireExpectedStateAndItem(
                request.sourceState(), request.item());
        requireInitialGeometry(minecraft, request, player, level, support, target);

        int sourceSlot = findEligibleInventorySlot(player, request.item());
        if (sourceSlot < 0) throw new IllegalStateException("pillar item is unavailable");
        int originalSlot = player.getInventory().getSelectedSlot();
        var before = reconciliations.bindAndSnapshot(level, session.worldSessionId());
        int inventoryBefore = eligibleInventoryCount(player, request.item());
        boolean staging = sourceSlot >= Inventory.getSelectionSize();
        if (staging && !MinecraftSemanticActionPort.stageInventorySlotIntoSelectedHotbar(
                minecraft,
                player,
                sourceSlot,
                stack -> eligibleStack(stack, request.item()))) {
            throw new IllegalStateException("pillar item could not be staged");
        }
        int ownedSlot = staging ? originalSlot : sourceSlot;
        Handle handle = new Handle(
                UUID.randomUUID(), session.clientTick(), leaseExpiresAtClientTick);
        State state = new State(
                request,
                session.worldSessionId(),
                player,
                player.getHealth(),
                player.getY(),
                player.getYRot(),
                player.getXRot(),
                originalSlot,
                ownedSlot,
                inventoryBefore,
                staging ? before.selectedSlotInventoryRevision() : -1L);
        active.put(handle, state);
        try {
            if (!staging) state.selectOwnedSlot();
            state.turnToward(aimPoint(support));
            return handle;
        } catch (RuntimeException | LinkageError failure) {
            active.remove(handle);
            state.close();
            throw failure;
        }
    }

    @Override
    public void maintain(Handle handle) {
        State state = requireState(handle);
        WorldSessionTracker.Snapshot session = requireSession();
        if (session.clientTick() >= handle.leaseExpiresAtClientTick()) {
            state.fail("pillar_lease_expired");
            return;
        }
        try {
            Minecraft minecraft = assertClientThread();
            requireCurrentSafety(minecraft, state);
            if (!refreshStaging(state)) return;
            state.requireControls();
            state.selectOwnedSlot();
            if (!state.placed) state.turnToward(aimPoint(blockPos(state.request.support())));
            if (state.movement != null) {
                boolean cleared = targetCleared(state);
                state.movement.setDesired(
                        handle.attemptId(),
                        cleared ? java.util.Set.of()
                                : java.util.Set.of(MovementInputLease.MovementKey.JUMP));
                if (!state.movement.heartbeat(
                        handle.attemptId(), System.nanoTime(), leaseHorizon(
                                session.clientTick(), handle.leaseExpiresAtClientTick()))) {
                    state.fail("pillar_input_lease_expired");
                }
            }
        } catch (RuntimeException | LinkageError failure) {
            state.fail("pillar_safety_changed");
        }
    }

    @Override
    public Evidence evidence(Handle handle) {
        State state = requireState(handle);
        WorldSessionTracker.Snapshot session = requireSession();
        boolean prepared = false;
        boolean cleared = false;
        boolean blockConfirmed = false;
        boolean inventoryConfirmed = false;
        boolean yConfirmed = false;
        if (state.failure == null) {
            try {
                Minecraft minecraft = assertClientThread();
                requireCurrentSafety(minecraft, state);
                boolean staged = refreshStaging(state);
                prepared = !state.jumping && staged
                        && state.controlsHeld()
                        && state.ownedStackSelected()
                        && state.aligned(aimPoint(blockPos(state.request.support())))
                        && actualSupportHit(minecraft, blockPos(state.request.support())) != null;
                cleared = state.jumping && targetCleared(state);
                if (state.prediction != null) {
                    state.prediction.captureIssuedPredictions();
                    ClientPredictionSignals.Confirmation<BlockState> confirmation =
                            state.prediction.confirmation(after ->
                                    state.request.sourceState().equals(fingerprint(after)));
                    if (confirmation.status()
                            == ClientPredictionSignals.ConfirmationStatus.SERVER_STATE_MISMATCH) {
                        state.fail("pillar_block_postcondition_mismatch");
                    } else if (confirmation.status()
                            == ClientPredictionSignals.ConfirmationStatus.INCOMPATIBLE) {
                        state.fail("pillar_prediction_bridge_incompatible");
                    }
                    blockConfirmed = confirmation.serverConfirmed()
                            && state.request.sourceState().equals(
                                    fingerprint(Objects.requireNonNull(minecraft.level)
                                            .getBlockState(blockPos(state.request.support()).above())));
                    inventoryConfirmed = refreshInventoryConfirmation(state);
                    if (state.inventoryMismatch) {
                        state.fail("pillar_inventory_postcondition_mismatch");
                    }
                    yConfirmed = finalPoseConfirmed(state);
                }
            } catch (RuntimeException | LinkageError failure) {
                state.fail("pillar_safety_changed");
            }
        }
        return new Evidence(
                handle.attemptId(), session.clientTick(), prepared, cleared,
                blockConfirmed, inventoryConfirmed, yConfirmed, state.failure);
    }

    @Override
    public void startJump(Handle handle) {
        State state = requireState(handle);
        if (state.failure != null || state.jumping || state.placed) {
            throw new IllegalStateException("pillar jump cannot start");
        }
        Minecraft minecraft = assertClientThread();
        requireCurrentSafety(minecraft, state);
        if (!refreshStaging(state)
                || !state.ownedStackSelected()
                || !state.aligned(aimPoint(blockPos(state.request.support())))
                || actualSupportHit(minecraft, blockPos(state.request.support())) == null) {
            throw new IllegalStateException("pillar preparation is not complete");
        }
        state.movement = MovementInputLease.acquire(
                minecraft,
                handle.attemptId(),
                System.nanoTime(),
                leaseHorizon(requireSession().clientTick(), handle.leaseExpiresAtClientTick()));
        state.movement.setDesired(
                handle.attemptId(), java.util.Set.of(MovementInputLease.MovementKey.JUMP));
        state.movement.heartbeat(
                handle.attemptId(),
                System.nanoTime(),
                leaseHorizon(requireSession().clientTick(), handle.leaseExpiresAtClientTick()));
        state.jumping = true;
    }

    @Override
    public void placeOnce(Handle handle) {
        State state = requireState(handle);
        if (state.failure != null || !state.jumping || state.placed || !targetCleared(state)) {
            throw new IllegalStateException("pillar placement is not ready");
        }
        Minecraft minecraft = assertClientThread();
        LocalPlayer player = Objects.requireNonNull(minecraft.player);
        ClientLevel level = Objects.requireNonNull(minecraft.level);
        requireCurrentSafety(minecraft, state);
        state.movement.setDesired(handle.attemptId(), java.util.Set.of());
        state.movement.heartbeat(
                handle.attemptId(),
                System.nanoTime(),
                leaseHorizon(requireSession().clientTick(), handle.leaseExpiresAtClientTick()));
        BlockPos support = blockPos(state.request.support());
        BlockPos target = support.above();
        BlockHitResult hit = actualSupportHit(minecraft, support);
        if (hit == null || !level.getBlockState(target).isAir()) {
            throw new IllegalStateException("pillar target or aim changed");
        }
        requireExactSupport(state, level, support);
        ItemStack stack = player.getMainHandItem();
        if (!eligibleStack(stack, state.request.item())) {
            throw new IllegalStateException("pillar item selection changed");
        }
        BlockItem item = (BlockItem) stack.getItem();
        BlockPlaceContext context = item.updatePlacementContext(new BlockPlaceContext(
                new UseOnContext(player, InteractionHand.MAIN_HAND, hit)));
        if (context == null || !context.canPlace() || !target.equals(context.getClickedPos())) {
            throw new IllegalStateException("pillar placement context is not exact");
        }
        BlockState predicted = ((BlockItemPlacementInvoker) (Object) item)
                .mcmcp$invokeGetPlacementState(context);
        if (predicted == null
                || !state.request.sourceState().equals(fingerprint(predicted))) {
            throw new IllegalStateException("pillar placement state is not exact");
        }
        SafeConstructionBlockPolicy.requireLiveState(
                predicted, level.getBlockEntity(target) != null);
        state.inventoryAtDispatch = eligibleInventoryCount(player, state.request.item());
        var reconciliation = reconciliations.bindAndSnapshot(
                level, requireSession().worldSessionId());
        state.inventoryRevisionAtDispatch = reconciliation.selectedSlotInventoryRevision();
        state.prediction = predictions.begin(level, target, requireSession().clientTick());
        int before = state.prediction.sequenceBeforePrediction();
        var result = SafePlacementSupportPolicy.dispatchUseIfAllowed(
                level.getBlockState(support),
                level.getBlockEntity(support) != null,
                () -> Objects.requireNonNull(minecraft.gameMode)
                        .useItemOn(player, InteractionHand.MAIN_HAND, hit));
        int after = state.prediction.captureIssuedPredictions();
        if (after != before + 1 || !result.consumesAction()) {
            throw new IllegalStateException("pillar placement did not dispatch exactly once");
        }
        state.placed = true;
    }

    @Override
    public void release(Handle handle) {
        assertClientThread();
        State state = active.get(Objects.requireNonNull(handle, "handle"));
        if (state == null) return;
        state.close();
        if (state.closed()) active.remove(handle);
    }

    /** Releases all attempt-owned state on disconnect, level replacement, and shutdown. */
    public void clearSession() {
        assertClientThread();
        RuntimeException failure = null;
        for (State state : new ArrayList<>(active.values())) {
            try {
                state.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        active.entrySet().removeIf(entry -> entry.getValue().closed());
        if (failure != null) throw failure;
    }

    private boolean refreshStaging(State state) {
        if (state.stagingRevision < 0L) return true;
        ClientLevel level = Objects.requireNonNull(assertClientThread().level);
        var current = reconciliations.bindAndSnapshot(level, state.worldSessionId);
        if (current.selectedSlotInventoryRevision() <= state.stagingRevision) return false;
        if (!state.ownedStackSelected()
                || eligibleInventoryCount(state.player, state.request.item())
                        != state.initialInventoryCount) {
            state.fail("pillar_inventory_staging_mismatch");
            return false;
        }
        state.stagingRevision = -1L;
        return true;
    }

    private boolean refreshInventoryConfirmation(State state) {
        if (!state.placed || state.inventoryRevisionAtDispatch < 0L) return false;
        ClientLevel level = Objects.requireNonNull(assertClientThread().level);
        var current = reconciliations.bindAndSnapshot(level, state.worldSessionId);
        if (current.selectedSlotInventoryRevision() <= state.inventoryRevisionAtDispatch) {
            return false;
        }
        int count = eligibleInventoryCount(state.player, state.request.item());
        if (state.inventoryAtDispatch > 0 && count == state.inventoryAtDispatch - 1) return true;
        if (count != state.inventoryAtDispatch) state.inventoryMismatch = true;
        return false;
    }

    private void requireInitialGeometry(
            Minecraft minecraft,
            KnownPillarUpRequest request,
            LocalPlayer player,
            ClientLevel level,
            BlockPos support,
            BlockPos target) {
        if (!universalSafetyClear(minecraft, player, level, player.getHealth())
                || !player.onGround()
                || player.isPassenger()
                || player.isInWater()
                || player.isInLava()
                || !centered(player, support)
                || Math.abs(player.getY() - target.getY()) > Y_EPSILON) {
            throw new IllegalStateException("pillar player pose is unsafe");
        }
        requireExactSupport(request, level, support);
        if (!trajectoryClear(level, player, target)) {
            throw new IllegalStateException("pillar trajectory is not clear");
        }
    }

    private void requireCurrentSafety(Minecraft minecraft, State state) {
        WorldSessionTracker.Snapshot session = requireSession();
        LocalPlayer player = Objects.requireNonNull(minecraft.player);
        ClientLevel level = Objects.requireNonNull(minecraft.level);
        BlockPos support = blockPos(state.request.support());
        BlockPos target = support.above();
        if (player != state.player
                || !state.worldSessionId.equals(session.worldSessionId())
                || !universalSafetyClear(minecraft, player, level, state.initialHealth)
                || player.isPassenger()
                || player.isInWater()
                || player.isInLava()
                || !centered(player, support)
                || player.getY() < state.initialY - 0.05D
                || player.getY() > state.initialY + 1.5D
                || !allLoaded(level, support, target, target.above(), target.above(2))
                || !allWithinBorder(level, support, target, target.above(), target.above(2))) {
            throw new IllegalStateException("pillar safety changed");
        }
        requireExactSupport(state, level, support);
        if (!state.placed && !trajectoryClear(level, player, target)) {
            throw new IllegalStateException("pillar trajectory changed");
        }
        if (state.placed && (!clearAir(level, target.above())
                || !clearAir(level, target.above(2)))) {
            throw new IllegalStateException("pillar landing changed");
        }
    }

    private boolean universalSafetyClear(
            Minecraft minecraft,
            LocalPlayer player,
            ClientLevel level,
            float initialHealth) {
        return !minecraft.isPaused()
                && minecraft.gui.screen() == null
                && minecraft.gui.overlay() == null
                && minecraft.getConnection() != null
                && minecraft.gameMode != null
                && minecraft.gameMode.getPlayerMode() == GameType.SURVIVAL
                && player.containerMenu == player.inventoryMenu
                && !player.isUsingItem()
                && player.isAlive()
                && !player.isDeadOrDying()
                && player.getHealth() >= MIN_SAFE_HEALTH
                && player.getHealth() + 0.001F >= initialHealth
                && player.hurtTime == 0
                && player.getRemainingFireTicks() <= 0
                && visibleThreatClear(minecraft, player, level);
    }

    private boolean visibleThreatClear(
            Minecraft minecraft, LocalPlayer player, ClientLevel level) {
        return level.getEntities(player, player.getBoundingBox().inflate(THREAT_RADIUS),
                        entity -> entity.isAlive() && (entity instanceof Enemy
                                || entity instanceof Mob mob && mob.getTarget() == player))
                .stream().noneMatch(entity -> observations.isEntityCurrentlyVisible(
                        minecraft, entity, THREAT_RADIUS));
    }

    private static boolean trajectoryClear(
            ClientLevel level, LocalPlayer player, BlockPos target) {
        if (!clearAir(level, target)
                || !clearAir(level, target.above())
                || !clearAir(level, target.above(2))) return false;
        AABB swept = player.getBoundingBox().expandTowards(0.0D, 1.0D, 0.0D);
        if (!level.noCollision(player, swept)) return false;
        return level.getEntities(player, swept, entity -> entity.isAlive()
                        && !entity.isSpectator()
                        && (entity instanceof Mob || entity instanceof Player))
                .isEmpty();
    }

    private static boolean clearAir(ClientLevel level, BlockPos position) {
        BlockState state = level.getBlockState(position);
        return state.isAir()
                && state.getFluidState().isEmpty()
                && state.getCollisionShape(level, position).isEmpty();
    }

    private static void requireExactSupport(
            KnownPillarUpRequest request, ClientLevel level, BlockPos support) {
        requireExactSupport(request.expectedSupport(), level, support);
    }

    private static void requireExactSupport(State state, ClientLevel level, BlockPos support) {
        requireExactSupport(state.request.expectedSupport(), level, support);
    }

    private static void requireExactSupport(
            BlockStateFingerprint expected, ClientLevel level, BlockPos support) {
        BlockState live = level.getBlockState(support);
        SafePlacementSupportPolicy.requireLiveState(
                live, level.getBlockEntity(support) != null);
        if (!expected.equals(fingerprint(live))
                || !Block.isShapeFullBlock(live.getCollisionShape(level, support))
                || !live.isFaceSturdy(level, support, Direction.UP)) {
            throw new IllegalStateException("pillar support changed");
        }
    }

    private static boolean targetCleared(State state) {
        BlockPos target = blockPos(state.request.support()).above();
        return state.player.getBoundingBox().minY
                >= target.getY() + 1.0D - TARGET_CLEAR_EPSILON;
    }

    private static boolean finalPoseConfirmed(State state) {
        BlockPos support = blockPos(state.request.support());
        return state.player.onGround()
                && centered(state.player, support)
                && Math.abs(state.player.getY() - (state.initialY + 1.0D)) <= 0.05D;
    }

    private static boolean centered(LocalPlayer player, BlockPos support) {
        return Math.abs(player.getX() - (support.getX() + 0.5D)) <= CENTER_EPSILON
                && Math.abs(player.getZ() - (support.getZ() + 0.5D)) <= CENTER_EPSILON;
    }

    private static BlockHitResult actualSupportHit(Minecraft minecraft, BlockPos support) {
        LocalPlayer player = Objects.requireNonNull(minecraft.player);
        HitResult result = player.pick(player.blockInteractionRange(), 1.0F, false);
        if (!(result instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK
                || !support.equals(hit.getBlockPos())
                || hit.getDirection() != Direction.UP) return null;
        return hit;
    }

    private static Vec3 aimPoint(BlockPos support) {
        return new Vec3(support.getX() + 0.75D, support.getY() + 1.0D,
                support.getZ() + 0.5D);
    }

    private static int findEligibleInventorySlot(LocalPlayer player, String item) {
        Inventory inventory = player.getInventory();
        return MinecraftSemanticActionPort.firstPreparingSlot(
                inventory.getSelectedSlot(), slot -> eligibleStack(inventory.getItem(slot), item));
    }

    private static int eligibleInventoryCount(LocalPlayer player, String item) {
        Inventory inventory = player.getInventory();
        int limit = Math.min(Inventory.INVENTORY_SIZE, inventory.getContainerSize());
        int count = 0;
        for (int slot = 0; slot < limit; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (eligibleStack(stack, item)) count = Math.addExact(count, stack.getCount());
        }
        return count;
    }

    private static boolean eligibleStack(ItemStack stack, String item) {
        return !stack.isEmpty()
                && item.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
                && MinecraftApplyBlockPlanPort.eligibleBlockStack(stack);
    }

    private State requireState(Handle handle) {
        assertClientThread();
        State state = active.get(Objects.requireNonNull(handle, "handle"));
        if (state == null) throw new IllegalStateException("pillar handle is not active");
        return state;
    }

    private Minecraft assertClientThread() {
        Minecraft minecraft = Objects.requireNonNull(minecraftSupplier.get(), "minecraft");
        if (!minecraft.isSameThread()) {
            throw new IllegalStateException("pillar adapter must run on the client thread");
        }
        return minecraft;
    }

    private WorldSessionTracker.Snapshot requireSession() {
        WorldSessionTracker.Snapshot session = sessionSupplier.get();
        if (session == null || !session.worldReady() || session.worldSessionId() == null) {
            throw new IllegalStateException("pillar world session is unavailable");
        }
        return session;
    }

    private static boolean allLoaded(ClientLevel level, BlockPos... positions) {
        for (BlockPos position : positions) if (!level.isLoaded(position)) return false;
        return true;
    }

    private static boolean allWithinBorder(ClientLevel level, BlockPos... positions) {
        for (BlockPos position : positions) {
            if (!level.getWorldBorder().isWithinBounds(position)) return false;
        }
        return true;
    }

    private static Duration leaseHorizon(long clientTick, long deadlineTick) {
        long ticks = Math.max(1L, Math.min(40L, deadlineTick - clientTick));
        return Duration.ofMillis(Math.multiplyExact(ticks, 50L));
    }

    private static BlockPos blockPos(BlockTarget target) {
        return new BlockPos(target.x(), target.y(), target.z());
    }

    private static BlockStateFingerprint fingerprint(BlockState state) {
        var properties = new LinkedHashMap<String, String>();
        state.getValues().forEach(value ->
                properties.put(value.property().getName(), value.valueName()));
        return new BlockStateFingerprint(
                BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), properties);
    }

    private static Rotation rotationTo(Vec3 eye, Vec3 point) {
        Vec3 delta = point.subtract(eye);
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        return new Rotation(
                (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0D),
                (float) -Math.toDegrees(Math.atan2(delta.y, horizontal)));
    }

    private record Rotation(float yaw, float pitch) { }

    private final class State {
        private final KnownPillarUpRequest request;
        private final UUID worldSessionId;
        private final LocalPlayer player;
        private final float initialHealth;
        private final double initialY;
        private final float originalYaw;
        private final float originalPitch;
        private final int originalSlot;
        private final int ownedSlot;
        private final int initialInventoryCount;
        private float expectedYaw;
        private float expectedPitch;
        private int expectedSlot;
        private long stagingRevision;
        private MovementInputLease movement;
        private ClientPredictionSignals.PredictionAttempt prediction;
        private int inventoryAtDispatch = -1;
        private long inventoryRevisionAtDispatch = -1L;
        private boolean inventoryMismatch;
        private boolean jumping;
        private boolean placed;
        private String failure;
        private boolean movementReleased;
        private boolean slotRestored;
        private boolean viewRestored;
        private boolean predictionReleased;

        private State(
                KnownPillarUpRequest request,
                UUID worldSessionId,
                LocalPlayer player,
                float initialHealth,
                double initialY,
                float originalYaw,
                float originalPitch,
                int originalSlot,
                int ownedSlot,
                int initialInventoryCount,
                long stagingRevision) {
            this.request = request;
            this.worldSessionId = worldSessionId;
            this.player = player;
            this.initialHealth = initialHealth;
            this.initialY = initialY;
            this.originalYaw = originalYaw;
            this.originalPitch = originalPitch;
            this.originalSlot = originalSlot;
            this.ownedSlot = ownedSlot;
            this.initialInventoryCount = initialInventoryCount;
            this.stagingRevision = stagingRevision;
            expectedYaw = originalYaw;
            expectedPitch = originalPitch;
            expectedSlot = originalSlot;
        }

        private void fail(String value) {
            if (failure == null) failure = Objects.requireNonNull(value, "failure");
        }

        private void requireControls() {
            if (!controlsHeld()) throw new IllegalStateException("pillar controls changed");
        }

        private boolean controlsHeld() {
            return Math.abs(Mth.wrapDegrees(player.getYRot() - expectedYaw))
                            <= CONTROL_DRIFT_EPSILON
                    && Math.abs(player.getXRot() - expectedPitch) <= CONTROL_DRIFT_EPSILON
                    && player.getInventory().getSelectedSlot() == expectedSlot;
        }

        private void selectOwnedSlot() {
            requireControls();
            player.getInventory().setSelectedSlot(ownedSlot);
            expectedSlot = ownedSlot;
        }

        private boolean ownedStackSelected() {
            return player.getInventory().getSelectedSlot() == ownedSlot
                    && eligibleStack(player.getMainHandItem(), request.item());
        }

        private void turnToward(Vec3 point) {
            requireControls();
            Rotation desired = rotationTo(player.getEyePosition(), point);
            float yaw = Mth.clamp(Mth.wrapDegrees(desired.yaw() - player.getYRot()),
                    -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK);
            float pitch = Mth.clamp(desired.pitch() - player.getXRot(),
                    -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK);
            player.turn(yaw / 0.15D, pitch / 0.15D);
            expectedYaw = player.getYRot();
            expectedPitch = player.getXRot();
        }

        private boolean aligned(Vec3 point) {
            Rotation desired = rotationTo(player.getEyePosition(), point);
            return Math.abs(Mth.wrapDegrees(desired.yaw() - player.getYRot())) <= AIM_EPSILON
                    && Math.abs(desired.pitch() - player.getXRot()) <= AIM_EPSILON;
        }

        private void close() {
            RuntimeException failure = null;
            if (!movementReleased) {
                try {
                    if (movement != null) movement.close();
                    movementReleased = true;
                } catch (RuntimeException closeFailure) {
                    failure = closeFailure;
                }
            }
            if (!predictionReleased) {
                try {
                    if (prediction != null) prediction.close();
                    predictionReleased = true;
                } catch (RuntimeException closeFailure) {
                    if (failure == null) failure = closeFailure;
                    else failure.addSuppressed(closeFailure);
                }
            }
            if (!slotRestored) {
                try {
                    player.getInventory().setSelectedSlot(originalSlot);
                    slotRestored = true;
                } catch (RuntimeException closeFailure) {
                    if (failure == null) failure = closeFailure;
                    else failure.addSuppressed(closeFailure);
                }
            }
            if (!viewRestored) {
                try {
                    float yaw = Mth.wrapDegrees(originalYaw - player.getYRot());
                    float pitch = originalPitch - player.getXRot();
                    player.turn(yaw / 0.15D, pitch / 0.15D);
                    viewRestored = true;
                } catch (RuntimeException closeFailure) {
                    if (failure == null) failure = closeFailure;
                    else failure.addSuppressed(closeFailure);
                }
            }
            if (failure != null) throw failure;
        }

        private boolean closed() {
            return movementReleased && predictionReleased && slotRestored && viewRestored;
        }
    }
}
