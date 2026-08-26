package dev.aod.mcmcp.agent.safety;

import java.util.Objects;

/**
 * Safety-only observation. Deliberately contains no block, registry, container, ore, or
 * structure identity: callers receive only movement-derived classifications.
 */
public record ObservationRecord(
        long observedTick,
        long worldRevision,
        int transitionDepth,
        Point from,
        Point requestedTo,
        Point to,
        Support support,
        Clearance clearance,
        Transition transition,
        Fluid fluid,
        boolean suffocation,
        Hazard hazard,
        LoadedState loaded,
        Drop drop,
        boolean neutralizeAgentHorizontal) {
    public ObservationRecord {
        if (observedTick < 0L) {
            throw new IllegalArgumentException("observedTick must be non-negative");
        }
        if (worldRevision < LocalObservationVolume.UNKNOWN_WORLD_REVISION) {
            throw new IllegalArgumentException("worldRevision must be non-negative or UNKNOWN");
        }
        if (transitionDepth < 0 || transitionDepth > LocalObservationVolume.MAX_TRANSITIONS) {
            throw new IllegalArgumentException("transitionDepth is outside the local volume");
        }
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(requestedTo, "requestedTo");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(support, "support");
        Objects.requireNonNull(clearance, "clearance");
        Objects.requireNonNull(transition, "transition");
        Objects.requireNonNull(fluid, "fluid");
        Objects.requireNonNull(hazard, "hazard");
        Objects.requireNonNull(loaded, "loaded");
        Objects.requireNonNull(drop, "drop");
        if (transitionDepth == 0 && transition != Transition.STATIONARY) {
            throw new IllegalArgumentException("depth zero is reserved for the current AABB");
        }
        if (transition == Transition.UNKNOWN && loaded != LoadedState.UNKNOWN
                && fluid != Fluid.UNKNOWN) {
            throw new IllegalArgumentException("UNKNOWN transition requires unknown evidence");
        }
    }

    public boolean canExpand() {
        return loaded == LoadedState.LOADED
                && clearance == Clearance.CLEAR
                && transition == Transition.PROBE_ALLOWED
                && support == Support.PRESENT
                && fluid != Fluid.LAVA
                && fluid != Fluid.UNKNOWN
                && hazard == Hazard.NONE;
    }

    public enum Support {
        PRESENT,
        ABSENT,
        UNKNOWN
    }

    public enum Clearance {
        CLEAR,
        BLOCKED,
        UNKNOWN
    }

    public enum Transition {
        STATIONARY,
        PROBE_ALLOWED,
        CONTACT,
        BLOCKED,
        UNKNOWN
    }

    public enum Fluid {
        NONE,
        WATER,
        LAVA,
        UNKNOWN
    }

    public enum Hazard {
        NONE,
        COLLISION,
        FALL,
        LAVA,
        FIRE_DAMAGE,
        CONTACT_DAMAGE,
        FREEZING,
        SUFFOCATION,
        UNKNOWN
    }

    public enum LoadedState {
        LOADED,
        UNKNOWN
    }

    public enum Drop {
        SUPPORTED,
        AIRBORNE_OR_SWIMMING,
        WITHIN_WALKING_LIMIT,
        EXCEEDS_WALKING_LIMIT,
        UNKNOWN
    }

    public record Point(double x, double y, double z) {
        public Point {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("point coordinates must be finite");
            }
        }

        public double distanceSquared(Point other) {
            Objects.requireNonNull(other, "other");
            double xDistance = x - other.x;
            double yDistance = y - other.y;
            double zDistance = z - other.z;
            return xDistance * xDistance + yDistance * yDistance + zDistance * zDistance;
        }
    }
}
