package dev.aod.mcmcp.routine;

import java.util.Objects;
import java.util.Optional;

public record InteractBlockRequest(
        BlockTarget target,
        BlockStateFingerprint expectedBefore,
        BlockStateFingerprint expectedAfter,
        ActionBounds bounds,
        Optional<BlockAimWitness> plannedAim) implements SemanticActionRequest {
    public static final String KIND = "interact_block";

    public InteractBlockRequest {
        BreakBlockRequest.validateBlockRequest(
                target, expectedBefore, expectedAfter, bounds, false);
        plannedAim = Objects.requireNonNull(plannedAim, "plannedAim");
        plannedAim.ifPresent(aim -> BreakBlockRequest.requireTargetAim(target, aim));
        if (!expectedBefore.blockId().equals(expectedAfter.blockId())
                || expectedAfter.properties().isEmpty()) {
            throw new IllegalArgumentException(
                    "interact_block requires a same-block property transition");
        }
    }

    public InteractBlockRequest(
            BlockTarget target,
            BlockStateFingerprint expectedBefore,
            BlockStateFingerprint expectedAfter,
            ActionBounds bounds) {
        this(target, expectedBefore, expectedAfter, bounds, Optional.empty());
    }

    @Override
    public String kind() {
        return KIND;
    }
}
