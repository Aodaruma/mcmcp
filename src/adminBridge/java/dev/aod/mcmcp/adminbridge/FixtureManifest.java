package dev.aod.mcmcp.adminbridge;

import java.util.List;
import java.util.Objects;

/** Strict, data-only authority envelope for one externally editable fixture. */
public record FixtureManifest(
        int schemaVersion,
        String id,
        String dimension,
        Bounds mutationBounds,
        Bounds playerBounds,
        int maxChangedBlocks,
        List<BlockPosition> containers,
        RandomTickLease randomTickLease) {

    public FixtureManifest {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("schemaVersion");
        }
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(mutationBounds, "mutationBounds");
        Objects.requireNonNull(playerBounds, "playerBounds");
        containers = List.copyOf(containers);
    }

    public record BlockPosition(int x, int y, int z) {
    }

    public record Bounds(BlockPosition min, BlockPosition max) {
        public Bounds {
            Objects.requireNonNull(min, "min");
            Objects.requireNonNull(max, "max");
            if (min.x() > max.x() || min.y() > max.y() || min.z() > max.z()) {
                throw new IllegalArgumentException("bounds");
            }
        }

        public boolean contains(BlockPosition position) {
            return position.x() >= min.x() && position.x() <= max.x()
                    && position.y() >= min.y() && position.y() <= max.y()
                    && position.z() >= min.z() && position.z() <= max.z();
        }

        public boolean contains(double x, double y, double z) {
            return x >= min.x() && x <= max.x() + 1.0D
                    && y >= min.y() && y <= max.y() + 1.0D
                    && z >= min.z() && z <= max.z() + 1.0D;
        }

        public boolean contains(Bounds other) {
            return contains(other.min()) && contains(other.max());
        }
    }

    public record RandomTickLease(int target, int maximumSeconds) {
    }
}
