package dev.aod.mcmcp.mixin.client;

import dev.aod.mcmcp.runtime.ClientChatSignals;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatComponent.class)
abstract class ChatComponentMixin {
    @Inject(method = "addMessageToQueue", at = @At("TAIL"), require = 1, expect = 1)
    private void mcmcp$accepted(GuiMessage message, CallbackInfo ci) {
        ClientChatSignals.added(message);
    }

    @Inject(method = "deleteMessage", at = @At("HEAD"), require = 1, expect = 1)
    private void mcmcp$deleted(MessageSignature signature, CallbackInfo ci) {
        ClientChatSignals.deleted(signature);
    }

    @Inject(method = "clearMessages", at = @At("TAIL"), require = 1, expect = 1)
    private void mcmcp$cleared(boolean clearSentHistory, CallbackInfo ci) {
        ClientChatSignals.cleared();
    }
}
