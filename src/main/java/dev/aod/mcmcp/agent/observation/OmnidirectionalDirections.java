package dev.aod.mcmcp.agent.observation;

import java.util.ArrayList;
import java.util.List;

/** A world-axis-fixed, deterministic equal-area direction set for visual observations. */
final class OmnidirectionalDirections {
    static final int DIRECTION_COUNT = 2_048;

    private static final double GOLDEN_ANGLE = Math.PI * (3.0D - Math.sqrt(5.0D));
    private static final List<DirectionVector> DIRECTIONS = createDirections();

    private OmnidirectionalDirections() {
    }

    static List<DirectionVector> all() {
        return DIRECTIONS;
    }

    static int ticksToComplete(int raysPerTick) {
        requireRaysPerTick(raysPerTick);
        return Math.ceilDiv(DIRECTION_COUNT, raysPerTick);
    }

    static int requireRaysPerTick(int raysPerTick) {
        if (raysPerTick < OmnidirectionalObserver.MIN_RAYS_PER_TICK
                || raysPerTick > OmnidirectionalObserver.MAX_RAYS_PER_TICK) {
            throw new IllegalArgumentException("raysPerTick must be between 64 and 512");
        }
        return raysPerTick;
    }

    private static List<DirectionVector> createDirections() {
        var result = new ArrayList<DirectionVector>(DIRECTION_COUNT);
        for (int index = 0; index < DIRECTION_COUNT; index++) {
            // Midpoints of equal-height latitude bands give every sample the same solid angle.
            double y = 1.0D - (2.0D * (index + 0.5D) / DIRECTION_COUNT);
            double radius = Math.sqrt(Math.max(0.0D, 1.0D - y * y));
            double azimuth = index * GOLDEN_ANGLE;
            result.add(new DirectionVector(
                    radius * Math.cos(azimuth),
                    y,
                    radius * Math.sin(azimuth)));
        }
        return List.copyOf(result);
    }

    record DirectionVector(double x, double y, double z) {
        DirectionVector {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("direction components must be finite");
            }
            double lengthSquared = x * x + y * y + z * z;
            if (Math.abs(lengthSquared - 1.0D) > 1.0E-12D) {
                throw new IllegalArgumentException("direction must have unit length");
            }
        }
    }
}
