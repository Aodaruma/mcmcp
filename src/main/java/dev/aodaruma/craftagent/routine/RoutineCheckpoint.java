package dev.aodaruma.craftagent.routine;

/** Last server-confirmed progress boundary retained independently of the event ring. */
public record RoutineCheckpoint(long seq, long observationRevision) {
    public RoutineCheckpoint {
        if (seq < 0 || observationRevision < 0) {
            throw new IllegalArgumentException("checkpoint values must be non-negative");
        }
    }
}
