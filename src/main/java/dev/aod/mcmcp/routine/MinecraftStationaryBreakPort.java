package dev.aod.mcmcp.routine;

import dev.aod.mcmcp.observation.MinecraftObservationService;
import dev.aod.mcmcp.observation.ObservedContext;
import dev.aod.mcmcp.observation.WorldMemory;
import dev.aod.mcmcp.runtime.ClientPredictionSignals;
import dev.aod.mcmcp.runtime.WorldSessionTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.time.Duration;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/** Minecraft 26.2 adapter for the deterministic stationary-break core. */
public final class MinecraftStationaryBreakPort implements StationaryBreakPort {
    private static final double MAX_POSITION_DRIFT_SQUARED = 0.01D * 0.01D;
    private static final float MAX_ROTATION_DRIFT = 0.25F;
    private static final float MIN_SAFE_HEALTH = 10.0F;
    private static final double THREAT_RADIUS = 8.0D;

    private final Supplier<Minecraft> minecraftSupplier;
    private final Supplier<WorldSessionTracker.Snapshot> sessionSupplier;
    private final WorldMemory memory;
    private final MinecraftObservationService observations;
    private final ClientPredictionSignals predictionSignals;
    private final Map<StationaryBreakRequest, Baseline> baselines = new IdentityHashMap<>();
    private final Map<AttackAttempt, ActiveAttempt> activeAttempts = new IdentityHashMap<>();

    public MinecraftStationaryBreakPort(
            Supplier<Minecraft> minecraftSupplier,
            Supplier<WorldSessionTracker.Snapshot> sessionSupplier,
            WorldMemory memory,
            MinecraftObservationService observations,
            ClientPredictionSignals predictionSignals) {
        this.minecraftSupplier = Objects.requireNonNull(minecraftSupplier, "minecraftSupplier");
        this.sessionSupplier = Objects.requireNonNull(sessionSupplier, "sessionSupplier");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.predictionSignals = Objects.requireNonNull(predictionSignals, "predictionSignals");
    }

    /** Captures the full visible source state used as the immutable action precondition. */
    public BlockStateFingerprint captureExpectedSource(BlockTarget target, Set<String> allowedBlocks) {
        assertClientThread();
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(allowedBlocks, "allowedBlocks");
        var level = requireTargetLevel(target);
        var position = blockPos(target);
        if (!crosshairOn(position) || !withinReach(position)) {
            throw new IllegalArgumentException("target must be the current in-reach crosshair block");
        }
        var state = level.getBlockState(position);
        SafeBreakSourcePolicy.requireLiveState(
                state, level.getBlockEntity(position) != null);
        var fingerprint = fingerprint(state);
        if (!allowedBlocks.contains(fingerprint.blockId())) {
            throw new IllegalArgumentException("current target block is not in allowed_blocks");
        }
        return fingerprint;
    }

    @Override
    public StationaryBreakFrame observe(StationaryBreakRequest request) {
        var minecraft = assertClientThread();
        Objects.requireNonNull(request, "request");
        var session = sessionSupplier.get();
        var player = minecraft.player;
        var level = minecraft.level;
        var gameMode = minecraft.gameMode;
        var targetPos = blockPos(request.target());
        boolean worldReady = session != null
                && session.worldReady()
                && level != null
                && player != null
                && gameMode != null
                && minecraft.getConnection() != null
                && request.target().dimension().equals(session.dimension())
                && request.target().dimension().equals(level.dimension().identifier().toString())
                && level.isLoaded(targetPos);

        if (!worldReady) {
            return unavailableFrame(session);
        }

        var baseline = baselines.computeIfAbsent(request, ignored -> new Baseline(
                player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot(),
                player.getInventory().getSelectedSlot(), player.getHealth()));
        boolean stable = baseline.matches(player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot(), player.getInventory().getSelectedSlot());
        boolean controlContextClear = !minecraft.isPaused()
                && minecraft.gui.screen() == null
                && minecraft.gui.overlay() == null
                && stable
                && !player.isUsingItem()
                && gameMode.getPlayerMode() == GameType.SURVIVAL;
        boolean alive = player.isAlive() && !player.isDeadOrDying();
        boolean healthSafe = alive
                && player.getHealth() >= MIN_SAFE_HEALTH
                && player.getHealth() + 0.001F >= baseline.health()
                && player.hurtTime == 0
                && player.getRemainingFireTicks() <= 0;
        boolean threatClear = level.getEntities(
                        player,
                        player.getBoundingBox().inflate(THREAT_RADIUS),
                        entity -> entity.isAlive()
                                && (entity instanceof Enemy
                                || entity instanceof Mob mob && mob.getTarget() == player))
                .stream()
                .noneMatch(entity -> observations.isEntityCurrentlyVisible(
                        minecraft, entity, THREAT_RADIUS));
        boolean crosshair = crosshairOn(targetPos);
        boolean inReach = withinReach(targetPos)
                && level.getWorldBorder().isWithinBounds(targetPos)
                && !player.blockActionRestricted(level, targetPos, gameMode.getPlayerMode())
                && level.getBlockState(targetPos).getDestroyProgress(player, level, targetPos) > 0.0F;
        Optional<BlockStateFingerprint> liveState = crosshair
                ? Optional.of(fingerprint(level.getBlockState(targetPos)))
                : Optional.empty();
        boolean inventorySynchronized = minecraft.gui.screen() == null
                && player.containerMenu == player.inventoryMenu;

        return new StationaryBreakFrame(
                session.clientTick(),
                memory.revision(),
                liveState,
                inventoryCount(request.goal().itemId()),
                inventorySynchronized,
                true,
                controlContextClear,
                alive,
                healthSafe,
                threatClear,
                inReach,
                crosshair);
    }

    @Override
    public AttackAttempt beginAttack(StationaryBreakRequest request, long leaseExpiresAtClientTick) {
        return beginAttack(request, leaseExpiresAtClientTick, true);
    }

    @Override
    public AttackAttempt beginAgentAttack(
            StationaryBreakRequest request, long leaseExpiresAtClientTick) {
        return beginAttack(request, leaseExpiresAtClientTick, false);
    }

    private AttackAttempt beginAttack(
            StationaryBreakRequest request,
            long leaseExpiresAtClientTick,
            boolean requireLegacyRoutineSafety) {
        var minecraft = assertClientThread();
        var frame = observe(request);
        if (!frame.worldReady() || !frame.playerAlive()
                || !frame.targetInReach() || !frame.crosshairOnTarget()
                || requireLegacyRoutineSafety && (!frame.controlContextClear() || !frame.healthSafe()
                        || !frame.visibleThreatClear())) {
            throw new IllegalStateException("stationary_break preconditions changed before attack");
        }
        var level = requireTargetLevel(request.target());
        var position = blockPos(request.target());
        SafeBreakSourcePolicy.requireLiveState(
                level.getBlockState(position), level.getBlockEntity(position) != null);
        if (frame.liveTargetState().filter(request.expectedSourceState()::matches).isEmpty()) {
            throw new IllegalStateException("stationary_break preconditions changed before attack");
        }
        if (leaseExpiresAtClientTick <= frame.clientTick()) {
            throw new IllegalArgumentException("attack lease is already expired");
        }

        var prediction = predictionSignals.begin(level, position, frame.clientTick());
        AttackInputLease lease = null;
        try {
            int sequenceBefore = prediction.sequenceBeforePrediction();
            int expectedSequence = sequenceBefore + 1;
            lease = AttackInputLease.acquire(
                    minecraft,
                    System.nanoTime(),
                    leaseHorizon(frame.clientTick(), leaseExpiresAtClientTick));
            var attempt = new AttackAttempt(
                    Integer.toUnsignedLong(expectedSequence),
                    request.target(),
                    leaseExpiresAtClientTick);
            activeAttempts.put(attempt, new ActiveAttempt(
                    prediction, lease, request.expectedSourceState(), false, false));
            return attempt;
        }
        catch (RuntimeException | LinkageError failure) {
            if (lease != null) {
                try {
                    lease.close();
                }
                catch (RuntimeException | LinkageError releaseFailure) {
                    failure.addSuppressed(releaseFailure);
                }
            }
            prediction.close();
            throw failure;
        }
    }

    @Override
    public void holdAttack(AttackAttempt attempt) {
        var minecraft = assertClientThread();
        var active = requireActive(attempt);
        if (active.inputReleased()) {
            return;
        }
        long currentTick = sessionSupplier.get().clientTick();
        if (currentTick >= attempt.leaseExpiresAtClientTick()) {
            active.lease().close();
            throw new IllegalStateException("attack lease expired");
        }
        var level = requireTargetLevel(attempt.target());
        var position = blockPos(attempt.target());
        SafeBreakSourcePolicy.requireLiveState(
                level.getBlockState(position), level.getBlockEntity(position) != null);
        active.lease().renew(
                System.nanoTime(),
                leaseHorizon(currentTick, attempt.leaseExpiresAtClientTick()));
        if (!active.lease().maintain(System.nanoTime())) {
            throw new IllegalStateException("attack input lease could not be maintained");
        }
    }

    @Override
    public void stopAttackInput(AttackAttempt attempt) {
        assertClientThread();
        var active = requireActive(attempt);
        if (active.inputReleased()) {
            return;
        }
        active.lease().close();
        activeAttempts.put(attempt, active.withInputReleased());
    }

    @Override
    public PredictionEvidence predictionEvidence(AttackAttempt attempt) {
        assertClientThread();
        var active = requireActive(attempt);
        var confirmation = active.prediction().confirmation(BlockState::isAir);
        Integer requiredSequence = confirmation.stateRequiredSequence() != null
                ? confirmation.stateRequiredSequence()
                : confirmation.issuedSequence();
        boolean acknowledged = requiredSequence != null
                && confirmation.acknowledgedSequence() != null
                && Integer.compareUnsigned(
                        confirmation.acknowledgedSequence(), requiredSequence) >= 0;
        Optional<BlockStateFingerprint> serverState = Optional.ofNullable(confirmation.serverState())
                .map(MinecraftStationaryBreakPort::fingerprint);
        var session = sessionSupplier.get();
        if (confirmation.serverConfirmed() && !active.confirmationHandled()) {
            rememberInteractionConfirmation(attempt, confirmation, session);
            // A regenerated block must not later make the historical air transition look current.
            // The first terminal confirmation therefore consumes this one-shot memory decision,
            // whether it was recorded or safely skipped.
            activeAttempts.put(attempt, active.withConfirmationHandled());
        }
        return new PredictionEvidence(
                requiredSequence == null
                        ? attempt.predictionSequence()
                        : Integer.toUnsignedLong(requiredSequence),
                acknowledged,
                confirmation.postconditionObserved(),
                serverState,
                session.clientTick(),
                memory.revision());
    }

    private void rememberInteractionConfirmation(
            AttackAttempt attempt,
            ClientPredictionSignals.Confirmation<BlockState> confirmation,
            WorldSessionTracker.Snapshot session) {
        var level = requireMinecraft().level;
        var confirmed = confirmation.serverState();
        var position = blockPos(attempt.target());
        if (level == null
                || confirmed == null
                || session == null
                || !attempt.target().dimension().equals(level.dimension().identifier().toString())
                || !level.isLoaded(position)) {
            return;
        }
        var current = level.getBlockState(position);
        InteractionConfirmationRecorder.rememberIfCurrent(
                memory,
                session,
                attempt.target(),
                fingerprint(confirmed),
                fingerprint(current),
                observedContext(level, position, current));
    }

    /**
     * Captures the prediction sequence immediately after vanilla processed the leased attack key.
     * This runs in ClientTickEvent.Post so a very fast server reply cannot arrive before the
     * attempt has been associated with its exact prediction sequence on the following tick.
     */
    public void captureIssuedPredictions() {
        assertClientThread();
        for (var active : activeAttempts.values()) {
            active.prediction().captureIssuedPredictions();
        }
    }

    @Override
    public void releaseAttack(AttackAttempt attempt) {
        assertClientThread();
        var active = activeAttempts.get(attempt);
        if (active == null) {
            return;
        }
        RuntimeException runtimeFailure = null;
        LinkageError linkageFailure = null;
        try {
            active.lease().close();
        }
        catch (RuntimeException failure) {
            runtimeFailure = failure;
        }
        catch (LinkageError failure) {
            linkageFailure = failure;
        }
        try {
            active.prediction().close();
        }
        catch (RuntimeException failure) {
            if (runtimeFailure == null) {
                runtimeFailure = failure;
            }
            else {
                runtimeFailure.addSuppressed(failure);
            }
        }
        catch (LinkageError failure) {
            if (runtimeFailure != null) {
                runtimeFailure.addSuppressed(failure);
            }
            else if (linkageFailure == null) {
                linkageFailure = failure;
            }
            else {
                linkageFailure.addSuppressed(failure);
            }
        }
        if (runtimeFailure != null) {
            throw runtimeFailure;
        }
        if (linkageFailure != null) {
            throw linkageFailure;
        }
        // Only forget the exact lease after both input and prediction release have succeeded.
        activeAttempts.remove(attempt, active);
    }

    public void clearSession() {
        assertClientThread();
        for (var attempt : activeAttempts.keySet().toArray(AttackAttempt[]::new)) {
            try {
                releaseAttack(attempt);
            }
            catch (RuntimeException | LinkageError ignored) {
                // The global input release remains the final lifecycle fence.
            }
        }
        baselines.clear();
    }

    @Override
    public void retire(StationaryBreakRequest request) {
        assertClientThread();
        baselines.remove(Objects.requireNonNull(request, "request"));
    }

    private StationaryBreakFrame unavailableFrame(WorldSessionTracker.Snapshot session) {
        long tick = session == null ? 0 : Math.max(0, session.clientTick());
        return new StationaryBreakFrame(
                tick, memory.revision(), Optional.empty(), 0, false,
                false, false, false, false, false, false, false);
    }

    private net.minecraft.client.multiplayer.ClientLevel requireTargetLevel(BlockTarget target) {
        var minecraft = requireMinecraft();
        var level = minecraft.level;
        var session = sessionSupplier.get();
        if (level == null || session == null || !session.worldReady()
                || !target.dimension().equals(session.dimension())
                || !target.dimension().equals(level.dimension().identifier().toString())) {
            throw new IllegalStateException("target dimension is not the current ready client world");
        }
        var position = blockPos(target);
        if (!level.isLoaded(position)) {
            throw new IllegalStateException("target block is not loaded");
        }
        return level;
    }

    private boolean withinReach(BlockPos position) {
        var minecraft = requireMinecraft();
        var player = minecraft.player;
        return player != null && player.isWithinBlockInteractionRange(position, 0.0D);
    }

    private boolean crosshairOn(BlockPos position) {
        var minecraft = requireMinecraft();
        return minecraft.hitResult instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(position);
    }

    private int inventoryCount(String itemId) {
        var minecraft = requireMinecraft();
        var player = Objects.requireNonNull(minecraft.player, "player");
        int total = 0;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (!stack.isEmpty() && itemId.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())) {
                total = Math.min(2_304, total + stack.getCount());
            }
        }
        return total;
    }

    private ActiveAttempt requireActive(AttackAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt");
        var active = activeAttempts.get(attempt);
        if (active == null) {
            throw new IllegalStateException("attack attempt is not active");
        }
        return active;
    }

    private Minecraft assertClientThread() {
        var minecraft = requireMinecraft();
        if (!minecraft.isSameThread()) {
            throw new IllegalStateException("stationary_break adapter must run on the client thread");
        }
        return minecraft;
    }

    private Minecraft requireMinecraft() {
        return Objects.requireNonNull(
                minecraftSupplier.get(),
                "Minecraft client is not initialized");
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

    /** Canonical full-state encoding shared with Action packet-adjacent policy checks. */
    public static BlockStateFingerprint fingerprintForPolicy(BlockState state) {
        return fingerprint(Objects.requireNonNull(state, "state"));
    }

    @SuppressWarnings("deprecation") // Minecraft exposes replaceability without a world context here.
    private static ObservedContext observedContext(
            net.minecraft.client.multiplayer.ClientLevel level,
            BlockPos position,
            BlockState state) {
        var fluid = state.getFluidState();
        var sturdyFaces = new java.util.ArrayList<String>();
        for (Direction face : Direction.values()) {
            if (state.isFaceSturdy(level, position, face)) {
                sturdyFaces.add(face.getSerializedName());
            }
        }
        return new ObservedContext(
                level.getBrightness(LightLayer.BLOCK, position),
                level.getBrightness(LightLayer.SKY, position),
                fluid.isEmpty() ? null : BuiltInRegistries.FLUID.getKey(fluid.getType()).toString(),
                fluid.isEmpty() ? null : fluid.isSource(),
                fluid.isEmpty() ? null : fluid.getAmount(),
                state.canBeReplaced(),
                state.getCollisionShape(level, position).isEmpty(),
                sturdyFaces);
    }

    private static Duration leaseHorizon(long currentTick, long expiresAtTick) {
        long remainingTicks = Math.max(1, expiresAtTick - currentTick);
        return Duration.ofMillis(Math.min(2_000L, Math.multiplyExact(remainingTicks, 50L)));
    }

    private record Baseline(
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            int selectedSlot,
            float health) {
        private boolean matches(
                double currentX,
                double currentY,
                double currentZ,
                float currentYaw,
                float currentPitch,
                int currentSelectedSlot) {
            double dx = currentX - x;
            double dy = currentY - y;
            double dz = currentZ - z;
            return dx * dx + dy * dy + dz * dz <= MAX_POSITION_DRIFT_SQUARED
                    && Math.abs(currentYaw - yaw) <= MAX_ROTATION_DRIFT
                    && Math.abs(currentPitch - pitch) <= MAX_ROTATION_DRIFT
                    && currentSelectedSlot == selectedSlot;
        }
    }

    private record ActiveAttempt(
            ClientPredictionSignals.PredictionAttempt prediction,
            AttackInputLease lease,
            BlockStateFingerprint expectedSource,
            boolean inputReleased,
            boolean confirmationHandled) {
        private ActiveAttempt withInputReleased() {
            return new ActiveAttempt(
                    prediction, lease, expectedSource, true, confirmationHandled);
        }

        private ActiveAttempt withConfirmationHandled() {
            return new ActiveAttempt(
                    prediction, lease, expectedSource, inputReleased, true);
        }
    }
}
