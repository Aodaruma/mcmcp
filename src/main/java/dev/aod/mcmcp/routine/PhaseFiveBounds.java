package dev.aod.mcmcp.routine;

import java.util.Objects;

/** Immutable Phase 5 execution envelope without widening Phase 3's 120-second bound. */
public record PhaseFiveBounds(
        String dimension,
        BlockTarget minimum,
        BlockTarget maximum,
        int maxTravelBlocks,
        int maxDurationSeconds,
        boolean allowBreak) {
    public PhaseFiveBounds {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(minimum, "minimum");
        Objects.requireNonNull(maximum, "maximum");
        if (!dimension.equals(minimum.dimension()) || !dimension.equals(maximum.dimension())) {
            throw new IllegalArgumentException("bounds positions must use the bounds dimension");
        }
        if (minimum.x() > maximum.x()
                || minimum.y() > maximum.y()
                || minimum.z() > maximum.z()) {
            throw new IllegalArgumentException("bounds minimum must not exceed maximum");
        }
        if (maxTravelBlocks < 0 || maxTravelBlocks > 128) {
            throw new IllegalArgumentException("max travel blocks must be in 0..128");
        }
        if (maxDurationSeconds < 1 || maxDurationSeconds > 7_200) {
            throw new IllegalArgumentException("max duration seconds must be in 1..7200");
        }
    }

    public boolean contains(BlockTarget target) {
        Objects.requireNonNull(target, "target");
        return dimension.equals(target.dimension())
                && target.x() >= minimum.x() && target.x() <= maximum.x()
                && target.y() >= minimum.y() && target.y() <= maximum.y()
                && target.z() >= minimum.z() && target.z() <= maximum.z();
    }

    public long hardDeadlineClientTick(long admittedClientTick) {
        if (admittedClientTick < 0) {
            throw new IllegalArgumentException("admission tick must be non-negative");
        }
        long durationTicks = Math.multiplyExact(maxDurationSeconds, 20L);
        return Long.MAX_VALUE - admittedClientTick < durationTicks
                ? Long.MAX_VALUE
                : admittedClientTick + durationTicks;
    }
}
