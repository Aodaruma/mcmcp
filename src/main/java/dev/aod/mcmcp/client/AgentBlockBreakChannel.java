package dev.aod.mcmcp.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Objects;

/** Applies agent block breaking after vanilla has finished processing physical attack input. */
public final class AgentBlockBreakChannel {
    private final AgentInputState inputState;

    public AgentBlockBreakChannel(AgentInputState inputState) {
        this.inputState = Objects.requireNonNull(inputState, "inputState");
    }

    public void onClientPostTick(Minecraft minecraft) {
        Objects.requireNonNull(minecraft, "minecraft");
        if (!minecraft.isSameThread()) {
            throw new IllegalStateException("agent block breaking must run on the client thread");
        }
        if (!inputState.attackActive()) {
            return;
        }
        var gameMode = minecraft.gameMode;
        var player = minecraft.player;
        var level = minecraft.level;
        if (gameMode == null || player == null || level == null
                || !(minecraft.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK) {
            if (gameMode != null) {
                gameMode.stopDestroyBlock();
            }
            return;
        }
        if (gameMode.continueDestroyBlock(hit.getBlockPos(), hit.getDirection())) {
            level.addBreakingBlockEffect(hit.getBlockPos(), hit.getDirection());
            player.swing(InteractionHand.MAIN_HAND);
        }
    }
}
