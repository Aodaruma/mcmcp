package dev.aod.mcmcp.safety;

import dev.aod.mcmcp.client.AgentInputState;
import dev.aod.mcmcp.client.AgentMovementInput;
import net.minecraft.client.Minecraft;

/** Idempotent neutralization of every Agent-owned input path. */
public final class InputReleaseController {
    public boolean releaseAll(Minecraft minecraft) {
        if (!minecraft.isSameThread()) {
            throw new IllegalStateException("input release must run on the Minecraft client thread");
        }
        boolean released = true;
        var agentInput = AgentInputState.global();
        var player = minecraft.player;
        // A generic RELEASE_USE_ITEM fires a drawn bow; cancel its owned use via ordinary slot changes first.
        if (player != null && agentInput.inputOwnershipSnapshot().useOwned()
                && player.isUsingItem() && player.getUseItem().is(net.minecraft.world.item.Items.BOW)
                && minecraft.getConnection() != null) {
            int slot = player.getInventory().getSelectedSlot();
            minecraft.getConnection().send(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket((slot + 1) % 9));
            minecraft.getConnection().send(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(slot));
        }
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

    /** Verifies that no Agent channel or tracked Agent velocity still has an owner. */
    public boolean inputOwnerNone(Minecraft minecraft) {
        if (!minecraft.isSameThread()) {
            throw new IllegalStateException("input ownership must be checked on the Minecraft client thread");
        }
        return AgentInputState.global().inputOwnerNone();
    }
}
