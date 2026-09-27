package dev.aod.mcmcp.safety;

import dev.aod.mcmcp.client.AgentInputState;
import dev.aod.mcmcp.client.AgentMovementInput;
import net.minecraft.client.Minecraft;

/** Releases Action input paths. An explicit resting ladder hold has a separate lifecycle. */
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
            } else {
                agentInput.discardTrackedAgentVelocity();
            }
        } catch (RuntimeException | LinkageError failure) {
            released = false;
        } finally {
            agentInput.suppressAllRetainingTrackedVelocity();
        }
        var gameMode = minecraft.gameMode;
        if (player != null && player.input instanceof AgentMovementInput movementInput) {
            try {
                movementInput.apply(agentInput.movementSnapshot(),
                        dev.aod.mcmcp.client.LadderHoldState.global().active(player, player.level()));
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

    /** Verifies Action channels and tracked velocity; resting ladder hold is reported separately. */
    public boolean inputOwnerNone(Minecraft minecraft) {
        if (!minecraft.isSameThread()) {
            throw new IllegalStateException("input ownership must be checked on the Minecraft client thread");
        }
        return AgentInputState.global().inputOwnerNone();
    }
}
