package dev.aod.mcmcp.routine;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Shared lifecycle, safety and finalization shell for pure Phase 3 state machines. */
abstract class AbstractSemanticRoutine implements ManagedRoutine {
    protected static final int MAX_RETRIES = 2;

    protected final UUID routineId;
    protected final SemanticActionRequest request;
    protected final SemanticActionPort port;
    protected final RoutineEventRing events;
    protected final long hardDeadlineClientTick;

    protected RoutineState state = RoutineState.QUEUED;
    protected String phase = "queued";
    protected RoutineFailure failure;
    protected boolean goalVerified;
    protected boolean finalizationCompleted;
    protected RoutineFailure finalizationFailure;
    protected int attempts;
    protected long lastClientTick;
    protected long lastObservationRevision;
    protected SemanticActionAttempt attempt;

    private boolean retired;

    AbstractSemanticRoutine(
            UUID routineId,
            SemanticActionRequest request,
            SemanticActionPort port,
            int eventCapacity,
            long admittedClientTick) {
        this.routineId = Objects.requireNonNull(routineId, "routineId");
        this.request = Objects.requireNonNull(request, "request");
        this.port = Objects.requireNonNull(port, "port");
        request.validateAdmissionTick(admittedClientTick);
        events = new RoutineEventRing(eventCapacity);
        hardDeadlineClientTick = request.bounds().hardDeadlineClientTick(admittedClientTick);
        lastClientTick = admittedClientTick;
        events.append(RoutineEventType.PHASE_STARTED, admittedClientTick, 0, Map.of("phase", phase));
    }

    @Override
    public final void tick() {
        if (state.terminal() || state == RoutineState.FINALIZING) {
            return;
        }
        final SemanticActionFrame frame;
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
                    false,
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

        if (frame.clientTick() >= hardDeadlineClientTick) {
            fail(failure(
                    RoutineFailure.Category.TRANSIENT,
                    "HARD_DEADLINE_EXPIRED",
                    false,
                    RoutineFailure.Recovery.REPLAN,
                    RoutineFailure.Scope.ROUTINE,
                    Map.of("before_client_tick", hardDeadlineClientTick),
                    Map.of("client_tick", frame.clientTick()),
                    Map.of("attempts", attempts),
                    false));
            return;
        }
        var safetyFailure = safetyFailure(frame);
        if (safetyFailure != null) {
            fail(safetyFailure);
            return;
        }
        try {
            tickFrame(frame);
        } catch (SafeBreakSourcePolicy.UnsafeBreakSourceException rejected) {
            fail(failure(
                    RoutineFailure.Category.PRECONDITION,
                    "UNSAFE_BREAK_SOURCE",
                    false,
                    RoutineFailure.Recovery.REPLAN,
                    RoutineFailure.Scope.STEP,
                    Map.of("safe_break_source", true),
                    Map.of("safe_break_source", false),
                    Map.of(),
                    false));
        } catch (SafePlacementSupportPolicy.UnsafePlacementSupportException rejected) {
            fail(failure(
                    RoutineFailure.Category.PRECONDITION,
                    "UNSAFE_PLACEMENT_SUPPORT",
                    false,
                    RoutineFailure.Recovery.REPLAN,
                    RoutineFailure.Scope.STEP,
                    Map.of("safe_placement_support", true),
                    Map.of("safe_placement_support", false),
                    Map.of(),
                    false));
        } catch (RuntimeException | LinkageError adapterFailure) {
            fail(adapterFailure("ACTION_ADAPTER_FAILURE"));
        }
    }

    protected abstract void tickFrame(SemanticActionFrame frame);

    @Override
    public final void cancel(String reason) {
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
    public final void completeFinalization(RoutineFailure cleanupFailure) {
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
    public final void recordTerminalFinalization(RoutineFailure cleanupFailure) {
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
    public final RoutineSnapshot snapshot(long afterEventSeq, int maxEvents) {
        return new RoutineSnapshot(
                routineId,
                kind(),
                state,
                phase,
                goalVerified,
                progress(),
                state.terminal() ? null : currentStep(),
                checkpoint(),
                verification(),
                List.of(),
                state == RoutineState.WAITING ? waitState() : null,
                lastClientTick,
                diagnostics(),
                failure,
                finalizationCompleted,
                finalizationFailure,
                events.page(afterEventSeq, maxEvents));
    }

    protected abstract RoutineProgress progress();

    protected abstract RoutineStep currentStep();

    protected abstract RoutineCheckpoint checkpoint();

    protected abstract RoutineVerification verification();

    protected abstract RoutineWait waitState();

    protected abstract Map<String, Object> diagnostics();

    @Override
    public final UUID routineId() {
        return routineId;
    }

    @Override
    public final String kind() {
        return request.kind();
    }

    @Override
    public final RoutineState state() {
        return state;
    }

    @Override
    public final long lastClientTick() {
        return lastClientTick;
    }

    @Override
    public final void retire() {
        if (retired) {
            return;
        }
        retired = true;
        releaseCurrent();
        port.retire(request);
    }

    protected final SemanticActionAttempt dispatch(SemanticActionFrame frame) {
        // This absolute ceiling is the domain action deadline, not the two-second input
        // watchdog. The adapter clamps each heartbeat to a short watchdog horizon and renews
        // it on maintain(); passing current+40 here would make every long action expire at the
        // original dispatch tick despite otherwise healthy heartbeats.
        long leaseDeadline = hardDeadlineClientTick;
        var issued = Objects.requireNonNull(
                port.dispatch(request, leaseDeadline), "adapter returned no attempt");
        if (!kind().equals(issued.kind())
                || issued.issuedClientTick() != frame.clientTick()
                // A dispatch adapter may take a stricter same-tick live observation (for
                // example navigation route visibility) and thereby advance WorldMemory.
                || issued.issuedObservationRevision() < frame.observationRevision()
                || issued.leaseExpiresAtClientTick() != leaseDeadline
                || issued.positionCorrectionRevisionAtDispatch()
                        != frame.positionCorrectionRevision()) {
            try {
                port.release(issued);
            } catch (RuntimeException | LinkageError ignored) {
            }
            throw new IllegalStateException("semantic action adapter violated dispatch contract");
        }
        attempt = issued;
        attempts++;
        return issued;
    }

    protected final SemanticActionEvidence evidence() {
        var currentAttempt = Objects.requireNonNull(attempt, "no active attempt");
        var evidence = Objects.requireNonNull(
                port.evidence(currentAttempt), "adapter returned no evidence");
        if (!currentAttempt.attemptId().equals(evidence.attemptId())
                || evidence.clientTick() < currentAttempt.issuedClientTick()
                || evidence.observationRevision() < currentAttempt.issuedObservationRevision()) {
            throw new IllegalStateException("semantic action adapter violated evidence contract");
        }
        return evidence;
    }

    protected final void stopInput() {
        if (attempt != null) {
            port.stopInput(attempt);
        }
    }

    protected final void releaseCurrent() {
        var current = attempt;
        attempt = null;
        if (current == null) {
            return;
        }
        try {
            port.release(current);
        } catch (RuntimeException | LinkageError ignored) {
            // Runtime global input release is the final lifecycle fence.
        }
    }

    protected final void beginFinalization() {
        releaseCurrent();
        goalVerified = true;
        events.append(RoutineEventType.GOAL_VERIFIED, lastClientTick, lastObservationRevision,
                Map.of("kind", kind()));
        state = RoutineState.FINALIZING;
        phase = "finalizing";
        events.append(RoutineEventType.FINALIZATION_STARTED, lastClientTick, lastObservationRevision,
                Map.of("phase", phase));
    }

    protected final void startPhase(String nextPhase, RoutineState nextState) {
        phase = nextPhase;
        state = nextState;
        events.append(RoutineEventType.PHASE_STARTED, lastClientTick, lastObservationRevision,
                Map.of("phase", nextPhase));
    }

    protected final void fail(RoutineFailure outcome) {
        if (state.terminal()) {
            return;
        }
        releaseCurrent();
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

    protected final RoutineFailure failure(
            RoutineFailure.Category category,
            String code,
            boolean retryable,
            RoutineFailure.Recovery recovery,
            RoutineFailure.Scope scope,
            Map<String, Object> expected,
            Map<String, Object> observed,
            Map<String, Object> evidence,
            boolean requiresUser) {
        return new RoutineFailure(
                category, code, retryable, recovery, scope, attempts,
                expected, observed, evidence,
                List.of("player", request instanceof InteractEntityRequest
                        ? "visible_entities" : "target"),
                requiresUser);
    }

    protected final RoutineFailure adapterFailure(String code) {
        return failure(
                RoutineFailure.Category.EXTERNAL,
                code,
                false,
                RoutineFailure.Recovery.USER,
                RoutineFailure.Scope.ROUTINE,
                Map.of(), Map.of(), Map.of("attempts", attempts), true);
    }

    private RoutineFailure safetyFailure(SemanticActionFrame frame) {
        String code = !frame.worldReady() ? "WORLD_UNAVAILABLE"
                : !frame.clientFocused() ? "CLIENT_NOT_FOCUSED"
                : !frame.playerAlive() ? "PLAYER_NOT_ALIVE"
                : !frame.healthSafe() ? "HEALTH_UNSAFE"
                : !frame.visibleThreatClear() ? "VISIBLE_THREAT"
                : !frame.screenClear() ? "UNEXPECTED_SCREEN"
                : null;
        return code == null ? null : failure(
                RoutineFailure.Category.SAFETY,
                code,
                false,
                RoutineFailure.Recovery.USER,
                RoutineFailure.Scope.ROUTINE,
                Map.of("safe", true),
                Map.of("safe", false),
                Map.of(),
                true);
    }

    private static String sanitizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "operator_cancel";
        }
        var normalized = reason.replaceAll("[\\p{Cntrl}]", " ").strip();
        return normalized.substring(0, Math.min(normalized.length(), 96));
    }
}
