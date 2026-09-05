package dev.aod.mcmcp.agent.observation;

import dev.aod.mcmcp.agent.observation.ObservationRecord.VisibleEntity;
import dev.aod.mcmcp.agent.observation.ObservationRecord.VisibleSurface;
import dev.aod.mcmcp.agent.observation.ObservationValues.WorldPosition;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Objects;

/** At most five actual ray witnesses for one container face; never synthesizes a hit. */
final class ContainerSurfaceWitnesses {
    private final VisibleSurface first;
    private final VisibleSurface[] corners = new VisibleSurface[4];

    ContainerSurfaceWitnesses(VisibleSurface first) {
        this.first = Objects.requireNonNull(first, "first");
        add(first);
    }

    static boolean supports(VisibleSurface surface) {
        return surface.rayHit() != null && (surface.block().value().equals("minecraft:chest")
                || surface.block().value().equals("minecraft:barrel"));
    }

    void add(VisibleSurface surface) {
        if (!surface.position().equals(first.position()) || surface.face() != first.face()
                || !surface.block().equals(first.block())
                || !Objects.equals(surface.state(), first.state())
                || surface.shapeClass() != first.shapeClass() || surface.rayHit() == null) {
            return;
        }
        // Corners are only ranking directions. The retained values are unmodified ray samples.
        for (int index = 0; index < corners.length; index++) {
            if (corners[index] == null || score(surface, index) < score(corners[index], index)) {
                corners[index] = surface;
            }
        }
    }

    VisibleSurface choose(List<VisibleEntity> entities) {
        if (usable(first, entities)) return first;
        for (VisibleSurface candidate : corners) {
            if (candidate != null && usable(candidate, entities)) return candidate;
        }
        // All rays may be blocked for interaction while the block is still visually observable.
        // Preserve the real sample; the planner and live crosshair gates decide usability.
        return first;
    }

    private static boolean usable(VisibleSurface surface, List<VisibleEntity> entities) {
        return ContainerAimOcclusion.hasSurfaceClearance(surface)
                && entities.stream().filter(entity -> entity.dimension().equals(surface.dimension()))
                .noneMatch(entity -> ContainerAimOcclusion.intersects(
                        point(surface.eyeOrigin()), point(surface.rayHit()), entity.aabb()));
    }

    private static double score(VisibleSurface surface, int corner) {
        if (!ContainerAimOcclusion.hasSurfaceClearance(surface)) return Double.POSITIVE_INFINITY;
        var hit = surface.rayHit();
        var block = surface.position();
        double u = switch (surface.face()) {
            case UP, DOWN, NORTH, SOUTH -> hit.x() - block.x();
            case EAST, WEST -> hit.z() - block.z();
        };
        double v = switch (surface.face()) {
            case UP, DOWN -> hit.z() - block.z();
            case NORTH, SOUTH, EAST, WEST -> hit.y() - block.y();
        };
        boolean chest = surface.block().value().equals("minecraft:chest");
        boolean horizontalFace = surface.face() == ObservationRecord.Face.UP
                || surface.face() == ObservationRecord.Face.DOWN;
        double lowU = chest ? 0.0625D : 0.0D;
        double highU = chest ? 0.9375D : 1.0D;
        double lowV = chest && horizontalFace ? 0.0625D : 0.0D;
        double highV = chest ? horizontalFace ? 0.9375D : 0.875D : 1.0D;
        // A slightly inset corner is a ranking preference only. Every chosen point still came
        // from a real ray, including connected-half points outside these preferred coordinates.
        double du = u - ((corner & 1) == 0 ? lowU + 0.02D : highU - 0.02D);
        double dv = v - ((corner & 2) == 0 ? lowV + 0.02D : highV - 0.02D);
        return du * du + dv * dv;
    }

    private static Vec3 point(WorldPosition point) {
        return new Vec3(point.x(), point.y(), point.z());
    }
}
