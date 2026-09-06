package dev.aod.mcmcp.agent.observation;

import dev.aod.mcmcp.routine.KnownContainerPolicy;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import static dev.aod.mcmcp.agent.observation.ObservationValues.Aabb;
import static dev.aod.mcmcp.agent.observation.ObservationValues.BlockPosition;
import static dev.aod.mcmcp.agent.observation.ObservationValues.ResourceId;
import static dev.aod.mcmcp.agent.observation.ObservationValues.Vector;
import static dev.aod.mcmcp.agent.observation.ObservationValues.WorldPosition;

/** Policy-filtered records permitted by the normative observation catalog. */
public sealed interface ObservationRecord permits ObservationRecord.VisibleSurface,
        ObservationRecord.VisibleEntity, ObservationRecord.Traversability,
        ObservationRecord.Hazard, ObservationRecord.UnknownBoundary,
        ObservationRecord.SoundClue {

    ObservationKind kind();

    ResourceId dimension();

    long oldestObservedTick();

    long newestObservedTick();

    long worldRevision();

    record VisibleSurface(
            BlockPosition position,
            Face face,
            ResourceId block,
            BlockStateView state,
            ResourceId placementItem,
            ShapeClass shapeClass,
            Boolean cropMature,
            WorldPosition rayHit,
            WorldPosition eyeOrigin,
            long observedTick,
            long worldRevision) implements ObservationRecord {
        public VisibleSurface {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(face, "face");
            Objects.requireNonNull(block, "block");
            if (state != null && !block.equals(state.block())) {
                throw new IllegalArgumentException(
                        "Visible surface block must equal state.block");
            }
            if (placementItem != null && state == null) {
                throw new IllegalArgumentException(
                        "Visible surface placement item requires a complete state");
            }
            if (placementItem != null && "minecraft:air".equals(placementItem.value())) {
                throw new IllegalArgumentException(
                        "Visible surface placement item must not be air");
            }
            Objects.requireNonNull(shapeClass, "shapeClass");
            Objects.requireNonNull(eyeOrigin, "eyeOrigin");
            if (rayHit != null) {
                ObservationValues.requireSameDimension(position.dimension(), rayHit.dimension());
                if (rayHit.x() < position.x() || rayHit.x() > position.x() + 1.0D
                        || rayHit.y() < position.y() || rayHit.y() > position.y() + 1.0D
                        || rayHit.z() < position.z() || rayHit.z() > position.z() + 1.0D) {
                    throw new IllegalArgumentException("Visible surface ray hit must be inside its block");
                }
            }
            ObservationValues.requireSameDimension(position.dimension(), eyeOrigin.dimension());
            ObservationValues.requireTick(observedTick, "observedTick");
            ObservationValues.requireTick(worldRevision, "worldRevision");
        }

        public VisibleSurface(
                BlockPosition position,
                Face face,
                ResourceId block,
                BlockStateView state,
                ResourceId placementItem,
                ShapeClass shapeClass,
                Boolean cropMature,
                WorldPosition eyeOrigin,
                long observedTick,
                long worldRevision) {
            this(position, face, block, state, placementItem, shapeClass, cropMature, null,
                    eyeOrigin, observedTick, worldRevision);
        }

        /**
         * Compatibility constructor for a surface without policy-visible complete state.
         */
        public VisibleSurface(
                BlockPosition position,
                Face face,
                ResourceId block,
                ShapeClass shapeClass,
                Boolean cropMature,
                WorldPosition rayHit,
                WorldPosition eyeOrigin,
                long observedTick,
                long worldRevision) {
            this(position, face, block, null, null,
                    shapeClass, cropMature, rayHit, eyeOrigin, observedTick, worldRevision);
        }

        /** Compatibility constructor; see the overload retaining an explicit ray hit. */
        public VisibleSurface(
                BlockPosition position,
                Face face,
                ResourceId block,
                ShapeClass shapeClass,
                Boolean cropMature,
                WorldPosition eyeOrigin,
                long observedTick,
                long worldRevision) {
            this(position, face, block, null, null,
                    shapeClass, cropMature, null, eyeOrigin, observedTick, worldRevision);
        }

        /** Compatibility constructor; see the overload retaining an explicit ray hit. */
        public VisibleSurface(
                BlockPosition position,
                Face face,
                ResourceId block,
                ShapeClass shapeClass,
                WorldPosition eyeOrigin,
                long observedTick,
                long worldRevision) {
            this(position, face, block, null, null,
                    shapeClass, null, null,
                    eyeOrigin, observedTick, worldRevision);
        }

        @Override public ObservationKind kind() { return ObservationKind.VISIBLE_SURFACE; }
        @Override public ResourceId dimension() { return position.dimension(); }
        @Override public long oldestObservedTick() { return observedTick; }
        @Override public long newestObservedTick() { return observedTick; }
        public EvidenceProvenance provenance() { return EvidenceProvenance.OMNIDIRECTIONAL_VISUAL; }
    }

    /** Complete, canonical BlockState identity for an audited action surface. */
    record BlockStateView(ResourceId block, Map<String, String> properties) {
        public BlockStateView {
            Objects.requireNonNull(block, "block");
            Objects.requireNonNull(properties, "properties");
            var ordered = new TreeMap<String, String>();
            for (var entry : properties.entrySet()) {
                String name = Objects.requireNonNull(entry.getKey(), "property name");
                String value = Objects.requireNonNull(entry.getValue(), "property value");
                if (!name.matches("[a-z0-9_]{1,64}")
                        || value.isBlank() || value.length() > 96) {
                    throw new IllegalArgumentException("invalid block state property");
                }
                ordered.put(name, value);
            }
            properties = Collections.unmodifiableMap(ordered);
        }
    }

    record VisibleEntity(
            ResourceId entityType,
            ResourceId displayedItem,
            String entityRef,
            WorldPosition position,
            Vector velocity,
            Aabb aabb,
            EntityHazardClass hazardClass,
            WorldPosition eyeOrigin,
            long observedTick,
            long worldRevision,
            ContainerLabel containerLabel,
            FrameDisplay frameDisplay) implements ObservationRecord {
        public VisibleEntity {
            Objects.requireNonNull(entityType, "entityType");
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(velocity, "velocity");
            Objects.requireNonNull(aabb, "aabb");
            Objects.requireNonNull(hazardClass, "hazardClass");
            Objects.requireNonNull(eyeOrigin, "eyeOrigin");
            boolean itemEntity = "minecraft:item".equals(entityType.value());
            if (itemEntity != (displayedItem != null)
                    || displayedItem != null && "minecraft:air".equals(displayedItem.value())) {
                throw new IllegalArgumentException(
                        "displayedItem is required exactly for a non-empty minecraft:item entity");
            }
            if (entityRef != null && !entityRef.matches("[A-Za-z0-9_-]{24}")) {
                throw new IllegalArgumentException(
                        "entityRef must be a 24-character opaque reference");
            }
            if (hazardClass == EntityHazardClass.PLAYER && entityRef != null) {
                throw new IllegalArgumentException("player entities must not expose entityRef");
            }
            boolean itemFrame = "minecraft:item_frame".equals(entityType.value())
                    || "minecraft:glow_item_frame".equals(entityType.value());
            if (frameDisplay != null && (!itemFrame || entityRef == null)) {
                throw new IllegalArgumentException(
                        "frameDisplay requires an opaque visible item-frame reference");
            }
            if (frameDisplay != null) {
                ObservationValues.requireSameDimension(
                        position.dimension(), frameDisplay.aimPoint().dimension());
            }
            if (containerLabel != null) {
                if (!itemFrame || displayedItem != null || entityRef == null) {
                    throw new IllegalArgumentException(
                            "containerLabel requires an opaque visible item-frame reference");
                }
                ObservationValues.requireSameDimension(
                        position.dimension(), containerLabel.containerPosition().dimension());
            }
            ObservationValues.requireSameDimension(position.dimension(), eyeOrigin.dimension());
            ObservationValues.requireTick(observedTick, "observedTick");
            ObservationValues.requireTick(worldRevision, "worldRevision");
        }

        /** Compatibility constructor for observations without visible frame-display evidence. */
        public VisibleEntity(
                ResourceId entityType, ResourceId displayedItem, String entityRef,
                WorldPosition position, Vector velocity, Aabb aabb, EntityHazardClass hazardClass,
                WorldPosition eyeOrigin, long observedTick, long worldRevision,
                ContainerLabel containerLabel) {
            this(entityType, displayedItem, entityRef, position, velocity, aabb, hazardClass,
                    eyeOrigin, observedTick, worldRevision, containerLabel, null);
        }

        /** Compatibility constructor for visible entities without a container routing label. */
        public VisibleEntity(
                ResourceId entityType,
                ResourceId displayedItem,
                String entityRef,
                WorldPosition position,
                Vector velocity,
                Aabb aabb,
                EntityHazardClass hazardClass,
                WorldPosition eyeOrigin,
                long observedTick,
                long worldRevision) {
            this(entityType, displayedItem, entityRef, position, velocity, aabb, hazardClass,
                    eyeOrigin, observedTick, worldRevision, null);
        }

        /** Compatibility constructor for observations captured before opaque refs are attached. */
        public VisibleEntity(
                ResourceId entityType,
                ResourceId displayedItem,
                WorldPosition position,
                Vector velocity,
                Aabb aabb,
                EntityHazardClass hazardClass,
                WorldPosition eyeOrigin,
                long observedTick,
                long worldRevision) {
            this(entityType, displayedItem, null, position, velocity, aabb, hazardClass,
                    eyeOrigin, observedTick, worldRevision, null);
        }

        /** Backward-compatible constructor for visible entities that do not render an item stack. */
        public VisibleEntity(
                ResourceId entityType,
                WorldPosition position,
                Vector velocity,
                Aabb aabb,
                EntityHazardClass hazardClass,
                WorldPosition eyeOrigin,
                long observedTick,
                long worldRevision) {
            this(entityType, null, null, position, velocity, aabb, hazardClass,
                    eyeOrigin, observedTick, worldRevision, null);
        }

        @Override public ObservationKind kind() { return ObservationKind.VISIBLE_ENTITY; }
        @Override public ResourceId dimension() { return position.dimension(); }
        @Override public long oldestObservedTick() { return observedTick; }
        @Override public long newestObservedTick() { return observedTick; }
        public EvidenceProvenance provenance() { return EvidenceProvenance.OMNIDIRECTIONAL_VISUAL; }
    }

    /** Rendered front-face content only; a null item means the visible frame is empty. */
    record FrameDisplay(ResourceId item, int rotation, WorldPosition aimPoint) {
        public FrameDisplay {
            Objects.requireNonNull(aimPoint, "aimPoint");
            if (item != null && "minecraft:air".equals(item.value())) {
                throw new IllegalArgumentException("an empty frame display uses null");
            }
            if (rotation < 0 || rotation > 7) {
                throw new IllegalArgumentException("frame rotation must be between 0 and 7");
            }
        }
    }

    /** A visible item-frame label directly attached to one supported Vanilla container. */
    record ContainerLabel(
            ResourceId item,
            BlockPosition containerPosition,
            ResourceId containerBlock,
            Face attachmentFace) {
        public ContainerLabel {
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(containerPosition, "containerPosition");
            Objects.requireNonNull(containerBlock, "containerBlock");
            Objects.requireNonNull(attachmentFace, "attachmentFace");
            if ("minecraft:air".equals(item.value())) {
                throw new IllegalArgumentException("container label item must not be air");
            }
            if (!KnownContainerPolicy.allows(containerBlock.value())) {
                throw new IllegalArgumentException(
                        "container label must be attached to a supported container");
            }
        }
    }

    record Traversability(
            WorldPosition from,
            WorldPosition to,
            TraversabilityStatus status,
            TargetSupport targetSupport,
            TransitionClearance transitionClearance,
            Fluid fluid,
            WorldPosition observerPosition,
            long observedTick,
            long worldRevision,
            EvidenceProvenance provenance) implements ObservationRecord {
        public Traversability {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(targetSupport, "targetSupport");
            Objects.requireNonNull(transitionClearance, "transitionClearance");
            Objects.requireNonNull(fluid, "fluid");
            Objects.requireNonNull(observerPosition, "observerPosition");
            Objects.requireNonNull(provenance, "provenance");
            ObservationValues.requireSameDimension(from.dimension(), to.dimension());
            ObservationValues.requireSameDimension(from.dimension(), observerPosition.dimension());
            ObservationValues.requireTick(observedTick, "observedTick");
            ObservationValues.requireTick(worldRevision, "worldRevision");
            requireLocalOrContact(provenance);
        }

        @Override public ObservationKind kind() { return ObservationKind.TRAVERSABILITY; }
        @Override public ResourceId dimension() { return from.dimension(); }
        @Override public long oldestObservedTick() { return observedTick; }
        @Override public long newestObservedTick() { return observedTick; }

        /** Exact integer feet-space cell accepted by Action DSL navigate_to_known. */
        public BlockPosition navigationTarget() {
            return new BlockPosition(
                    to.dimension(), floorCoordinate(to.x()), floorCoordinate(to.y()),
                    floorCoordinate(to.z()));
        }

        private static int floorCoordinate(double value) {
            double floored = Math.floor(value);
            if (floored < Integer.MIN_VALUE || floored > Integer.MAX_VALUE) {
                throw new IllegalStateException("traversability coordinate is outside integer range");
            }
            return (int) floored;
        }
    }

    record Hazard(
            HazardType hazardType,
            WorldPosition position,
            HazardSeverity severity,
            WorldPosition observerPosition,
            long observedTick,
            long worldRevision,
            EvidenceProvenance provenance) implements ObservationRecord {
        public Hazard {
            Objects.requireNonNull(hazardType, "hazardType");
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(observerPosition, "observerPosition");
            Objects.requireNonNull(provenance, "provenance");
            ObservationValues.requireSameDimension(position.dimension(), observerPosition.dimension());
            ObservationValues.requireTick(observedTick, "observedTick");
            ObservationValues.requireTick(worldRevision, "worldRevision");
            requireLocalOrContact(provenance);
        }

        @Override public ObservationKind kind() { return ObservationKind.HAZARD; }
        @Override public ResourceId dimension() { return position.dimension(); }
        @Override public long oldestObservedTick() { return observedTick; }
        @Override public long newestObservedTick() { return observedTick; }
    }

    record UnknownBoundary(
            WorldPosition position,
            UnknownBoundaryReason reason,
            WorldPosition eyeOrigin,
            long observedTick,
            long worldRevision) implements ObservationRecord {
        public UnknownBoundary {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(eyeOrigin, "eyeOrigin");
            ObservationValues.requireSameDimension(position.dimension(), eyeOrigin.dimension());
            ObservationValues.requireTick(observedTick, "observedTick");
            ObservationValues.requireTick(worldRevision, "worldRevision");
        }

        @Override public ObservationKind kind() { return ObservationKind.UNKNOWN_BOUNDARY; }
        @Override public ResourceId dimension() { return position.dimension(); }
        @Override public long oldestObservedTick() { return observedTick; }
        @Override public long newestObservedTick() { return observedTick; }
    }

    record SoundClue(
            ResourceId soundEvent,
            SoundCategory category,
            WorldPosition position,
            long firstObservedTick,
            long lastObservedTick,
            int ageTicks,
            int occurrences,
            ResourceId entityHint,
            long worldRevision) implements ObservationRecord {
        public SoundClue {
            Objects.requireNonNull(soundEvent, "soundEvent");
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(position, "position");
            ObservationValues.requireTick(firstObservedTick, "firstObservedTick");
            ObservationValues.requireTick(lastObservedTick, "lastObservedTick");
            ObservationValues.requireTick(worldRevision, "worldRevision");
            if (lastObservedTick < firstObservedTick) {
                throw new IllegalArgumentException("lastObservedTick precedes firstObservedTick");
            }
            ObservationValues.requireRange(ageTicks, 0, 600, "ageTicks");
            if (occurrences < 1) {
                throw new IllegalArgumentException("occurrences must be positive");
            }
        }

        @Override public ObservationKind kind() { return ObservationKind.SOUND_CLUE; }
        @Override public ResourceId dimension() { return position.dimension(); }
        @Override public long oldestObservedTick() { return firstObservedTick; }
        @Override public long newestObservedTick() { return lastObservedTick; }
        public String provenance() { return "client_playback_start"; }
    }

    private static void requireLocalOrContact(EvidenceProvenance provenance) {
        if (provenance != EvidenceProvenance.LOCAL_VOLUME
                && provenance != EvidenceProvenance.CONTACT) {
            throw new IllegalArgumentException("Record requires LOCAL_VOLUME or CONTACT provenance");
        }
    }

    interface WireNamed {
        default String wireName() {
            return ((Enum<?>) this).name().toLowerCase(Locale.ROOT);
        }
    }

    enum Face implements WireNamed { DOWN, UP, NORTH, SOUTH, WEST, EAST }
    enum ShapeClass implements WireNamed { OPAQUE, TRANSPARENT, CUTOUT, PARTIAL, FLUID, UNKNOWN }
    enum EntityHazardClass implements WireNamed { PASSIVE, NEUTRAL, HOSTILE, PLAYER, PROJECTILE, UNKNOWN }
    enum TraversabilityStatus { CONFIRMED, PROBE_ALLOWED, BLOCKED, STALE }
    enum TargetSupport implements WireNamed { CONFIRMED, ABSENT, UNKNOWN }
    enum TransitionClearance implements WireNamed { CONFIRMED, BLOCKED, UNKNOWN }
    enum Fluid implements WireNamed { NONE, WATER, LAVA, OTHER, UNKNOWN }
    enum HazardType implements WireNamed {
        FALL, LAVA, FIRE, WATER, SUFFOCATION, FREEZING, CONTACT_DAMAGE, COLLISION, UNKNOWN
    }
    enum HazardSeverity implements WireNamed { CAUTION, URGENT, UNKNOWN }
    enum UnknownBoundaryReason implements WireNamed {
        UNLOADED, OPAQUE_OCCLUSION, AMBIGUOUS_RENDER, RADIUS_LIMIT, FOG_LIMIT
    }
    enum SoundCategory implements WireNamed {
        MASTER, RECORDS, WEATHER, BLOCKS, HOSTILE, NEUTRAL, PLAYERS, AMBIENT, VOICE
    }
    enum EvidenceProvenance { OMNIDIRECTIONAL_VISUAL, LOCAL_VOLUME, CONTACT }
}
