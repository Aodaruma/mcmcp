package dev.aodaruma.craftagent.routine;

/** Current client clock plus any fail-closed preflight outcome. */
public record PhaseFiveFrame(
        long clientTick,
        long observationRevision,
        RoutineFailure failure) {
    public PhaseFiveFrame {
        if (clientTick < 0 || observationRevision < 0) {
            throw new IllegalArgumentException("frame clocks must be non-negative");
        }
        if (failure != null && failure.scope() == RoutineFailure.Scope.FINALIZATION) {
            throw new IllegalArgumentException("preflight failure cannot use FINALIZATION scope");
        }
    }
}
