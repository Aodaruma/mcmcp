package dev.aodaruma.craftagent.routine;

/** Complete current verification counts; these do not depend on retained events. */
public record RoutineVerification(int confirmed, int expected, int unknown) {
    public RoutineVerification {
        if (confirmed < 0 || expected < 0 || unknown < 0) {
            throw new IllegalArgumentException("verification counts must be non-negative");
        }
        if (confirmed > expected) {
            throw new IllegalArgumentException("confirmed verification cannot exceed expected");
        }
        if ((long) confirmed + unknown > expected) {
            throw new IllegalArgumentException("confirmed and unknown verification cannot exceed expected");
        }
    }
}
