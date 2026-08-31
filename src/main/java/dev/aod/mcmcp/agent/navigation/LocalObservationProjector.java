package dev.aod.mcmcp.agent.navigation;

import dev.aod.mcmcp.agent.observation.ObservationRecord.EvidenceProvenance;
import dev.aod.mcmcp.agent.observation.ObservationRecord.HazardSeverity;
import dev.aod.mcmcp.agent.observation.ObservationRecord.HazardType;
import dev.aod.mcmcp.agent.observation.ObservationRecord.TargetSupport;
import dev.aod.mcmcp.agent.observation.ObservationRecord.TransitionClearance;
import dev.aod.mcmcp.agent.observation.ObservationRecord.TraversabilityStatus;
import dev.aod.mcmcp.agent.observation.ObservationValues.ResourceId;
import dev.aod.mcmcp.agent.observation.ObservationValues.WorldPosition;
import dev.aod.mcmcp.agent.safety.LocalObservationVolume;
import dev.aod.mcmcp.agent.safety.Locomotion;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Converts Local Observation Volume classifications into the public frame and route graph. */
public final class LocalObservationProjector {
    /** One current hazard plus, conservatively, one hazard and traversal per local record. */
    public static final int MAX_PUBLIC_RECORDS =
            1 + (LocalObservationVolume.MAX_OBSERVATIONS * 2);

    private LocalObservationProjector() {
    }

    public static Projection project(
            LocalObservationVolume.Snapshot snapshot,
            UUID worldSessionId,
            String dimension,
            long currentWorldRevision,
            double currentFeetY) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(worldSessionId, "worldSessionId");
        var dimensionId = new ResourceId(Objects.requireNonNull(dimension, "dimension"));
        if (currentWorldRevision < 0L) {
            throw new IllegalArgumentException("currentWorldRevision must be non-negative");
        }
        double centerToFeet = snapshot.center().y() - currentFeetY;
        if (!Double.isFinite(centerToFeet) || centerToFeet <= 0.0D) {
            throw new IllegalArgumentException("currentFeetY must be below the observation center");
        }
        if (snapshot.worldRevision() != currentWorldRevision
                || snapshot.current().worldRevision() != currentWorldRevision) {
            return new Projection(List.of(), List.of(), CurrentSafety.REPLAN);
        }

        var publicRecords = new ArrayList<dev.aod.mcmcp.agent.observation.ObservationRecord>();
        var edges = new ArrayList<TraversabilityEdge>();
        addHazard(snapshot.current(), dimensionId, snapshot.center(), publicRecords);
        for (var source : snapshot.transitions()) {
            projectTransition(
                    source,
                    snapshot.center(),
                    centerToFeet,
                    worldSessionId,
                    dimensionId,
                    currentWorldRevision,
                    publicRecords,
                    edges);
        }
        return new Projection(
                publicRecords,
                edges,
                currentSafety(snapshot.current()));
    }

    private static void projectTransition(
            dev.aod.mcmcp.agent.safety.ObservationRecord source,
            dev.aod.mcmcp.agent.safety.ObservationRecord.Point observer,
            double centerToFeet,
            UUID worldSessionId,
            ResourceId dimension,
            long worldRevision,
            List<dev.aod.mcmcp.agent.observation.ObservationRecord> records,
            List<TraversabilityEdge> edges) {
        if (source.worldRevision() != worldRevision) {
            return;
        }
        addHazard(source, dimension, observer, records);
        if (unknown(source)) {
            return;
        }

        TraversabilityEdge.Status status = status(source);
        NavCell from = navCell(dimension.value(), source.from(), centerToFeet);
        NavCell requested = navCell(dimension.value(), source.requestedTo(), centerToFeet);
        NavCell resolved = navCell(dimension.value(), source.to(), centerToFeet);
        var to = new NavCell(
                dimension.value(), requested.x(), resolved.y(), requested.z());
        final TraversabilityEdge.Key key;
        try {
            key = new TraversabilityEdge.Key(from, to);
        } catch (IllegalArgumentException invalidEdge) {
            return;
        }

        EvidenceProvenance provenance = source.transition()
                        == dev.aod.mcmcp.agent.safety.ObservationRecord.Transition.CONTACT
                ? EvidenceProvenance.CONTACT
                : EvidenceProvenance.LOCAL_VOLUME;
        var edge = new TraversabilityEdge(
                worldSessionId,
                key,
                status,
                edgeSupport(source),
                edgeClearance(source),
                edgeTransition(status),
                edgeFluid(source),
                edgeHazard(source),
                provenance == EvidenceProvenance.CONTACT
                        ? TraversabilityEdge.Provenance.CONTACT
                        : TraversabilityEdge.Provenance.LOCAL_VOLUME,
                navCell(dimension.value(), observer, centerToFeet),
                source.observedTick(),
                worldRevision,
                source.locomotion());
        edges.add(edge);
        if (source.locomotion() != Locomotion.GROUND && !edge.destination()) {
            return;
        }

        var publicFrom = worldPosition(dimension, source.from());
        var publicTo = new WorldPosition(
                dimension, source.requestedTo().x(), source.to().y(), source.requestedTo().z());
        records.add(new dev.aod.mcmcp.agent.observation.ObservationRecord.Traversability(
                publicFrom,
                publicTo,
                TraversabilityStatus.valueOf(status.name()),
                targetSupport(source),
                clearance(source),
                fluid(source),
                worldPosition(dimension, observer),
                source.observedTick(),
                worldRevision,
                provenance));
    }

    private static boolean unknown(dev.aod.mcmcp.agent.safety.ObservationRecord source) {
        return source.worldRevision() < 0L
                || source.loaded()
                        == dev.aod.mcmcp.agent.safety.ObservationRecord.LoadedState.UNKNOWN
                || source.support()
                        == dev.aod.mcmcp.agent.safety.ObservationRecord.Support.UNKNOWN
                || source.clearance()
                        == dev.aod.mcmcp.agent.safety.ObservationRecord.Clearance.UNKNOWN
                || source.transition()
                        == dev.aod.mcmcp.agent.safety.ObservationRecord.Transition.UNKNOWN
                || source.fluid()
                        == dev.aod.mcmcp.agent.safety.ObservationRecord.Fluid.UNKNOWN
                || source.hazard()
                        == dev.aod.mcmcp.agent.safety.ObservationRecord.Hazard.UNKNOWN;
    }

    private static TraversabilityEdge.Status status(
            dev.aod.mcmcp.agent.safety.ObservationRecord source) {
        boolean supported = source.support()
                        == dev.aod.mcmcp.agent.safety.ObservationRecord.Support.PRESENT
                || source.locomotion() != Locomotion.GROUND
                        && source.support()
                                == dev.aod.mcmcp.agent.safety.ObservationRecord.Support.ABSENT;
        boolean safe = supported
                && source.clearance()
                        == dev.aod.mcmcp.agent.safety.ObservationRecord.Clearance.CLEAR
                && source.fluid()
                        == dev.aod.mcmcp.agent.safety.ObservationRecord.Fluid.NONE
                && source.hazard()
                        == dev.aod.mcmcp.agent.safety.ObservationRecord.Hazard.NONE;
        if (safe && source.transition()
                == dev.aod.mcmcp.agent.safety.ObservationRecord.Transition.CONTACT) {
            return TraversabilityEdge.Status.CONFIRMED;
        }
        if (safe && source.transition()
                == dev.aod.mcmcp.agent.safety.ObservationRecord.Transition.PROBE_ALLOWED) {
            return TraversabilityEdge.Status.PROBE_ALLOWED;
        }
        return TraversabilityEdge.Status.BLOCKED;
    }

    private static TraversabilityEdge.TargetSupport edgeSupport(
            dev.aod.mcmcp.agent.safety.ObservationRecord source) {
        return switch (source.support()) {
            case PRESENT -> TraversabilityEdge.TargetSupport.CONFIRMED;
            case ABSENT -> TraversabilityEdge.TargetSupport.ABSENT;
            case UNKNOWN -> TraversabilityEdge.TargetSupport.UNKNOWN;
        };
    }

    private static TargetSupport targetSupport(
            dev.aod.mcmcp.agent.safety.ObservationRecord source) {
        return switch (source.support()) {
            case PRESENT -> TargetSupport.CONFIRMED;
            case ABSENT -> TargetSupport.ABSENT;
            case UNKNOWN -> TargetSupport.UNKNOWN;
        };
    }

    private static TraversabilityEdge.Clearance edgeClearance(
            dev.aod.mcmcp.agent.safety.ObservationRecord source) {
        return switch (source.clearance()) {
            case CLEAR -> TraversabilityEdge.Clearance.CONFIRMED;
            case BLOCKED -> TraversabilityEdge.Clearance.BLOCKED;
            case UNKNOWN -> TraversabilityEdge.Clearance.UNKNOWN;
        };
    }

    private static TransitionClearance clearance(
            dev.aod.mcmcp.agent.safety.ObservationRecord source) {
        return switch (source.clearance()) {
            case CLEAR -> TransitionClearance.CONFIRMED;
            case BLOCKED -> TransitionClearance.BLOCKED;
            case UNKNOWN -> TransitionClearance.UNKNOWN;
        };
    }

    private static TraversabilityEdge.Transition edgeTransition(TraversabilityEdge.Status status) {
        return switch (status) {
            case CONFIRMED -> TraversabilityEdge.Transition.CONFIRMED;
            case PROBE_ALLOWED -> TraversabilityEdge.Transition.PARTIAL;
            case BLOCKED -> TraversabilityEdge.Transition.BLOCKED;
            case STALE -> throw new IllegalArgumentException("projector cannot produce STALE");
        };
    }

    private static TraversabilityEdge.Fluid edgeFluid(
            dev.aod.mcmcp.agent.safety.ObservationRecord source) {
        return switch (source.fluid()) {
            case NONE -> TraversabilityEdge.Fluid.NONE;
            case WATER -> TraversabilityEdge.Fluid.WATER;
            case LAVA -> TraversabilityEdge.Fluid.LAVA;
            case UNKNOWN -> TraversabilityEdge.Fluid.UNKNOWN;
        };
    }

    private static dev.aod.mcmcp.agent.observation.ObservationRecord.Fluid fluid(
            dev.aod.mcmcp.agent.safety.ObservationRecord source) {
        return switch (source.fluid()) {
            case NONE -> dev.aod.mcmcp.agent.observation.ObservationRecord.Fluid.NONE;
            case WATER -> dev.aod.mcmcp.agent.observation.ObservationRecord.Fluid.WATER;
            case LAVA -> dev.aod.mcmcp.agent.observation.ObservationRecord.Fluid.LAVA;
            case UNKNOWN -> dev.aod.mcmcp.agent.observation.ObservationRecord.Fluid.UNKNOWN;
        };
    }

    private static TraversabilityEdge.Hazard edgeHazard(
            dev.aod.mcmcp.agent.safety.ObservationRecord source) {
        if (source.fluid() == dev.aod.mcmcp.agent.safety.ObservationRecord.Fluid.WATER) {
            return TraversabilityEdge.Hazard.CAUTION;
        }
        return switch (source.hazard()) {
            case NONE -> TraversabilityEdge.Hazard.NONE;
            case COLLISION -> TraversabilityEdge.Hazard.CAUTION;
            case FALL, LAVA, FIRE_DAMAGE, CONTACT_DAMAGE, FREEZING, SUFFOCATION ->
                    TraversabilityEdge.Hazard.URGENT;
            case UNKNOWN -> TraversabilityEdge.Hazard.UNKNOWN;
        };
    }

    private static void addHazard(
            dev.aod.mcmcp.agent.safety.ObservationRecord source,
            ResourceId dimension,
            dev.aod.mcmcp.agent.safety.ObservationRecord.Point observer,
            List<dev.aod.mcmcp.agent.observation.ObservationRecord> records) {
        HazardType type;
        HazardSeverity severity;
        if (source.fluid() == dev.aod.mcmcp.agent.safety.ObservationRecord.Fluid.WATER) {
            type = HazardType.WATER;
            severity = HazardSeverity.CAUTION;
        } else {
            type = switch (source.hazard()) {
                case NONE -> null;
                case COLLISION -> HazardType.COLLISION;
                case FALL -> HazardType.FALL;
                case LAVA -> HazardType.LAVA;
                case FIRE_DAMAGE -> HazardType.FIRE;
                case CONTACT_DAMAGE -> HazardType.CONTACT_DAMAGE;
                case FREEZING -> HazardType.FREEZING;
                case SUFFOCATION -> HazardType.SUFFOCATION;
                case UNKNOWN -> HazardType.UNKNOWN;
            };
            severity = switch (source.hazard()) {
                case NONE -> null;
                case COLLISION -> HazardSeverity.CAUTION;
                case FALL, LAVA, FIRE_DAMAGE, CONTACT_DAMAGE, FREEZING, SUFFOCATION ->
                        HazardSeverity.URGENT;
                case UNKNOWN -> HazardSeverity.UNKNOWN;
            };
        }
        if (type == null) {
            return;
        }
        EvidenceProvenance provenance = source.transition()
                        == dev.aod.mcmcp.agent.safety.ObservationRecord.Transition.CONTACT
                ? EvidenceProvenance.CONTACT
                : EvidenceProvenance.LOCAL_VOLUME;
        records.add(new dev.aod.mcmcp.agent.observation.ObservationRecord.Hazard(
                type,
                worldPosition(dimension, source.to()),
                severity,
                worldPosition(dimension, observer),
                source.observedTick(),
                source.worldRevision(),
                provenance));
    }

    private static CurrentSafety currentSafety(
            dev.aod.mcmcp.agent.safety.ObservationRecord current) {
        return switch (current.hazard()) {
            case FALL, LAVA, FIRE_DAMAGE, CONTACT_DAMAGE, FREEZING, SUFFOCATION ->
                    CurrentSafety.RECOVER;
            case COLLISION, UNKNOWN -> CurrentSafety.REPLAN;
            case NONE -> current.neutralizeAgentHorizontal()
                    || current.loaded()
                            == dev.aod.mcmcp.agent.safety.ObservationRecord.LoadedState.UNKNOWN
                    || current.fluid()
                            == dev.aod.mcmcp.agent.safety.ObservationRecord.Fluid.UNKNOWN
                    ? CurrentSafety.REPLAN : CurrentSafety.CONTINUE;
        };
    }

    private static NavCell navCell(
            String dimension,
            dev.aod.mcmcp.agent.safety.ObservationRecord.Point point,
            double centerToFeet) {
        return new NavCell(
                dimension,
                floor(point.x()),
                floor(point.y() - centerToFeet),
                floor(point.z()));
    }

    private static WorldPosition worldPosition(
            ResourceId dimension,
            dev.aod.mcmcp.agent.safety.ObservationRecord.Point point) {
        return new WorldPosition(dimension, point.x(), point.y(), point.z());
    }

    private static int floor(double value) {
        double floor = Math.floor(value);
        if (floor < Integer.MIN_VALUE || floor > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("navigation coordinate is out of range");
        }
        return (int) floor;
    }

    public record Projection(
            List<dev.aod.mcmcp.agent.observation.ObservationRecord> records,
            List<TraversabilityEdge> edges,
            CurrentSafety currentSafety) {
        public Projection {
            records = List.copyOf(records);
            edges = List.copyOf(edges);
            Objects.requireNonNull(currentSafety, "currentSafety");
        }
    }

    public enum CurrentSafety { CONTINUE, REPLAN, RECOVER }
}
