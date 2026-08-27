package dev.aod.mcmcp.routine;

import java.util.Objects;
import java.util.Optional;

/** One exact, audited normal-use transition on the targeted block. */
public record UseItemOnBlockRequest(
        BlockTarget target,
        BlockStateFingerprint expectedBefore,
        String item,
        BlockStateFingerprint expectedAfter,
        ActionBounds bounds,
        Optional<BlockAimWitness> plannedAim) implements SemanticActionRequest {
    public static final String KIND = "use_item_on_block";

    public UseItemOnBlockRequest {
        BreakBlockRequest.validateBlockRequest(
                target, expectedBefore, expectedAfter, bounds, false);
        item = PlaceBlockRequest.requireRegistryId(item, "item");
        plannedAim = Objects.requireNonNull(plannedAim, "plannedAim");
        plannedAim.ifPresent(aim -> BreakBlockRequest.requireTargetAim(target, aim));
        if (expectedBefore.equals(expectedAfter)) {
            throw new IllegalArgumentException("use_item_on_block requires a state transition");
        }
    }

    public UseItemOnBlockRequest(
            BlockTarget target,
            BlockStateFingerprint expectedBefore,
            String item,
            BlockStateFingerprint expectedAfter,
            ActionBounds bounds) {
        this(target, expectedBefore, item, expectedAfter, bounds, Optional.empty());
    }

    @Override
    public String kind() {
        return KIND;
    }
}
