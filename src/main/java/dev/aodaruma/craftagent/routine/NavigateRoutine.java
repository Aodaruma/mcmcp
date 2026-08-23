package dev.aodaruma.craftagent.routine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Separate movement/settle FSM requiring both physical stability and reconciliation evidence. */
final class NavigateRoutine extends AbstractSemanticRoutine {
    private static final String PRECHECK = "precheck";
    private static final String EXECUTE = "execute";
    private static final String MOVE = "move";
    private static final String WAIT_ROUTE_CLEAR = "wait_route_clear";
    private static final String SETTLE = "settle";
    private static final String VERIFY = "verify";
    private static final String RETRY_FRESH = "retry_fresh_observation";
    private static final int REQUIRED_SETTLED_TICKS = 10;
    private static final double MAX_SETTLED_VELOCITY_SQUARED = 0.01D;
    private static final double MAX_SETTLE_DRIFT_SQUARED = 0.1D * 0.1D;
    private static final double MAX_VERTICAL_DISTANCE = 1.0D;
    static final int MAX_ROUTE_WAIT_TICKS = 40;

    private final NavigateToRequest navigation;
    private int retries;
    private int verifiedDestinations;
    private long retryAfterTick;
    private long routeWaitDeadlineTick;
    private String routeWaitReason;
    private String routeWaitResumePhase;
    private int consecutiveSettledTicks;
    private double settleAnchorX;
    private double settleAnchorY;
    private double settleAnchorZ;
    private long correctionWatermark;
    private SemanticActionEvidence lastEvidence;

    NavigateRoutine(
            UUID routineId,
            NavigateToRequest request,
            SemanticActionPort port,
            int eventCapacity,
            long admittedClientTick) {
        super(routineId, request, port, eventCapacity, admittedClientTick);
        navigation = request;
    }

    @Override
    protected void tickFrame(SemanticActionFrame frame) {
        switch (phase) {
            case "queued" -> startPhase(PRECHECK, RoutineState.VALIDATING);
            case PRECHECK -> precheck(frame);
            case EXECUTE -> execute(frame);
            case MOVE -> move(frame);
            case WAIT_ROUTE_CLEAR -> waitForRoute(frame);
            case SETTLE -> settle(frame);
            case VERIFY -> verify(frame);
            case RETRY_FRESH -> awaitFreshObservation(frame);
            default -> fail(adapterFailure("INVALID_ROUTINE_PHASE"));
        }
    }

    private void precheck(SemanticActionFrame frame) {
        if (frame.routeSafe()) {
            startPhase(EXECUTE, RoutineState.RUNNING);
            return;
        }
        if (frame.routeNeedsReobservation()) {
            // Dispatch acquires neutral movement/view ownership only. The adapter cannot press a
            // movement key until the ordinary first-person scan makes the next route cells CURRENT.
            startPhase(EXECUTE, RoutineState.RUNNING);
            return;
        }
        if (frame.routeTemporarilyOccupied()) {
            beginRouteWait(frame, EXECUTE);
            return;
        }
        fail(routeFailure("ROUTE_NOT_SAFE", frame));
    }

    private void execute(SemanticActionFrame frame) {
        var issued = dispatch(frame);
        correctionWatermark = issued.positionCorrectionRevisionAtDispatch();
        startPhase(MOVE, RoutineState.RUNNING);
    }

    private void move(SemanticActionFrame frame) {
        var observed = evidence();
        lastEvidence = observed;
        if (observed.failure() != null) {
            handleEvidenceFailure(observed, frame);
            return;
        }
        if (frame.positionCorrectionRevision() > correctionWatermark) {
            fail(unreportedCorrectionFailure("POSITION_CORRECTION_UNREPORTED", frame, observed));
            return;
        }
        if (frame.stationaryNavigation() && !withinDestination(frame)) {
            retryOrFail("DESTINATION_DRIFTED", frame);
            return;
        }
        if (frame.routeSafe() && withinDestination(frame)) {
            stopInput();
            consecutiveSettledTicks = 0;
            resetSettleAnchor(frame);
            startPhase(SETTLE, RoutineState.WAITING);
            return;
        }
        if (frame.routeTransientWait()) {
            port.maintain(attempt);
            beginRouteWait(frame, MOVE);
            return;
        }
        if (!frame.routeSafe()) {
            fail(routeFailure("ROUTE_BECAME_UNSAFE", frame));
            return;
        }
        // MovementInputLease is renewed by the adapter heartbeat. Its short watchdog is not
        // the navigation deadline; the shared routine hard deadline remains authoritative.
        port.maintain(attempt);
    }

    private void waitForRoute(SemanticActionFrame frame) {
        if (frame.clientTick() >= routeWaitDeadlineTick) {
            retryOrFail(routeWaitReason.equals("route_occupancy")
                    ? "ROUTE_OCCUPANCY_TIMEOUT"
                    : "ROUTE_REOBSERVATION_EXHAUSTED", frame);
            return;
        }
        if (frame.routeTransientWait()) {
            if (attempt != null) {
                port.maintain(attempt);
            }
            return;
        }
        if (frame.routeSafe()) {
            startPhase(routeWaitResumePhase, RoutineState.RUNNING);
            return;
        }
        if (attempt == null && frame.routeNeedsReobservation()) {
            startPhase(EXECUTE, RoutineState.RUNNING);
            return;
        }
        if (frame.routeTemporarilyOccupied()) {
            return;
        }
        fail(routeFailure("ROUTE_BECAME_UNSAFE", frame));
    }

    private void beginRouteWait(SemanticActionFrame frame, String resumePhase) {
        routeWaitReason = frame.routeTemporarilyOccupied()
                ? "route_occupancy" : "route_reobservation";
        routeWaitResumePhase = resumePhase;
        routeWaitDeadlineTick = Math.min(
                hardDeadlineClientTick, frame.clientTick() + MAX_ROUTE_WAIT_TICKS);
        startPhase(WAIT_ROUTE_CLEAR, RoutineState.WAITING);
    }

    private void settle(SemanticActionFrame frame) {
        if (!frame.routeSafe()) {
            fail(routeFailure("ROUTE_BECAME_UNSAFE", frame));
            return;
        }
        // The adapter keeps the stopped navigation attempt alive here. Its reconciliation
        // watcher owns packet-level position/rotation/motion correction detection and its
        // acknowledged bit only becomes true after its own ten-tick settle window.
        port.maintain(attempt);
        var observed = evidence();
        lastEvidence = observed;
        if (observed.failure() != null) {
            handleEvidenceFailure(observed, frame);
            return;
        }
        if (frame.positionCorrectionRevision() > correctionWatermark) {
            fail(unreportedCorrectionFailure(
                    "POSITION_CORRECTION_UNREPORTED_DURING_SETTLE", frame, observed));
            return;
        }
        if (frame.routeTransientWait()) {
            consecutiveSettledTicks = 0;
            resetSettleAnchor(frame);
            beginRouteWait(frame, SETTLE);
            return;
        }
        if (!withinDestination(frame)) {
            retryOrFail("DESTINATION_DRIFTED", frame);
            return;
        }
        if (!destinationVerified(frame) || !withinSettleDrift(frame)) {
            consecutiveSettledTicks = 0;
            resetSettleAnchor(frame);
            return;
        }
        consecutiveSettledTicks++;
        if (consecutiveSettledTicks < REQUIRED_SETTLED_TICKS || !observed.acknowledged()) {
            return;
        }
        startPhase(VERIFY, RoutineState.RUNNING);
    }

    private void verify(SemanticActionFrame frame) {
        if (frame.routeTransientWait()) {
            consecutiveSettledTicks = 0;
            resetSettleAnchor(frame);
            beginRouteWait(frame, SETTLE);
            return;
        }
        var observed = evidence();
        lastEvidence = observed;
        if (observed.failure() != null) {
            handleEvidenceFailure(observed, frame);
            return;
        }
        if (!destinationVerified(frame)
                || !withinSettleDrift(frame)
                || frame.positionCorrectionRevision() > correctionWatermark
                || !observed.acknowledged()) {
            fail(failure(
                    RoutineFailure.Category.DIVERGENCE,
                    "DESTINATION_NOT_STABLE",
                    false,
                    RoutineFailure.Recovery.REPLAN,
                    RoutineFailure.Scope.STEP,
                    expectedDestination(),
                    observedPosition(frame),
                    observed.basis(),
                    false));
            return;
        }
        verifiedDestinations = 1;
        events.append(RoutineEventType.STEP_VERIFIED, lastClientTick, lastObservationRevision,
                Map.of("kind", kind(), "server_reconciled", true, "attempts", attempts));
        beginFinalization();
    }

    private void handleEvidenceFailure(
            SemanticActionEvidence observed, SemanticActionFrame frame) {
        var outcome = observed.failure();
        boolean retryPermitted = outcome.retryable()
                && outcome.recovery() == RoutineFailure.Recovery.RETRY
                && observed.safeToRetry()
                && frame.safeToRetry()
                && retries < MAX_RETRIES;
        if (!retryPermitted) {
            fail(outcome.recovery() == RoutineFailure.Recovery.RETRY
                    ? asReplanFailure(outcome)
                    : outcome);
            return;
        }
        releaseCurrent();
        retries++;
        retryAfterTick = Math.max(observed.clientTick(), frame.clientTick());
        events.append(RoutineEventType.RETRYING, lastClientTick, lastObservationRevision,
                Map.of("code", outcome.code(), "retry", retries, "max_retries", MAX_RETRIES));
        startPhase(RETRY_FRESH, RoutineState.WAITING);
    }

    private void retryOrFail(String code, SemanticActionFrame frame) {
        var retryFailure = failure(
                RoutineFailure.Category.TRANSIENT,
                code,
                true,
                RoutineFailure.Recovery.RETRY,
                RoutineFailure.Scope.STEP,
                expectedDestination(),
                observedPosition(frame),
                Map.of("safe_to_retry", frame.safeToRetry(),
                        "position_correction_revision", frame.positionCorrectionRevision()),
                false);
        if (!frame.safeToRetry() || retries >= MAX_RETRIES) {
            fail(asReplanFailure(retryFailure));
            return;
        }
        releaseCurrent();
        retries++;
        retryAfterTick = frame.clientTick();
        events.append(RoutineEventType.RETRYING, lastClientTick, lastObservationRevision,
                Map.of("code", code, "retry", retries, "max_retries", MAX_RETRIES));
        startPhase(RETRY_FRESH, RoutineState.WAITING);
    }

    private void awaitFreshObservation(SemanticActionFrame frame) {
        // Navigation has no positive server ACK and does not mutate WorldMemory while sampling
        // live position/route facts. A later client tick is therefore the freshness boundary;
        // precheck immediately revalidates the live route and player state on that tick.
        if (frame.clientTick() <= retryAfterTick) {
            return;
        }
        startPhase(PRECHECK, RoutineState.VALIDATING);
    }

    private boolean destinationVerified(SemanticActionFrame frame) {
        return withinDestination(frame)
                && frame.onGround()
                && frame.playerHorizontalVelocitySquared() <= MAX_SETTLED_VELOCITY_SQUARED
                && frame.routeSafe();
    }

    private boolean withinDestination(SemanticActionFrame frame) {
        double dx = frame.playerX() - (navigation.target().x() + 0.5D);
        double dz = frame.playerZ() - (navigation.target().z() + 0.5D);
        double vertical = Math.abs(frame.playerY() - navigation.target().y());
        return dx * dx + dz * dz
                <= navigation.horizontalToleranceBlocks() * navigation.horizontalToleranceBlocks()
                && vertical <= MAX_VERTICAL_DISTANCE;
    }

    private boolean withinSettleDrift(SemanticActionFrame frame) {
        double dx = frame.playerX() - settleAnchorX;
        double dy = frame.playerY() - settleAnchorY;
        double dz = frame.playerZ() - settleAnchorZ;
        return dx * dx + dy * dy + dz * dz <= MAX_SETTLE_DRIFT_SQUARED;
    }

    private void resetSettleAnchor(SemanticActionFrame frame) {
        settleAnchorX = frame.playerX();
        settleAnchorY = frame.playerY();
        settleAnchorZ = frame.playerZ();
    }

    private RoutineFailure routeFailure(String code, SemanticActionFrame frame) {
        return failure(
                RoutineFailure.Category.SAFETY,
                code,
                false,
                RoutineFailure.Recovery.REPLAN,
                RoutineFailure.Scope.STEP,
                Map.of("route_safe", true),
                Map.of("route_safe", frame.routeSafe()),
                Map.of("route_check_reason", frame.routeCheckReason()),
                false);
    }

    private RoutineFailure asReplanFailure(RoutineFailure source) {
        return new RoutineFailure(
                source.category(),
                source.code(),
                false,
                RoutineFailure.Recovery.REPLAN,
                source.scope(),
                attempts,
                source.expected(),
                source.observed(),
                source.evidence(),
                source.suggestedSnapshotScopes(),
                source.requiresUser());
    }

    private RoutineFailure unreportedCorrectionFailure(
            String code, SemanticActionFrame frame, SemanticActionEvidence observed) {
        var basis = new LinkedHashMap<String, Object>(observed.basis());
        basis.put("dispatch_position_correction_revision", correctionWatermark);
        basis.put("current_position_correction_revision", frame.positionCorrectionRevision());
        return failure(
                RoutineFailure.Category.DIVERGENCE,
                code,
                false,
                RoutineFailure.Recovery.REPLAN,
                RoutineFailure.Scope.STEP,
                Map.of("position_correction_revision", correctionWatermark),
                Map.of("position_correction_revision", frame.positionCorrectionRevision()),
                basis,
                false);
    }

    @Override
    protected RoutineProgress progress() {
        return new RoutineProgress(verifiedDestinations, 1, "destinations");
    }

    @Override
    protected RoutineStep currentStep() {
        return new RoutineStep(kind(), Map.of(
                "target", targetMap(navigation.target()),
                "horizontal_tolerance_blocks", navigation.horizontalToleranceBlocks()));
    }

    @Override
    protected RoutineCheckpoint checkpoint() {
        return new RoutineCheckpoint(verifiedDestinations, lastObservationRevision);
    }

    @Override
    protected RoutineVerification verification() {
        return new RoutineVerification(
                verifiedDestinations, 1, verifiedDestinations == 0 ? 1 : 0);
    }

    @Override
    protected RoutineWait waitState() {
        if (phase.equals(WAIT_ROUTE_CLEAR)) {
            return new RoutineWait(
                    routeWaitReason,
                    routeWaitDeadlineTick,
                    routeWaitReason.equals("route_occupancy")
                            ? "the currently visible mob or player leaves the next route cell"
                            : "a fresh first-person scan makes all next route cells CURRENT");
        }
        return new RoutineWait(
                phase.equals(SETTLE) ? "movement_settle" : "server_sync",
                hardDeadlineClientTick,
                phase.equals(SETTLE)
                        ? "position remains stable without a server correction"
                        : "a later client tick is available for live route revalidation");
    }

    @Override
    protected Map<String, Object> diagnostics() {
        var result = new LinkedHashMap<String, Object>();
        result.put("attempts", attempts);
        result.put("retries", retries);
        result.put("position_correction_revision", correctionWatermark);
        result.put("verification_basis", "server_reconciled");
        if (lastEvidence != null) {
            result.put("reconciliation_acknowledged", lastEvidence.acknowledged());
            result.put("reconciliation_evidence", lastEvidence.basis());
        }
        return result;
    }

    private Map<String, Object> expectedDestination() {
        return Map.of(
                "target", targetMap(navigation.target()),
                "horizontal_tolerance_blocks", navigation.horizontalToleranceBlocks(),
                "on_ground", true,
                "max_horizontal_velocity_squared", MAX_SETTLED_VELOCITY_SQUARED);
    }

    private static Map<String, Object> observedPosition(SemanticActionFrame frame) {
        return Map.of(
                "x", frame.playerX(), "y", frame.playerY(), "z", frame.playerZ(),
                "horizontal_velocity_squared", frame.playerHorizontalVelocitySquared(),
                "on_ground", frame.onGround());
    }

    private static Map<String, Object> targetMap(BlockTarget target) {
        return Map.of(
                "dimension", target.dimension(),
                "x", target.x(), "y", target.y(), "z", target.z());
    }
}
