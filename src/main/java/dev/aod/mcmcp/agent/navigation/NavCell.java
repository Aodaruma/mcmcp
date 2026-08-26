package dev.aod.mcmcp.agent.navigation;

import java.util.Objects;

/** Dimension-qualified logical player-feet cell used by the pure navigation graph. */
public record NavCell(String dimension, int x, int y, int z) implements Comparable<NavCell> {
    public NavCell {
        Objects.requireNonNull(dimension, "dimension");
        if (dimension.isBlank()) {
            throw new IllegalArgumentException("dimension must not be blank");
        }
    }

    public double distanceTo(NavCell other) {
        requireSameDimension(other);
        long dx = (long) other.x - x;
        long dy = (long) other.y - y;
        long dz = (long) other.z - z;
        return Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz);
    }

    public boolean horizontallyDiagonalTo(NavCell other) {
        requireSameDimension(other);
        return Math.abs((long) other.x - x) == 1 && Math.abs((long) other.z - z) == 1;
    }

    public NavCell offset(int dx, int dy, int dz) {
        return new NavCell(
                dimension,
                Math.addExact(x, dx),
                Math.addExact(y, dy),
                Math.addExact(z, dz));
    }

    private void requireSameDimension(NavCell other) {
        Objects.requireNonNull(other, "other");
        if (!dimension.equals(other.dimension)) {
            throw new IllegalArgumentException("navigation cells belong to different dimensions");
        }
    }

    @Override
    public int compareTo(NavCell other) {
        int compared = dimension.compareTo(other.dimension);
        if (compared != 0) return compared;
        compared = Integer.compare(x, other.x);
        if (compared != 0) return compared;
        compared = Integer.compare(y, other.y);
        if (compared != 0) return compared;
        return Integer.compare(z, other.z);
    }
}
