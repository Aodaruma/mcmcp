package dev.aod.mcmcp.agent.navigation;

import dev.aod.mcmcp.agent.safety.Locomotion;

import java.util.Objects;
import java.util.UUID;

/** Immutable evidence and decision state for one directed, adjacent transition. */
public record TraversabilityEdge(
        UUID worldSessionId,
        Key key,
        Status status,
        TargetSupport targetSupport,
        Clearance clearance,
        Transition transition,
        Fluid fluid,
        Hazard hazard,
        Provenance provenance,
        NavCell observerPosition,
        long observedTick,
        long worldRevision,
        Locomotion locomotion) {
    public TraversabilityEdge(
            UUID worldSessionId,
            Key key,
            Status status,
            TargetSupport targetSupport,
            Clearance clearance,
            Transition transition,
            Fluid fluid,
            Hazard hazard,
            Provenance provenance,
            NavCell observerPosition,
            long observedTick,
            long worldRevision) {
        this(
                worldSessionId, key, status, targetSupport, clearance, transition,
                fluid, hazard, provenance, observerPosition, observedTick, worldRevision,
                Locomotion.GROUND);
    }

    public TraversabilityEdge {
        Objects.requireNonNull(worldSessionId, "worldSessionId");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(targetSupport, "targetSupport");
        Objects.requireNonNull(clearance, "clearance");
        Objects.requireNonNull(transition, "transition");
        Objects.requireNonNull(fluid, "fluid");
        Objects.requireNonNull(hazard, "hazard");
        Objects.requireNonNull(provenance, "provenance");
        Objects.requireNonNull(observerPosition, "observerPosition");
        Objects.requireNonNull(locomotion, "locomotion");
        if (!key.from().dimension().equals(observerPosition.dimension())) {
            throw new IllegalArgumentException("observer and edge must use the same dimension");
        }
        if (observedTick < 0 || worldRevision < 0) {
            throw new IllegalArgumentException("edge tick and revision must be non-negative");
        }
        validateStatus(status, targetSupport, clearance, transition, fluid, hazard, locomotion);
        if (locomotion == Locomotion.LADDER && !key.ladderAdjacent()) {
            throw new IllegalArgumentException(
                    "ladder edges must be vertical or cardinal horizontal transitions");
        }
    }

    public boolean traversable() {
        return status == Status.CONFIRMED || status == Status.PROBE_ALLOWED;
    }

    public boolean requiresProbe() {
        return status == Status.PROBE_ALLOWED;
    }

    /** Ladder rungs remain internal transit nodes unless they also have floor support. */
    public boolean destination() {
        return traversable()
                && (locomotion == Locomotion.GROUND
                        || targetSupport == TargetSupport.CONFIRMED);
    }

    TraversabilityEdge stale() {
        if (status == Status.STALE) return this;
        return new TraversabilityEdge(
                worldSessionId, key, Status.STALE, targetSupport, clearance, transition,
                fluid, hazard, provenance, observerPosition, observedTick, worldRevision,
                locomotion);
    }

    private static void validateStatus(
            Status status,
            TargetSupport support,
            Clearance clearance,
            Transition transition,
            Fluid fluid,
            Hazard hazard,
            Locomotion locomotion) {
        if (status == Status.STALE) {
            return;
        }
        if (status == Status.CONFIRMED
                && ((locomotion == Locomotion.GROUND
                                && support != TargetSupport.CONFIRMED)
                        || support == TargetSupport.UNKNOWN
                || clearance != Clearance.CONFIRMED
                || transition != Transition.CONFIRMED
                || fluid != Fluid.NONE
                || hazard != Hazard.NONE)) {
            throw new IllegalArgumentException(
                    "CONFIRMED requires valid support/clearance and no fluid/hazard");
        }
        if (status == Status.PROBE_ALLOWED
                && ((locomotion == Locomotion.GROUND
                                && support != TargetSupport.CONFIRMED)
                        || support == TargetSupport.UNKNOWN
                || clearance != Clearance.CONFIRMED
                || transition != Transition.PARTIAL
                || fluid != Fluid.NONE
                || hazard != Hazard.NONE)) {
            throw new IllegalArgumentException(
                    "PROBE_ALLOWED requires valid support/clearance and a partial transition");
        }
        if (status == Status.BLOCKED
                && support != TargetSupport.ABSENT
                && clearance != Clearance.BLOCKED
                && transition != Transition.BLOCKED
                && fluid == Fluid.NONE
                && hazard == Hazard.NONE) {
            throw new IllegalArgumentException("BLOCKED requires concrete blocking evidence");
        }
    }

    public record Key(NavCell from, NavCell to) implements Comparable<Key> {
        public Key {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            if (!from.dimension().equals(to.dimension())) {
                throw new IllegalArgumentException("edge endpoints must use the same dimension");
            }
            long dx = Math.abs((long) to.x() - from.x());
            long dy = Math.abs((long) to.y() - from.y());
            long dz = Math.abs((long) to.z() - from.z());
            if (dx > 1 || dy > 1 || dz > 1 || dx + dy + dz == 0) {
                throw new IllegalArgumentException("edge endpoints must be distinct adjacent cells");
            }
        }

        public double length() {
            return from.distanceTo(to);
        }

        boolean ladderAdjacent() {
            int dx = Math.abs(to.x() - from.x());
            int dy = Math.abs(to.y() - from.y());
            int dz = Math.abs(to.z() - from.z());
            return dx + dy + dz == 1;
        }

        @Override
        public int compareTo(Key other) {
            int compared = from.compareTo(other.from);
            return compared != 0 ? compared : to.compareTo(other.to);
        }
    }

    public enum Status {
        CONFIRMED,
        PROBE_ALLOWED,
        BLOCKED,
        STALE
    }

    public enum TargetSupport {
        CONFIRMED,
        ABSENT,
        UNKNOWN
    }

    public enum Clearance {
        CONFIRMED,
        BLOCKED,
        UNKNOWN
    }

    public enum Transition {
        CONFIRMED,
        PARTIAL,
        BLOCKED,
        UNKNOWN
    }

    public enum Fluid {
        NONE,
        WATER,
        LAVA,
        OTHER,
        UNKNOWN
    }

    public enum Hazard {
        NONE,
        CAUTION,
        URGENT,
        UNKNOWN
    }

    public enum Provenance {
        OMNIDIRECTIONAL_VISUAL,
        LOCAL_VOLUME,
        CONTACT,
        SOUND;

        public boolean mayUpdateTraversability() {
            return this == LOCAL_VOLUME || this == CONTACT;
        }
    }
}
