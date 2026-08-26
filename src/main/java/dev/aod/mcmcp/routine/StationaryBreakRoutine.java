package dev.aod.mcmcp.routine;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Client-tick-owned deterministic core for the Phase 2 stationary_break routine. */
final class StationaryBreakRoutine implements ManagedRoutine {
    static final String KIND = "stationary_break";

    private static final String PHASE_QUEUED = "queued";
    private static final String PHASE_PRECHECK = "precheck";
    private static final String PHASE_EXECUTE = "execute";
    private static final String PHASE_WAIT_SERVER_SYNC = "wait_server_sync";
    private static final String PHASE_VERIFY = "verify";
    private static final String PHASE_WAIT_REGENERATION = "wait_regeneration";
    private static final String PHASE_FINALIZING = "finalizing";

    private final UUID routineId;
    private final StationaryBreakRequest request;
    private final StationaryBreakPort port;
    private final RoutineEventRing events;

    private RoutineState state = RoutineState.QUEUED;
    private String phase = PHASE_QUEUED;
    private RoutineFailure failure;
    private boolean goalVerified;
    private boolean finalizationCompleted;
    private RoutineFailure finalizationFailure;
    private int observedGoalCount;
    private boolean inventoryServerSynchronized;
    private int attempts;
    private int verifiedBreaks;
    private long lastClientTick;
    private long lastObservationRevision;
    private long regenerationDeadlineTick;
    private long lastEvidencePredictionSequence;
    private AttackAttempt attackAttempt;
    private boolean attackInputStopped;
    private PredictionEvidence confirmedTransition;
    private boolean retired;

    StationaryBreakRoutine(
            UUID routineId,
            StationaryBreakRequest request,
            StationaryBreakPort port,
            int eventCapacity,
            long admittedClientTick) {
        this.routineId = Objects.requireNonNull(routineId, "routineId");
        this.request = Objects.requireNonNull(request, "request");
        this.port = Objects.requireNonNull(port, "port");
        events = new RoutineEventRing(eventCapacity);
        lastClientTick = admittedClientTick;
        events.append(
                RoutineEventType.PHASE_STARTED,
                admittedClientTick,
                0,
                Map.of("phase", PHASE_QUEUED));
    }

    @Override
    public void tick() {
        if (state.terminal() || state == RoutineState.FINALIZING) {
            return;
        }

        final StationaryBreakFrame frame;
        try {
            frame = Objects.requireNonNull(port.observe(request), "adapter returned no observation");
        } catch (RuntimeException adapterFailure) {
            fail(adapterFailure("OBSERVATION_ADAPTER_FAILURE"));
            return;
        }

        if (frame.clientTick() < lastClientTick
                || frame.observationRevision() < lastObservationRevision) {
            fail(new RoutineFailure(
                    RoutineFailure.Category.EXTERNAL,
                    "NON_MONOTONIC_OBSERVATION",
                    false,
                    RoutineFailure.Recovery.USER,
                    RoutineFailure.Scope.ROUTINE,
                    attempts,
                    Map.of(
                            "minimum_client_tick", lastClientTick,
                            "minimum_observation_revision", lastObservationRevision),
                    Map.of(
                            "client_tick", frame.clientTick(),
                            "observation_revision", frame.observationRevision()),
                    Map.of(),
                    List.of("player", "target"),
                    true));
            return;
        }
        remember(frame);

        if (frame.clientTick() >= request.hardDeadlineClientTick()) {
            fail(new RoutineFailure(
                    RoutineFailure.Category.TRANSIENT,
                    "HARD_DEADLINE_EXPIRED",
                    false,
                    RoutineFailure.Recovery.REPLAN,
                    RoutineFailure.Scope.ROUTINE,
                    attempts,
                    Map.of("before_client_tick", request.hardDeadlineClientTick()),
                    Map.of("client_tick", frame.clientTick()),
                    Map.of("verified_breaks", verifiedBreaks),
                    List.of("player", "inventory", "target"),
                    false));
            return;
        }

        var unsafe = universalSafetyFailure(frame);
        if (unsafe != null) {
            fail(unsafe);
            return;
        }

        // The first tick only admits VALIDATING. From PRECHECK onward a confirmed
        // target state may short-circuit without touching the attack input.
        if (!phase.equals(PHASE_QUEUED)
                && attackAttempt == null
                && confirmedTransition == null
                && frame.goalConfirmed(request.goal())) {
            succeed(frame);
            return;
        }

        if (requiresTargetControl(phase)) {
            var targetUnsafe = targetControlFailure(frame);
            if (targetUnsafe != null) {
                fail(targetUnsafe);
                return;
            }
        }

        switch (phase) {
            case PHASE_QUEUED -> beginValidation(frame);
            case PHASE_PRECHECK -> validateTarget(frame);
            case PHASE_EXECUTE -> beginAttack(frame);
            case PHASE_WAIT_SERVER_SYNC -> awaitServerSync(frame);
            case PHASE_VERIFY -> verifyTransition(frame);
            case PHASE_WAIT_REGENERATION -> awaitRegeneration(frame);
            default -> fail(adapterFailure("INVALID_ROUTINE_PHASE"));
        }
    }

    @Override
    public void cancel(String reason) {
        if (state.terminal()) {
            return;
        }
        safeReleaseAttack();
        state = RoutineState.CANCELLED;
        phase = "cancelled";
        events.append(
                RoutineEventType.CANCELLED,
                lastClientTick,
                lastObservationRevision,
                Map.of("reason", sanitizeReason(reason)));
    }

    @Override
    public void completeFinalization(RoutineFailure finalizationFailure) {
        if (state != RoutineState.FINALIZING) {
            throw new IllegalStateException("routine is not finalizing");
        }
        if (finalizationFailure != null
                && finalizationFailure.scope() != RoutineFailure.Scope.FINALIZATION) {
            throw new IllegalArgumentException("finalization failure must use FINALIZATION scope");
        }
        finalizationCompleted = true;
        this.finalizationFailure = finalizationFailure;
        if (finalizationFailure != null) {
            fail(finalizationFailure);
            return;
        }
        state = RoutineState.SUCCEEDED;
        phase = "succeeded";
        events.append(
                RoutineEventType.SUCCEEDED,
                lastClientTick,
                lastObservationRevision,
                Map.of("verified_breaks", verifiedBreaks));
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
        var progress = new RoutineProgress(
                observedGoalCount,
                request.goal().minimumInventoryCount(),
                "items");
        var diagnostics = Map.<String, Object>of(
                "inventory_server_synchronized", inventoryServerSynchronized,
                "verified_breaks", verifiedBreaks,
                "attempts", attempts,
                "target_item", request.goal().itemId());
        return new RoutineSnapshot(
                routineId,
                KIND,
                state,
                phase,
                goalVerified,
                progress,
                state.terminal() ? null : RoutineStep.block("break_block", request.target()),
                new RoutineCheckpoint(verifiedBreaks, lastObservationRevision),
                new RoutineVerification(
                        inventoryServerSynchronized
                                ? Math.min(progress.completed(), progress.total())
                                : 0,
                        progress.total(),
                        inventoryServerSynchronized ? 0 : 1),
                List.of(),
                state == RoutineState.WAITING
                        ? new RoutineWait(
                                "target_regeneration",
                                regenerationDeadlineTick,
                                "target matches the original full block state")
                        : null,
                lastClientTick,
                diagnostics,
                failure,
                finalizationCompleted,
                finalizationFailure,
                events.page(afterEventSeq, maxEvents));
    }

    @Override
    public UUID routineId() {
        return routineId;
    }

    @Override
    public String kind() {
        return KIND;
    }

    StationaryBreakRequest request() {
        return request;
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
        safeReleaseAttack();
        port.retire(request);
    }

    private void beginValidation(StationaryBreakFrame frame) {
        state = RoutineState.VALIDATING;
        startPhase(PHASE_PRECHECK, frame);
    }

    private void validateTarget(StationaryBreakFrame frame) {
        var current = frame.liveTargetState();
        if (current.isEmpty()) {
            fail(new RoutineFailure(
                    RoutineFailure.Category.PRECONDITION,
                    "TARGET_NOT_CURRENTLY_OBSERVABLE",
                    true,
                    RoutineFailure.Recovery.REPLAN,
                    RoutineFailure.Scope.STEP,
                    attempts,
                    expectedSourceMap(),
                    Map.of("currentness", "unknown"),
                    Map.of(),
                    List.of("target", "visible_blocks"),
                    false));
            return;
        }
        if (!request.expectedSourceState().matches(current.orElseThrow())) {
            fail(targetMismatch(current.orElseThrow(), "TARGET_STATE_CHANGED"));
            return;
        }
        state = RoutineState.RUNNING;
        startPhase(PHASE_EXECUTE, frame);
    }

    private void beginAttack(StationaryBreakFrame frame) {
        var current = frame.liveTargetState();
        if (current.isEmpty() || !request.expectedSourceState().matches(current.orElseThrow())) {
            fail(current.isEmpty()
                    ? targetUnknownFailure()
                    : targetMismatch(current.orElseThrow(), "TARGET_STATE_CHANGED"));
            return;
        }

        long requestedExpiry = Math.min(
                frame.clientTick() + request.attackLeaseTicks(),
                request.hardDeadlineClientTick());
        try {
            var started = Objects.requireNonNull(
                    port.beginAttack(request, requestedExpiry),
                    "adapter returned no attack attempt");
            // beginAttack transfers ownership even when the adapter's returned metadata is
            // invalid. Latch first so every rejected lease still flows through safeReleaseAttack.
            attackAttempt = started;
            attackInputStopped = false;
            lastEvidencePredictionSequence = started.predictionSequence();
            if (!started.target().equals(request.target())
                    || started.leaseExpiresAtClientTick() <= frame.clientTick()
                    || started.leaseExpiresAtClientTick() > requestedExpiry) {
                fail(adapterFailure("ATTACK_ADAPTER_CONTRACT_VIOLATION"));
                return;
            }
            attempts++;
            startPhase(PHASE_WAIT_SERVER_SYNC, frame);
        } catch (SafeBreakSourcePolicy.UnsafeBreakSourceException rejected) {
            fail(simpleFailure(
                    RoutineFailure.Category.PRECONDITION,
                    "UNSAFE_BREAK_SOURCE",
                    RoutineFailure.Recovery.REPLAN,
                    "safe_break_source",
                    false,
                    false));
        } catch (RuntimeException adapterFailure) {
            fail(adapterFailure("ATTACK_ADAPTER_FAILURE"));
        }
    }

    private void awaitServerSync(StationaryBreakFrame frame) {
        if (attackAttempt == null) {
            fail(adapterFailure("MISSING_ATTACK_LEASE"));
            return;
        }

        final PredictionEvidence evidence;
        try {
            evidence = Objects.requireNonNull(
                    port.predictionEvidence(attackAttempt),
                    "adapter returned no prediction evidence");
            boolean sourceStillControlled = frame.crosshairOnTarget()
                    && frame.targetInReach()
                    && frame.liveTargetState()
                    .filter(request.expectedSourceState()::matches)
                    .isPresent();
            if ((!sourceStillControlled || evidence.serverVerifiedTransition())
                    && !attackInputStopped) {
                port.stopAttackInput(attackAttempt);
                attackInputStopped = true;
            }
            if (!attackInputStopped
                    && frame.clientTick() < attackAttempt.leaseExpiresAtClientTick()) {
                port.holdAttack(attackAttempt);
            }
        } catch (SafeBreakSourcePolicy.UnsafeBreakSourceException rejected) {
            fail(simpleFailure(
                    RoutineFailure.Category.PRECONDITION,
                    "UNSAFE_BREAK_SOURCE",
                    RoutineFailure.Recovery.REPLAN,
                    "safe_break_source",
                    false,
                    false));
            return;
        } catch (RuntimeException adapterFailure) {
            fail(adapterFailure("PREDICTION_ADAPTER_FAILURE"));
            return;
        }

        if (Long.compareUnsigned(
                        evidence.predictionSequence(), attackAttempt.predictionSequence()) < 0
                || Long.compareUnsigned(
                        evidence.predictionSequence(), lastEvidencePredictionSequence) < 0) {
            fail(adapterFailure("PREDICTION_SEQUENCE_MISMATCH"));
            return;
        }
        lastEvidencePredictionSequence = evidence.predictionSequence();
        if (evidence.confirmsBreakFrom(request.expectedSourceState())) {
            confirmedTransition = evidence;
            safeReleaseAttack();
            startPhase(PHASE_VERIFY, frame);
            return;
        }

        if (frame.clientTick() >= attackAttempt.leaseExpiresAtClientTick()) {
            var code = syncFailureCode(evidence);
            var category = code.equals("POSTCONDITION_MISMATCH")
                    ? RoutineFailure.Category.DIVERGENCE
                    : RoutineFailure.Category.TRANSIENT;
            fail(new RoutineFailure(
                    category,
                    code,
                    false,
                    RoutineFailure.Recovery.REPLAN,
                    RoutineFailure.Scope.STEP,
                    attempts,
                    Map.of(
                            "acknowledged", true,
                            "server_verified_transition", true,
                            "source_state_must_change", true),
                    Map.of(
                            "acknowledged", evidence.acknowledged(),
                            "server_verified_transition", evidence.serverVerifiedTransition(),
                            "transitioned_to", evidence.transitionedTo()
                                    .map(BlockStateFingerprint::blockId)
                                    .orElse("unknown")),
                    Map.of(
                            "prediction_sequence", evidence.predictionSequence(),
                            "lease_expired_at_client_tick", attackAttempt.leaseExpiresAtClientTick()),
                    List.of("target", "visible_blocks"),
                    false));
        }
    }

    private void verifyTransition(StationaryBreakFrame frame) {
        if (confirmedTransition == null
                || !confirmedTransition.confirmsBreakFrom(request.expectedSourceState())) {
            fail(adapterFailure("MISSING_SERVER_VERIFIED_TRANSITION"));
            return;
        }

        verifiedBreaks++;
        events.append(
                RoutineEventType.STEP_VERIFIED,
                frame.clientTick(),
                Math.max(frame.observationRevision(), confirmedTransition.observationRevision()),
                Map.of(
                        "kind", "break_block",
                        "prediction_sequence", confirmedTransition.predictionSequence(),
                        "transitioned_to", confirmedTransition.transitionedTo().orElseThrow().blockId(),
                        "verified_breaks", verifiedBreaks));
        confirmedTransition = null;

        if (frame.goalConfirmed(request.goal())) {
            succeed(frame);
            return;
        }

        state = RoutineState.WAITING;
        regenerationDeadlineTick = Math.min(
                frame.clientTick() + request.regenerationWaitTicks(),
                request.hardDeadlineClientTick());
        startPhase(PHASE_WAIT_REGENERATION, frame);
    }

    private void awaitRegeneration(StationaryBreakFrame frame) {
        var current = frame.liveTargetState();
        if (current.filter(request.expectedSourceState()::matches).isPresent()) {
            state = RoutineState.RUNNING;
            startPhase(PHASE_EXECUTE, frame);
            return;
        }
        if (frame.clientTick() >= regenerationDeadlineTick) {
            fail(new RoutineFailure(
                    RoutineFailure.Category.DIVERGENCE,
                    "TARGET_NOT_REGENERATED",
                    false,
                    RoutineFailure.Recovery.REPLAN,
                    RoutineFailure.Scope.STEP,
                    attempts,
                    expectedSourceMap(),
                    current.<Map<String, Object>>map(state -> Map.of("block", state.blockId()))
                            .orElseGet(() -> Map.of("currentness", "unknown")),
                    Map.of(
                            "regeneration_deadline_client_tick", regenerationDeadlineTick,
                            "verified_breaks", verifiedBreaks),
                    List.of("target", "visible_blocks", "inventory"),
                    false));
        }
    }

    private void succeed(StationaryBreakFrame frame) {
        safeReleaseAttack();
        if (!goalVerified) {
            goalVerified = true;
            events.append(
                    RoutineEventType.GOAL_VERIFIED,
                    frame.clientTick(),
                    frame.observationRevision(),
                    Map.of(
                            "item", request.goal().itemId(),
                            "minimum_count", request.goal().minimumInventoryCount(),
                            "observed_count", frame.goalItemCount(),
                            "server_synchronized", true));
        }
        state = RoutineState.FINALIZING;
        startPhase(PHASE_FINALIZING, frame);
        events.append(
                RoutineEventType.FINALIZATION_STARTED,
                frame.clientTick(),
                frame.observationRevision(),
                Map.of("verified_breaks", verifiedBreaks));
    }

    private RoutineFailure universalSafetyFailure(StationaryBreakFrame frame) {
        if (!frame.worldReady()) {
            return simpleFailure(
                    RoutineFailure.Category.EXTERNAL,
                    "WORLD_UNAVAILABLE",
                    RoutineFailure.Recovery.REPLAN,
                    "world_ready",
                    false,
                    true);
        }
        if (!frame.playerAlive()) {
            return simpleFailure(
                    RoutineFailure.Category.SAFETY,
                    "PLAYER_DEAD",
                    RoutineFailure.Recovery.USER,
                    "player_alive",
                    false,
                    true);
        }
        if (!frame.healthSafe()) {
            return simpleFailure(
                    RoutineFailure.Category.SAFETY,
                    "LOW_HEALTH",
                    RoutineFailure.Recovery.USER,
                    "health_safe",
                    false,
                    true);
        }
        if (!frame.visibleThreatClear()) {
            return simpleFailure(
                    RoutineFailure.Category.SAFETY,
                    "VISIBLE_THREAT",
                    RoutineFailure.Recovery.USER,
                    "visible_threat_clear",
                    false,
                    true);
        }
        if (!frame.clientFocused()) {
            return simpleFailure(
                    RoutineFailure.Category.SAFETY,
                    "CLIENT_NOT_FOCUSED",
                    RoutineFailure.Recovery.USER,
                    "client_focused",
                    false,
                    true);
        }
        return null;
    }

    private RoutineFailure targetControlFailure(StationaryBreakFrame frame) {
        if (!frame.targetInReach()) {
            return simpleFailure(
                    RoutineFailure.Category.PRECONDITION,
                    "TARGET_OUT_OF_REACH",
                    RoutineFailure.Recovery.REPLAN,
                    "target_in_reach",
                    false,
                    false);
        }
        if (!frame.crosshairOnTarget()) {
            return simpleFailure(
                    RoutineFailure.Category.PRECONDITION,
                    "CROSSHAIR_TARGET_CHANGED",
                    RoutineFailure.Recovery.REPLAN,
                    "crosshair_on_target",
                    false,
                    false);
        }
        return null;
    }

    private static boolean requiresTargetControl(String phase) {
        return phase.equals(PHASE_QUEUED)
                || phase.equals(PHASE_PRECHECK)
                || phase.equals(PHASE_EXECUTE);
    }

    private RoutineFailure simpleFailure(
            RoutineFailure.Category category,
            String code,
            RoutineFailure.Recovery recovery,
            String condition,
            boolean observed,
            boolean requiresUser) {
        return new RoutineFailure(
                category,
                code,
                false,
                recovery,
                RoutineFailure.Scope.ROUTINE,
                attempts,
                Map.of(condition, true),
                Map.of(condition, observed),
                Map.of("verified_breaks", verifiedBreaks),
                List.of("player", "target", "visible_entities"),
                requiresUser);
    }

    private RoutineFailure targetUnknownFailure() {
        return new RoutineFailure(
                RoutineFailure.Category.DIVERGENCE,
                "TARGET_BECAME_UNKNOWN",
                true,
                RoutineFailure.Recovery.REPLAN,
                RoutineFailure.Scope.STEP,
                attempts,
                expectedSourceMap(),
                Map.of("currentness", "unknown"),
                Map.of(),
                List.of("target", "visible_blocks"),
                false);
    }

    private RoutineFailure targetMismatch(BlockStateFingerprint observed, String code) {
        return new RoutineFailure(
                RoutineFailure.Category.DIVERGENCE,
                code,
                false,
                RoutineFailure.Recovery.REPLAN,
                RoutineFailure.Scope.STEP,
                attempts,
                expectedSourceMap(),
                Map.of("block", observed.blockId(), "properties", observed.properties()),
                Map.of(),
                List.of("target", "visible_blocks"),
                false);
    }

    private RoutineFailure adapterFailure(String code) {
        return new RoutineFailure(
                RoutineFailure.Category.EXTERNAL,
                code,
                false,
                RoutineFailure.Recovery.USER,
                RoutineFailure.Scope.ROUTINE,
                attempts,
                Map.of(),
                Map.of(),
                Map.of("verified_breaks", verifiedBreaks),
                List.of("player", "target"),
                true);
    }

    private void fail(RoutineFailure outcome) {
        if (state.terminal()) {
            return;
        }
        safeReleaseAttack();
        failure = Objects.requireNonNull(outcome, "outcome");
        state = RoutineState.FAILED;
        phase = "failed";
        if (outcome.recovery() == RoutineFailure.Recovery.REPLAN) {
            events.append(
                    RoutineEventType.NEEDS_REPLAN,
                    lastClientTick,
                    lastObservationRevision,
                    Map.of(
                            "code", outcome.code(),
                            "suggested_snapshot_scopes", outcome.suggestedSnapshotScopes()));
        }
        events.append(
                RoutineEventType.FAILED,
                lastClientTick,
                lastObservationRevision,
                Map.of(
                        "category", outcome.category().wireName(),
                        "code", outcome.code(),
                        "attempts", outcome.attempts()));
    }

    private void safeReleaseAttack() {
        var leased = attackAttempt;
        attackAttempt = null;
        attackInputStopped = false;
        if (leased == null) {
            return;
        }
        try {
            port.releaseAttack(leased);
        } catch (RuntimeException | LinkageError ignored) {
            // The Minecraft integration also performs global release before completing
            // cancel/stop. The domain must still reach a terminal state deterministically.
        }
    }

    private void remember(StationaryBreakFrame frame) {
        lastClientTick = frame.clientTick();
        lastObservationRevision = frame.observationRevision();
        observedGoalCount = frame.goalItemCount();
        inventoryServerSynchronized = frame.inventoryServerSynchronized();
    }

    private void startPhase(String nextPhase, StationaryBreakFrame frame) {
        phase = nextPhase;
        events.append(
                RoutineEventType.PHASE_STARTED,
                frame.clientTick(),
                frame.observationRevision(),
                Map.of("phase", nextPhase));
    }

    private Map<String, Object> expectedSourceMap() {
        return Map.of(
                "block", request.expectedSourceState().blockId(),
                "properties", request.expectedSourceState().properties());
    }

    private static String syncFailureCode(PredictionEvidence evidence) {
        if (evidence.acknowledged() && evidence.transitionedTo().isPresent()) {
            return "POSTCONDITION_MISMATCH";
        }
        if (evidence.acknowledged()) {
            return "SERVER_TRANSITION_TIMEOUT";
        }
        if (evidence.serverVerifiedTransition() || evidence.transitionedTo().isPresent()) {
            return "SERVER_ACK_TIMEOUT";
        }
        return "SERVER_SYNC_TIMEOUT";
    }

    private static String sanitizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "operator_cancel";
        }
        var normalized = reason.replaceAll("[\\p{Cntrl}]", " ").strip();
        return normalized.substring(0, Math.min(normalized.length(), 96));
    }
}
