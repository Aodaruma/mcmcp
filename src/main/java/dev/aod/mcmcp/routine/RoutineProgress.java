package dev.aod.mcmcp.routine;

import java.util.Objects;

public record RoutineProgress(int completed, int total, String unit) {
    public RoutineProgress {
        if (completed < 0 || total < 0) {
            throw new IllegalArgumentException("progress counts must be non-negative");
        }
        Objects.requireNonNull(unit, "unit");
        if (unit.isBlank()) {
            throw new IllegalArgumentException("progress unit must be non-blank");
        }
    }
}
