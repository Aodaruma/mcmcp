package dev.aod.mcmcp.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.aod.mcmcp.agent.safety.AgentMovementTrace;
import dev.aod.mcmcp.client.AgentInputState;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Promotes resolved movement to CONTACT only after a complete client-player tick. */
@Mixin(LocalPlayer.class)
abstract class LocalPlayerMovementTickMixin {
    @WrapOperation(method = "aiStep()V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;goDownInWater()V"),
            require = 1, expect = 1)
    private void mcmcp$trackAgentWaterSink(LocalPlayer player, Operation<Void> original) {
        var before = player.getDeltaMovement();
        original.call(player);
        if (AgentInputState.global().goalMovementOutputActive()) {
            AgentInputState.global().addAgentMoveContribution(player.getDeltaMovement().subtract(before));
        }
    }

    @Inject(method = "tick()V", at = @At("HEAD"), require = 1, expect = 1)
    private void mcmcp$beginAgentMovementTick(CallbackInfo callback) {
        var player = (LocalPlayer) (Object) this;
        AgentInputState.global().beginPlayerMovementTick(player, player.level());
        AgentMovementTrace.global().beginTick(player);
    }

    @Inject(method = "tick()V", at = @At("RETURN"), require = 1, expect = 1)
    private void mcmcp$endAgentMovementTick(CallbackInfo callback) {
        var player = (LocalPlayer) (Object) this;
        try {
            AgentMovementTrace.global().endTick(player);
        } finally {
            AgentInputState.global().endPlayerMovementTick(player, player.level());
        }
    }
}
