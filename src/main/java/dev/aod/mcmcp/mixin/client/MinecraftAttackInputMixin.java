package dev.aod.mcmcp.mixin.client;

import dev.aod.mcmcp.client.AgentInputState;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents a released physical attack key from stopping the separate agent break channel. */
@Mixin(Minecraft.class)
abstract class MinecraftAttackInputMixin {
    @Inject(
            method = "continueAttack(Z)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 1,
            expect = 1)
    private void mcmcp$keepAgentAttackSeparate(boolean physicalAttack, CallbackInfo callback) {
        if (AgentInputState.global().attackActive()) {
            callback.cancel();
        }
    }
}
