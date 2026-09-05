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
        if (!blocked(first, entities)) return first;
        for (VisibleSurface candidate : corners) {
            if (candidate != null && !blocked(candidate, entities)) return candidate;
        }
        // All rays may be blocked for interaction while the block is still visually observable.
        // Preserve the real sample; the planner and live crosshair gates decide usability.
        return first;
    }

    private static boolean blocked(VisibleSurface surface, List<VisibleEntity> entities) {
        return entities.stream().filter(entity -> entity.dimension().equals(surface.dimension()))
                .anyMatch(entity -> ContainerAimOcclusion.intersects(
                        point(surface.eyeOrigin()), point(surface.rayHit()), entity.aabb()));
    }

    private static double score(VisibleSurface surface, int corner) {
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
        double du = u - (corner & 1);
        double dv = v - ((corner >> 1) & 1);
        return du * du + dv * dv;
    }

    private static Vec3 point(WorldPosition point) {
        return new Vec3(point.x(), point.y(), point.z());
    }
}
