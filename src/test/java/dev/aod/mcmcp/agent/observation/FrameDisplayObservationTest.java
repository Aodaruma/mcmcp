package dev.aod.mcmcp.agent.observation;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class FrameDisplayObservationTest {
    private static final ObservationValues.ResourceId DIMENSION =
            new ObservationValues.ResourceId("minecraft:overworld");

    @Test
    void frontFacesInAllSixDirectionsSupplyOnlyTheActuallyTracedAimPoint() {
        for (Direction direction : Direction.values()) {
            var normal = new Vec3(direction.getStepX(), direction.getStepY(), direction.getStepZ());
            var box = new AABB(-0.375, 63.625, -0.375, 0.375, 64.375, 0.375);
            var front = box.getCenter().add(normal.scale(0.375));
            var eye = front.add(normal.scale(2));
            var rays = new AtomicInteger();
            assertThat(OmnidirectionalObserver.frameDisplayAim(box, direction, sample(eye, 3), point -> {
                rays.incrementAndGet();
                assertThat(point).isEqualTo(front);
                return true;
            })).contains(front);
            assertThat(rays.get()).isOne();
            assertThat(OmnidirectionalObserver.frameDisplayAim(box, direction,
                    sample(front.subtract(normal), 3), point -> {
                        throw new AssertionError("back face must not reveal the displayed item");
                    })).isEmpty();
        }
    }

    @Test
    void edgeOnFogAndBlockedDisplayRemainUnknownEvenIfOtherPartsOfTheFrameAreVisible() {
        var box = new AABB(-0.375, 63.625, -0.03125, 0.375, 64.375, 0.03125);
        assertThat(OmnidirectionalObserver.frameDisplayAim(box, Direction.SOUTH,
                sample(new Vec3(1, 64, 0.03125), 3), point -> {
                    throw new AssertionError("edge-on view must not reveal the display");
                })).isEmpty();
        assertThat(OmnidirectionalObserver.frameDisplayAim(box, Direction.SOUTH,
                sample(new Vec3(0, 64, 1.04), 1), point -> {
                    throw new AssertionError("front display point is beyond fog");
                })).isEmpty();
        assertThat(OmnidirectionalObserver.frameDisplayAim(box, Direction.SOUTH,
                sample(new Vec3(0, 64, 1), 3), point -> false)).isEmpty();
    }

    private static OmnidirectionalObserver.TickSample sample(Vec3 eye, double radius) {
        return new OmnidirectionalObserver.TickSample(DIMENSION,
                new ObservationValues.WorldPosition(DIMENSION, eye.x, eye.y, eye.z),
                10, 5, 5, radius, ObservationRecord.UnknownBoundaryReason.FOG_LIMIT);
    }
}
