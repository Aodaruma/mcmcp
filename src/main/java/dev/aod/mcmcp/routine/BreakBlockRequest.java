package dev.aod.mcmcp.routine;

import java.util.Objects;
import java.util.Optional;

public record BreakBlockRequest(
        BlockTarget target,
        BlockStateFingerprint expectedBefore,
        BlockStateFingerprint expectedAfter,
        ActionBounds bounds,
        Optional<BlockAimWitness> plannedAim) implements SemanticActionRequest {
    public static final String KIND = "break_block";

    public BreakBlockRequest {
        validateBlockRequest(target, expectedBefore, expectedAfter, bounds, true);
        plannedAim = Objects.requireNonNull(plannedAim, "plannedAim");
        plannedAim.ifPresent(aim -> requireTargetAim(target, aim));
        if (!"minecraft:air".equals(expectedAfter.blockId())
                || !expectedAfter.properties().isEmpty()) {
            throw new IllegalArgumentException(
                    "Phase 3 break_block only supports minecraft:air as expected_after");
        }
    }

    public BreakBlockRequest(
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

    static void requireTargetAim(BlockTarget target, BlockAimWitness aim) {
        if (!target.equals(aim.block())) {
            throw new IllegalArgumentException("planned aim block must equal the action target");
        }
    }
}
