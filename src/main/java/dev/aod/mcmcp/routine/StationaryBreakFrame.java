package dev.aod.mcmcp.routine;

import java.util.Objects;
import java.util.Optional;

/** Fresh current-tick observation consumed by the deterministic stationary_break FSM. */
public record StationaryBreakFrame(
        long clientTick,
        long observationRevision,
        Optional<BlockStateFingerprint> liveTargetState,
        int goalItemCount,
        boolean inventoryServerSynchronized,
        boolean worldReady,
        boolean clientFocused,
        boolean playerAlive,
        boolean healthSafe,
        boolean visibleThreatClear,
        boolean targetInReach,
        boolean crosshairOnTarget) {
    public StationaryBreakFrame {
        if (clientTick < 0 || observationRevision < 0) {
            throw new IllegalArgumentException("frame clocks must be non-negative");
        }
        liveTargetState = Objects.requireNonNull(liveTargetState, "liveTargetState");
        if (goalItemCount < 0) {
            throw new IllegalArgumentException("goal item count must be non-negative");
        }
    }

    public boolean goalConfirmed(StationaryBreakGoal goal) {
        return inventoryServerSynchronized && goalItemCount >= goal.minimumInventoryCount();
    }
}
