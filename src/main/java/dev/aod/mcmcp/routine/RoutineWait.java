package dev.aod.mcmcp.routine;

import java.util.Objects;

/** Bounded wait state with an explicit wake condition. */
public record RoutineWait(String reason, long deadlineClientTick, String wakeCondition) {
    public RoutineWait {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(wakeCondition, "wakeCondition");
        if (!reason.matches("[a-z][a-z0-9_]{0,95}")) {
            throw new IllegalArgumentException("invalid wait reason");
        }
        if (deadlineClientTick < 0) {
            throw new IllegalArgumentException("wait deadline must be non-negative");
        }
        if (wakeCondition.isBlank() || wakeCondition.length() > 160) {
            throw new IllegalArgumentException("wake condition must be 1..160 characters");
        }
    }
}
