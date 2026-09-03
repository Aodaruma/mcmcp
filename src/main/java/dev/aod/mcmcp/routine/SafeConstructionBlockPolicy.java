package dev.aod.mcmcp.routine;

import dev.aod.mcmcp.construction.SafeConstructionBlocks;
import dev.aod.mcmcp.observation.BlockPlan;
import dev.aod.mcmcp.observation.BlockPlanStateTransformer;
import dev.aod.mcmcp.observation.BlockStateView;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Closed v1 policy for one-cell construction blocks.
 *
 * <p>The allowlist is intentionally based on canonical vanilla registry ids rather than broad
 * class heuristics.  That keeps falling, fluid, redstone, dynamic, hazardous, multi-cell, and
 * neighbour-sensitive blocks outside the construction surface. The few audited partial shapes
 * are state-limited by {@link SafeConstructionBlocks#allowsConstructionState(BlockState)} and
 * still require an exact adjacent support witness.</p>
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
            if (expected.equals(fingerprint(candidate)) && allowsExpectedState(candidate, false)) {
                return candidate;
            }
        }
        throw new UnsafeConstructionBlockException();
    }

    /** Packet-adjacent defence in depth for the exact state selected by placement. */
    public static boolean allowsLiveState(BlockState state, boolean liveBlockEntityPresent) {
        return allowsExpectedState(state, liveBlockEntityPresent)
                && !(state.getBlock() instanceof DoorBlock);
    }

    /** Exact state accepted immediately beside one normal placement packet. */
    public static boolean allowsPlacementState(
            BlockState state, boolean liveBlockEntityPresent) {
        return allowsExpectedState(state, liveBlockEntityPresent)
                && (!(state.getBlock() instanceof DoorBlock) || supportedDoorPlacement(state));
    }

    public static void requirePlacementState(BlockState state, boolean liveBlockEntityPresent) {
        if (!allowsPlacementState(state, liveBlockEntityPresent)) {
            throw new UnsafeConstructionBlockException();
        }
    }

    /**
     * Matches the state fixed by the placement context while deferring only properties which
     * vanilla recomputes from horizontal neighbours until the closed component is complete.
     */
    public static boolean placementStateMatchesFinalStableProperties(
            BlockStateFingerprint expectedFinal,
            BlockStateFingerprint immediate) {
        Objects.requireNonNull(expectedFinal, "expectedFinal");
        Objects.requireNonNull(immediate, "immediate");
        try {
            requireExpectedState(expectedFinal);
            requireExpectedState(immediate);
        } catch (UnsafeConstructionBlockException rejected) {
            return false;
        }
        if (!expectedFinal.blockId().equals(immediate.blockId())
                || !expectedFinal.properties().keySet().equals(immediate.properties().keySet())) {
            return false;
        }
        Set<String> derived = neighborDerivedProperties(expectedFinal.blockId());
        for (var property : expectedFinal.properties().entrySet()) {
            if (!derived.contains(property.getKey())
                    && !property.getValue().equals(
                            immediate.properties().get(property.getKey()))) {
                return false;
            }
        }
        return !derived.isEmpty() || expectedFinal.equals(immediate);
    }

    private static Set<String> neighborDerivedProperties(String blockId) {
        return switch (blockId) {
            case "minecraft:glass_pane" -> Set.of("north", "east", "south", "west");
            default -> Set.of();
        };
    }

    public static boolean supportedDoorPlacement(BlockState state) {
        return state.is(Blocks.OAK_DOOR)
                && state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)
                        == DoubleBlockHalf.LOWER
                && !state.getValue(BlockStateProperties.OPEN)
                && !state.getValue(BlockStateProperties.POWERED)
                && state.getFluidState().isEmpty();
    }

    private static boolean allowsExpectedState(
            BlockState state, boolean liveBlockEntityPresent) {
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
                && !state.canBeReplaced()
                && SafeConstructionBlocks.allowsConstructionState(state);
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
                && (!(state.getBlock() instanceof DoorBlock) || supportedDoorPlacement(state))
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
