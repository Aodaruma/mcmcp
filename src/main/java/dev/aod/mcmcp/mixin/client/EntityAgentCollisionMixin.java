package dev.aod.mcmcp.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.aod.mcmcp.agent.safety.AgentMovementTrace;
import dev.aod.mcmcp.agent.safety.LocalObservationVolume;
import dev.aod.mcmcp.client.AgentInputState;
import dev.aod.mcmcp.runtime.ClientReconciliationSignals;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Captures only Vanilla's authoritative collision resolution for agent-controlled local movement. */
@Mixin(Entity.class)
abstract class EntityAgentCollisionMixin {
    @WrapOperation(
            method = "moveRelative(FLnet/minecraft/world/phys/Vec3;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"),
            require = 1,
            expect = 1)
    private void mcmcp$trackAgentRelativeAcceleration(
            Entity instance,
            Vec3 velocity,
            Operation<Void> original,
            float speed,
            Vec3 input) {
        Vec3 before = instance.getDeltaMovement();
        original.call(instance, velocity);
        if (instance instanceof LocalPlayer && AgentInputState.global().goalMovementOutputActive()) {
            AgentInputState.global().addAgentMoveContribution(velocity.subtract(before));
        }
    }

    @WrapOperation(
            method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/Vec3;multiply(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"),
            require = 1,
            expect = 1)
    private Vec3 mcmcp$scaleAgentContributionWhenStuck(
            Vec3 velocity,
            Vec3 factor,
            Operation<Vec3> original,
            MoverType moverType,
            Vec3 requestedMovement) {
        Vec3 scaled = original.call(velocity, factor);
        if ((Object) this instanceof LocalPlayer
                && moverType == MoverType.SELF
                && AgentInputState.global().goalMovementOutputActive()) {
            AgentInputState.global().scaleAgentMoveContribution(factor);
        }
        return scaled;
    }

    @WrapOperation(
            method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;maybeBackOffFromEdge(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/entity/MoverType;)Lnet/minecraft/world/phys/Vec3;"),
            require = 1,
            expect = 1)
    private Vec3 mcmcp$guardAgentMovementBeforeCollision(
            Entity instance,
            Vec3 intendedDelta,
            MoverType moverType,
            Operation<Vec3> original,
            MoverType requestedMoverType,
            Vec3 requestedMovement) {
        Vec3 fullBacked = original.call(instance, intendedDelta, moverType);
        var input = AgentInputState.global();
        if (!(instance instanceof LocalPlayer player) || moverType != MoverType.SELF) {
            return fullBacked;
        }
        var boundary = input.movementBoundary(player);
        if (boundary.expired()) {
            try {
                Vec3 externalBacked = original.call(
                        instance, intendedDelta.subtract(boundary.contribution()), moverType);
                if (boundary.contribution().lengthSqr() > 0.0D && !boundary.velocityReset()) {
                    player.setDeltaMovement(
                            player.getDeltaMovement().subtract(boundary.contribution()));
                }
                return externalBacked;
            } finally {
                input.completeExpiredMovementBoundary();
            }
        }
        if (!boundary.active()) return fullBacked;

        Vec3 contribution = input.agentMoveContribution(player, player.level());
        var proof = input.goalMovementProofFor(player, player.level());
        Vec3 previewResolved;
        Vec3 externalBackedForProof = null;
        boolean verified = false;
        try {
            if (proof.isPresent()) {
                var current = proof.orElseThrow();
                boolean revisionCurrent = ClientReconciliationSignals.global()
                        .currentSnapshot((ClientLevel) player.level())
                        .map(snapshot -> snapshot.worldRevision() == current.worldRevision())
                        .orElse(false);
                if (!revisionCurrent) {
                    throw new IllegalStateException("goal movement proof revision changed");
                }
                if (!LocalObservationVolume.global().canPreviewGoalMovement(
                        player, fullBacked, current.worldRevision())) {
                    throw new IllegalStateException("goal movement preview crossed loaded evidence");
                }
                previewResolved = ((EntityCollisionInvoker) instance).mcmcp$collide(fullBacked);
                Vec3 externalResolved = previewResolved;
                if (current.recoveryIntent() != null) {
                    externalBackedForProof = original.call(
                            instance, intendedDelta.subtract(contribution), moverType);
                    if (!LocalObservationVolume.global().canPreviewGoalMovement(
                            player, externalBackedForProof, current.worldRevision())) {
                        throw new IllegalStateException(
                                "recovery external movement crossed loaded evidence");
                    }
                    externalResolved = ((EntityCollisionInvoker) instance)
                            .mcmcp$collide(externalBackedForProof);
                }
                boolean movementSafe;
                if (current.recoveryIntent() != null) {
                    movementSafe = LocalObservationVolume.global()
                            .verifiesRecoveryResolvedMovement(
                                    player,
                                    fullBacked,
                                    previewResolved,
                                    externalResolved,
                                    player.tickCount,
                                    current.worldRevision(),
                                    current.recoveryIntent());
                } else if (current.navigationIntent() != null) {
                    movementSafe = LocalObservationVolume.global()
                            .verifiesNavigationResolvedMovement(
                                    player,
                                    fullBacked,
                                    previewResolved,
                                    player.tickCount,
                                    current.worldRevision(),
                                    current.navigationIntent());
                } else {
                    movementSafe = LocalObservationVolume.global().verifiesGoalResolvedMovement(
                            player,
                            fullBacked,
                            previewResolved,
                            player.tickCount,
                            current.worldRevision());
                }
                verified = previewResolved.length()
                                <= current.distanceAllowance() + 1.0E-9D
                        && movementSafe;
            } else {
                previewResolved = Vec3.ZERO;
            }
        } catch (RuntimeException | LinkageError ignored) {
            previewResolved = Vec3.ZERO;
        }
        if (verified) {
            input.acceptGoalMovement(previewResolved.length());
            return fullBacked;
        }

        Vec3 externalBacked = fullBacked;
        if (contribution.lengthSqr() > 0.0D) {
            externalBacked = externalBackedForProof == null
                    ? original.call(instance, intendedDelta.subtract(contribution), moverType)
                    : externalBackedForProof;
            if (!input.agentVelocityReset()) {
                player.setDeltaMovement(player.getDeltaMovement().subtract(contribution));
            }
        }
        input.rejectGoalMovement();
        return externalBacked;
    }

    @WrapOperation(
            method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"),
            require = 1,
            expect = 1)
    private Vec3 mcmcp$recordAgentCollision(
            Entity instance,
            Vec3 intendedDelta,
            Operation<Vec3> original,
            MoverType moverType,
            Vec3 requestedMovement) {
        var resolvedDelta = original.call(instance, intendedDelta);
        if (instance instanceof LocalPlayer
                && moverType == MoverType.SELF
                && AgentInputState.global().goalMovementOutputActive()) {
            AgentInputState.global().resolveAgentContributionAfterCollision(
                    intendedDelta, resolvedDelta);
        }
        if (instance instanceof LocalPlayer player
                && moverType == MoverType.SELF
                && AgentMovementTrace.agentMovementActive()) {
            AgentMovementTrace.global().recordCollision(
                    player,
                    moverType,
                    player.getBoundingBox(),
                    intendedDelta,
                    resolvedDelta);
        }
        return resolvedDelta;
    }

    @WrapOperation(
            method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/Vec3;multiply(DDD)Lnet/minecraft/world/phys/Vec3;"),
            require = 1,
            expect = 1)
    private Vec3 mcmcp$trackAgentBlockSpeedFactor(
            Vec3 velocity,
            double x,
            double y,
            double z,
            Operation<Vec3> original,
            MoverType moverType,
            Vec3 requestedMovement) {
        Vec3 scaled = original.call(velocity, x, y, z);
        if ((Object) this instanceof LocalPlayer
                && moverType == MoverType.SELF
                && AgentInputState.global().goalMovementOutputActive()) {
            AgentInputState.global().scaleAgentVelocity(new Vec3(x, y, z));
        }
        return scaled;
    }
}
