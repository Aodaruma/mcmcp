package dev.aod.mcmcp.mixin.client;

import dev.aod.mcmcp.client.AgentInputState;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Charge a new break attempt at Vanilla's start, excluding cooldown and ongoing break ticks. */
@Mixin(MultiPlayerGameMode.class)
abstract class MultiPlayerGameModeBoundedInputMixin {
    @Inject(method = "startDestroyBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Z",
            at = @At("HEAD"), cancellable = true, require = 1, expect = 1)
    private void mcmcp$boundNewBreak(BlockPos position, Direction face,
            CallbackInfoReturnable<Boolean> callback) {
        var input = AgentInputState.global();
        if (input.attackActive() && !input.beginBoundedInput()) callback.setReturnValue(false);
    }
}
