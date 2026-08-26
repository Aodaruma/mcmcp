package dev.aod.mcmcp.agent.safety;

import dev.aod.mcmcp.mixin.client.EntityCollisionInvoker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/** Minecraft 26.2 Entity#collide logic over an explicit hypothetical AABB. */
final class VanillaCollisionResolver {
    private static final double STEP_COLLIDER_EPSILON = -9.999999747378752E-6D;

    private VanillaCollisionResolver() {
    }

    static Vec3 resolve(
            LocalPlayer player,
            ClientLevel level,
            AABB start,
            Vec3 intendedDelta) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(intendedDelta, "intendedDelta");

        var entityCollisions = level.getEntityCollisions(
                player,
                start.expandTowards(intendedDelta));
        var resolved = intendedDelta.lengthSqr() == 0.0D
                ? intendedDelta
                : Entity.collideBoundingBox(
                        player,
                        intendedDelta,
                        start,
                        level,
                        entityCollisions);

        boolean clippedX = intendedDelta.x != resolved.x;
        boolean clippedY = intendedDelta.y != resolved.y;
        boolean clippedZ = intendedDelta.z != resolved.z;
        boolean hitGroundWhileDescending = clippedY && intendedDelta.y < 0.0D;
        float maxUpStep = player.maxUpStep();
        if (maxUpStep <= 0.0F
                || (!hitGroundWhileDescending && !player.onGround())
                || (!clippedX && !clippedZ)) {
            return resolved;
        }

        var stepBase = hitGroundWhileDescending
                ? start.move(0.0D, resolved.y, 0.0D)
                : start;
        var stepBounds = stepBase.expandTowards(
                intendedDelta.x,
                maxUpStep,
                intendedDelta.z);
        if (!hitGroundWhileDescending) {
            stepBounds = stepBounds.expandTowards(0.0D, STEP_COLLIDER_EPSILON, 0.0D);
        }
        var colliders = EntityCollisionInvoker.mcmcp$collectColliders(
                player,
                level,
                entityCollisions,
                stepBounds);
        var candidateHeights = EntityCollisionInvoker.mcmcp$collectCandidateStepUpHeights(
                stepBase,
                colliders,
                maxUpStep,
                (float) resolved.y);
        for (float height : candidateHeights) {
            var stepped = EntityCollisionInvoker.mcmcp$collideWithShapes(
                    new Vec3(intendedDelta.x, height, intendedDelta.z),
                    stepBase,
                    colliders);
            if (stepped.horizontalDistanceSqr() > resolved.horizontalDistanceSqr()) {
                return stepped.subtract(0.0D, start.minY - stepBase.minY, 0.0D);
            }
        }
        return resolved;
    }
}
