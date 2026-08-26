package dev.aod.mcmcp.mixin.client;

import dev.aod.mcmcp.client.InputIsolationController;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Blocks only the physical mouse-to-camera conversion; cursor movement remains vanilla. */
@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
    @Inject(
            method = "turnPlayer(D)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 1,
            expect = 1)
    private void mcmcp$interceptPhysicalTurn(double elapsedTime, CallbackInfo callback) {
        if (InputIsolationController.interceptMouseTurn()) {
            callback.cancel();
        }
    }
}
