package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.MinecraftActionPrimitiveExecutor;
import dev.aod.mcmcp.agent.action.V2BlockAimResolver;
import dev.aod.mcmcp.agent.navigation.CoordinateGoalPlanner;
import dev.aod.mcmcp.agent.navigation.NavCell;
import dev.aod.mcmcp.agent.navigation.TraversabilityEdge;
import dev.aod.mcmcp.agent.observation.ObservationRecord;
import dev.aod.mcmcp.agent.safety.LocalObservationVolume;
import dev.aod.mcmcp.client.AgentInputState;
import dev.aod.mcmcp.client.McmcpClientConfig;
import dev.aod.mcmcp.mixin.client.BlockItemPlacementInvoker;
import dev.aod.mcmcp.routine.BlockStateFingerprint;
import dev.aod.mcmcp.routine.BoundedInputLease;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DoubleHighBlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/** Uses a current observed support face, one normal placement, and server confirmation. */
final class MinecraftV2PlaceDriver
        implements V2BlockJobExecution.Driver<V2PlaceArguments> {
    private static final int MAX_CROSSHAIR_WAIT_TICKS = 20;
    private static final int MAX_CONFIRM_WAIT_TICKS = 60;
    private static final int MAX_APPROACH_EVIDENCE_WAIT_TICKS = 80;
    private static final int MAX_STAGED_OBSERVATION_WAIT_TICKS = 80;
    private static final double APPROACH_TOLERANCE = 0.35D;
    private static final Duration SNEAK_LEASE = Duration.ofSeconds(1);

    private final Minecraft minecraft;
    private final Supplier<WorldSessionTracker.Snapshot> sessions;
    private final AgentObservations observations;
    private final ClientReconciliationSignals reconciliationSignals;
    private final ClientPredictionSignals predictions;
    private final DoubleSupplier remainingDistance;
    private MinecraftActionPrimitiveExecutor navigation;
    private CoordinateGoalPlanner planner;
    private Map<TraversabilityEdge.Key, TraversabilityEdge> waitingEvidence;
    private int approachEvidenceWaitTicks;
    private int stagedObservationWaitTicks;
    private MinecraftActionPrimitiveExecutor facing;
    private BoundedInputLease sneak;
    private ClientPredictionSignals.PredictionAttempt prediction;
    private V2InventorySwap staging;
    private NavCell target;
    private V2PlaceArguments request;
    private Candidate candidate;
    private BlockStateFingerprint before;
    private V2BlockJobExecution.ConfirmedChange confirmedChange;
    private Stage stage = Stage.IDLE;
    private int originalSlot = -1;
    private int selectedSlot = -1;
    private Object selectedPlayer;
    private int crosshairWaitTicks;
    private long dispatchedTick;

    MinecraftV2PlaceDriver(Minecraft minecraft,
            Supplier<WorldSessionTracker.Snapshot> sessions,
            AgentObservations observations,
            ClientReconciliationSignals reconciliationSignals,
            ClientPredictionSignals predictions, DoubleSupplier remainingDistance) {
        this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.reconciliationSignals = Objects.requireNonNull(
                reconciliationSignals, "reconciliationSignals");
        this.predictions = Objects.requireNonNull(predictions, "predictions");
        this.remainingDistance = Objects.requireNonNull(remainingDistance, "remainingDistance");
    }

    @Override
    public String dimension() {
        return sessions.get().dimension();
    }

    @Override
    public V2BlockJobExecution.BeginResult begin(NavCell next,
            V2PlaceArguments request, BooleanSupplier outputAllowed) {
        if (stage != Stage.IDLE) throw new IllegalStateException("place driver already active");
        if (!outputAllowed.getAsBoolean()) return V2BlockJobExecution.BeginResult.FAILED;
        var observed = beginObservedTarget(next, request, outputAllowed);
        if (observed != V2BlockJobExecution.BeginResult.WAITING
                || !request.advance() || request.maxDistance() <= 0.0D) return observed;
        target = next;
        this.request = request;
        // An empty placement target must remain empty; it is not a standing waypoint.
        planner = new CoordinateGoalPlanner(sessions.get().worldSessionId(), next,
                cell -> !cell.equals(next));
        navigation = new MinecraftActionPrimitiveExecutor(
                McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0F);
        stage = Stage.APPROACH;
        return V2BlockJobExecution.BeginResult.STARTED;
    }

    private V2BlockJobExecution.BeginResult beginObservedTarget(NavCell next,
            V2PlaceArguments request, BooleanSupplier outputAllowed) {
        var session = sessions.get();
        var player = minecraft.player;
        var level = minecraft.level;
        if (!session.worldReady() || player == null || level == null
                || minecraft.gameMode == null
                || minecraft.gameMode.getPlayerMode() != GameType.SURVIVAL
                || !session.dimension().equals(next.dimension())) {
            return V2BlockJobExecution.BeginResult.FAILED;
        }
        var position = blockPos(next);
        if (!level.isLoaded(position)
                || !player.isWithinBlockInteractionRange(position, 0.0D)) {
            return V2BlockJobExecution.BeginResult.WAITING;
        }
        var frame = observations.latestInternalFrame();
        if (frame.isEmpty()) return V2BlockJobExecution.BeginResult.WAITING;
        var reconciliation = reconciliationSignals.bindAndSnapshot(
                level, session.worldSessionId());
        List<Candidate> visibleSupports = new ArrayList<>();
        for (Direction face : Direction.values()) {
            BlockPos support = position.relative(face.getOpposite());
            if (!level.isLoaded(support)
                    || !player.isWithinBlockInteractionRange(support, 0.0D)) continue;
            var supportCell = new NavCell(next.dimension(),
                    support.getX(), support.getY(), support.getZ());
            long barrier = reconciliation.surfaceBarrierWorldRevision(
                    support.getX(), support.getY(), support.getZ());
            var aim = V2BlockAimResolver.resolve(frame.orElseThrow(), supportCell,
                    ObservationRecord.Face.valueOf(face.name()),
                    player.getEyePosition(), session.worldSessionId(),
                    session.clientTick(), reconciliation.worldRevision(), barrier);
            if (aim.isPresent()
                    && blockId(level.getBlockState(support)).equals(aim.orElseThrow().blockId())) {
                visibleSupports.add(new Candidate(support, face, aim.orElseThrow().aim()));
            }
        }
        if (visibleSupports.isEmpty()) return V2BlockJobExecution.BeginResult.WAITING;
        var desired = new BlockStateFingerprint(request.blockId(), request.properties());
        BlockState before = level.getBlockState(position);
        if (desired.matches(fingerprint(before))) {
            return V2BlockJobExecution.BeginResult.SKIPPED;
        }
        if (!request.acceptsTarget(blockId(before))) {
            return V2BlockJobExecution.BeginResult.SKIPPED;
        }
        if (before.hasBlockEntity() || level.getBlockEntity(position) != null) {
            return V2BlockJobExecution.BeginResult.FAILED;
        }
        int slot = findHotbarItem(request.itemId());
        if (slot < 0) {
            int source = findMainInventoryItem(request.itemId());
            if (source < 0 || !outputAllowed.getAsBoolean()) {
                return V2BlockJobExecution.BeginResult.FAILED;
            }
            staging = V2InventorySwap.start(minecraft, session.worldSessionId(),
                    session.clientTick(), source, player.getInventory().getSelectedSlot());
            target = next;
            this.request = request;
            stage = Stage.STAGING;
            return V2BlockJobExecution.BeginResult.STARTED;
        }
        var stack = player.getInventory().getItem(slot);
        if (!(stack.getItem() instanceof BlockItem item)
                || item instanceof BedItem || item instanceof DoubleHighBlockItem) {
            // Multi-cell placement needs companion-cell ownership and confirmation.
            return V2BlockJobExecution.BeginResult.FAILED;
        }
        int previousSlot = player.getInventory().getSelectedSlot();
        player.getInventory().setSelectedSlot(slot);
        try {
            Candidate found = null;
            for (Candidate support : visibleSupports) {
                var witness = support.aim();
                var hit = new BlockHitResult(new Vec3(
                        witness.aimX(), witness.aimY(), witness.aimZ()),
                        support.face(), support.support(), false);
                if (!matchesTarget(item, hit, position)) continue;
                BlockState predicted = predictedState(item, hit);
                if (predicted != null && desired.matches(fingerprint(predicted))) {
                    found = support;
                    break;
                }
            }
            if (found == null) return V2BlockJobExecution.BeginResult.FAILED;
            if (!outputAllowed.getAsBoolean()) return V2BlockJobExecution.BeginResult.FAILED;
            this.target = next;
            this.request = request;
            this.candidate = found;
            originalSlot = previousSlot;
            selectedSlot = slot;
            selectedPlayer = player;
            facing = new MinecraftActionPrimitiveExecutor(
                    McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0F);
            facing.beginFace(found.aim(), 60L);
            stage = Stage.FACING;
            return V2BlockJobExecution.BeginResult.STARTED;
        } finally {
            if (stage == Stage.IDLE) player.getInventory().setSelectedSlot(previousSlot);
        }
    }

    @Override
    public V2BlockJobExecution.StepResult tick(long clientTick,
            BooleanSupplier outputAllowed) {
        if (stage == Stage.IDLE || !outputAllowed.getAsBoolean()) {
            return V2BlockJobExecution.StepResult.FAILED;
        }
        var session = sessions.get();
        if (!session.worldReady() || !session.dimension().equals(target.dimension())
                || minecraft.player == null || minecraft.level == null) {
            return V2BlockJobExecution.StepResult.FAILED;
        }
        if (stage == Stage.APPROACH) {
            return tickApproach(session, clientTick, outputAllowed);
        }
        if (stage == Stage.STAGING) {
            var result = staging.poll(session.worldSessionId(), clientTick);
            if (result == V2InventorySwap.Result.WAITING) {
                return V2BlockJobExecution.StepResult.RUNNING;
            }
            if (result != V2InventorySwap.Result.CONFIRMED) {
                return V2BlockJobExecution.StepResult.FAILED;
            }
            staging.close();
            staging = null;
            stage = Stage.STAGED;
        }
        if (stage == Stage.STAGED) {
            return switch (beginObservedTarget(target, request, outputAllowed)) {
                case STARTED -> V2BlockJobExecution.StepResult.RUNNING;
                case SKIPPED -> V2BlockJobExecution.StepResult.SKIPPED;
                case WAITING -> ++stagedObservationWaitTicks
                        <= MAX_STAGED_OBSERVATION_WAIT_TICKS
                        ? V2BlockJobExecution.StepResult.RUNNING
                        : V2BlockJobExecution.StepResult.FAILED;
                case FAILED -> V2BlockJobExecution.StepResult.FAILED;
            };
        }
        if (stage == Stage.FACING) {
            var result = facing.tick(minecraft, observations.requireAgentMap(session),
                    LocalObservationVolume.global(), 0.0D, 1_080.0D,
                    clientTick, outputAllowed);
            if (result.status() == MinecraftActionPrimitiveExecutor.Status.RUNNING) {
                return V2BlockJobExecution.StepResult.RUNNING;
            }
            if (result.status() != MinecraftActionPrimitiveExecutor.Status.SUCCEEDED) {
                return V2BlockJobExecution.StepResult.FAILED;
            }
            facing.close();
            facing = null;
            sneak = BoundedInputLease.acquire(AgentInputState.global(),
                    Set.of(BoundedInputLease.Input.SNEAK),
                    System.nanoTime(), SNEAK_LEASE);
            stage = Stage.WAIT_SNEAK;
            return V2BlockJobExecution.StepResult.RUNNING;
        }
        if (!heartbeatSneak(session)) return V2BlockJobExecution.StepResult.FAILED;
        if (stage == Stage.WAIT_SNEAK) {
            if (!minecraft.player.isShiftKeyDown()) {
                return ++crosshairWaitTicks <= MAX_CROSSHAIR_WAIT_TICKS
                        ? V2BlockJobExecution.StepResult.RUNNING
                        : V2BlockJobExecution.StepResult.FAILED;
            }
            facing = new MinecraftActionPrimitiveExecutor(
                    McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0F);
            facing.beginFace(candidate.aim(), 60L);
            stage = Stage.FACING_CROUCHED;
            crosshairWaitTicks = 0;
            return V2BlockJobExecution.StepResult.RUNNING;
        }
        if (stage == Stage.FACING_CROUCHED) {
            var result = facing.tick(minecraft, observations.requireAgentMap(session),
                    LocalObservationVolume.global(), 0.0D, 1_080.0D,
                    clientTick, outputAllowed);
            if (result.status() == MinecraftActionPrimitiveExecutor.Status.RUNNING) {
                return V2BlockJobExecution.StepResult.RUNNING;
            }
            if (result.status() != MinecraftActionPrimitiveExecutor.Status.SUCCEEDED) {
                return V2BlockJobExecution.StepResult.FAILED;
            }
            facing.close();
            facing = null;
            stage = Stage.WAIT_CROSSHAIR;
            return V2BlockJobExecution.StepResult.RUNNING;
        }
        if (stage == Stage.WAIT_CROSSHAIR) {
            if (!minecraft.player.isShiftKeyDown() || !crosshairOnSupport()) {
                return ++crosshairWaitTicks <= MAX_CROSSHAIR_WAIT_TICKS
                        ? V2BlockJobExecution.StepResult.RUNNING
                        : V2BlockJobExecution.StepResult.FAILED;
            }
            if (!dispatch(clientTick, outputAllowed)) {
                return V2BlockJobExecution.StepResult.FAILED;
            }
            sneak.close();
            sneak = null;
            stage = Stage.CONFIRMING;
            return V2BlockJobExecution.StepResult.RUNNING;
        }
        var desired = new BlockStateFingerprint(request.blockId(), request.properties());
        var confirmation = prediction.confirmation(state -> desired.matches(fingerprint(state)));
        if (confirmation.serverConfirmed()) {
            var after = fingerprint(minecraft.level.getBlockState(blockPos(target)));
            if (!desired.matches(after)) return V2BlockJobExecution.StepResult.FAILED;
            confirmedChange = new V2BlockJobExecution.ConfirmedChange(before, after);
            return V2BlockJobExecution.StepResult.CONFIRMED;
        }
        if (confirmation.status() == ClientPredictionSignals.ConfirmationStatus.SERVER_STATE_MISMATCH
                || confirmation.status() == ClientPredictionSignals.ConfirmationStatus.INCOMPATIBLE
                || confirmation.status() == ClientPredictionSignals.ConfirmationStatus.CLOSED
                || clientTick - dispatchedTick > MAX_CONFIRM_WAIT_TICKS) {
            return V2BlockJobExecution.StepResult.FAILED;
        }
        return V2BlockJobExecution.StepResult.RUNNING;
    }

    @Override
    public V2BlockJobExecution.ConfirmedChange confirmedChange() {
        return confirmedChange;
    }

    private V2BlockJobExecution.StepResult tickApproach(
            WorldSessionTracker.Snapshot session, long clientTick,
            BooleanSupplier outputAllowed) {
        var map = observations.requireAgentMap(session);
        if (navigation.active()) {
            var motion = navigation.tick(minecraft, map, LocalObservationVolume.global(),
                    Math.max(0.0D, remainingDistance.getAsDouble()),
                    1_080.0D, clientTick, outputAllowed);
            return switch (motion.status()) {
                case RUNNING -> V2BlockJobExecution.StepResult.RUNNING;
                case SUCCEEDED, REPLAN_REQUIRED -> {
                    navigation.close();
                    waitingEvidence = map.edges();
                    approachEvidenceWaitTicks = 0;
                    yield V2BlockJobExecution.StepResult.RUNNING;
                }
                case FAILED -> V2BlockJobExecution.StepResult.FAILED;
            };
        }
        var observed = beginObservedTarget(target, request, outputAllowed);
        switch (observed) {
            case STARTED -> { return V2BlockJobExecution.StepResult.RUNNING; }
            case SKIPPED -> { return V2BlockJobExecution.StepResult.SKIPPED; }
            case FAILED -> { return V2BlockJobExecution.StepResult.FAILED; }
            case WAITING -> { }
        }
        if (waitingEvidence != null && waitingEvidence.equals(map.edges())) {
            return ++approachEvidenceWaitTicks <= MAX_APPROACH_EVIDENCE_WAIT_TICKS
                    ? V2BlockJobExecution.StepResult.RUNNING
                    : V2BlockJobExecution.StepResult.FAILED;
        }
        var current = ActionPlanning.playerCell(minecraft.player, session.dimension());
        var plan = planner.plan(map, current, session.worldSessionId(), map.worldRevision(),
                CoordinateGoalPlanner.Budget.DEFAULT,
                () -> !outputAllowed.getAsBoolean());
        return switch (plan.status()) {
            case KNOWN_GOAL_ROUTE, PARTIAL_WAYPOINT -> {
                navigation.beginNavigate(plan.route().orElseThrow(), APPROACH_TOLERANCE);
                waitingEvidence = null;
                yield V2BlockJobExecution.StepResult.RUNNING;
            }
            case BLOCKED, REACHED_KNOWN_GOAL -> {
                waitingEvidence = map.edges();
                approachEvidenceWaitTicks = 0;
                yield V2BlockJobExecution.StepResult.RUNNING;
            }
            case LIMIT, CANCELLED, STALE_MAP, WORLD_MISMATCH ->
                    V2BlockJobExecution.StepResult.FAILED;
        };
    }

    private boolean heartbeatSneak(WorldSessionTracker.Snapshot session) {
        if (sneak == null) return stage == Stage.CONFIRMING;
        if (!sneak.heartbeat(System.nanoTime(), SNEAK_LEASE)) return false;
        var recon = reconciliationSignals.bindAndSnapshot(
                minecraft.level, session.worldSessionId());
        AgentInputState.global().requireGoalMovementSafety(
                minecraft.player, minecraft.level, recon.worldRevision(), 0.0D);
        return true;
    }

    private boolean dispatch(long clientTick, BooleanSupplier outputAllowed) {
        var level = minecraft.level;
        var player = minecraft.player;
        var position = blockPos(target);
        if (!outputAllowed.getAsBoolean() || !level.isLoaded(position)
                || !level.getWorldBorder().isWithinBounds(position)
                || player.blockActionRestricted(level, position,
                        minecraft.gameMode.getPlayerMode())
                || player.getInventory().getSelectedSlot() != selectedSlot
                || !(player.getMainHandItem().getItem() instanceof BlockItem item)
                || !request.itemId().equals(
                        BuiltInRegistries.ITEM.getKey(item).toString())) return false;
        var hit = (BlockHitResult) minecraft.hitResult;
        if (!matchesTarget(item, hit, position)) return false;
        BlockState before = level.getBlockState(position);
        if (!request.acceptsTarget(blockId(before))
                || before.hasBlockEntity() || level.getBlockEntity(position) != null) return false;
        BlockState predicted = predictedState(item, hit);
        var desired = new BlockStateFingerprint(request.blockId(), request.properties());
        if (predicted == null || !desired.matches(fingerprint(predicted))) return false;
        prediction = predictions.begin(level, position, clientTick);
        int beforeSequence = prediction.sequenceBeforePrediction();
        var result = minecraft.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        int afterSequence = prediction.captureIssuedPredictions();
        if (afterSequence != beforeSequence + 1 || !result.consumesAction()) return false;
        this.before = fingerprint(before);
        dispatchedTick = clientTick;
        return true;
    }

    private boolean crosshairOnSupport() {
        return minecraft.hitResult instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK
                && !hit.isWorldBorderHit()
                && hit.getBlockPos().equals(candidate.support())
                && hit.getDirection() == candidate.face();
    }

    private int findHotbarItem(String itemId) {
        var inventory = minecraft.player.getInventory();
        for (int slot = 0; slot < 9; slot++) {
            var stack = inventory.getItem(slot);
            if (!stack.isEmpty() && itemId.equals(
                    BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())) return slot;
        }
        return -1;
    }

    private int findMainInventoryItem(String itemId) {
        var inventory = minecraft.player.getInventory();
        for (int slot = Inventory.getSelectionSize(); slot < Inventory.INVENTORY_SIZE; slot++) {
            var stack = inventory.getItem(slot);
            if (!stack.isEmpty() && itemId.equals(
                    BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
                    && stack.getItem() instanceof BlockItem item
                    && !(item instanceof BedItem)
                    && !(item instanceof DoubleHighBlockItem)) return slot;
        }
        return -1;
    }

    private boolean matchesTarget(BlockItem item, BlockHitResult hit, BlockPos position) {
        if (hit.getType() != HitResult.Type.BLOCK || hit.isWorldBorderHit()) return false;
        var context = item.updatePlacementContext(new BlockPlaceContext(
                new UseOnContext(minecraft.player, InteractionHand.MAIN_HAND, hit)));
        return context != null && context.canPlace()
                && context.getClickedPos().equals(position);
    }

    private BlockState predictedState(BlockItem item, BlockHitResult hit) {
        var context = item.updatePlacementContext(new BlockPlaceContext(
                new UseOnContext(minecraft.player, InteractionHand.MAIN_HAND, hit)));
        return context == null ? null
                : ((BlockItemPlacementInvoker) (Object) item)
                        .mcmcp$invokeGetPlacementState(context);
    }

    @Override
    public void close() {
        Throwable failure = null;
        if (prediction != null) {
            try {
                prediction.close();
                prediction = null;
            } catch (RuntimeException | LinkageError closeFailure) {
                failure = closeFailure;
            }
        }
        if (staging != null) {
            try {
                staging.close();
                staging = null;
            } catch (RuntimeException | LinkageError closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        if (sneak != null) {
            try {
                sneak.close();
                sneak = null;
            } catch (RuntimeException | LinkageError closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        if (facing != null) {
            try {
                facing.close();
                facing = null;
            } catch (RuntimeException | LinkageError closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        if (navigation != null) {
            try {
                navigation.close();
                navigation = null;
            } catch (RuntimeException | LinkageError closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        var player = minecraft.player;
        if (player == selectedPlayer && originalSlot >= 0
                && player.getInventory().getSelectedSlot() == selectedSlot) {
            player.getInventory().setSelectedSlot(originalSlot);
        }
        originalSlot = -1;
        selectedSlot = -1;
        selectedPlayer = null;
        target = null;
        request = null;
        planner = null;
        waitingEvidence = null;
        approachEvidenceWaitTicks = 0;
        stagedObservationWaitTicks = 0;
        candidate = null;
        before = null;
        confirmedChange = null;
        crosshairWaitTicks = 0;
        dispatchedTick = 0L;
        stage = Stage.IDLE;
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof LinkageError linkage) throw linkage;
    }

    private static BlockPos blockPos(NavCell cell) {
        return new BlockPos(cell.x(), cell.y(), cell.z());
    }

    private static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static BlockStateFingerprint fingerprint(BlockState state) {
        Map<String, String> properties = new LinkedHashMap<>();
        state.getValues().forEach(value ->
                properties.put(value.property().getName(), value.valueName()));
        return new BlockStateFingerprint(blockId(state), properties);
    }

    private record Candidate(BlockPos support, Direction face,
                             MinecraftActionPrimitiveExecutor.KnownFaceTarget aim) { }
    private enum Stage {
        IDLE, APPROACH, STAGING, STAGED, FACING, WAIT_SNEAK, FACING_CROUCHED,
        WAIT_CROSSHAIR, CONFIRMING
    }
}
