package dev.aodaruma.craftagent.routine;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;
import java.util.Set;

/** Closed v1 policy for blocks whose normal player-destroy hooks have been audited. */
public final class SafeBreakSourcePolicy {
    public static final String REJECTION_MESSAGE =
            "break source is outside the closed safe allowlist";

    private static final Set<String> ALLOWED_BLOCK_IDS = Set.of(
            "minecraft:cobblestone",
            "minecraft:dirt",
            "minecraft:grass_block",
            "minecraft:obsidian",
            "minecraft:stone");

    private SafeBreakSourcePolicy() {
    }

    /** Admission-time check for a canonical, registered vanilla source id. */
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

    public static void requireRegisteredBlockId(String blockId) {
        if (!allowsRegisteredBlockId(blockId)) {
            throw new IllegalArgumentException(REJECTION_MESSAGE);
        }
    }

    public static void requireLiveState(BlockState state, boolean liveBlockEntityPresent) {
        if (!allowsLiveState(state, liveBlockEntityPresent)) {
            throw new UnsafeBreakSourceException();
        }
    }

    /** Typed packet-boundary rejection so domain routines can preserve precondition semantics. */
    public static final class UnsafeBreakSourceException extends IllegalStateException {
        public UnsafeBreakSourceException() {
            super(REJECTION_MESSAGE);
        }
    }
}
