package dev.aod.mcmcp.client;

import dev.aod.mcmcp.mixin.client.MinecraftUseItemInvoker;
import net.minecraft.client.Minecraft;

import java.util.Objects;

/** Applies an agent-owned held-use input through Vanilla's normal keybind dispatch path. */
public final class AgentUseInputChannel {
    private final AgentInputState inputState;

    public AgentUseInputChannel(AgentInputState inputState) {
        this.inputState = Objects.requireNonNull(inputState, "inputState");
    }

    public void onClientPostTick(Minecraft minecraft) {
        Objects.requireNonNull(minecraft, "minecraft");
        if (!minecraft.isSameThread()) {
            throw new IllegalStateException("agent use input must run on the client thread");
        }
        if (!inputState.useActive() || minecraft.player == null || minecraft.level == null
                || minecraft.gameMode == null || minecraft.gui.screen() != null
                || minecraft.gui.overlay() != null) {
            return;
        }
        MinecraftUseItemInvoker accessor = (MinecraftUseItemInvoker) minecraft;
        if (accessor.mcmcp$getRightClickDelay() == 0 && !minecraft.player.isUsingItem()) {
            accessor.mcmcp$startUseItem();
        }
    }
}
