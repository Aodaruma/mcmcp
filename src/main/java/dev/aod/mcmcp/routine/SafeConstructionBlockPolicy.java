package dev.aod.mcmcp.routine;

import dev.aod.mcmcp.construction.SafeConstructionBlocks;
import dev.aod.mcmcp.observation.BlockPlan;
import dev.aod.mcmcp.observation.BlockPlanStateTransformer;
import dev.aod.mcmcp.observation.BlockStateView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.Objects;

/**
 * Closed v1 policy for one-cell construction blocks.
 *
 * <p>The allowlist is intentionally based on canonical vanilla registry ids rather than broad
 * class heuristics.  That keeps falling, fluid, redstone, dynamic, hazardous, multi-cell, and
 * neighbour-sensitive blocks outside the construction surface. Ladder and wall torch are the two
 * audited exceptions and still require an exact adjacent support witness.</p>
 */
public final class SafeConstructionBlockPolicy {
    public static final String REJECTION_MESSAGE =
            "construction block is outside the closed allowlist";

    private static final BlockPlan.Transform IDENTITY_TRANSFORM =
            new BlockPlan.Transform(0, "none");

    private SafeConstructionBlockPolicy() {
    }

    /** Strictly validates a complete state and its unmodified vanilla BlockItem. */
    public static void requireExpectedStateAndItem(
            BlockStateFingerprint expected, String requiredItemId) {
        BlockState state = requireExpectedState(expected);
        Identifier itemIdentifier = Identifier.tryParse(requiredItemId);
        var registeredItem = itemIdentifier == null
                ? java.util.Optional.<net.minecraft.core.Holder.Reference<net.minecraft.world.item.Item>>empty()
                : BuiltInRegistries.ITEM.get(itemIdentifier);
        if (itemIdentifier == null
                || !requiredItemId.equals(itemIdentifier.toString())
                || !"minecraft".equals(itemIdentifier.getNamespace())
                || registeredItem.isEmpty()
                || !(registeredItem.orElseThrow().value() instanceof BlockItem blockItem)
                || !matchesPlacementItem(state, blockItem, requiredItemId)) {
            throw new UnsafeConstructionBlockException();
        }
    }

    /** Returns the exact registered state, rejecting partial/subset fingerprints. */
    public static BlockState requireExpectedState(BlockStateFingerprint expected) {
        Objects.requireNonNull(expected, "expected");
        try {
            // Reuse the canonical complete-state transform gate.  Identity still validates that
            // every registered property is present and that the values resolve exactly one state.
            BlockStateView normalized = BlockPlanStateTransformer.transformFull(
                    new BlockStateView(expected.blockId(), expected.properties()),
                    IDENTITY_TRANSFORM,
                    "construction.expected_after");
            if (!expected.blockId().equals(normalized.block())
                    || !expected.properties().equals(normalized.properties())) {
                throw new UnsafeConstructionBlockException();
            }
        } catch (RuntimeException rejected) {
            if (rejected instanceof UnsafeConstructionBlockException typed) throw typed;
            throw new UnsafeConstructionBlockException();
        }

        Identifier identifier = Identifier.tryParse(expected.blockId());
        var registered = identifier == null
                ? java.util.Optional.<net.minecraft.core.Holder.Reference<Block>>empty()
                : BuiltInRegistries.BLOCK.get(identifier);
        if (identifier == null
                || !expected.blockId().equals(identifier.toString())
                || !"minecraft".equals(identifier.getNamespace())
                || !SafeConstructionBlocks.allows(identifier.toString())
                || registered.isEmpty()) {
            throw new UnsafeConstructionBlockException();
        }
        Block block = registered.orElseThrow().value();
        for (BlockState candidate : block.getStateDefinition().getPossibleStates()) {
            if (expected.equals(fingerprint(candidate)) && allowsLiveState(candidate, false)) {
                return candidate;
            }
        }
        throw new UnsafeConstructionBlockException();
    }

    /** Packet-adjacent defence in depth for the exact state selected by placement. */
    public static boolean allowsLiveState(BlockState state, boolean liveBlockEntityPresent) {
        Objects.requireNonNull(state, "state");
        Block block = state.getBlock();
        Identifier identifier = BuiltInRegistries.BLOCK.getKey(block);
        var registered = identifier == null
                ? java.util.Optional.<net.minecraft.core.Holder.Reference<Block>>empty()
                : BuiltInRegistries.BLOCK.get(identifier);
        return identifier != null
                && "minecraft".equals(identifier.getNamespace())
                && SafeConstructionBlocks.allows(identifier.toString())
                && registered.isPresent()
                && registered.orElseThrow().value() == block
                && !(block instanceof EntityBlock)
                && !state.hasBlockEntity()
                && !liveBlockEntityPresent
                && state.getFluidState().isEmpty()
                && !state.canBeReplaced()
                && (Block.isShapeFullBlock(state.getCollisionShape(
                        EmptyBlockGetter.INSTANCE, BlockPos.ZERO))
                        || SafeConstructionBlocks.isSurfaceAttachment(identifier.toString()));
    }

    public static void requireLiveState(BlockState state, boolean liveBlockEntityPresent) {
        if (!allowsLiveState(state, liveBlockEntityPresent)) {
            throw new UnsafeConstructionBlockException();
        }
    }

    public static boolean allowsRegisteredBlockId(String blockId) {
        if (!SafeConstructionBlocks.allows(blockId)) return false;
        Identifier identifier = Identifier.tryParse(blockId);
        if (identifier == null || !blockId.equals(identifier.toString())) return false;
        var registered = BuiltInRegistries.BLOCK.get(identifier);
        return registered.isPresent()
                && allowsLiveState(registered.orElseThrow().value().defaultBlockState(), false);
    }

    private static boolean matchesPlacementItem(
            BlockState state, BlockItem item, String requiredItemId) {
        return item.getBlock() == state.getBlock()
                || state.is(Blocks.WALL_TORCH)
                        && "minecraft:torch".equals(requiredItemId)
                        && item instanceof StandingAndWallBlockItem;
    }

    private static BlockStateFingerprint fingerprint(BlockState state) {
        var properties = new java.util.TreeMap<String, String>();
        state.getValues().forEach(value ->
                properties.put(value.property().getName(), value.valueName()));
        return new BlockStateFingerprint(
                BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), properties);
    }

    public static final class UnsafeConstructionBlockException extends IllegalStateException {
        public UnsafeConstructionBlockException() {
            super(REJECTION_MESSAGE);
        }
    }
}
