package dev.aod.mcmcp.mixin.client;

import dev.aod.mcmcp.agent.observation.ClientFogDistanceSignals;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Captures the current renderer policy without reading or changing camera orientation. */
@Mixin(FogRenderer.class)
abstract class FogRendererMixin {
    @Inject(
            method = "setupFog(Lnet/minecraft/client/Camera;ILnet/minecraft/client/DeltaTracker;"
                    + "FLnet/minecraft/client/multiplayer/ClientLevel;)"
                    + "Lnet/minecraft/client/renderer/fog/FogData;",
            at = @At("RETURN"),
            require = 1,
            expect = 1)
    private void mcmcp$captureFogDistance(
            Camera camera,
            int renderDistanceInChunks,
            DeltaTracker deltaTracker,
            float darkenWorldAmount,
            ClientLevel level,
            CallbackInfoReturnable<FogData> callback) {
        var cameraEntity = camera.entity();
        if (cameraEntity != null) {
            ClientFogDistanceSignals.record(level, cameraEntity, callback.getReturnValue());
        }
    }
}
