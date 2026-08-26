package dev.aod.mcmcp.safety;

import dev.aod.mcmcp.client.AgentInputState;
import dev.aod.mcmcp.client.AgentMovementInput;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/** Idempotent neutralization of every physical or agent input path automation may own. */
public final class InputReleaseController {
    public boolean releaseAll(Minecraft minecraft) {
        if (!minecraft.isSameThread()) {
            throw new IllegalStateException("input release must run on the Minecraft client thread");
        }
        boolean released = true;
        var agentInput = AgentInputState.global();
        var player = minecraft.player;
        try {
            if (player != null) {
                agentInput.neutralizeTrackedAgentVelocity(player);
            }
        } catch (RuntimeException | LinkageError failure) {
            released = false;
        } finally {
            agentInput.suppressAll();
        }
        try {
            KeyMapping.releaseAll();
        } catch (RuntimeException | LinkageError failure) {
            released = false;
        }
        var gameMode = minecraft.gameMode;
        if (player != null && player.input instanceof AgentMovementInput movementInput) {
            try {
                movementInput.apply(agentInput.movementSnapshot());
            } catch (RuntimeException | LinkageError failure) {
                released = false;
            }
        }
        if (gameMode != null) {
            try {
                gameMode.stopDestroyBlock();
            } catch (RuntimeException | LinkageError failure) {
                released = false;
            }
        }
        if (player != null) {
            try {
                player.stopUsingItem();
            } catch (RuntimeException | LinkageError failure) {
                released = false;
            }
            if (gameMode != null) {
                try {
                    gameMode.releaseUsingItem(player);
                } catch (RuntimeException | LinkageError failure) {
                    released = false;
                }
            }
        }
        return released;
    }
}
