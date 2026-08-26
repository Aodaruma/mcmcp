package dev.aod.mcmcp.mixin.client;

import dev.aod.mcmcp.runtime.ClientPredictionSignals;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Required hooks for server state and sequence acknowledgement evidence. */
@Mixin(ClientLevel.class)
public abstract class ClientLevelPredictionMixin {
    @Inject(method = "<init>", at = @At("TAIL"), require = 1, expect = 1)
    private void mcmcp$bindPredictionBridge(CallbackInfo callback) {
        String minecraftVersion;
        try {
            minecraftVersion = SharedConstants.getCurrentVersion().name();
        } catch (RuntimeException | LinkageError failure) {
            minecraftVersion = null;
        }
        ClientPredictionSignals.global().onBridgeReady(
                (ClientLevel) (Object) this, minecraftVersion);
    }

    @Inject(
            method = "setServerVerifiedBlockState(Lnet/minecraft/core/BlockPos;"
                    + "Lnet/minecraft/world/level/block/state/BlockState;I)V",
            at = @At("TAIL"),
            require = 1,
            expect = 1)
    private void mcmcp$recordServerVerifiedState(
            BlockPos position,
            BlockState state,
            int flags,
            CallbackInfo callback) {
        ClientPredictionSignals.global().onServerVerifiedBlockState(
                (ClientLevel) (Object) this, position, state);
    }

    @Inject(
            method = "handleBlockChangedAck(I)V",
            at = @At("TAIL"),
            require = 1,
            expect = 1)
    private void mcmcp$recordBlockChangedAck(int sequence, CallbackInfo callback) {
        ClientPredictionSignals.global().onBlockChangedAck(
                (ClientLevel) (Object) this, sequence);
    }

    @Inject(
            method = "disconnect(Lnet/minecraft/network/chat/Component;)V",
            at = @At("HEAD"),
            require = 1,
            expect = 1)
    private void mcmcp$closePredictionBridge(Component reason, CallbackInfo callback) {
        ClientPredictionSignals.global().closeLevel((ClientLevel) (Object) this);
    }
}
