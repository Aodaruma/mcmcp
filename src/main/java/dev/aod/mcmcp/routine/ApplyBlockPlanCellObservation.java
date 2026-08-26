package dev.aod.mcmcp.routine;

import java.util.Objects;
import java.util.Optional;

/** Same-frame live facts for one required plan cell. Empty state means unknown, not air. */
public record ApplyBlockPlanCellObservation(
        BlockTarget target,
        Optional<BlockStateFingerprint> liveState,
        boolean replaceable,
        boolean safeStandAvailable,
        boolean aimFeasible) {
    public ApplyBlockPlanCellObservation {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(liveState, "liveState");
    }
}
