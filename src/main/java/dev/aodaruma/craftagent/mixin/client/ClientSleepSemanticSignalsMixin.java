package dev.aodaruma.craftagent.mixin.client;

import dev.aodaruma.craftagent.runtime.SleepSemanticSignals;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records only allowlisted vanilla bed semantics after inbound packet handling. */
@Mixin(ClientPacketListener.class)
public abstract class ClientSleepSemanticSignalsMixin {
    @Inject(method = "handleSystemChat", at = @At("TAIL"), require = 1, expect = 1)
    private void craftagent$sleepSemanticSignal(
            ClientboundSystemChatPacket packet, CallbackInfo callback) {
        var minecraft = Minecraft.getInstance();
        SleepSemanticSignals.global().onSystemChat(minecraft.level, packet.content());
    }

    @Inject(method = "clearLevel", at = @At("HEAD"), require = 1, expect = 1)
    private void craftagent$clearSleepSemanticSession(CallbackInfo callback) {
        var minecraft = Minecraft.getInstance();
        SleepSemanticSignals.global().clearSession(minecraft.level);
    }
}
