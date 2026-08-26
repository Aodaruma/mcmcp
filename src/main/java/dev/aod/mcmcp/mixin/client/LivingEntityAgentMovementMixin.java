package dev.aod.mcmcp.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.aod.mcmcp.client.AgentInputState;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Tracks only the acceleration contributed by the current Agent input cycle. */
@Mixin(LivingEntity.class)
abstract class LivingEntityAgentMovementMixin {
    @Unique
    private float mcmcp$travelHorizontalFriction = 1.0F;
    @Unique
    private float mcmcp$travelVerticalInputScale = 1.0F;

    @Shadow
    protected abstract float getAirDrag();

    @WrapOperation(
            method = "travelInAir(Lnet/minecraft/world/phys/Vec3;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;handleRelativeFrictionAndCalculateMovement(Lnet/minecraft/world/phys/Vec3;F)Lnet/minecraft/world/phys/Vec3;"),
            require = 1,
            expect = 1)
    private Vec3 mcmcp$captureAgentAirFactors(
            LivingEntity instance,
            Vec3 input,
            float friction,
            Operation<Vec3> original) {
        if (instance instanceof LocalPlayer && AgentInputState.global().goalMovementOutputActive()) {
            mcmcp$travelHorizontalFriction = friction;
            mcmcp$travelVerticalInputScale = instance.hasEffect(MobEffects.LEVITATION)
                    ? 0.8F
                    : instance.level().hasChunkAt(
                            instance.getBlockPosBelowThatAffectsMyMovement()) ? 1.0F : 0.0F;
        }
        return original.call(instance, input, friction);
    }

    @WrapOperation(
            method = "aiStep()V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;jumpInFluid(Lnet/neoforged/neoforge/fluids/FluidType;)V"),
            require = 3,
            expect = 3)
    private void mcmcp$trackAgentFluidJump(
            LivingEntity instance,
            FluidType fluidType,
            Operation<Void> original) {
        Vec3 before = instance.getDeltaMovement();
        original.call(instance, fluidType);
        if (instance instanceof LocalPlayer && AgentInputState.global().goalMovementOutputActive()) {
            AgentInputState.global().addAgentMoveContribution(
                    instance.getDeltaMovement().subtract(before));
        }
    }

    @WrapOperation(
            method = "jumpFromGround()V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;setDeltaMovement(DDD)V"),
            require = 1,
            expect = 1)
    private void mcmcp$trackAgentJump(
            LivingEntity instance,
            double x,
            double y,
            double z,
            Operation<Void> original) {
        Vec3 before = instance.getDeltaMovement();
        original.call(instance, x, y, z);
        if (instance instanceof LocalPlayer && AgentInputState.global().goalMovementOutputActive()) {
            AgentInputState.global().addAgentMoveContribution(
                    new Vec3(x, y, z).subtract(before));
        }
    }

    @WrapOperation(
            method = "jumpFromGround()V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;addDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"),
            require = 1,
            expect = 1)
    private void mcmcp$trackAgentSprintJump(
            LivingEntity instance,
            Vec3 addition,
            Operation<Void> original) {
        original.call(instance, addition);
        if (instance instanceof LocalPlayer && AgentInputState.global().goalMovementOutputActive()) {
            AgentInputState.global().addAgentMoveContribution(addition);
        }
    }

    @WrapOperation(
            method = "handleRelativeFrictionAndCalculateMovement(Lnet/minecraft/world/phys/Vec3;F)Lnet/minecraft/world/phys/Vec3;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;handleOnClimbable(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"),
            require = 1,
            expect = 1)
    private Vec3 mcmcp$transformAgentContributionOnClimbable(
            LivingEntity instance,
            Vec3 fullVelocity,
            Operation<Vec3> original,
            Vec3 input,
            float friction) {
        Vec3 full = original.call(instance, fullVelocity);
        if (!(instance instanceof LocalPlayer player)
                || !AgentInputState.global().goalMovementOutputActive()) {
            return full;
        }
        Vec3 contribution = AgentInputState.global()
                .agentMoveContribution(player, player.level());
        Vec3 external = original.call(instance, fullVelocity.subtract(contribution));
        AgentInputState.global().replaceAgentMoveContribution(full.subtract(external));
        return full;
    }

    @WrapOperation(
            method = "travelInAir(Lnet/minecraft/world/phys/Vec3;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;setDeltaMovement(DDD)V",
                    ordinal = 0),
            require = 1,
            expect = 1)
    private void mcmcp$trackAgentAirWithoutFriction(
            LivingEntity instance,
            double x,
            double y,
            double z,
            Operation<Void> original,
            Vec3 input) {
        original.call(instance, x, y, z);
        if (instance instanceof LocalPlayer && AgentInputState.global().goalMovementOutputActive()) {
            AgentInputState.global().scaleAgentVelocity(
                    new Vec3(1.0D, mcmcp$travelVerticalInputScale, 1.0D));
        }
    }

    @WrapOperation(
            method = "travelInAir(Lnet/minecraft/world/phys/Vec3;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;setDeltaMovement(DDD)V",
                    ordinal = 1),
            require = 1,
            expect = 1)
    private void mcmcp$trackAgentAirFriction(
            LivingEntity instance,
            double x,
            double y,
            double z,
            Operation<Void> original,
            Vec3 input) {
        original.call(instance, x, y, z);
        if (!(instance instanceof LocalPlayer)
                || !AgentInputState.global().goalMovementOutputActive()) {
            return;
        }
        float horizontal = mcmcp$travelHorizontalFriction * modifiedFriction(
                0.91F, (float) instance.getAttributeValue(Attributes.AIR_DRAG_MODIFIER));
        float vertical = getAirDrag() * mcmcp$travelVerticalInputScale;
        AgentInputState.global().scaleAgentVelocity(
                new Vec3(horizontal, vertical, horizontal));
    }

    private static float modifiedFriction(float base, float modifier) {
        return net.minecraft.util.Mth.clamp(1.0F - (1.0F - base) * modifier, 0.0F, 1.0F);
    }
}
