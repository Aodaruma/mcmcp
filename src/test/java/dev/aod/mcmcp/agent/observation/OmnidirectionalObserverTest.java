package dev.aod.mcmcp.agent.observation;

import dev.aod.mcmcp.agent.observation.ObservationRecord.Face;
import dev.aod.mcmcp.agent.observation.ObservationRecord.ShapeClass;
import dev.aod.mcmcp.agent.observation.ObservationRecord.UnknownBoundary;
import dev.aod.mcmcp.agent.observation.ObservationRecord.UnknownBoundaryReason;
import dev.aod.mcmcp.agent.observation.ObservationRecord.VisibleSurface;
import dev.aod.mcmcp.agent.observation.ObservationValues.BlockPosition;
import dev.aod.mcmcp.agent.observation.ObservationValues.ResourceId;
import dev.aod.mcmcp.agent.observation.ObservationValues.WorldPosition;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OmnidirectionalObserverTest {
    private static final ResourceId DIMENSION = new ResourceId("minecraft:overworld");

    @Test
    void publishesOnlyAfterEightTicksAtTwoHundredFiftySixRaysPerTick() {
        var observer = new OmnidirectionalObserver(16.0D, 256);
        var entityQueries = new AtomicInteger();
        Optional<ObservationFrame> completed = Optional.empty();

        for (long tick = 1L; tick <= 8L; tick++) {
            var sample = sample(tick, 101L, UnknownBoundaryReason.RADIUS_LIMIT);
            completed = observer.collectTick(
                    sample,
                    (index, direction, actual) -> miss(direction, actual),
                    () -> {
                        entityQueries.incrementAndGet();
                        return OmnidirectionalObserver.EntityObservation.empty();
                    });
            if (tick < 8L) {
                assertThat(completed).isEmpty();
            }
        }

        assertThat(completed).isPresent();
        assertThat(completed.orElseThrow().frameId()).matches("obs-[0-9a-f]{16}");
        assertThat(completed.orElseThrow().frameCompletedTick()).isEqualTo(8L);
        assertThat(completed.orElseThrow().configuredVisualRadiusBlocks()).isEqualTo(16.0D);
        assertThat(completed.orElseThrow().records())
                .hasSize(2_048)
                .allMatch(UnknownBoundary.class::isInstance);
        assertThat(entityQueries).hasValue(1);
    }

    @Test
    void worldRevisionChangeDiscardsThePartialFrame() {
        var observer = new OmnidirectionalObserver(
                8.0D,
                512,
                () -> "obs-0000000000000002");
        Optional<ObservationFrame> completed = Optional.empty();

        observer.collectTick(
                sample(1L, 1L, UnknownBoundaryReason.RADIUS_LIMIT),
                (index, direction, actual) -> new OmnidirectionalObserver.RayTrace(
                        OmnidirectionalObserver.RayOutcome.UNKNOWN,
                        List.of(surface(actual)),
                        boundary(actual, UnknownBoundaryReason.UNLOADED, 4, 65, 0)),
                OmnidirectionalObserver.EntityObservation::empty);
        for (long tick = 2L; tick <= 5L; tick++) {
            completed = observer.collectTick(
                    sample(tick, 2L, UnknownBoundaryReason.RADIUS_LIMIT),
                    (index, direction, actual) -> new OmnidirectionalObserver.RayTrace(
                            OmnidirectionalObserver.RayOutcome.UNKNOWN,
                            List.of(surface(actual)),
                            boundary(actual, UnknownBoundaryReason.UNLOADED, 4, 65, 0)),
                    OmnidirectionalObserver.EntityObservation::empty);
            if (tick < 5L) assertThat(completed).isEmpty();
        }

        assertThat(completed.orElseThrow().records())
                .allMatch(record -> record.worldRevision() == 2L);
    }

    @Test
    void surfaceKeyIsPositionAndFaceAndUnknownBoundaryPolicyIsExplicit() {
        var observer = new OmnidirectionalObserver(
                8.0D,
                512,
                () -> "obs-000000000000beef");
        Optional<ObservationFrame> completed = Optional.empty();

        for (long tick = 10L; tick < 14L; tick++) {
            var sample = sample(tick, 7L, UnknownBoundaryReason.RADIUS_LIMIT);
            completed = observer.collectTick(
                    sample,
                    (index, direction, actual) -> new OmnidirectionalObserver.RayTrace(
                            OmnidirectionalObserver.RayOutcome.UNKNOWN,
                            List.of(surface(actual)),
                            boundary(actual, UnknownBoundaryReason.UNLOADED, 4.0D, 65.0D, 0.0D)),
                    OmnidirectionalObserver.EntityObservation::empty);
        }

        ObservationFrame frame = completed.orElseThrow();
        assertThat(frame.records()).filteredOn(VisibleSurface.class::isInstance).hasSize(1);
        assertThat(frame.records()).filteredOn(UnknownBoundary.class::isInstance).hasSize(1);
        VisibleSurface retained = (VisibleSurface) frame.records().getFirst();
        assertThat(retained.observedTick()).isEqualTo(10L);
        assertThat(retained.worldRevision()).isEqualTo(7L);

        var sample = sample(20L, 8L, UnknownBoundaryReason.RADIUS_LIMIT);
        assertThatThrownBy(() -> new OmnidirectionalObserver.RayTrace(
                OmnidirectionalObserver.RayOutcome.HIT,
                List.of(surface(sample)),
                boundary(sample, UnknownBoundaryReason.RADIUS_LIMIT, 8.0D, 65.0D, 0.0D)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outcome");
        assertThat(new OmnidirectionalObserver.RayTrace(
                OmnidirectionalObserver.RayOutcome.UNKNOWN,
                List.of(),
                boundary(sample, UnknownBoundaryReason.AMBIGUOUS_RENDER, 1.0D, 65.0D, 0.0D)).outcome())
                .isEqualTo(OmnidirectionalObserver.RayOutcome.UNKNOWN);
    }

    @Test
    void repeatedTickDoesNotAdvanceTemporalFrameTwice() {
        var observer = new OmnidirectionalObserver(
                8.0D,
                512,
                () -> "obs-0000000000000001");
        var rays = new AtomicInteger();
        var sample = sample(1L, 1L, UnknownBoundaryReason.RADIUS_LIMIT);

        observer.collectTick(sample, (index, direction, actual) -> {
            rays.incrementAndGet();
            return miss(direction, actual);
        }, OmnidirectionalObserver.EntityObservation::empty);
        observer.collectTick(sample, (index, direction, actual) -> {
            rays.incrementAndGet();
            return miss(direction, actual);
        }, OmnidirectionalObserver.EntityObservation::empty);

        assertThat(rays).hasValue(512);
    }

    @Test
    void collectorSourceCannotDependOnViewScreenFocusOrLegacyFovSampler() throws Exception {
        Path source = projectPath(
                "src", "main", "java", "dev", "aod", "mcmcp", "agent", "observation",
                "OmnidirectionalObserver.java");
        String code = Files.readString(source);

        assertThat(code)
                .doesNotContain("mainCamera(")
                .doesNotContain("getYRot(")
                .doesNotContain("getXRot(")
                .doesNotContain("isWindowActive(")
                .doesNotContain(".screen")
                .doesNotContain(".options")
                .doesNotContain("getFov(")
                .doesNotContain("SampledVisibility");
        assertThat(code)
                .contains("player.getEyePosition()")
                .contains("entity.isInvisibleTo(player)")
                .contains("state.is(Blocks.GLASS_PANE)");
    }

    @Test
    void rayPolicyStopsAtSolidPartialShapesAndFailsClosedAtCutoutTextures() {
        assertThat(OmnidirectionalObserver.occlusionReason(ShapeClass.OPAQUE))
                .isEqualTo(UnknownBoundaryReason.OPAQUE_OCCLUSION);
        assertThat(OmnidirectionalObserver.occlusionReason(ShapeClass.PARTIAL))
                .isEqualTo(UnknownBoundaryReason.OPAQUE_OCCLUSION);
        assertThat(OmnidirectionalObserver.occlusionReason(ShapeClass.CUTOUT))
                .isEqualTo(UnknownBoundaryReason.AMBIGUOUS_RENDER);
        assertThat(OmnidirectionalObserver.occlusionReason(ShapeClass.TRANSPARENT)).isNull();
        assertThat(OmnidirectionalObserver.occlusionReason(ShapeClass.FLUID)).isNull();
    }

    @Test
    void fluidContainingTheEyeDoesNotMaskAFartherBlockSurface() {
        Vec3 eye = new Vec3(0.5D, 64.5D, 0.5D);
        BlockPos cell = new BlockPos(0, 64, 0);
        var fluidInside = new BlockHitResult(eye, Direction.WEST, cell, true);
        var blockSurface = new BlockHitResult(
                new Vec3(0.9D, 64.5D, 0.5D), Direction.WEST, cell, false);

        assertThat(OmnidirectionalObserver.nearestRenderableHit(
                eye, blockSurface, fluidInside)).isSameAs(blockSurface);
        assertThat(OmnidirectionalObserver.nearestRenderableHit(eye, null, null)).isNull();
    }

    @Test
    void nearerFluidDoesNotDiscardAFartherSolidShapeInTheSameCell() {
        Vec3 eye = new Vec3(0.0D, 64.5D, 0.5D);
        BlockPos cell = new BlockPos(0, 64, 0);
        var fluid = new BlockHitResult(
                new Vec3(0.2D, 64.5D, 0.5D), Direction.WEST, cell, false);
        var block = new BlockHitResult(
                new Vec3(0.8D, 64.5D, 0.5D), Direction.WEST, cell, false);

        assertThat(OmnidirectionalObserver.orderedRenderableHits(eye, block, fluid))
                .extracting(OmnidirectionalObserver.RenderableHit::fluid)
                .containsExactly(true, false);
    }

    @Test
    void completedFrameMarksAnIncompleteVisibleEntityQuery() {
        var observer = new OmnidirectionalObserver(8.0D, 512);
        Optional<ObservationFrame> completed = Optional.empty();
        for (long tick = 1; tick <= 4; tick++) {
            completed = observer.collectTick(
                    sample(tick, 1, UnknownBoundaryReason.RADIUS_LIMIT),
                    (index, direction, actual) -> miss(direction, actual),
                    () -> new OmnidirectionalObserver.EntityObservation(List.of(), true));
        }

        assertThat(completed.orElseThrow().visibleEntitiesTruncated()).isTrue();
        assertThat(completed.orElseThrow().summary().visibleEntitiesTruncated()).isTrue();
    }

    @Test
    void rejectsOutOfPolicyConfiguration() {
        assertThatThrownBy(() -> new OmnidirectionalObserver(0.99D, 256))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OmnidirectionalObserver(32.01D, 256))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OmnidirectionalObserver(16.0D, 32))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OmnidirectionalObserver(16.0D, 1_024))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static OmnidirectionalObserver.TickSample sample(
            long tick,
            long revision,
            UnknownBoundaryReason terminalReason) {
        return new OmnidirectionalObserver.TickSample(
                DIMENSION,
                new WorldPosition(DIMENSION, 0.0D, 65.0D, 0.0D),
                tick,
                revision,
                8.0D,
                terminalReason);
    }

    private static OmnidirectionalObserver.RayTrace miss(
            OmnidirectionalDirections.DirectionVector direction,
            OmnidirectionalObserver.TickSample sample) {
        WorldPosition eye = sample.eyeOrigin();
        double radius = sample.effectiveRadiusBlocks();
        return new OmnidirectionalObserver.RayTrace(
                OmnidirectionalObserver.RayOutcome.MISS,
                List.of(),
                boundary(
                        sample,
                        sample.terminalReason(),
                        eye.x() + direction.x() * radius,
                        eye.y() + direction.y() * radius,
                        eye.z() + direction.z() * radius));
    }

    private static VisibleSurface surface(OmnidirectionalObserver.TickSample sample) {
        return new VisibleSurface(
                new BlockPosition(DIMENSION, 1, 64, 0),
                Face.WEST,
                new ResourceId("minecraft:glass"),
                ShapeClass.TRANSPARENT,
                sample.eyeOrigin(),
                sample.observedTick(),
                sample.worldRevision());
    }

    private static UnknownBoundary boundary(
            OmnidirectionalObserver.TickSample sample,
            UnknownBoundaryReason reason,
            double x,
            double y,
            double z) {
        return new UnknownBoundary(
                new WorldPosition(DIMENSION, x, y, z),
                reason,
                sample.eyeOrigin(),
                sample.observedTick(),
                sample.worldRevision());
    }

    private static Path projectPath(String... elements) {
        Path result = Path.of(System.getProperty("mcmcp.projectDir", "."));
        for (String element : elements) {
            result = result.resolve(element);
        }
        return result;
    }
}
