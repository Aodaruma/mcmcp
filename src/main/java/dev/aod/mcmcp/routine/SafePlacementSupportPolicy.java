package dev.aod.mcmcp.routine;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Closed v1 policy for support blocks whose normal use path is intentionally inert. */
public final class SafePlacementSupportPolicy {
    public static final String REJECTION_MESSAGE =
            "placement support is outside the closed inert allowlist";

    private static final Set<String> ALLOWED_BLOCK_IDS = Set.of(
            "minecraft:cobblestone",
            "minecraft:dirt",
            "minecraft:glass",
            "minecraft:grass_block",
            "minecraft:obsidian",
            "minecraft:smooth_stone",
            "minecraft:stone");

    private SafePlacementSupportPolicy() {
    }

    /** Admission-time check for a canonical, registered vanilla support id. */
    public static boolean allowsRegisteredBlockId(String blockId) {
        if (blockId == null || !ALLOWED_BLOCK_IDS.contains(blockId)) {
            return false;
        }
        Identifier identifier = Identifier.tryParse(blockId);
        if (identifier == null
                || !blockId.equals(identifier.toString())
                || !"minecraft".equals(identifier.getNamespace())) {
            return false;
        }
        var registered = BuiltInRegistries.BLOCK.get(identifier);
        return registered.isPresent()
                && allowsLiveState(registered.orElseThrow().value().defaultBlockState(), false);
    }

    /** Packet-adjacent check, including state-external block-entity presence. */
    public static boolean allowsLiveState(BlockState state, boolean liveBlockEntityPresent) {
        Objects.requireNonNull(state, "state");
        var block = state.getBlock();
        Identifier identifier = BuiltInRegistries.BLOCK.getKey(block);
        if (identifier == null
                || !"minecraft".equals(identifier.getNamespace())
                || !ALLOWED_BLOCK_IDS.contains(identifier.toString())) {
            return false;
        }
        var registered = BuiltInRegistries.BLOCK.get(identifier);
        return registered.isPresent()
                && registered.orElseThrow().value() == block
                && !(block instanceof EntityBlock)
                && !state.hasBlockEntity()
                && !liveBlockEntityPresent
                && state.getFluidState().isEmpty();
    }

    public static void requireLiveState(BlockState state, boolean liveBlockEntityPresent) {
        if (!allowsLiveState(state, liveBlockEntityPresent)) {
            throw new UnsafePlacementSupportException();
        }
    }

    /**
     * Performs the normal-use dispatch only after the packet-adjacent support check succeeds.
     * The callback is deliberately not evaluated for an unsafe support.
     */
    public static <T> T dispatchUseIfAllowed(
            BlockState state, boolean liveBlockEntityPresent, Supplier<T> dispatch) {
        Objects.requireNonNull(dispatch, "dispatch");
        requireLiveState(state, liveBlockEntityPresent);
        return dispatch.get();
    }

    /** Typed rejection so routines can preserve precondition semantics. */
    public static final class UnsafePlacementSupportException extends IllegalStateException {
        public UnsafePlacementSupportException() {
            super(REJECTION_MESSAGE);
        }
    }
}
