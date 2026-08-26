package dev.aodaruma.craftagent.safety;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/** Idempotent release of every vanilla input path the automation may own. */
public final class InputReleaseController {
    public boolean releaseAll(Minecraft minecraft) {
        if (!minecraft.isSameThread()) {
            throw new IllegalStateException("input release must run on the Minecraft client thread");
        }
        boolean released = true;
        try {
            KeyMapping.releaseAll();
        } catch (RuntimeException | LinkageError failure) {
            released = false;
        }
        var player = minecraft.player;
        var gameMode = minecraft.gameMode;
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
