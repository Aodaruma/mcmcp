package dev.aod.mcmcp.mixin.client;

import dev.aod.mcmcp.client.AgentInputState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Keep an Agent-owned use alive without altering physical keys or enabling a new use. */
@Mixin(Minecraft.class)
abstract class MinecraftBoundedUseMixin {
    @Redirect(method = "handleKeybinds", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;releaseUsingItem(Lnet/minecraft/world/entity/player/Player;)V"),
            require = 1, expect = 1)
    private void mcmcp$preserveOngoingUse(MultiPlayerGameMode gameMode, Player player) {
        if (!AgentInputState.global().maintainsBoundedUse()) gameMode.releaseUsingItem(player);
    }
}
