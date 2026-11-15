package dev.aod.mcmcp.client;

import dev.aod.mcmcp.mixin.client.MinecraftPickInvoker;
import net.minecraft.client.Minecraft;

import java.util.Objects;

/** Emits one Vanilla pick action for each new agent-owned middle-click hold. */
public final class AgentPickInputChannel {
    private final AgentInputState inputState;
    private boolean wasActive;

    public AgentPickInputChannel(AgentInputState inputState) {
        this.inputState = Objects.requireNonNull(inputState, "inputState");
    }

    public void onClientPostTick(Minecraft minecraft) {
        Objects.requireNonNull(minecraft, "minecraft");
        if (!minecraft.isSameThread()) {
            throw new IllegalStateException("agent pick input must run on the client thread");
        }
        boolean active = inputState.pickActive();
        boolean firstTick = active && !wasActive;
        wasActive = active;
        if (!firstTick || minecraft.player == null || minecraft.level == null
                || minecraft.gameMode == null
                || !AgentScreenPolicy.allowsWorldInput(minecraft.gui.screen())
                || minecraft.gui.overlay() != null) return;
        ((MinecraftPickInvoker) minecraft).mcmcp$pickBlockOrEntity();
    }
}
