package dev.aod.mcmcp.mixin.client;

import dev.aod.mcmcp.client.InputIsolationController;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.PreeditEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Exact physical-key entry points; synthetic runtime input does not pass through these methods. */
@Mixin(KeyboardHandler.class)
abstract class KeyboardHandlerMixin {
    @Inject(
            method = "keyPress(JILnet/minecraft/client/input/KeyEvent;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 1,
            expect = 1)
    private void mcmcp$interceptKeyPress(
            long window,
            int action,
            KeyEvent event,
            CallbackInfo callback) {
        if (InputIsolationController.interceptKeyPress(
                Minecraft.getInstance(), action, event)) {
            callback.cancel();
        }
    }

    @Inject(
            method = "charTyped(JLnet/minecraft/client/input/CharacterEvent;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 1,
            expect = 1)
    private void mcmcp$interceptCharacter(
            long window,
            CharacterEvent event,
            CallbackInfo callback) {
        if (InputIsolationController.interceptTextInput()) {
            callback.cancel();
        }
    }

    @Inject(
            method = "preeditCallback(JLnet/minecraft/client/input/PreeditEvent;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 1,
            expect = 1)
    private void mcmcp$interceptPreedit(
            long window,
            PreeditEvent event,
            CallbackInfo callback) {
        if (InputIsolationController.interceptTextInput()) {
            callback.cancel();
        }
    }
}
