package dev.aod.mcmcp.routine;

import java.util.Objects;

public record BreakBlockRequest(
        BlockTarget target,
        BlockStateFingerprint expectedBefore,
        BlockStateFingerprint expectedAfter,
        ActionBounds bounds) implements SemanticActionRequest {
    public static final String KIND = "break_block";

    public BreakBlockRequest {
        validateBlockRequest(target, expectedBefore, expectedAfter, bounds, true);
        if (!"minecraft:air".equals(expectedAfter.blockId())
                || !expectedAfter.properties().isEmpty()) {
            throw new IllegalArgumentException(
                    "Phase 3 break_block only supports minecraft:air as expected_after");
        }
    }

    @Override
    public String kind() {
        return KIND;
    }

    static void validateBlockRequest(
            BlockTarget target,
            BlockStateFingerprint expectedBefore,
            BlockStateFingerprint expectedAfter,
            ActionBounds bounds,
            boolean allowBreak) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(expectedBefore, "expectedBefore");
        Objects.requireNonNull(expectedAfter, "expectedAfter");
        Objects.requireNonNull(bounds, "bounds");
        if (!bounds.contains(target)
                || bounds.maxTravelBlocks() != 0
                || bounds.allowBreak() != allowBreak
                || bounds.maxDurationSeconds() > 30) {
            throw new IllegalArgumentException("block action target/bounds are inconsistent");
        }
    }
}
