package dev.aod.mcmcp.mixin.client;

import dev.aod.mcmcp.client.AgentInputState;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The physical use key remains released while the separate agent lease owns the hold. */
@Mixin(MultiPlayerGameMode.class)
abstract class MultiPlayerGameModeUseMixin {
    @Inject(method = "releaseUsingItem", at = @At("HEAD"), cancellable = true, require = 1, expect = 1)
    private void mcmcp$retainOwnedUse(Player player, CallbackInfo callback) {
        if (AgentInputState.global().useActive()) callback.cancel();
    }
}
