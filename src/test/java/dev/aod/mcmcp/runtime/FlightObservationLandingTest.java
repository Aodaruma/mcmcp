package dev.aod.mcmcp.runtime;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

class FlightObservationLandingTest {
    private static final Vec3 EYE = new Vec3(.5, 3.5, .5);
    private static final BlockPos SUPPORT = new BlockPos(4, 0, 0);
    private static final BlockPos WALL = new BlockPos(3, 0, 0);
    private static final VoxelShape LOW_WALL = Shapes.box(.5, 0, 0, .6, 1.8, 1);

    @Test void visibleAirAboveAnOccludedFloorCannotAuthorizeItsSupport() {
        var air = new Vec3(4.5, 1.5, .5);
        assertThat(LOW_WALL.clip(EYE, air, WALL)).isNull();
        assertThat(FlightObservation.visibleLandingSupport(EYE, SUPPORT, 1, 96, 96,
                pos -> true, (mode, end) -> {
                    var blocked = LOW_WALL.clip(EYE, end, WALL);
                    return blocked == null ? floor(end) : blocked;
                })).isFalse();
    }

    @Test void bothVisualAndCollisionRaysMustHitTheExactTop() {
        var calls = new AtomicInteger();
        assertThat(FlightObservation.visibleLandingSupport(EYE, SUPPORT, 1, 96, 96,
                pos -> true, (mode, end) -> { calls.incrementAndGet(); return floor(end); })).isTrue();
        assertThat(calls).hasValue(2);
        for (var blockedMode : new ClipContext.Block[] {ClipContext.Block.VISUAL, ClipContext.Block.COLLIDER}) {
            assertThat(FlightObservation.visibleLandingSupport(EYE, SUPPORT, 1, 96, 96,
                    pos -> true, (mode, end) -> mode == blockedMode ? LOW_WALL.clip(EYE, end, WALL) : floor(end)))
                    .isFalse();
        }
    }

    @Test void everyCrossedCellMustBeLoadedBeforeAnySupportRay() {
        var calls = new AtomicInteger();
        assertThat(FlightObservation.visibleLandingSupport(EYE, SUPPORT, 1, 96, 96,
                pos -> pos.getX() != 2, (mode, end) -> { calls.incrementAndGet(); return floor(end); })).isFalse();
        assertThat(calls).hasValue(0);
    }

    @Test void missingShortFogAndConfiguredRangeRejectTheFloorBeforeRaycasting() {
        var calls = new AtomicInteger();
        for (var limits : new double[][] {{96, 0}, {96, Double.NaN}, {96, 1}, {1, 96}}) {
            assertThat(FlightObservation.visibleLandingSupport(EYE, SUPPORT, 1, limits[0], limits[1],
                    pos -> true, (mode, end) -> { calls.incrementAndGet(); return floor(end); })).isFalse();
        }
        assertThat(calls).hasValue(0);
    }

    @Test void theStoppingPlaneCannotUseTheLegacyLandingHeightTolerance() {
        assertThat(FlightObservation.sameLandingHeight(101, 101)).isTrue();
        assertThat(FlightObservation.sameLandingHeight(101, 101 + 1e-8)).isTrue();
        for (double actual : new double[] {100.75, 101.25, 100, Double.NaN})
            assertThat(FlightObservation.sameLandingHeight(101, actual)).isFalse();
        assertThat(FlightObservation.visibleLandingSupport(EYE, SUPPORT, .75, 96, 96,
                pos -> true, (mode, end) -> floor(end))).isFalse();
        assertThat(FlightObservation.visibleLandingSupport(EYE, SUPPORT, 1, 96, 96,
                pos -> true, (mode, end) -> new BlockHitResult(new Vec3(4.5, 1, .5), Direction.UP, SUPPORT, true)))
                .isFalse();
    }

    private static BlockHitResult floor(Vec3 end) {
        var hit = Shapes.block().clip(EYE, end, SUPPORT);
        return hit == null ? BlockHitResult.miss(end, Direction.DOWN, BlockPos.containing(end)) : hit;
    }
}
