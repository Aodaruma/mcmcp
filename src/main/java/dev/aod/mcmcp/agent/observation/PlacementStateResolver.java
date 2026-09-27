package dev.aod.mcmcp.agent.observation;

import java.util.Objects;
import java.util.Optional;

/** Resolves a session-local identity learned from delivered surface or own-inventory evidence. */
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
