package dev.aodaruma.craftagent.routine;

/** Stable public lifecycle states shared by every typed routine. */
public enum RoutineState {
    QUEUED,
    VALIDATING,
    RUNNING,
    WAITING,
    FINALIZING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
