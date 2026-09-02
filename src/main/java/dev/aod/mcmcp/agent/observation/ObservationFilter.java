package dev.aod.mcmcp.agent.observation;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static dev.aod.mcmcp.agent.observation.ObservationValues.BlockPosition;
import static dev.aod.mcmcp.agent.observation.ObservationValues.ResourceId;
import static dev.aod.mcmcp.agent.observation.ObservationValues.WorldPosition;
import static dev.aod.mcmcp.agent.observation.ObservationRecord.Face;

/**
 * Optional delivery-only projection for an already policy-filtered observation frame.
 *
 * <p>The complete internal frame remains available to safety planning. This value can only
 * remove records from an MCP response; it cannot expand the observed area or authorize evidence
 * that was not delivered.</p>
 */
public record ObservationFilter(
        Set<ResourceId> blockIds,
        Set<ResourceId> entityTypes,
        Set<ResourceId> displayedItems,
        Optional<Boolean> cropMature,
        Optional<PositionBounds> positionBounds,
        Set<Face> faces) {

    public static final ObservationFilter NONE = new ObservationFilter(
            Set.of(), Set.of(), Set.of(), Optional.empty(), Optional.empty(), Set.of());

    public ObservationFilter {
        blockIds = Set.copyOf(Objects.requireNonNull(blockIds, "blockIds"));
        entityTypes = Set.copyOf(Objects.requireNonNull(entityTypes, "entityTypes"));
        displayedItems = Set.copyOf(Objects.requireNonNull(displayedItems, "displayedItems"));
        cropMature = Objects.requireNonNull(cropMature, "cropMature");
        positionBounds = Objects.requireNonNull(positionBounds, "positionBounds");
        faces = Set.copyOf(Objects.requireNonNull(faces, "faces"));
    }

    public ObservationFilter(
            Set<ResourceId> blockIds,
            Set<ResourceId> entityTypes,
            Set<ResourceId> displayedItems,
            Optional<Boolean> cropMature,
            Optional<PositionBounds> positionBounds) {
        this(blockIds, entityTypes, displayedItems, cropMature, positionBounds, Set.of());
    }

    public ObservationFilter(
            Set<ResourceId> blockIds,
            Set<ResourceId> entityTypes,
            Set<ResourceId> displayedItems,
            Optional<Boolean> cropMature) {
        this(blockIds, entityTypes, displayedItems, cropMature, Optional.empty());
    }

    public boolean matches(ObservationRecord record) {
        Objects.requireNonNull(record, "record");
        if (positionBounds.isPresent() && !positionBounds.orElseThrow().contains(record)) {
            return false;
        }
        if (record instanceof ObservationRecord.VisibleSurface surface) {
            return (blockIds.isEmpty() || blockIds.contains(surface.block()))
                    && (faces.isEmpty() || faces.contains(surface.face()))
                    && (cropMature.isEmpty()
                            || Objects.equals(cropMature.get(), surface.cropMature()));
        }
        if (record instanceof ObservationRecord.VisibleEntity entity) {
            return (entityTypes.isEmpty() || entityTypes.contains(entity.entityType()))
                    && (displayedItems.isEmpty()
                            || entity.displayedItem() != null
                                    && displayedItems.contains(entity.displayedItem()));
        }
        return true;
    }

    /** Inclusive block-cell bounds applied only to records in an already visible frame. */
    public record PositionBounds(
            ResourceId dimension,
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ) {

        public PositionBounds {
            Objects.requireNonNull(dimension, "dimension");
            // Reuse the normative coordinate bounds without retaining redundant endpoints.
            new BlockPosition(dimension, minX, minY, minZ);
            new BlockPosition(dimension, maxX, maxY, maxZ);
            if (minX > maxX || minY > maxY || minZ > maxZ) {
                throw new IllegalArgumentException(
                        "position bounds minimum must not exceed maximum");
            }
        }

        boolean contains(ObservationRecord record) {
            BlockPosition anchor = anchor(record);
            return dimension.equals(anchor.dimension())
                    && anchor.x() >= minX && anchor.x() <= maxX
                    && anchor.y() >= minY && anchor.y() <= maxY
                    && anchor.z() >= minZ && anchor.z() <= maxZ;
        }

        private static BlockPosition anchor(ObservationRecord record) {
            return switch (record) {
                case ObservationRecord.VisibleSurface surface -> surface.position();
                case ObservationRecord.VisibleEntity entity -> blockPosition(entity.position());
                case ObservationRecord.Traversability edge -> edge.navigationTarget();
                case ObservationRecord.Hazard hazard -> blockPosition(hazard.position());
                case ObservationRecord.UnknownBoundary boundary -> blockPosition(boundary.position());
                case ObservationRecord.SoundClue sound -> blockPosition(sound.position());
            };
        }

        private static BlockPosition blockPosition(WorldPosition position) {
            return new BlockPosition(
                    position.dimension(),
                    floorCoordinate(position.x()),
                    floorCoordinate(position.y()),
                    floorCoordinate(position.z()));
        }

        private static int floorCoordinate(double value) {
            return Math.toIntExact((long) Math.floor(value));
        }
    }
}
