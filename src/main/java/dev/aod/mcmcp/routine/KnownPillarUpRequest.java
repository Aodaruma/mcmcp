package dev.aod.mcmcp.routine;

import dev.aod.mcmcp.construction.SafeConstructionBlocks;
import dev.aod.mcmcp.observation.BlockPlan;
import dev.aod.mcmcp.observation.BlockPlanStateTransformer;
import dev.aod.mcmcp.observation.BlockStateView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;

/** Closed request for exactly one full-block pillar placement. */
public record KnownPillarUpRequest(
        BlockTarget support,
        BlockStateFingerprint expectedSupport,
        BlockStateFingerprint sourceState,
        String item) {
    private static final BlockPlan.Transform IDENTITY = new BlockPlan.Transform(0, "none");

    public KnownPillarUpRequest {
        Objects.requireNonNull(support, "support");
        Objects.requireNonNull(expectedSupport, "expectedSupport");
        Objects.requireNonNull(sourceState, "sourceState");
        Objects.requireNonNull(item, "item");
        Math.addExact(support.y(), 3);
        requireComplete(expectedSupport, "pillar.expected_support");
        if (!allowsSupportBlockId(expectedSupport.blockId())) {
            throw new IllegalArgumentException(SafePlacementSupportPolicy.REJECTION_MESSAGE);
        }
        requireSourceStateAndItem(sourceState, item);
    }

    static boolean allowsSupportBlockId(String blockId) {
        return SafePlacementSupportPolicy.allowsRegisteredBlockId(blockId)
                || SafeConstructionBlockPolicy.allowsRegisteredBlockId(blockId);
    }

    static void requireLiveSupport(BlockState state, boolean liveBlockEntityPresent) {
        if (!SafePlacementSupportPolicy.allowsLiveState(state, liveBlockEntityPresent)
                && !SafeConstructionBlockPolicy.allowsLiveState(
                        state, liveBlockEntityPresent)) {
            throw new SafePlacementSupportPolicy.UnsafePlacementSupportException();
        }
    }

    /** Pillaring accepts only one ordinary full collision block from the construction policy. */
    public static void requireSourceStateAndItem(
            BlockStateFingerprint sourceState, String item) {
        SafeConstructionBlockPolicy.requireExpectedStateAndItem(sourceState, item);
        BlockState resolved = SafeConstructionBlockPolicy.requireExpectedState(sourceState);
        if (SafeConstructionBlocks.placementCellCount(sourceState.blockId()) != 1
                || !Block.isShapeFullBlock(resolved.getCollisionShape(
                        EmptyBlockGetter.INSTANCE, BlockPos.ZERO))) {
            throw new IllegalArgumentException(
                    "pillar placement requires one ordinary full block");
        }
    }

    private static void requireComplete(BlockStateFingerprint state, String path) {
        BlockStateView normalized = BlockPlanStateTransformer.transformFull(
                new BlockStateView(state.blockId(), state.properties()), IDENTITY, path);
        if (!state.blockId().equals(normalized.block())
                || !state.properties().equals(normalized.properties())) {
            throw new IllegalArgumentException(path + " must be one complete state");
        }
    }
}
