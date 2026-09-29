package dev.aod.mcmcp.mixin.client;

import dev.aod.mcmcp.client.AgentInputState;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla restores the pre-travel vertical velocity with flight drag after ordinary air travel. */
@Mixin(Player.class)
abstract class PlayerFlightMovementMixin {
    @Unique private double mcmcp$flightContributionY;
    @Unique private boolean mcmcp$trackedFlight;

    @Inject(method = "travel(Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"), require = 1, expect = 1)
    private void mcmcp$beforeFlight(Vec3 input, CallbackInfo ci) {
        mcmcp$trackedFlight = (Object) this instanceof LocalPlayer player
                && player.getAbilities().flying && AgentInputState.global().goalMovementOutputActive();
        if (mcmcp$trackedFlight) {
            var player = (LocalPlayer) (Object) this;
            mcmcp$flightContributionY = AgentInputState.global().agentMoveContribution(player, player.level()).y;
        }
    }

    @Inject(method = "travel(Lnet/minecraft/world/phys/Vec3;)V", at = @At("RETURN"), require = 1)
    private void mcmcp$afterFlight(Vec3 input, CallbackInfo ci) {
        if (!mcmcp$trackedFlight) return;
        var player = (LocalPlayer) (Object) this;
        if (!AgentInputState.global().goalMovementOutputActive()) {
            // A guard may have removed Agent motion during move(), but Player.travel then
            // restores its saved pre-move Y. Remove that reinstated part on rejection/expiry.
            player.setDeltaMovement(player.getDeltaMovement().add(0, -mcmcp$flightContributionY * 0.6D, 0));
            return;
        }
        var contribution = AgentInputState.global().agentMoveContribution(player, player.level());
        AgentInputState.global().replaceAgentMoveContribution(
                new Vec3(contribution.x, mcmcp$flightContributionY * 0.6D, contribution.z));
    }
}
