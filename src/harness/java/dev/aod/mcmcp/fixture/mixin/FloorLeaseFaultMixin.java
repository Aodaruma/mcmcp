package dev.aod.mcmcp.fixture.mixin;

import dev.aod.mcmcp.fixture.FloorLeaseFaultInjector;
import dev.aod.mcmcp.routine.MinecraftFloorExtensionAttempt;
import dev.aod.mcmcp.runtime.WorldSessionTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = MinecraftFloorExtensionAttempt.class, remap = false)
public abstract class FloorLeaseFaultMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void mcmcp$stallBeforeEntry(WorldSessionTracker.Snapshot session,
            CallbackInfoReturnable<MinecraftFloorExtensionAttempt.Result> callback) {
        FloorLeaseFaultInjector.beforeCheck(this, true);
    }

    // The third nanoTime call measures elapsed work just before the heartbeat.
    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Ljava/lang/System;nanoTime()J", ordinal = 2))
    private void mcmcp$stallBeforeHeartbeat(WorldSessionTracker.Snapshot session,
            CallbackInfoReturnable<MinecraftFloorExtensionAttempt.Result> callback) {
        FloorLeaseFaultInjector.beforeCheck(this, false);
    }
}
