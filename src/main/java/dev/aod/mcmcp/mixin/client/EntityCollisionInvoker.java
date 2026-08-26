package dev.aod.mcmcp.mixin.client;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/** Exact access to the private Vanilla helpers used by Entity#collide in Minecraft 26.2. */
@Mixin(Entity.class)
public interface EntityCollisionInvoker {
    @Invoker("collide")
    Vec3 mcmcp$collide(Vec3 movement);

    @Invoker("collectCollidersIgnoringWorldBorder")
    static List<VoxelShape> mcmcp$collectColliders(
            Entity entity,
            Level level,
            List<VoxelShape> entityCollisions,
            AABB bounds) {
        throw new AssertionError("mixin invoker was not transformed");
    }

    @Invoker("collectCandidateStepUpHeights")
    static float[] mcmcp$collectCandidateStepUpHeights(
            AABB box,
            List<VoxelShape> colliders,
            float maxUpStep,
            float resolvedY) {
        throw new AssertionError("mixin invoker was not transformed");
    }

    @Invoker("collideWithShapes")
    static Vec3 mcmcp$collideWithShapes(
            Vec3 intendedDelta,
            AABB box,
            List<VoxelShape> colliders) {
        throw new AssertionError("mixin invoker was not transformed");
    }
}
