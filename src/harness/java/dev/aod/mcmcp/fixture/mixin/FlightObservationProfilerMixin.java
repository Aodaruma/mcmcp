package dev.aod.mcmcp.fixture.mixin;

import dev.aod.mcmcp.fixture.FlightRecoveryProfiler;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.function.BiFunction;
import java.util.function.Predicate;

@Mixin(targets = "dev.aod.mcmcp.runtime.FlightObservation", remap = false)
abstract class FlightObservationProfilerMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void created(Minecraft minecraft, CallbackInfo info) { FlightRecoveryProfiler.observe(this); }

    @Inject(method = "visibleLandingSupport", at = @At("HEAD"))
    private static void supportStart(Vec3 eye, BlockPos support, double ground, double radius, double fog,
            Predicate<BlockPos> loaded, BiFunction<ClipContext.Block, Vec3, BlockHitResult> ray,
            CallbackInfoReturnable<Boolean> info) {
        FlightRecoveryProfiler.supportStart(eye, support, ground, radius, fog);
    }

    @WrapOperation(method = "visibleLandingSupport", at = @At(value = "INVOKE",
            target = "Ljava/util/function/BiFunction;apply(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
    private static Object supportRay(BiFunction<?, ?, ?> ray, Object mode, Object end, Operation<Object> original) {
        Object hit = original.call(ray, mode, end);
        FlightRecoveryProfiler.supportRay((ClipContext.Block) mode, (BlockHitResult) hit);
        return hit;
    }

    @Inject(method = "visibleLandingSupport", at = @At("RETURN"))
    private static void supportEnd(Vec3 eye, BlockPos support, double ground, double radius, double fog,
            Predicate<BlockPos> loaded, BiFunction<ClipContext.Block, Vec3, BlockHitResult> ray,
            CallbackInfoReturnable<Boolean> info) {
        FlightRecoveryProfiler.supportEnd(info.getReturnValue());
    }
}
