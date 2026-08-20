package dev.aodaruma.craftagent.routine;

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
        BlockTarget target,
        Long waitDeadlineClientTick,
        long lastClientTick,
        long lastObservationRevision,
        Map<String, Object> verification,
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
        Objects.requireNonNull(target, "target");
        if (waitDeadlineClientTick != null && waitDeadlineClientTick < 0) {
            throw new IllegalArgumentException("wait deadline must be non-negative");
        }
        if (lastClientTick < 0 || lastObservationRevision < 0) {
            throw new IllegalArgumentException("routine clocks must be non-negative");
        }
        verification = Map.copyOf(verification);
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
}
