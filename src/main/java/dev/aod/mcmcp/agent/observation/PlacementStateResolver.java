package dev.aod.mcmcp.agent.observation;

import java.util.Objects;
import java.util.Optional;

/** Resolves an opaque, session-local identity learned from a delivered visible surface. */
@FunctionalInterface
public interface PlacementStateResolver {
    Optional<PlacementState> resolve(String placementStateRef);

    static PlacementStateResolver none() {
        return ignored -> Optional.empty();
    }

    record PlacementState(
            ObservationRecord.BlockStateView state,
            ObservationValues.ResourceId placementItem) {
        public PlacementState {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(placementItem, "placementItem");
        }
    }
}
