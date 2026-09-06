package dev.aod.mcmcp.agent.observation;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only bridge to the production observer's package-local ray seam. */
public final class SurfaceReobservationFixture {
    private SurfaceReobservationFixture() { }

    public static Optional<ObservationRecord.VisibleSurface> reobserve(
            ObservationRecord.VisibleSurface known, long tick, long revision, double fogDistance,
            ObservationValues.ResourceId currentBlock, AtomicInteger rays) {
        var sample = new OmnidirectionalObserver.TickSample(known.position().dimension(), known.eyeOrigin(),
                tick, revision, revision, fogDistance, ObservationRecord.UnknownBoundaryReason.RADIUS_LIMIT);
        return OmnidirectionalObserver.reobserveSurface(known, sample, (index, direction, current) -> {
            rays.incrementAndGet();
            var hit = currentBlock == null ? new ObservationValues.WorldPosition(known.dimension(),
                    (known.eyeOrigin().x() + known.rayHit().x()) / 2,
                    (known.eyeOrigin().y() + known.rayHit().y()) / 2,
                    (known.eyeOrigin().z() + known.rayHit().z()) / 2) : known.rayHit();
            var position = currentBlock == null ? new ObservationValues.BlockPosition(known.dimension(),
                    (int) Math.floor(hit.x()), (int) Math.floor(hit.y()), (int) Math.floor(hit.z())) : known.position();
            var visible = List.of(new ObservationRecord.VisibleSurface(position, known.face(),
                            currentBlock == null ? new ObservationValues.ResourceId("minecraft:stone") : currentBlock,
                            known.shapeClass(), null, hit, current.eyeOrigin(),
                            current.observedTick(), current.worldRevision()));
            return new OmnidirectionalObserver.RayTrace(OmnidirectionalObserver.RayOutcome.HIT,
                    visible, new ObservationRecord.UnknownBoundary(hit,
                            ObservationRecord.UnknownBoundaryReason.OPAQUE_OCCLUSION,
                            current.eyeOrigin(), current.observedTick(), current.worldRevision()));
        });
    }
}
