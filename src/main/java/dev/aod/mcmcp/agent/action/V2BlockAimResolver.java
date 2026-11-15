package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.navigation.NavCell;
import dev.aod.mcmcp.agent.observation.ObservationFrame;
import dev.aod.mcmcp.agent.observation.ObservationRecord;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.List;
import java.util.EnumMap;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/** Selects a current local ray for an explicitly requested v2 block coordinate. */
public final class V2BlockAimResolver {
    private static final long MAX_RAY_AGE_TICKS = 40L;
    private static final double MAX_EYE_DRIFT_SQUARED = 0.125D * 0.125D;
    public static final double MAX_REACH = 4.5D;
    private static final double MAX_REACH_SQUARED = MAX_REACH * MAX_REACH;

    private V2BlockAimResolver() { }

    public static Optional<Result> resolve(ObservationFrame frame, NavCell target,
            Vec3 currentEye, UUID worldSessionId, long currentTick,
            long currentRevision, long surfaceBarrierRevision) {
        return resolve(frame, target, null, currentEye, worldSessionId,
                currentTick, currentRevision, surfaceBarrierRevision);
    }

    /** A placement support must expose the particular face adjacent to its target cell. */
    public static Optional<Result> resolve(ObservationFrame frame, NavCell target,
            ObservationRecord.Face requiredFace, Vec3 currentEye,
            UUID worldSessionId, long currentTick,
            long currentRevision, long surfaceBarrierRevision) {
        return resolve(frame, target, requiredFace, currentEye, worldSessionId,
                currentTick, currentRevision, surfaceBarrierRevision,
                id -> id.startsWith("minecraft:"));
    }

    /** Callers can admit MOD targets while retaining all current-ray evidence checks. */
    public static Optional<Result> resolve(ObservationFrame frame, NavCell target,
            ObservationRecord.Face requiredFace, Vec3 currentEye,
            UUID worldSessionId, long currentTick,
            long currentRevision, long surfaceBarrierRevision,
            Predicate<String> allowedBlock) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(currentEye, "currentEye");
        Objects.requireNonNull(worldSessionId, "worldSessionId");
        Objects.requireNonNull(allowedBlock, "allowedBlock");
        if (currentTick < 0 || currentRevision < 0 || surfaceBarrierRevision < 0
                || surfaceBarrierRevision > currentRevision
                || !frame.dimension().value().equals(target.dimension())) return Optional.empty();

        ObservationRecord.VisibleSurface best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (var record : frame.records()) {
            if (!(record instanceof ObservationRecord.VisibleSurface surface)
                    || !fresh(surface, target, currentTick, currentRevision, surfaceBarrierRevision)
                    || (requiredFace != null && surface.face() != requiredFace)
                    || !allowedBlock.test(surface.block().value())) continue;
            var eye = surface.eyeOrigin();
            if (distanceSquared(currentEye, eye.x(), eye.y(), eye.z())
                    > MAX_EYE_DRIFT_SQUARED) continue;
            var hit = surface.rayHit();
            double reach = distanceSquared(currentEye, hit.x(), hit.y(), hit.z());
            if (reach > MAX_REACH_SQUARED) continue;
            if (best == null || surface.observedTick() > best.observedTick()
                    || surface.observedTick() == best.observedTick()
                            && reach < bestDistance) {
                best = surface;
                bestDistance = reach;
            }
        }
        if (best == null) return Optional.empty();
        var hit = best.rayHit();
        return Optional.of(new Result(best.block().value(),
                new MinecraftActionPrimitiveExecutor.KnownFaceTarget(
                        worldSessionId, currentRevision,
                        new ActionDsl.Position(target.dimension(), target.x(), target.y(), target.z()),
                        hit.x(), hit.y(), hit.z(), true)));
    }

    /** Planning hints only: executing from a new eye still requires resolve() with fresh evidence. */
    public static List<Vec3> approachPoints(ObservationFrame frame, NavCell target,
            long currentTick, long currentRevision, long surfaceBarrierRevision) {
        if (currentTick < 0 || currentRevision < 0 || surfaceBarrierRevision < 0
                || surfaceBarrierRevision > currentRevision
                || !frame.dimension().value().equals(target.dimension())) return List.of();
        var faces = new EnumMap<ObservationRecord.Face, ObservationRecord.VisibleSurface>(ObservationRecord.Face.class);
        for (var record : frame.records()) {
            if (record instanceof ObservationRecord.VisibleSurface surface
                    && fresh(surface, target, currentTick, currentRevision, surfaceBarrierRevision)) {
                var previous = faces.get(surface.face());
                if (previous == null || surface.observedTick() > previous.observedTick()) faces.put(surface.face(), surface);
            }
        }
        return faces.values().stream().map(surface -> new Vec3(
                surface.rayHit().x(), surface.rayHit().y(), surface.rayHit().z())).toList();
    }

    private static boolean fresh(ObservationRecord.VisibleSurface surface, NavCell target,
            long currentTick, long currentRevision, long surfaceBarrierRevision) {
        return surface.rayHit() != null
                && surface.position().x() == target.x() && surface.position().y() == target.y()
                && surface.position().z() == target.z()
                && surface.worldRevision() >= surfaceBarrierRevision && surface.worldRevision() <= currentRevision
                && surface.observedTick() <= currentTick && currentTick - surface.observedTick() <= MAX_RAY_AGE_TICKS;
    }

    private static double distanceSquared(Vec3 current, double x, double y, double z) {
        double dx = current.x - x;
        double dy = current.y - y;
        double dz = current.z - z;
        return dx * dx + dy * dy + dz * dz;
    }

    public record Result(String blockId,
                         MinecraftActionPrimitiveExecutor.KnownFaceTarget aim) { }
}
