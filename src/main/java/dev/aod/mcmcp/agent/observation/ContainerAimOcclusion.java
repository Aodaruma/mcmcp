package dev.aod.mcmcp.agent.observation;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Selection-only check against observed entity bounds; never authorizes an interaction. */
public final class ContainerAimOcclusion {
    // Small selection allowance for position noise and Vanilla's float/65,536-entry
    // trigonometric view-vector reconstruction. Actual use still needs the live exactHit gate.
    private static final double ENTITY_CLEARANCE = 0.01D;
    private static final double SURFACE_EDGE_CLEARANCE = 0.001D;
    private static final double[] CELL_EDGES = {0.0D, 1.0D};
    // Potential edges of either Vanilla chest half. This also works when public state is null;
    // no half identity or unobserved point is inferred from these selection-only coordinates.
    private static final double[] CHEST_HORIZONTAL_EDGES = {0.0D, 0.0625D, 0.9375D, 1.0D};
    private static final double[] CHEST_VERTICAL_EDGES = {0.0D, 0.875D, 1.0D};

    private ContainerAimOcclusion() {}

    public static boolean intersects(Vec3 eye, Vec3 target, ObservationValues.Aabb bounds) {
        var box = new AABB(bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ()).inflate(ENTITY_CLEARANCE);
        return box.contains(eye) || box.clip(eye, target).isPresent();
    }

    /** Reject only a narrow band at known possible outline edges, without moving the witness. */
    public static boolean hasSurfaceClearance(ObservationRecord.VisibleSurface surface) {
        if (surface.rayHit() == null) return false;
        var point = surface.rayHit();
        var block = surface.position();
        boolean chest = surface.block().value().equals("minecraft:chest");
        double[] horizontal = chest ? CHEST_HORIZONTAL_EDGES : CELL_EDGES;
        double[] vertical = chest ? CHEST_VERTICAL_EDGES : CELL_EDGES;
        double clearance = switch (surface.face()) {
            case UP, DOWN -> Math.min(distanceToEdges(point.x() - block.x(), horizontal),
                    distanceToEdges(point.z() - block.z(), horizontal));
            case NORTH, SOUTH -> Math.min(distanceToEdges(point.x() - block.x(), horizontal),
                    distanceToEdges(point.y() - block.y(), vertical));
            case EAST, WEST -> Math.min(distanceToEdges(point.z() - block.z(), horizontal),
                    distanceToEdges(point.y() - block.y(), vertical));
        };
        return clearance >= SURFACE_EDGE_CLEARANCE;
    }

    private static double distanceToEdges(double coordinate, double[] edges) {
        double distance = Double.POSITIVE_INFINITY;
        for (double edge : edges) distance = Math.min(distance, Math.abs(coordinate - edge));
        return distance;
    }
}
