package dev.aod.mcmcp.agent.observation;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Selection-only check against observed entity bounds; never authorizes an interaction. */
public final class ContainerAimOcclusion {
    private ContainerAimOcclusion() {}

    public static boolean intersects(Vec3 eye, Vec3 target, ObservationValues.Aabb bounds) {
        var box = new AABB(bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ());
        return box.contains(eye) || box.clip(eye, target).isPresent();
    }
}
