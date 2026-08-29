package dev.aod.mcmcp.routine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Parent-only block-plan supervisor. Child preparation and mutation attempts never become
 * independently addressable routines, and no failed child is retried blindly.
 */
final class ApplyBlockPlanRoutine implements ManagedRoutine {
    static final String KIND = ApplyBlockPlanRequest.KIND;

    private static final String PREFLIGHT = "preflight";
    private static final String PREPARE = "prepare";
    private static final String WAIT_PREPARE = "wait_prepare";
    private static final String DISPATCH = "dispatch";
    private static final String WAIT_WORLD_DIFF = "wait_world_diff";
    private static final String FRESH_OBSERVE = "fresh_observe";
    private static final String FINAL_VERIFY = "final_verify";
    private static final int MAX_REACQUISITION_GRACE_TICKS = 3;

    private final UUID routineId;
    private final ApplyBlockPlanRequest request;
    private final ApplyBlockPlanPort port;
    private final RoutineEventRing events;
    private final long hardDeadlineClientTick;
    private final boolean[] verified;
    private final List<RoutineEffect> effects = new ArrayList<>();

    private RoutineState state = RoutineState.QUEUED;
    private String phase = "queued";
    private RoutineFailure failure;
    private RoutineFailure finalizationFailure;
    private boolean goalVerified;
    private boolean finalizationCompleted;
    private boolean retired;
    private int currentStepIndex;
    private int verifiedCells;
    private int confirmedCells;
    private int unknownCells;
    private long checkpointSeq;
    private long checkpointRevision;
    private int childAttempts;
    private Map<String, Integer> lastInventoryCounts = Map.of();
    private boolean lastInventoryServerSynchronized;
    private long lastClientTick;
    private long lastObservationRevision;
    private long freshAfterTick = -1;
    private long freshAfterRevision;
    private boolean finalVerifyPending;
    private String reacquisitionGracePhase;
    private int reacquisitionGraceTicks;
    private long reacquisitionLastClientTick = -1;
    private boolean reacquisitionWaiting;
    private boolean resourceSynchronizationRequired;
    private ApplyBlockPlanChildAction child;
    private ApplyBlockPlanPreparationAttempt preparation;
    private ApplyBlockPlanActionAttempt action;

    ApplyBlockPlanRoutine(
            UUID routineId,
            ApplyBlockPlanRequest request,
            ApplyBlockPlanPort port,
            int eventCapacity,
            long admittedClientTick) {
        this.routineId = Objects.requireNonNull(routineId, "routineId");
        this.request = Objects.requireNonNull(request, "request");
        this.port = Objects.requireNonNull(port, "port");
        request.validateAdmissionTick(admittedClientTick);
        events = new RoutineEventRing(eventCapacity);
        hardDeadlineClientTick = request.bounds().hardDeadlineClientTick(admittedClientTick);
        verified = new boolean[request.steps().size()];
        unknownCells = request.steps().size();
        lastClientTick = admittedClientTick;
        events.append(RoutineEventType.PHASE_STARTED, admittedClientTick, 0,
                Map.of("phase", phase));
    }

    @Override
    public void tick() {
        if (state.terminal() || state == RoutineState.FINALIZING) {
            return;
        }
        final ApplyBlockPlanFrame frame;
        try {
            frame = Objects.requireNonNull(port.observe(request), "adapter returned no frame");
        } catch (RuntimeException | LinkageError adapterFailure) {
            fail(adapterFailure("PLAN_OBSERVATION_ADAPTER_FAILURE"));
            return;
        }
        if (frame.clientTick() < lastClientTick
                || frame.observationRevision() < lastObservationRevision) {
            fail(failure(
                    RoutineFailure.Category.EXTERNAL,
                    "NON_MONOTONIC_OBSERVATION",
                    RoutineFailure.Recovery.USER,
                    RoutineFailure.Scope.ROUTINE,
                    Map.of("client_tick", lastClientTick,
                            "observation_revision", lastObservationRevision),
                    Map.of("client_tick", frame.clientTick(),
                            "observation_revision", frame.observationRevision()),
                    Map.of(), true));
            return;
        }
        long previousClientTick = lastClientTick;
        lastClientTick = frame.clientTick();
        lastObservationRevision = frame.observationRevision();
        updateVerification(frame);
        if (frame.clientTick() >= hardDeadlineClientTick) {
            fail(failure(
                    RoutineFailure.Category.TRANSIENT,
                    "HARD_DEADLINE_EXPIRED",
                    RoutineFailure.Recovery.REPLAN,
                    RoutineFailure.Scope.ROUTINE,
                    Map.of("before_client_tick", hardDeadlineClientTick),
                    Map.of("client_tick", frame.clientTick()),
                    Map.of("child_attempts", childAttempts), false));
            return;
        }
        var safety = safetyFailure(frame);
        if (safety != null) {
            fail(safety);
            return;
        }
        var worldDivergence = requiredCellDivergence(frame, previousClientTick);
        if (worldDivergence != null) {
            fail(worldDivergence);
            return;
        }
        if (reacquisitionWaiting) {
            return;
        }
        try {
            tickFrame(frame);
        } catch (SafeBreakSourcePolicy.UnsafeBreakSourceException rejected) {
            fail(precondition(
                    "UNSAFE_BREAK_SOURCE",
                    Map.of("safe_break_source", true),
                    Map.of("safe_break_source", false)));
        } catch (SafePlacementSupportPolicy.UnsafePlacementSupportException rejected) {
            fail(precondition(
                    "UNSAFE_PLACEMENT_SUPPORT",
                    Map.of("safe_placement_support", true),
                    Map.of("safe_placement_support", false)));
        } catch (RuntimeException | LinkageError adapterFailure) {
            fail(adapterFailure("PLAN_ACTION_ADAPTER_FAILURE"));
        }
    }

    private void tickFrame(ApplyBlockPlanFrame frame) {
        switch (phase) {
            case "queued" -> startPhase(PREFLIGHT, RoutineState.VALIDATING);
            case PREFLIGHT -> preflight(frame);
            case PREPARE -> beginPreparation(frame);
            case WAIT_PREPARE -> awaitPreparation();
            case DISPATCH -> dispatch(frame);
            case WAIT_WORLD_DIFF -> awaitWorldDiff();
            case FRESH_OBSERVE -> awaitFreshObservation(frame);
            case FINAL_VERIFY -> finalVerify(frame);
            default -> fail(adapterFailure("INVALID_PLAN_PHASE"));
        }
    }

    private void preflight(ApplyBlockPlanFrame frame) {
        if (unknownCells != 0) {
            fail(precondition(
                    "REQUIRED_CELL_UNKNOWN",
                    Map.of("unknown", 0),
                    Map.of("unknown", unknownCells)));
            return;
        }

        var requiredItems = new LinkedHashMap<String, Integer>();
        for (int index = 0; index < request.steps().size(); index++) {
            var step = request.steps().get(index);
            var cell = frame.cells().get(step.target());
            var live = cell.liveState().orElseThrow();
            if (exact(step.expectedAfter(), live)) {
                verifyCell(index, frame.observationRevision(), true);
                continue;
            }
            if (step.operation() == ApplyBlockPlanOperation.VERIFY_ONLY) {
                fail(divergence("VERIFY_ONLY_MISMATCH", step.expectedAfter(), live, index));
                return;
            }
            if (!exact(step.expectedBefore(), live)) {
                fail(divergence(
                        "PRECONDITION_MISMATCH",
                        step.expectedBefore(), live, index));
                return;
            }
            if (!cell.safeStandAvailable()) {
                fail(precondition(
                        "STEP_NOT_PREPARABLE",
                        Map.of("safe_stand_available", true, "step_index", index),
                        Map.of("safe_stand_available", false, "step_index", index)));
                return;
            }
            if (step.operation() == ApplyBlockPlanOperation.PLACE && !cell.replaceable()) {
                fail(precondition(
                        "TARGET_NOT_REPLACEABLE",
                        Map.of("replaceable", true, "step_index", index),
                        Map.of("replaceable", false, "step_index", index)));
                return;
            }
            step.requiredItemId().ifPresent(item ->
                    requiredItems.merge(item, 1, Integer::sum));
        }

        // A plan may contain placement entries which were all already satisfied.  Those
        // entries consume no inventory and must not turn an otherwise read-only exact-state
        // verification into an inventory-synchronization dependency.
        resourceSynchronizationRequired = !requiredItems.isEmpty();

        if (!requiredItems.isEmpty() && !frame.inventoryServerSynchronized()) {
            fail(precondition(
                    "INVENTORY_NOT_SERVER_SYNCHRONIZED",
                    Map.of("inventory_server_synchronized", true),
                    Map.of("inventory_server_synchronized", false)));
            return;
        }
        for (var entry : requiredItems.entrySet()) {
            int available = frame.inventoryCounts().getOrDefault(entry.getKey(), 0);
            if (available < entry.getValue()) {
                fail(precondition(
                        "INSUFFICIENT_RESOURCES",
                        Map.of("item", entry.getKey(), "minimum_count", entry.getValue()),
                        Map.of("item", entry.getKey(), "available_count", available)));
                return;
            }
            if (!frame.hotbarItemIds().contains(entry.getKey())) {
                fail(precondition(
                        "REQUIRED_ITEM_NOT_IN_HOTBAR",
                        Map.of("item", entry.getKey(), "in_hotbar", true),
                        Map.of("item", entry.getKey(), "in_hotbar", false)));
                return;
            }
        }

        if (verifiedCells == request.steps().size()) {
            requireFreshThenFinalVerify(frame.clientTick(), frame.observationRevision());
            return;
        }
        selectNextChild();
        startPhase(PREPARE, RoutineState.RUNNING);
    }

    private void beginPreparation(ApplyBlockPlanFrame frame) {
        if (!remainingResourcesCurrent(frame)) {
            return;
        }
        var current = requireCurrentCell(frame);
        if (current == null) {
            return;
        }
        var issued = Objects.requireNonNull(
                port.beginPreparation(request, child, hardDeadlineClientTick),
                "adapter returned no preparation attempt");
        if (issued.stepIndex() != child.stepIndex()
                || issued.issuedClientTick() != frame.clientTick()
                || issued.issuedObservationRevision() < frame.observationRevision()
                || issued.leaseExpiresAtClientTick() != hardDeadlineClientTick) {
            try {
                port.releasePreparation(issued);
            } catch (RuntimeException | LinkageError ignored) {
            }
            throw new IllegalStateException("adapter violated preparation attempt contract");
        }
        preparation = issued;
        startPhase(WAIT_PREPARE, RoutineState.WAITING);
    }

    private void awaitPreparation() {
        var current = Objects.requireNonNull(preparation, "no preparation attempt");
        var evidence = Objects.requireNonNull(
                port.preparationEvidence(current), "adapter returned no preparation evidence");
        if (!current.attemptId().equals(evidence.attemptId())
                || evidence.clientTick() < current.issuedClientTick()
                || evidence.observationRevision() < current.issuedObservationRevision()) {
            throw new IllegalStateException("adapter violated preparation evidence contract");
        }
        if (evidence.failure() != null) {
            fail(noBlindRetryFailure(evidence.failure()));
            return;
        }
        if (!evidence.prepared()) {
            port.maintainPreparation(current);
            return;
        }
        var live = evidence.liveState().orElseThrow();
        if (!exact(child.expectedBefore(), live)) {
            fail(divergence(
                    "PRECONDITION_CHANGED_DURING_PREPARE",
                    child.expectedBefore(), live, child.stepIndex()));
            return;
        }
        if (child.stage() == ApplyBlockPlanChildStage.PLACE
                && !evidence.targetReplaceable()) {
            fail(precondition(
                    "TARGET_NOT_REPLACEABLE",
                    Map.of("replaceable", true, "step_index", child.stepIndex()),
                    Map.of("replaceable", false, "step_index", child.stepIndex())));
            return;
        }
        startPhase(DISPATCH, RoutineState.RUNNING);
    }

    private void dispatch(ApplyBlockPlanFrame frame) {
        if (!remainingResourcesCurrent(frame)) {
            return;
        }
        if (requireCurrentCell(frame) == null) {
            return;
        }
        var currentPreparation = Objects.requireNonNull(preparation, "no preparation attempt");
        var issued = Objects.requireNonNull(
                port.dispatchPrepared(request, child, currentPreparation, hardDeadlineClientTick),
                "adapter returned no action attempt");
        if (issued.stepIndex() != child.stepIndex()
                || issued.issuedClientTick() != frame.clientTick()
                || issued.issuedObservationRevision() < frame.observationRevision()
                || issued.leaseExpiresAtClientTick() != hardDeadlineClientTick) {
            try {
                port.releaseAction(issued);
            } catch (RuntimeException | LinkageError ignored) {
            }
            throw new IllegalStateException("adapter violated action attempt contract");
        }
        action = issued;
        childAttempts++;
        releasePreparationStrict();
        startPhase(WAIT_WORLD_DIFF, RoutineState.WAITING);
    }

    private void awaitWorldDiff() {
        var current = Objects.requireNonNull(action, "no action attempt");
        var evidence = Objects.requireNonNull(
                port.actionEvidence(current), "adapter returned no action evidence");
        if (!current.attemptId().equals(evidence.attemptId())
                || evidence.clientTick() < current.issuedClientTick()
                || evidence.observationRevision() < current.issuedObservationRevision()) {
            throw new IllegalStateException("adapter violated action evidence contract");
        }
        if (evidence.failure() != null) {
            fail(noBlindRetryFailure(evidence.failure()));
            return;
        }
        if (!evidence.acknowledged() || !evidence.worldDiffObserved()) {
            port.maintainAction(current);
            return;
        }
        if (evidence.liveStateAfter().isEmpty()) {
            fail(failure(
                    RoutineFailure.Category.DIVERGENCE,
                    "WORLD_DIFF_NOT_CONFIRMED",
                    RoutineFailure.Recovery.REPLAN,
                    RoutineFailure.Scope.STEP,
                    stateMap(child.expectedAfter()),
                    evidence.liveStateAfter().map(ApplyBlockPlanRoutine::stateMap)
                            .orElseGet(() -> Map.of("currentness", "unknown")),
                    evidence.basis(), false));
            return;
        }
        var after = evidence.liveStateAfter().orElseThrow();
        if (!exact(child.expectedAfter(), after)) {
            fail(failure(
                    RoutineFailure.Category.DIVERGENCE,
                    "POSTCONDITION_MISMATCH",
                    RoutineFailure.Recovery.REPLAN,
                    RoutineFailure.Scope.STEP,
                    stateMap(child.expectedAfter()), stateMap(after), evidence.basis(), false));
            return;
        }

        releaseActionStrict();
        checkpointSeq++;
        checkpointRevision = Math.max(checkpointRevision, evidence.observationRevision());
        effects.add(new RoutineEffect(
                child.stage() == ApplyBlockPlanChildStage.BREAK
                        ? "block_broken" : "block_placed",
                stateMap(child.expectedBefore()),
                stateMap(after),
                RoutineEffect.Verification.CONFIRMED));
        events.append(RoutineEventType.CHECKPOINT, lastClientTick, checkpointRevision,
                Map.of("checkpoint_seq", checkpointSeq,
                        "step_index", child.stepIndex(),
                        "cell_id", child.entryId(),
                        "stage", child.stage().wireName()));

        var step = request.steps().get(child.stepIndex());
        if (step.operation() == ApplyBlockPlanOperation.REPLACE
                && child.stage() == ApplyBlockPlanChildStage.BREAK) {
            child = ApplyBlockPlanChildAction.replacementPlacement(child.stepIndex(), step);
        } else {
            verifyCell(child.stepIndex(), checkpointRevision, false);
            selectNextChild();
        }
        freshAfterTick = evidence.clientTick();
        freshAfterRevision = evidence.observationRevision();
        finalVerifyPending = verifiedCells == request.steps().size();
        startPhase(FRESH_OBSERVE, RoutineState.WAITING);
    }

    private void awaitFreshObservation(ApplyBlockPlanFrame frame) {
        if (frame.clientTick() <= freshAfterTick
                || frame.observationRevision() < freshAfterRevision) {
            return;
        }
        if (resourceSynchronizationRequired
                && !frame.inventoryServerSynchronized()) {
            return;
        }
        if (finalVerifyPending) {
            startPhase(FINAL_VERIFY, RoutineState.VALIDATING);
        } else {
            startPhase(PREPARE, RoutineState.RUNNING);
        }
    }

    private void finalVerify(ApplyBlockPlanFrame frame) {
        updateVerification(frame);
        if (resourceSynchronizationRequired
                && !frame.inventoryServerSynchronized()) {
            freshAfterTick = frame.clientTick();
            freshAfterRevision = frame.observationRevision();
            finalVerifyPending = true;
            startPhase(FRESH_OBSERVE, RoutineState.WAITING);
            return;
        }
        if (unknownCells != 0 || confirmedCells != request.steps().size()) {
            fail(failure(
                    RoutineFailure.Category.DIVERGENCE,
                    "FINAL_PLAN_VERIFICATION_FAILED",
                    RoutineFailure.Recovery.REPLAN,
                    RoutineFailure.Scope.ROUTINE,
                    Map.of("confirmed", request.steps().size(), "unknown", 0),
                    Map.of("confirmed", confirmedCells, "unknown", unknownCells),
                    Map.of("same_tick", frame.clientTick(),
                            "observation_revision", frame.observationRevision()), false));
            return;
        }
        goalVerified = true;
        events.append(RoutineEventType.GOAL_VERIFIED, lastClientTick, lastObservationRevision,
                Map.of("kind", kind(), "confirmed", confirmedCells, "unknown", unknownCells));
        releaseAll();
        state = RoutineState.FINALIZING;
        phase = "finalizing";
        events.append(RoutineEventType.FINALIZATION_STARTED, lastClientTick,
                lastObservationRevision, Map.of("phase", phase));
    }

    private ApplyBlockPlanCellObservation requireCurrentCell(ApplyBlockPlanFrame frame) {
        var cell = frame.cells().get(child.target());
        if (cell == null || cell.liveState().isEmpty()) {
            fail(precondition(
                    "REQUIRED_CELL_UNKNOWN",
                    Map.of("unknown", 0, "step_index", child.stepIndex()),
                    Map.of("unknown", 1, "step_index", child.stepIndex())));
            return null;
        }
        var live = cell.liveState().orElseThrow();
        if (!exact(child.expectedBefore(), live)) {
            fail(divergence(
                    "PRECONDITION_CHANGED",
                    child.expectedBefore(), live, child.stepIndex()));
            return null;
        }
        if (!cell.safeStandAvailable() || !cell.aimFeasible()) {
            fail(precondition(
                    "STEP_NOT_PREPARABLE",
                    Map.of("safe_stand_available", true, "aim_feasible", true,
                            "step_index", child.stepIndex()),
                    Map.of("safe_stand_available", cell.safeStandAvailable(),
                            "aim_feasible", cell.aimFeasible(),
                            "step_index", child.stepIndex())));
            return null;
        }
        if (child.stage() == ApplyBlockPlanChildStage.PLACE) {
            var item = child.requiredItemId().orElseThrow();
            if (!cell.replaceable()) {
                fail(precondition(
                        "TARGET_NOT_REPLACEABLE",
                        Map.of("replaceable", true, "step_index", child.stepIndex()),
                        Map.of("replaceable", false, "step_index", child.stepIndex())));
                return null;
            }
            if (frame.inventoryCounts().getOrDefault(item, 0) < 1
                    || !frame.hotbarItemIds().contains(item)
                    || !frame.inventoryServerSynchronized()) {
                fail(precondition(
                        "RESOURCE_OR_HOTBAR_CHANGED",
                        Map.of("item", item, "minimum_count", 1, "in_hotbar", true,
                                "inventory_server_synchronized", true),
                        Map.of("item", item,
                                "available_count", frame.inventoryCounts().getOrDefault(item, 0),
                                "in_hotbar", frame.hotbarItemIds().contains(item),
                                "inventory_server_synchronized",
                                frame.inventoryServerSynchronized())));
                return null;
            }
        }
        return cell;
    }

    private boolean remainingResourcesCurrent(ApplyBlockPlanFrame frame) {
        var remaining = new LinkedHashMap<String, Integer>();
        for (int index = 0; index < request.steps().size(); index++) {
            if (!verified[index]) {
                request.steps().get(index).requiredItemId()
                        .ifPresent(item -> remaining.merge(item, 1, Integer::sum));
            }
        }
        if (remaining.isEmpty()) {
            return true;
        }
        if (!frame.inventoryServerSynchronized()) {
            fail(precondition(
                    "RESOURCE_OR_HOTBAR_CHANGED",
                    Map.of("inventory_server_synchronized", true,
                            "remaining", Map.copyOf(remaining)),
                    Map.of("inventory_server_synchronized", false,
                            "remaining", Map.copyOf(remaining))));
            return false;
        }
        for (var entry : remaining.entrySet()) {
            int available = frame.inventoryCounts().getOrDefault(entry.getKey(), 0);
            boolean inHotbar = frame.hotbarItemIds().contains(entry.getKey());
            if (available < entry.getValue() || !inHotbar) {
                fail(precondition(
                        "RESOURCE_OR_HOTBAR_CHANGED",
                        Map.of(
                                "item", entry.getKey(),
                                "minimum_count", entry.getValue(),
                                "in_hotbar", true,
                                "inventory_server_synchronized", true),
                        Map.of(
                                "item", entry.getKey(),
                                "available_count", available,
                                "in_hotbar", inHotbar,
                                "inventory_server_synchronized", true)));
                return false;
            }
        }
        return true;
    }

    private void selectNextChild() {
        for (int index = 0; index < verified.length; index++) {
            if (!verified[index]) {
                currentStepIndex = index;
                child = ApplyBlockPlanChildAction.first(index, request.steps().get(index));
                return;
            }
        }
        child = null;
    }

    private void verifyCell(int index, long observationRevision, boolean skipped) {
        if (verified[index]) {
            return;
        }
        verified[index] = true;
        verifiedCells++;
        if (skipped) {
            checkpointSeq++;
        }
        checkpointRevision = Math.max(checkpointRevision, observationRevision);
        events.append(RoutineEventType.STEP_VERIFIED, lastClientTick, checkpointRevision,
                Map.of("step_index", index, "operation",
                        request.steps().get(index).operation().wireName(),
                        "cell_id", request.steps().get(index).id(), "skipped", skipped));
    }

    private void requireFreshThenFinalVerify(long tick, long revision) {
        freshAfterTick = tick;
        freshAfterRevision = revision;
        finalVerifyPending = true;
        startPhase(FRESH_OBSERVE, RoutineState.WAITING);
    }

    private void updateVerification(ApplyBlockPlanFrame frame) {
        lastInventoryCounts = frame.inventoryCounts();
        lastInventoryServerSynchronized = frame.inventoryServerSynchronized();
        int confirmed = 0;
        int unknown = 0;
        for (var step : request.steps()) {
            var cell = frame.cells().get(step.target());
            if (cell == null || cell.liveState().isEmpty()) {
                unknown++;
            } else if (exact(step.expectedAfter(), cell.liveState().orElseThrow())) {
                confirmed++;
            }
        }
        confirmedCells = confirmed;
        unknownCells = unknown;
    }

    private RoutineFailure safetyFailure(ApplyBlockPlanFrame frame) {
        String code = !frame.worldReady() ? "WORLD_UNAVAILABLE"
                : !frame.controlContextClear() ? "CONTROL_CONTEXT_CHANGED"
                : !frame.playerAlive() ? "PLAYER_NOT_ALIVE"
                : !frame.healthSafe() ? "HEALTH_UNSAFE"
                : !frame.visibleThreatClear() ? "VISIBLE_THREAT"
                : !frame.screenClear() ? "UNEXPECTED_SCREEN"
                : null;
        if (code != null) {
            return failure(
                    RoutineFailure.Category.SAFETY, code,
                    RoutineFailure.Recovery.USER, RoutineFailure.Scope.ROUTINE,
                    Map.of("safe", true), Map.of("safe", false), Map.of(), true);
        }
        if (standMonitoringArmed() && !mutationStandReady(frame)) {
            return failure(
                    RoutineFailure.Category.SAFETY, "SAFE_STAND_CHANGED",
                    RoutineFailure.Recovery.REPLAN, RoutineFailure.Scope.ROUTINE,
                    Map.of("safe_stand", true), Map.of("safe_stand", false),
                    Map.of("phase", phase), false);
        }
        return null;
    }

    private boolean standMonitoringArmed() {
        return !phase.equals("queued") && !phase.equals(PREFLIGHT)
                && request.steps().stream()
                        .anyMatch(step -> step.operation() != ApplyBlockPlanOperation.VERIFY_ONLY);
    }

    private boolean mutationStandReady(ApplyBlockPlanFrame frame) {
        for (var step : request.steps()) {
            if (step.operation() == ApplyBlockPlanOperation.VERIFY_ONLY) continue;
            var cell = frame.cells().get(step.target());
            if (cell == null || !cell.safeStandAvailable()) return false;
        }
        return true;
    }

    private RoutineFailure requiredCellDivergence(
            ApplyBlockPlanFrame frame, long previousClientTick) {
        reacquisitionWaiting = false;
        if (phase.equals("queued") || phase.equals(PREFLIGHT)) {
            resetReacquisitionGrace();
            return null;
        }
        boolean globalReconcile = phase.equals(PREPARE)
                || phase.equals(FRESH_OBSERVE)
                || phase.equals(FINAL_VERIFY);
        int firstUnknownIndex = -1;
        BlockStateFingerprint firstUnknownExpected = null;
        for (int index = 0; index < verified.length; index++) {
            if (!globalReconcile && (child == null || child.stepIndex() != index)) {
                // Preparation may rotate the camera away from otherwise-current cells. Only
                // the owned child remains a valid live visibility requirement until release.
                continue;
            }
            if (!verified[index]
                    && action != null
                    && child != null
                    && child.stepIndex() == index) {
                // The in-flight child may change only its own cell. World-diff evidence below
                // is the authoritative reconciliation boundary for that transition.
                continue;
            }
            var step = request.steps().get(index);
            var expected = verified[index]
                    ? step.expectedAfter()
                    : child != null && child.stepIndex() == index
                            ? child.expectedBefore()
                            : step.expectedBefore();
            var cell = frame.cells().get(step.target());
            if (cell == null || cell.liveState().isEmpty()) {
                if (firstUnknownIndex < 0) {
                    firstUnknownIndex = index;
                    firstUnknownExpected = expected;
                }
                continue;
            }
            var live = cell.liveState().orElseThrow();
            if (!exact(expected, live)) {
                resetReacquisitionGrace();
                return worldDiverged(
                        index, expected, stateMap(live),
                        Map.of("reason", "exact_state_mismatch"));
            }
        }
        if (firstUnknownIndex >= 0) {
            return handleUnknownRequiredCell(
                    frame,
                    previousClientTick,
                    firstUnknownIndex,
                    Objects.requireNonNull(firstUnknownExpected));
        }
        resetReacquisitionGrace();
        return null;
    }

    private RoutineFailure handleUnknownRequiredCell(
            ApplyBlockPlanFrame frame,
            long previousClientTick,
            int stepIndex,
            BlockStateFingerprint expected) {
        var step = request.steps().get(stepIndex);
        boolean graceEligible = phase.equals(FRESH_OBSERVE) || phase.equals(FINAL_VERIFY);
        if (!graceEligible) {
            resetReacquisitionGrace();
            return worldDiverged(
                    stepIndex, expected, Map.of("currentness", "unknown"),
                    Map.of("reason", "unknown_outside_reacquisition"));
        }

        long expectedTick = previousClientTick == Long.MAX_VALUE
                ? Long.MAX_VALUE : previousClientTick + 1;
        if (frame.clientTick() != expectedTick) {
            resetReacquisitionGrace();
            return worldDiverged(
                    stepIndex, expected, Map.of("currentness", "unknown"),
                    Map.of(
                            "reason", "non_contiguous_reacquisition_tick",
                            "previous_client_tick", previousClientTick,
                            "client_tick", frame.clientTick()));
        }

        if (!phase.equals(reacquisitionGracePhase)) {
            reacquisitionGracePhase = phase;
            reacquisitionGraceTicks = 0;
            reacquisitionLastClientTick = previousClientTick;
        }
        long nextGraceTick = reacquisitionLastClientTick == Long.MAX_VALUE
                ? Long.MAX_VALUE : reacquisitionLastClientTick + 1;
        if (frame.clientTick() != nextGraceTick) {
            resetReacquisitionGrace();
            return worldDiverged(
                    stepIndex, expected, Map.of("currentness", "unknown"),
                    Map.of(
                            "reason", "invalid_reacquisition_sequence",
                            "previous_reacquisition_tick", reacquisitionLastClientTick,
                            "client_tick", frame.clientTick()));
        }

        reacquisitionLastClientTick = frame.clientTick();
        reacquisitionGraceTicks++;
        if (reacquisitionGraceTicks > MAX_REACQUISITION_GRACE_TICKS) {
            int elapsed = reacquisitionGraceTicks;
            resetReacquisitionGrace();
            return worldDiverged(
                    stepIndex, expected, Map.of("currentness", "unknown"),
                    Map.of(
                            "reason", "reacquisition_grace_expired",
                            "elapsed_client_ticks", elapsed,
                            "maximum_client_ticks", MAX_REACQUISITION_GRACE_TICKS));
        }
        reacquisitionWaiting = true;
        return null;
    }

    private RoutineFailure worldDiverged(
            int stepIndex,
            BlockStateFingerprint expected,
            Map<String, Object> observed,
            Map<String, Object> additionalEvidence) {
        var step = request.steps().get(stepIndex);
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("step_index", stepIndex);
        evidence.put("cell_id", step.id());
        evidence.putAll(additionalEvidence);
        return failure(
                RoutineFailure.Category.DIVERGENCE,
                "WORLD_DIVERGED",
                RoutineFailure.Recovery.REPLAN,
                RoutineFailure.Scope.ROUTINE,
                stateMap(expected), observed, evidence, false);
    }

    private void resetReacquisitionGrace() {
        reacquisitionGracePhase = null;
        reacquisitionGraceTicks = 0;
        reacquisitionLastClientTick = -1;
        reacquisitionWaiting = false;
    }

    private RoutineFailure precondition(
            String code, Map<String, Object> expected, Map<String, Object> observed) {
        return failure(
                RoutineFailure.Category.PRECONDITION, code,
                RoutineFailure.Recovery.REPLAN, RoutineFailure.Scope.STEP,
                expected, observed, Map.of(), false);
    }

    private RoutineFailure divergence(
            String code,
            BlockStateFingerprint expected,
            BlockStateFingerprint observed,
            int stepIndex) {
        return failure(
                RoutineFailure.Category.DIVERGENCE, code,
                RoutineFailure.Recovery.REPLAN, RoutineFailure.Scope.STEP,
                stateMap(expected), stateMap(observed), Map.of("step_index", stepIndex), false);
    }

    private RoutineFailure adapterFailure(String code) {
        return failure(
                RoutineFailure.Category.EXTERNAL, code,
                RoutineFailure.Recovery.USER, RoutineFailure.Scope.ROUTINE,
                Map.of(), Map.of(), Map.of("child_attempts", childAttempts), true);
    }

    private RoutineFailure noBlindRetryFailure(RoutineFailure source) {
        if (!source.retryable() && source.recovery() != RoutineFailure.Recovery.RETRY) {
            return source;
        }
        return new RoutineFailure(
                source.category(), source.code(), false,
                RoutineFailure.Recovery.REPLAN, source.scope(), childAttempts,
                source.expected(), source.observed(), source.evidence(),
                source.suggestedSnapshotScopes(), source.requiresUser());
    }

    private RoutineFailure failure(
            RoutineFailure.Category category,
            String code,
            RoutineFailure.Recovery recovery,
            RoutineFailure.Scope scope,
            Map<String, Object> expected,
            Map<String, Object> observed,
            Map<String, Object> evidence,
            boolean requiresUser) {
        return new RoutineFailure(
                category, code, false, recovery, scope, childAttempts,
                expected, observed, evidence, List.of("player", "target", "inventory"),
                requiresUser);
    }

    private void startPhase(String next, RoutineState nextState) {
        phase = next;
        state = nextState;
        events.append(RoutineEventType.PHASE_STARTED, lastClientTick, lastObservationRevision,
                Map.of("phase", next));
    }

    private void fail(RoutineFailure outcome) {
        if (state.terminal()) {
            return;
        }
        releaseAll();
        failure = Objects.requireNonNull(outcome, "outcome");
        state = RoutineState.FAILED;
        phase = "failed";
        if (outcome.recovery() == RoutineFailure.Recovery.REPLAN) {
            events.append(RoutineEventType.NEEDS_REPLAN, lastClientTick, lastObservationRevision,
                    Map.of("code", outcome.code(),
                            "suggested_snapshot_scopes", outcome.suggestedSnapshotScopes()));
        }
        events.append(RoutineEventType.FAILED, lastClientTick, lastObservationRevision,
                Map.of("category", outcome.category().wireName(),
                        "code", outcome.code(), "attempts", outcome.attempts()));
    }

    private void releasePreparation() {
        var current = preparation;
        preparation = null;
        if (current != null) {
            try {
                port.releasePreparation(current);
            } catch (RuntimeException | LinkageError ignored) {
            }
        }
    }

    private void releasePreparationStrict() {
        var current = preparation;
        preparation = null;
        if (current != null) {
            port.releasePreparation(current);
        }
    }

    private void releaseAction() {
        var current = action;
        action = null;
        if (current != null) {
            try {
                port.releaseAction(current);
            } catch (RuntimeException | LinkageError ignored) {
            }
        }
    }

    private void releaseActionStrict() {
        var current = action;
        action = null;
        if (current != null) {
            port.releaseAction(current);
        }
    }

    private void releaseAll() {
        releaseAction();
        releasePreparation();
    }

    @Override
    public void cancel(String reason) {
        if (state.terminal()) {
            return;
        }
        releaseAll();
        state = RoutineState.CANCELLED;
        phase = "cancelled";
        events.append(RoutineEventType.CANCELLED, lastClientTick, lastObservationRevision,
                Map.of("reason", sanitizeReason(reason)));
    }

    @Override
    public void completeFinalization(RoutineFailure cleanupFailure) {
        if (state != RoutineState.FINALIZING) {
            throw new IllegalStateException("routine is not finalizing");
        }
        if (cleanupFailure != null
                && cleanupFailure.scope() != RoutineFailure.Scope.FINALIZATION) {
            throw new IllegalArgumentException("finalization failure must use FINALIZATION scope");
        }
        finalizationCompleted = true;
        finalizationFailure = cleanupFailure;
        if (cleanupFailure != null) {
            fail(cleanupFailure);
            return;
        }
        state = RoutineState.SUCCEEDED;
        phase = "succeeded";
        events.append(RoutineEventType.SUCCEEDED, lastClientTick, lastObservationRevision,
                Map.of("attempts", childAttempts));
    }

    @Override
    public void recordTerminalFinalization(RoutineFailure cleanupFailure) {
        if (!state.terminal()) {
            throw new IllegalStateException("routine is not terminal");
        }
        if (cleanupFailure != null
                && cleanupFailure.scope() != RoutineFailure.Scope.FINALIZATION) {
            throw new IllegalArgumentException("cleanup failure must use FINALIZATION scope");
        }
        if (finalizationCompleted) {
            if (!Objects.equals(finalizationFailure, cleanupFailure)) {
                throw new IllegalStateException("terminal finalization outcome is already recorded");
            }
            return;
        }
        finalizationCompleted = true;
        finalizationFailure = cleanupFailure;
    }

    @Override
    public RoutineSnapshot snapshot(long afterEventSeq, int maxEvents) {
        return new RoutineSnapshot(
                routineId, kind(), state, phase, goalVerified,
                new RoutineProgress(verifiedCells, request.steps().size(), "cells"),
                state.terminal() ? null : currentStep(),
                new RoutineCheckpoint(checkpointSeq, checkpointRevision),
                new RoutineVerification(confirmedCells, request.steps().size(), unknownCells),
                List.copyOf(effects),
                state == RoutineState.WAITING ? waitState() : null,
                lastClientTick,
                diagnostics(),
                failure, finalizationCompleted, finalizationFailure,
                events.page(afterEventSeq, maxEvents));
    }

    private Map<String, Object> diagnostics() {
        var planned = request.requiredResources();
        var remaining = new LinkedHashMap<String, Integer>();
        for (int index = 0; index < request.steps().size(); index++) {
            if (!verified[index]) {
                request.steps().get(index).requiredItemId()
                        .ifPresent(item -> remaining.merge(item, 1, Integer::sum));
            }
        }
        var available = new LinkedHashMap<String, Integer>();
        planned.keySet().forEach(item ->
                available.put(item, lastInventoryCounts.getOrDefault(item, 0)));
        return Map.of(
                "child_attempts", childAttempts,
                "verified_cells", verifiedCells,
                "required_unknown", unknownCells,
                "blind_retries", 0,
                "phase", Map.of(
                        "id", request.phaseId(),
                        "index", request.phaseIndex(),
                        "total", request.phaseTotal()),
                "resource_plan", Map.of(
                        "planned", planned,
                        "remaining", Map.copyOf(remaining),
                        "available", Map.copyOf(available),
                        "server_synchronized", lastInventoryServerSynchronized,
                        "basis_observation_revision", lastObservationRevision));
    }

    private RoutineStep currentStep() {
        int index = Math.min(currentStepIndex, request.steps().size() - 1);
        var step = request.steps().get(index);
        var fields = new LinkedHashMap<String, Object>();
        fields.put("step_index", index);
        fields.put("phase_id", request.phaseId());
        fields.put("cell_id", step.id());
        fields.put("operation", step.operation().wireName());
        fields.put("target", targetMap(step.target()));
        fields.put("expected_after", stateMap(step.expectedAfter()));
        if (child != null) {
            fields.put("child_stage", child.stage().wireName());
        }
        step.requiredItemId().ifPresent(item -> fields.put("item", item));
        return new RoutineStep("plan_cell", fields);
    }

    private RoutineWait waitState() {
        String reason = switch (phase) {
            case WAIT_PREPARE -> "bounded_preparation";
            case WAIT_WORLD_DIFF -> "server_world_diff";
            case FRESH_OBSERVE -> "fresh_plan_observation";
            default -> "plan_progress";
        };
        String wake = switch (phase) {
            case WAIT_PREPARE -> "stand, aim, hotbar and live target state are ready";
            case WAIT_WORLD_DIFF -> "server acknowledgement and an exact world diff are observed";
            case FRESH_OBSERVE -> "a later client tick contains a fresh live observation";
            default -> "the next bounded plan phase is ready";
        };
        return new RoutineWait(reason, hardDeadlineClientTick, wake);
    }

    @Override
    public UUID routineId() {
        return routineId;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public RoutineState state() {
        return state;
    }

    @Override
    public long lastClientTick() {
        return lastClientTick;
    }

    @Override
    public void retire() {
        if (retired) {
            return;
        }
        retired = true;
        releaseAll();
        port.retire(request);
    }

    private static boolean exact(BlockStateFingerprint expected, BlockStateFingerprint observed) {
        return expected.equals(observed);
    }

    private static Map<String, Object> stateMap(BlockStateFingerprint state) {
        return Map.of("block", state.blockId(), "properties", state.properties());
    }

    private static Map<String, Object> targetMap(BlockTarget target) {
        return Map.of(
                "dimension", target.dimension(),
                "x", target.x(), "y", target.y(), "z", target.z());
    }

    private static String sanitizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "operator_cancel";
        }
        var normalized = reason.replaceAll("[\\p{Cntrl}]", " ").strip();
        return normalized.substring(0, Math.min(normalized.length(), 96));
    }
}
