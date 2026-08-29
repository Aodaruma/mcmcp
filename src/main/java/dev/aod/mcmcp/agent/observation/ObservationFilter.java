package dev.aod.mcmcp.agent.observation;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static dev.aod.mcmcp.agent.observation.ObservationValues.ResourceId;

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
        Optional<Boolean> cropMature) {

    public static final ObservationFilter NONE = new ObservationFilter(
            Set.of(), Set.of(), Set.of(), Optional.empty());

    public ObservationFilter {
        blockIds = Set.copyOf(Objects.requireNonNull(blockIds, "blockIds"));
        entityTypes = Set.copyOf(Objects.requireNonNull(entityTypes, "entityTypes"));
        displayedItems = Set.copyOf(Objects.requireNonNull(displayedItems, "displayedItems"));
        cropMature = Objects.requireNonNull(cropMature, "cropMature");
    }

    public boolean matches(ObservationRecord record) {
        Objects.requireNonNull(record, "record");
        if (record instanceof ObservationRecord.VisibleSurface surface) {
            return (blockIds.isEmpty() || blockIds.contains(surface.block()))
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
}
