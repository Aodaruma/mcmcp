package dev.aod.mcmcp.agent.safety;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

/** A short approach to one observed End portal cell, never an arbitrary drop corridor. */
public record PortalEntryGeometry(BlockPos portal) {
    private static final double EPSILON = 1.0E-6;
    public PortalEntryGeometry { portal = portal.immutable(); }

    public boolean nearby(AABB body) {
        return body.getXsize() <= 0.7 && body.getZsize() <= 0.7
                && body.minY >= portal.getY() + 0.25 - EPSILON
                && body.minY <= portal.getY() + 1.05 + EPSILON
                && horizontalDistanceSquared(body) <= 1.75 * 1.75;
    }

    public boolean overPortal(AABB body) {
        return body.minX >= portal.getX() - EPSILON && body.maxX <= portal.getX() + 1 + EPSILON
                && body.minZ >= portal.getZ() - EPSILON && body.maxZ <= portal.getZ() + 1 + EPSILON;
    }

    public boolean contact(AABB body) {
        return nearby(body) && overPortal(body) && body.intersects(new AABB(
                portal.getX(), portal.getY() + 0.375, portal.getZ(),
                portal.getX() + 1, portal.getY() + 0.75, portal.getZ() + 1));
    }

    public boolean approaches(AABB before, AABB after) {
        return nearby(before) && nearby(after)
                && after.minY <= before.minY + EPSILON
                && before.minY - after.minY <= 0.5 + EPSILON
                && horizontalDistanceSquared(after) <= horizontalDistanceSquared(before) + EPSILON;
    }

    public double horizontalDistanceSquared(AABB body) {
        double dx = body.getCenter().x - (portal.getX() + 0.5);
        double dz = body.getCenter().z - (portal.getZ() + 0.5);
        return dx * dx + dz * dz;
    }
}
