package dev.aod.mcmcp.fixture.mixin;

import dev.aod.mcmcp.fixture.BuildPhaseProfiler;
import dev.aod.mcmcp.fixture.FlightRecoveryProfiler;
import dev.aod.mcmcp.runtime.McmcpRuntime;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = McmcpRuntime.class, remap = false)
abstract class BuildPhaseProfilerMixin {
    @Inject(method = "onPreTick", at = @At("HEAD"))
    private void before(Minecraft minecraft, CallbackInfo info) { BuildPhaseProfiler.before(this); FlightRecoveryProfiler.before(this); }
    @Inject(method = "onPreTick", at = @At("RETURN"))
    private void after(Minecraft minecraft, CallbackInfo info) { BuildPhaseProfiler.after(); FlightRecoveryProfiler.after(); }
}
