package dev.aod.mcmcp.routine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Complete current state plus an event delta for get_routine-style polling. */
public record RoutineSnapshot(
        UUID routineId,
        String kind,
        RoutineState state,
        String phase,
        boolean goalVerified,
        RoutineProgress progress,
        RoutineStep currentStep,
        RoutineCheckpoint checkpoint,
        RoutineVerification verificationSummary,
        List<RoutineEffect> effects,
        RoutineWait waitState,
        long lastClientTick,
        Map<String, Object> diagnostics,
        RoutineFailure failure,
        boolean finalizationCompleted,
        RoutineFailure finalizationFailure,
        RoutineEventRing.EventPage eventPage) {
    public RoutineSnapshot {
        Objects.requireNonNull(routineId, "routineId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(progress, "progress");
        Objects.requireNonNull(checkpoint, "checkpoint");
        Objects.requireNonNull(verificationSummary, "verificationSummary");
        effects = List.copyOf(effects);
        if ((state == RoutineState.WAITING) != (waitState != null)) {
            throw new IllegalArgumentException("wait state is required exactly for WAITING");
        }
        if (lastClientTick < 0) {
            throw new IllegalArgumentException("routine clock must be non-negative");
        }
        Objects.requireNonNull(diagnostics, "diagnostics");
        diagnostics = Collections.unmodifiableMap(new LinkedHashMap<>(diagnostics));
        Objects.requireNonNull(eventPage, "eventPage");
        if ((state == RoutineState.FAILED) != (failure != null)) {
            throw new IllegalArgumentException("failure is required exactly for FAILED state");
        }
        if (finalizationFailure != null
                && finalizationFailure.scope() != RoutineFailure.Scope.FINALIZATION) {
            throw new IllegalArgumentException("finalization failure must use FINALIZATION scope");
        }
        if (finalizationFailure != null && !finalizationCompleted) {
            throw new IllegalArgumentException("finalization failure requires completed finalization");
        }
        if (finalizationCompleted && !state.terminal()) {
            throw new IllegalArgumentException("completed finalization requires a terminal state");
        }
        if (state == RoutineState.SUCCEEDED
                && (!finalizationCompleted || finalizationFailure != null)) {
            throw new IllegalArgumentException("SUCCEEDED requires successful finalization");
        }
        if (failure != null
                && failure.scope() == RoutineFailure.Scope.FINALIZATION
                && !Objects.equals(failure, finalizationFailure)) {
            throw new IllegalArgumentException(
                    "a terminal FINALIZATION failure must also be the finalization outcome");
        }
    }

    /** Compatibility constructor for the Phase 2 stationary_break domain/tests. */
    public RoutineSnapshot(
            UUID routineId,
            String kind,
            RoutineState state,
            String phase,
            boolean goalVerified,
            RoutineProgress progress,
            BlockTarget target,
            Long waitDeadlineClientTick,
            long lastClientTick,
            long lastObservationRevision,
            Map<String, Object> verification,
            RoutineFailure failure,
            boolean finalizationCompleted,
            RoutineFailure finalizationFailure,
            RoutineEventRing.EventPage eventPage) {
        this(
                routineId,
                kind,
                state,
                phase,
                goalVerified,
                progress,
                state.terminal() ? null : RoutineStep.block("break_block", target),
                new RoutineCheckpoint(
                        nonNegativeInt(verification.get("verified_breaks")),
                        lastObservationRevision),
                stationaryBreakVerification(progress, verification),
                List.of(),
                state == RoutineState.WAITING && waitDeadlineClientTick != null
                        ? new RoutineWait(
                                "target_regeneration",
                                waitDeadlineClientTick,
                                "target matches the original full block state")
                        : null,
                lastClientTick,
                verification,
                failure,
                finalizationCompleted,
                finalizationFailure,
                eventPage);
    }

    /** Phase 2 compatibility view used by finalization diagnostics. */
    public Map<String, Object> verification() {
        return diagnostics;
    }

    /** Compatibility clock accessor retained while callers migrate to checkpoint(). */
    public long lastObservationRevision() {
        return checkpoint.observationRevision();
    }

    private static RoutineVerification stationaryBreakVerification(
            RoutineProgress progress,
            Map<String, Object> diagnostics) {
        boolean synchronizedInventory = Boolean.TRUE.equals(
                diagnostics.get("inventory_server_synchronized"));
        return new RoutineVerification(
                synchronizedInventory ? Math.min(progress.completed(), progress.total()) : 0,
                progress.total(),
                synchronizedInventory ? 0 : 1);
    }

    private static int nonNegativeInt(Object value) {
        return value instanceof Number number ? Math.max(0, number.intValue()) : 0;
    }
}
