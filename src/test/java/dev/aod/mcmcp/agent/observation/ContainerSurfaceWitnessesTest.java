package dev.aod.mcmcp.agent.observation;

import dev.aod.mcmcp.agent.observation.ObservationRecord.*;
import dev.aod.mcmcp.agent.observation.ObservationValues.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerSurfaceWitnessesTest {
    private static final ResourceId DIMENSION = new ResourceId("minecraft:overworld");
    private static final WorldPosition EYE = new WorldPosition(DIMENSION, 0.5, 64.5, -3);

    @Test
    void frameKeepsAnActuallySampledEdgeInsteadOfTheFirstOccludedCenter() {
        var observer = new OmnidirectionalObserver(32, 512);
        Optional<ObservationFrame> result = Optional.empty();
        for (int tick = 1; tick <= 4; tick++) {
            var sample = new OmnidirectionalObserver.TickSample(
                    DIMENSION, EYE, tick, 0, 0, 32, UnknownBoundaryReason.RADIUS_LIMIT);
            result = observer.collectTick(sample, (index, direction, actual) -> {
                var surface = surface(index == 1 ? 0.09 : 0.5, actual.observedTick());
                return new OmnidirectionalObserver.RayTrace(
                        OmnidirectionalObserver.RayOutcome.HIT, List.of(surface),
                        new UnknownBoundary(surface.rayHit(), UnknownBoundaryReason.OPAQUE_OCCLUSION,
                                EYE, actual.observedTick(), 0));
            }, () -> new OmnidirectionalObserver.EntityObservation(List.of(frame(sample.observedTick())), false));
        }
        var surfaces = result.orElseThrow().records().stream()
                .filter(VisibleSurface.class::isInstance).map(VisibleSurface.class::cast).toList();
        assertThat(surfaces).containsExactly(surface(0.09, 1));
        // In particular, selecting the witness must not redate it to the completion tick.
        assertThat(surfaces.getFirst().observedTick()).isEqualTo(1);
        assertThat(result.orElseThrow().frameCompletedTick()).isEqualTo(4);
    }

    @Test
    void neverInventsAnEdgeWhenOnlyTheCenterWasObserved() {
        var center = surface(0.5, 1);
        var witnesses = new ContainerSurfaceWitnesses(center);
        assertThat(witnesses.choose(List.of(frame(1)))).isSameAs(center);
        assertThat(witnesses.choose(List.of())).isSameAs(center);
    }

    @Test
    void unrelatedFaceOrBlockCannotReplaceTheObservedWitness() {
        var center = surface(0.5, 1);
        var witnesses = new ContainerSurfaceWitnesses(center);
        var other = new VisibleSurface(new BlockPosition(DIMENSION, 1, 64, 0), Face.NORTH,
                new ResourceId("minecraft:chest"), ShapeClass.PARTIAL, null,
                new WorldPosition(DIMENSION, 1.0625, 64.0625, 0.0625), EYE, 1, 0);
        witnesses.add(other);
        assertThat(witnesses.choose(List.of(frame(1)))).isSameAs(center);
    }

    @Test
    void nullableStateChestPrefersAnActualInsetRayOverTheUnstableOutlineEdge() {
        var center = surface(0.5, 1);
        var outlineEdge = surface(0.0625, 1);
        var inset = surface(0.09, 2);
        var witnesses = new ContainerSurfaceWitnesses(center);
        witnesses.add(outlineEdge);
        witnesses.add(inset);

        assertThat(outlineEdge.state()).isNull();
        assertThat(witnesses.choose(List.of(frame(2)))).isSameAs(inset);
        assertThat(witnesses.choose(List.of(frame(2))).observedTick()).isEqualTo(2);
    }

    @Test
    void unsafeOnlyOutlineSampleRemainsVisualEvidenceWithoutSynthesizingAnInset() {
        var outlineEdge = surface(0.0625, 1);
        var witnesses = new ContainerSurfaceWitnesses(outlineEdge);
        assertThat(witnesses.choose(List.of())).isSameAs(outlineEdge);
        assertThat(ContainerAimOcclusion.hasSurfaceClearance(outlineEdge)).isFalse();
    }

    private static VisibleSurface surface(double edge, long tick) {
        return new VisibleSurface(new BlockPosition(DIMENSION, 0, 64, 0), Face.NORTH,
                new ResourceId("minecraft:chest"), ShapeClass.PARTIAL, null,
                new WorldPosition(DIMENSION, edge, 64 + edge, 0.0625), EYE, tick, 0);
    }

    private static VisibleEntity frame(long tick) {
        return new VisibleEntity(new ResourceId("minecraft:item_frame"), null, null,
                new WorldPosition(DIMENSION, 0.5, 64.5, -0.03),
                new Vector(0, 0, 0), new Aabb(0.125, 64.125, -0.0625, 0.875, 64.875, 0),
                EntityHazardClass.PASSIVE, EYE, tick, 0);
    }
}
