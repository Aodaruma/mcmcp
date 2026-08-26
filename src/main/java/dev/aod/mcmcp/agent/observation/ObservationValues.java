package dev.aod.mcmcp.agent.observation;

import java.util.Objects;
import java.util.regex.Pattern;

/** Minecraft-independent value objects bounded by the normative MCP catalog. */
public final class ObservationValues {
    private static final Pattern RESOURCE_ID = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]+$");

    private ObservationValues() {
    }

    public record ResourceId(String value) {
        public ResourceId {
            Objects.requireNonNull(value, "value");
            if (value.length() > 128 || !RESOURCE_ID.matcher(value).matches()) {
                throw new IllegalArgumentException("Invalid resource location");
            }
        }

        @Override
        public String toString() {
            return value;
        }
    }

    public record WorldPosition(ResourceId dimension, double x, double y, double z) {
        public WorldPosition {
            Objects.requireNonNull(dimension, "dimension");
            requireFiniteRange(x, -30_000_000.0, 30_000_000.0, "x");
            requireFiniteRange(y, -2_048.0, 2_048.0, "y");
            requireFiniteRange(z, -30_000_000.0, 30_000_000.0, "z");
        }
    }

    public record BlockPosition(ResourceId dimension, int x, int y, int z) {
        public BlockPosition {
            Objects.requireNonNull(dimension, "dimension");
            requireRange(x, -30_000_000, 30_000_000, "x");
            requireRange(y, -2_048, 2_048, "y");
            requireRange(z, -30_000_000, 30_000_000, "z");
        }
    }

    public record Vector(double x, double y, double z) {
        public Vector {
            requireFiniteRange(x, -128.0, 128.0, "x");
            requireFiniteRange(y, -128.0, 128.0, "y");
            requireFiniteRange(z, -128.0, 128.0, "z");
        }
    }

    public record Aabb(
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ) {
        public Aabb {
            requireFiniteRange(minX, -30_000_002.0, 30_000_002.0, "minX");
            requireFiniteRange(minY, -2_050.0, 2_050.0, "minY");
            requireFiniteRange(minZ, -30_000_002.0, 30_000_002.0, "minZ");
            requireFiniteRange(maxX, -30_000_002.0, 30_000_002.0, "maxX");
            requireFiniteRange(maxY, -2_050.0, 2_050.0, "maxY");
            requireFiniteRange(maxZ, -30_000_002.0, 30_000_002.0, "maxZ");
            if (minX > maxX || minY > maxY || minZ > maxZ) {
                throw new IllegalArgumentException("AABB minimum must not exceed maximum");
            }
        }
    }

    static long requireTick(long value, String name) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
        return value;
    }

    static int requireRange(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " is out of range");
        }
        return value;
    }

    static double requireFiniteRange(double value, double minimum, double maximum, String name) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " is not a finite in-range number");
        }
        return value;
    }

    static void requireSameDimension(ResourceId expected, ResourceId actual) {
        if (!Objects.requireNonNull(expected, "expected").equals(
                Objects.requireNonNull(actual, "actual"))) {
            throw new IllegalArgumentException("Observation positions must share one dimension");
        }
    }
}
