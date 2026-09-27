package dev.aod.mcmcp.agent.safety;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;

import java.util.Optional;

/** Geometry for a level diagonal whose footprint stays on either of two full-cube supports. */
final class DiagonalGroundPath {
    private static final double EPSILON = 1.0E-6D;
    private static final double SUPPORT_MARGIN = 0.05D;

    private DiagonalGroundPath() { }

    static Optional<Path> between(AABB start, AABB end) {
        if (Math.abs(start.minY - end.minY) > EPSILON
                || Math.abs(start.minY - Math.rint(start.minY)) > EPSILON
                || start.getXsize() <= 2 * SUPPORT_MARGIN || start.getXsize() > 1
                || start.getZsize() <= 2 * SUPPORT_MARGIN || start.getZsize() > 1
                || Math.abs(start.getXsize() - end.getXsize()) > EPSILON
                || Math.abs(start.getZsize() - end.getZsize()) > EPSILON) return Optional.empty();
        var from = new BlockPos(Mth.floor(start.getCenter().x),
                Mth.floor(start.minY + EPSILON) - 1, Mth.floor(start.getCenter().z));
        var to = new BlockPos(Mth.floor(end.getCenter().x),
                from.getY(), Mth.floor(end.getCenter().z));
        if (Math.abs((long) from.getX() - to.getX()) != 1
                || Math.abs((long) from.getZ() - to.getZ()) != 1) return Optional.empty();
        double dx = end.minX - start.minX;
        double dz = end.minZ - start.minZ;
        Interval first = supportedInterval(start, dx, dz, from);
        Interval last = supportedInterval(start, dx, dz, to);
        // Strict overlap leaves a positive supporting area throughout the crossing.
        if (first.start() > 0 || last.end() < 1
                || first.end() <= last.start() + EPSILON) return Optional.empty();
        // Contact may cover only part of an edge. Also prove the complete cell-centre corridor.
        var centeredStart = start.move(from.getX() + 0.5D - start.getCenter().x,
                0, from.getZ() + 0.5D - start.getCenter().z);
        var centeredEnd = start.move(to.getX() + 0.5D - start.getCenter().x,
                0, to.getZ() + 0.5D - start.getCenter().z);
        var bounds = start.minmax(end).minmax(centeredStart).minmax(centeredEnd);
        var corridor = new AABB(bounds.minX - SUPPORT_MARGIN,
                start.minY, bounds.minZ - SUPPORT_MARGIN,
                bounds.maxX + SUPPORT_MARGIN,
                start.minY + Math.max(1.8D, Math.max(start.getYsize(), end.getYsize())),
                bounds.maxZ + SUPPORT_MARGIN);
        return Optional.of(new Path(from, to, corridor));
    }

    private static Interval supportedInterval(AABB box, double dx, double dz, BlockPos block) {
        Interval x = interval(box.minX, box.maxX, dx, block.getX());
        Interval z = interval(box.minZ, box.maxZ, dz, block.getZ());
        return new Interval(Math.max(0, Math.max(x.start(), z.start())),
                Math.min(1, Math.min(x.end(), z.end())));
    }

    private static Interval interval(double min, double max, double delta, int block) {
        double a = (block + SUPPORT_MARGIN - max) / delta;
        double b = (block + 1.0D - SUPPORT_MARGIN - min) / delta;
        return new Interval(Math.min(a, b), Math.max(a, b));
    }

    record Path(BlockPos fromSupport, BlockPos toSupport, AABB corridor) { }
    private record Interval(double start, double end) { }
}
