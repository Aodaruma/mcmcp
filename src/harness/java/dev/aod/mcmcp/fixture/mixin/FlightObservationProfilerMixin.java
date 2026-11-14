package dev.aod.mcmcp.fixture.mixin;

import dev.aod.mcmcp.fixture.FlightRecoveryProfiler;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "dev.aod.mcmcp.runtime.FlightObservation", remap = false)
abstract class FlightObservationProfilerMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void created(Minecraft minecraft, CallbackInfo info) { FlightRecoveryProfiler.observe(this); }
}
