package dev.aodaruma.craftagent.routine;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** One fail-closed supervisor for all six Phase 5 kinds. */
final class PhaseFiveRoutine implements ManagedRoutine {
    private static final String QUEUED = "queued";
    private static final String PRECHECK = "precheck";
    private static final String EXECUTE = "execute";
    private static final String WAIT_SERVER_EVIDENCE = "wait_server_evidence";

    private final UUID routineId;
    private final PhaseFiveRequest request;
    private final PhaseFivePort port;
    private final RoutineEventRing events;
    private final long hardDeadlineClientTick;

    private RoutineState state = RoutineState.QUEUED;
    private String phase = QUEUED;
    private RoutineFailure failure;
    private boolean goalVerified;
    private boolean finalizationCompleted;
    private RoutineFailure finalizationFailure;
    private int attempts;
    private long lastClientTick;
    private long lastObservationRevision;
    private long confirmedObservationRevision;
    private PhaseFiveAttempt attempt;
    private PhaseFiveResult result;
    private Map<String, Object> lastEvidenceBasis = Map.of();
    private boolean retired;

    PhaseFiveRoutine(
            UUID routineId,
            PhaseFiveRequest request,
            PhaseFivePort port,
            int eventCapacity,
            long admittedClientTick) {
        this.routineId = Objects.requireNonNull(routineId, "routineId");
        this.request = Objects.requireNonNull(request, "request");
        this.port = Objects.requireNonNull(port, "port");
        request.validateAdmissionTick(admittedClientTick);
        events = new RoutineEventRing(eventCapacity);
        hardDeadlineClientTick = request.bounds().hardDeadlineClientTick(admittedClientTick);
        lastClientTick = admittedClientTick;
        events.append(RoutineEventType.PHASE_STARTED, admittedClientTick, 0,
                Map.of("phase", phase));
    }

    @Override
    public void tick() {
        if (state.terminal() || state == RoutineState.FINALIZING) {
            return;
        }
        final PhaseFiveFrame frame;
        try {
            frame = Objects.requireNonNull(port.observe(request), "adapter returned no frame");
        } catch (RuntimeException | LinkageError adapterFailure) {
            fail(adapterFailure("OBSERVATION_ADAPTER_FAILURE"));
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
                    Map.of(),
                    true));
            return;
        }
        lastClientTick = frame.clientTick();
        lastObservationRevision = frame.observationRevision();

        if (lastClientTick >= hardDeadlineClientTick) {
            fail(failure(
                    RoutineFailure.Category.TRANSIENT,
                    "HARD_DEADLINE_EXPIRED",
                    RoutineFailure.Recovery.REPLAN,
                    RoutineFailure.Scope.ROUTINE,
                    Map.of("before_client_tick", hardDeadlineClientTick),
                    Map.of("client_tick", lastClientTick),
                    Map.of("attempts", attempts),
                    false));
            return;
        }
        if (frame.failure() != null) {
            fail(frame.failure());
            return;
        }

        try {
            switch (phase) {
                case QUEUED -> startPhase(PRECHECK, RoutineState.VALIDATING);
                case PRECHECK -> begin(frame);
                case EXECUTE, WAIT_SERVER_EVIDENCE -> advanceAttempt();
                default -> fail(adapterFailure("INVALID_ROUTINE_PHASE"));
            }
        } catch (RuntimeException | LinkageError adapterFailure) {
            fail(adapterFailure("ACTION_ADAPTER_FAILURE"));
        }
    }

    private void begin(PhaseFiveFrame frame) {
        var issued = Objects.requireNonNull(
                port.begin(routineId, request, hardDeadlineClientTick),
                "adapter returned no attempt");
        if (!routineId.equals(issued.attemptId())
                || !request.kind().equals(issued.kind())
                || issued.issuedClientTick() != frame.clientTick()
                || issued.issuedObservationRevision() < frame.observationRevision()
                || issued.hardDeadlineClientTick() != hardDeadlineClientTick) {
            safeRelease(issued);
            throw new IllegalStateException("Phase 5 adapter violated begin contract");
        }
        attempt = issued;
        attempts = 1;
        startPhase(EXECUTE, RoutineState.RUNNING);
    }

    private void advanceAttempt() {
        var current = Objects.requireNonNull(attempt, "no active attempt");
        var evidence = Objects.requireNonNull(
                port.evidence(current), "adapter returned no evidence");
        if (!current.attemptId().equals(evidence.attemptId())
                || evidence.clientTick() < current.issuedClientTick()
                || evidence.clientTick() > lastClientTick
                || evidence.observationRevision() < current.issuedObservationRevision()) {
            throw new IllegalStateException("Phase 5 adapter violated evidence contract");
        }
        lastObservationRevision = Math.max(
                lastObservationRevision, evidence.observationRevision());
        lastEvidenceBasis = evidence.basis();

        switch (evidence) {
            case PhaseFiveEvidence.Pending ignored -> {
                port.maintain(current);
                if (!phase.equals(WAIT_SERVER_EVIDENCE)) {
                    startPhase(WAIT_SERVER_EVIDENCE, RoutineState.WAITING);
                }
            }
            case PhaseFiveEvidence.ServerConfirmed confirmed -> confirm(confirmed);
            case PhaseFiveEvidence.Inconclusive inconclusive -> fail(inconclusive(inconclusive));
            case PhaseFiveEvidence.Failed failed -> fail(failed.failure());
        }
    }

    private void confirm(PhaseFiveEvidence.ServerConfirmed confirmed) {
        var candidate = confirmed.result();
        if (!candidate.goalVerified() || candidate.verifiedUnits() < request.expectedUnits()) {
            fail(failure(
                    RoutineFailure.Category.DIVERGENCE,
                    "POSTCONDITION_NOT_CONFIRMED",
                    RoutineFailure.Recovery.REPLAN,
                    RoutineFailure.Scope.STEP,
                    Map.of("goal_verified", true,
                            "minimum_verified_units", request.expectedUnits()),
                    Map.of("goal_verified", candidate.goalVerified(),
                            "verified_units", candidate.verifiedUnits()),
                    confirmed.basis(),
                    false));
            return;
        }
        result = candidate;
        confirmedObservationRevision = confirmed.observationRevision();
        events.append(RoutineEventType.STEP_VERIFIED, lastClientTick,
                confirmedObservationRevision,
                Map.of("kind", kind(),
                        "verified_units", candidate.verifiedUnits(),
                        "server_positive", true));
        beginFinalization();
    }

    private RoutineFailure inconclusive(PhaseFiveEvidence.Inconclusive evidence) {
        String code = evidence.certainty() == PhaseFiveEvidence.Certainty.UNKNOWN
                ? "SERVER_EVIDENCE_UNKNOWN"
                : "SERVER_EVIDENCE_AMBIGUOUS";
        return failure(
                RoutineFailure.Category.DIVERGENCE,
                code,
                RoutineFailure.Recovery.REPLAN,
                RoutineFailure.Scope.STEP,
                Map.of("server_positive_terminal_evidence", true),
                Map.of("server_positive_terminal_evidence", false,
                        "reason", evidence.reason()),
                evidence.basis(),
                false);
    }

    private void beginFinalization() {
        releaseCurrent();
        goalVerified = true;
        events.append(RoutineEventType.GOAL_VERIFIED, lastClientTick,
                confirmedObservationRevision, Map.of("kind", kind()));
        state = RoutineState.FINALIZING;
        phase = "finalizing";
        events.append(RoutineEventType.FINALIZATION_STARTED, lastClientTick,
                confirmedObservationRevision, Map.of("phase", phase));
    }

    @Override
    public void cancel(String reason) {
        if (state.terminal()) {
            return;
        }
        releaseCurrent();
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
                Map.of("attempts", attempts));
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
        int completed = result == null
                ? 0
                : Math.min(result.verifiedUnits(), request.expectedUnits());
        int confirmed = result == null ? 0 : request.expectedUnits();
        var diagnostics = result == null
                ? Map.<String, Object>of(
                        "attempts", attempts,
                        "automatic_retries", 0,
                        "last_evidence_basis", lastEvidenceBasis)
                : Map.<String, Object>of(
                        "attempts", attempts,
                        "automatic_retries", 0,
                        "last_evidence_basis", lastEvidenceBasis,
                        "result", result.basis());
        return new RoutineSnapshot(
                routineId,
                kind(),
                state,
                phase,
                goalVerified,
                new RoutineProgress(completed, request.expectedUnits(), request.progressUnit()),
                state.terminal() ? null : new RoutineStep(kind(), Map.of()),
                new RoutineCheckpoint(completed,
                        result == null ? lastObservationRevision : confirmedObservationRevision),
                new RoutineVerification(
                        confirmed,
                        request.expectedUnits(),
                        result == null ? request.expectedUnits() : 0),
                result == null ? List.of() : result.effects(),
                state == RoutineState.WAITING
                        ? new RoutineWait(
                                "server_terminal_evidence",
                                hardDeadlineClientTick,
                                "server-positive terminal evidence is available")
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
        return request.kind();
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
        releaseCurrent();
        port.retire(request);
    }

    private void startPhase(String nextPhase, RoutineState nextState) {
        phase = nextPhase;
        state = nextState;
        events.append(RoutineEventType.PHASE_STARTED, lastClientTick,
                lastObservationRevision, Map.of("phase", nextPhase));
    }

    private void fail(RoutineFailure outcome) {
        if (state.terminal()) {
            return;
        }
        releaseCurrent();
        failure = Objects.requireNonNull(outcome, "outcome");
        state = RoutineState.FAILED;
        phase = "failed";
        if (outcome.recovery() == RoutineFailure.Recovery.REPLAN) {
            events.append(RoutineEventType.NEEDS_REPLAN, lastClientTick,
                    lastObservationRevision,
                    Map.of("code", outcome.code(),
                            "suggested_snapshot_scopes", outcome.suggestedSnapshotScopes()));
        }
        events.append(RoutineEventType.FAILED, lastClientTick, lastObservationRevision,
                Map.of("category", outcome.category().wireName(),
                        "code", outcome.code(),
                        "attempts", outcome.attempts()));
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
                category,
                code,
                false,
                recovery,
                scope,
                attempts,
                expected,
                observed,
                evidence,
                List.of("player", "inventory", "target"),
                requiresUser);
    }

    private RoutineFailure adapterFailure(String code) {
        return failure(
                RoutineFailure.Category.EXTERNAL,
                code,
                RoutineFailure.Recovery.USER,
                RoutineFailure.Scope.ROUTINE,
                Map.of(),
                Map.of(),
                Map.of("attempts", attempts),
                true);
    }

    private void releaseCurrent() {
        var current = attempt;
        attempt = null;
        if (current != null) {
            safeRelease(current);
        }
    }

    private void safeRelease(PhaseFiveAttempt current) {
        try {
            port.release(current);
        } catch (RuntimeException | LinkageError ignored) {
            // The integration's global ownership reset remains the final lifecycle fence.
        }
    }

    private static String sanitizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "operator_cancel";
        }
        var normalized = reason.replaceAll("[\\p{Cntrl}]", " ").strip();
        return normalized.substring(0, Math.min(normalized.length(), 96));
    }
}
