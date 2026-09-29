package dev.aod.mcmcp.routine;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;

/** v2 attack source rule: any explicitly targeted, registered vanilla non-air block. */
public final class V2BreakSourcePolicy {
    private V2BreakSourcePolicy() { }

    public static boolean allowsLiveState(BlockState state) {
        Objects.requireNonNull(state, "state");
        if (state.isAir()) return false;
        var block = state.getBlock();
        var id = BuiltInRegistries.BLOCK.getKey(block);
        return id != null && "minecraft".equals(id.getNamespace())
                && BuiltInRegistries.BLOCK.get(id)
                        .map(holder -> holder.value() == block).orElse(false);
    }

    public static void requireLiveState(BlockState state) {
        if (!allowsLiveState(state)) {
            throw new IllegalArgumentException(
                    "v2 break target must be a registered vanilla non-air block");
        }
    }
}
