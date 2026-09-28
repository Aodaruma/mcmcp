package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.MinecraftActionPrimitiveExecutor;
import dev.aod.mcmcp.agent.action.V2BlockAimResolver;
import dev.aod.mcmcp.agent.navigation.CoordinateGoalPlanner;
import dev.aod.mcmcp.agent.navigation.NavCell;
import dev.aod.mcmcp.agent.navigation.TraversabilityEdge;
import dev.aod.mcmcp.agent.safety.LocalObservationVolume;
import dev.aod.mcmcp.client.McmcpClientConfig;
import dev.aod.mcmcp.routine.BlockStateFingerprint;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/** Normal block use with exact target, local approach, and authoritative state confirmation. */
final class MinecraftV2BlockInteractDriver
        implements V2BlockJobExecution.Driver<V2BlockInteractArguments> {
    private static final int MAX_APPROACH_EVIDENCE_WAIT_TICKS = 80;
    private static final int MAX_CONFIRM_WAIT_TICKS = 60;
    private static final double APPROACH_TOLERANCE = 0.35D;

    private final Minecraft minecraft;
    private final Supplier<WorldSessionTracker.Snapshot> sessions;
    private final AgentObservations observations;
    private final ClientReconciliationSignals reconciliationSignals;
    private final ClientPredictionSignals predictions;
    private final DoubleSupplier remainingDistance;
    private final MinecraftV2MenuDriver menu;
    private MinecraftActionPrimitiveExecutor navigation;
    private CoordinateGoalPlanner planner;
    private Map<TraversabilityEdge.Key, TraversabilityEdge> waitingEvidence;
    private MinecraftActionPrimitiveExecutor facing;
    private ClientPredictionSignals.PredictionAttempt prediction;
    private NavCell target;
    private V2BlockInteractArguments request;
    private BlockStateFingerprint before;
    private V2BlockJobExecution.ConfirmedChange confirmedChange;
    private Stage stage = Stage.IDLE;
    private int originalSlot = -1;
    private int selectedSlot = -1;
    private Object selectedPlayer;
    private int approachEvidenceWaitTicks;
    private int crosshairWaitTicks;
    private long dispatchedTick;

    MinecraftV2BlockInteractDriver(Minecraft minecraft,
            Supplier<WorldSessionTracker.Snapshot> sessions,
            AgentObservations observations,
            ClientReconciliationSignals reconciliationSignals,
            ClientPredictionSignals predictions, DoubleSupplier remainingDistance) {
        this(minecraft, sessions, observations, reconciliationSignals, predictions, remainingDistance, null);
    }

    MinecraftV2BlockInteractDriver(Minecraft minecraft,
            Supplier<WorldSessionTracker.Snapshot> sessions, AgentObservations observations,
            ClientReconciliationSignals reconciliationSignals, ClientPredictionSignals predictions,
            DoubleSupplier remainingDistance, MinecraftV2MenuDriver menu) {
        this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.reconciliationSignals = Objects.requireNonNull(reconciliationSignals);
        this.predictions = Objects.requireNonNull(predictions);
        this.remainingDistance = Objects.requireNonNull(remainingDistance);
        this.menu = menu;
    }

    @Override
    public String dimension() {
        return sessions.get().dimension();
    }

    @Override
    public V2BlockJobExecution.BeginResult begin(NavCell next,
            V2BlockInteractArguments request, BooleanSupplier outputAllowed) {
        if (stage != Stage.IDLE || !outputAllowed.getAsBoolean()) {
            return V2BlockJobExecution.BeginResult.FAILED;
        }
        var observed = beginObservedTarget(next, request, outputAllowed);
        if (observed != V2BlockJobExecution.BeginResult.WAITING
                || !request.advance() || request.maxDistance() <= 0.0D) return observed;
        target = next;
        this.request = request;
        planner = new CoordinateGoalPlanner(sessions.get().worldSessionId(), next,
                cell -> !cell.equals(next));
        navigation = new MinecraftActionPrimitiveExecutor(
                McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0F);
        stage = Stage.APPROACH;
        return V2BlockJobExecution.BeginResult.STARTED;
    }

    private V2BlockJobExecution.BeginResult beginObservedTarget(NavCell next,
            V2BlockInteractArguments request, BooleanSupplier outputAllowed) {
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
        var reconciliation = reconciliationSignals.bindAndSnapshot(level, session.worldSessionId());
        long barrier = reconciliation.surfaceBarrierWorldRevision(next.x(), next.y(), next.z());
        var aim = V2BlockAimResolver.resolve(frame.orElseThrow(), next, null,
                player.getEyePosition(), session.worldSessionId(), session.clientTick(),
                reconciliation.worldRevision(), barrier, id -> true);
        if (aim.isEmpty()) return V2BlockJobExecution.BeginResult.WAITING;
        var state = level.getBlockState(position);
        var current = fingerprint(state);
        if (!current.blockId().equals(aim.orElseThrow().blockId())) {
            return V2BlockJobExecution.BeginResult.WAITING;
        }
        if (!request.accepts(current.blockId()) || state.isAir()) {
            return V2BlockJobExecution.BeginResult.SKIPPED;
        }
        int slot = findHotbarSlot(request.itemId());
        if (slot < 0 || !outputAllowed.getAsBoolean()) {
            return V2BlockJobExecution.BeginResult.FAILED;
        }
        this.target = next;
        this.request = request;
        this.before = current;
        originalSlot = player.getInventory().getSelectedSlot();
        selectedSlot = slot;
        selectedPlayer = player;
        player.getInventory().setSelectedSlot(slot);
        facing = new MinecraftActionPrimitiveExecutor(
                McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0F);
        facing.beginFace(aim.orElseThrow().aim(), 60L);
        stage = Stage.FACING;
        return V2BlockJobExecution.BeginResult.STARTED;
    }

    @Override
    public V2BlockJobExecution.StepResult tick(long clientTick, BooleanSupplier outputAllowed) {
        if (stage == Stage.IDLE || !outputAllowed.getAsBoolean()) {
            return V2BlockJobExecution.StepResult.FAILED;
        }
        var session = sessions.get();
        if (!session.worldReady() || !session.dimension().equals(target.dimension())
                || minecraft.player == null || minecraft.level == null) {
            return V2BlockJobExecution.StepResult.FAILED;
        }
        if (stage == Stage.APPROACH) return tickApproach(session, clientTick, outputAllowed);
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
            stage = Stage.WAIT_CROSSHAIR;
            return V2BlockJobExecution.StepResult.RUNNING;
        }
        if (stage == Stage.WAIT_CROSSHAIR) {
            if (!crosshairOnTarget()) {
                return ++crosshairWaitTicks <= 20
                        ? V2BlockJobExecution.StepResult.RUNNING
                        : V2BlockJobExecution.StepResult.FAILED;
            }
            if (!dispatch(clientTick, outputAllowed)) {
                return V2BlockJobExecution.StepResult.FAILED;
            }
            stage = Stage.CONFIRMING;
            return V2BlockJobExecution.StepResult.RUNNING;
        }
        if (menu != null) return menu.tickMenu(clientTick, outputAllowed);
        var confirmation = prediction.confirmation(
                state -> matchesExpected(request, before, fingerprint(state)));
        if (confirmation.serverConfirmed()) {
            var after = fingerprint(minecraft.level.getBlockState(blockPos(target)));
            if (!matchesExpected(request, before, after)) {
                return V2BlockJobExecution.StepResult.FAILED;
            }
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

    private boolean dispatch(long clientTick, BooleanSupplier outputAllowed) {
        var level = minecraft.level;
        var player = minecraft.player;
        var position = blockPos(target);
        if (!outputAllowed.getAsBoolean() || !crosshairOnTarget()
                || !level.isLoaded(position)
                || !level.getWorldBorder().isWithinBounds(position)
                || player.blockActionRestricted(level, position,
                        minecraft.gameMode.getPlayerMode())
                || player.getInventory().getSelectedSlot() != selectedSlot
                || !before.equals(fingerprint(level.getBlockState(position)))
                || request.itemId() != null && !request.itemId().equals(
                        BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).toString())
                || request.itemId() == null && !player.getMainHandItem().isEmpty()) {
            return false;
        }
        var hit = (BlockHitResult) minecraft.hitResult;
        if (menu != null && !menu.beforeUse(target, before, clientTick)) return false;
        prediction = predictions.begin(level, position, clientTick);
        int sequence = prediction.sequenceBeforePrediction();
        var result = minecraft.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        if (prediction.captureIssuedPredictions() != sequence + 1
                || !result.consumesAction()) return false;
        dispatchedTick = clientTick;
        return true;
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

    private int findHotbarSlot(String itemId) {
        var inventory = minecraft.player.getInventory();
        for (int slot = 0; slot < 9; slot++) {
            var stack = inventory.getItem(slot);
            if (itemId == null && stack.isEmpty()) return slot;
            if (itemId != null && !stack.isEmpty() && itemId.equals(
                    BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())) return slot;
        }
        return -1;
    }

    private boolean crosshairOnTarget() {
        return minecraft.hitResult instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK
                && !hit.isWorldBorderHit()
                && hit.getBlockPos().equals(blockPos(target))
                && minecraft.player.isWithinBlockInteractionRange(blockPos(target), 0.0D);
    }

    @Override
    public void close() {
        // Retain the prediction until any delayed OpenScreen has crossed its causal ACK barrier.
        if (menu != null) menu.releaseMenu(prediction);
        Throwable failure = null;
        if (prediction != null) {
            try {
                prediction.close();
                prediction = null;
            } catch (RuntimeException | LinkageError closeFailure) {
                failure = closeFailure;
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
        if (originalSlot >= 0 && minecraft.player == selectedPlayer
                && minecraft.player.getInventory().getSelectedSlot() == selectedSlot) {
            minecraft.player.getInventory().setSelectedSlot(originalSlot);
        }
        originalSlot = -1;
        selectedSlot = -1;
        selectedPlayer = null;
        target = null;
        request = null;
        before = null;
        confirmedChange = null;
        planner = null;
        waitingEvidence = null;
        approachEvidenceWaitTicks = 0;
        crosshairWaitTicks = 0;
        dispatchedTick = 0L;
        stage = Stage.IDLE;
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof LinkageError linkage) throw linkage;
    }

    static boolean matchesExpected(V2BlockInteractArguments request,
            BlockStateFingerprint before, BlockStateFingerprint actual) {
        if (before.equals(actual)) return false;
        String desiredId = request.expectedAfterBlockId() == null
                ? before.blockId() : request.expectedAfterBlockId();
        if (request.expectedAfterBlockId() == null
                && request.expectedAfterProperties().isEmpty()) return true;
        return new BlockStateFingerprint(desiredId,
                request.expectedAfterProperties()).matches(actual);
    }

    private static BlockPos blockPos(NavCell cell) {
        return new BlockPos(cell.x(), cell.y(), cell.z());
    }

    private static BlockStateFingerprint fingerprint(BlockState state) {
        Map<String, String> properties = new LinkedHashMap<>();
        state.getValues().forEach(value ->
                properties.put(value.property().getName(), value.valueName()));
        return new BlockStateFingerprint(
                BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), properties);
    }

    private enum Stage { IDLE, APPROACH, FACING, WAIT_CROSSHAIR, CONFIRMING }
}
