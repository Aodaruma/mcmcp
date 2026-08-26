package dev.aod.mcmcp.routine;

import java.util.Objects;

/** Target-state goal: a server-synchronized inventory contains at least this item count. */
public record StationaryBreakGoal(String itemId, int minimumInventoryCount) {
    public StationaryBreakGoal {
        Objects.requireNonNull(itemId, "itemId");
        if (!itemId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("invalid item registry id");
        }
        if (minimumInventoryCount < 1 || minimumInventoryCount > 2_304) {
            throw new IllegalArgumentException("minimum inventory count must be in 1..2304");
        }
    }
}
