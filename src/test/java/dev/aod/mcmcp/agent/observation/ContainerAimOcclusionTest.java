package dev.aod.mcmcp.agent.observation;

import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.util.Mth;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerAimOcclusionTest {
    @Test
    void frameBoundsOccludeOnlyRaysThatCrossThemBeforeTheTarget() {
        var frame = new ObservationValues.Aabb(-0.375D, -0.375D, 1.95D,
                0.375D, 0.375D, 2.0D);
        var eye = new Vec3(0.0D, 0.0D, 0.0D);
        assertThat(ContainerAimOcclusion.intersects(eye,
                new Vec3(0.0D, 0.0D, 2.1D), frame)).isTrue();
        assertThat(ContainerAimOcclusion.intersects(eye,
                new Vec3(0.49D, 0.49D, 2.1D), frame)).isFalse();
        assertThat(ContainerAimOcclusion.intersects(eye,
                new Vec3(0.0D, 0.0D, 1.8D), frame)).isFalse();
        assertThat(ContainerAimOcclusion.intersects(eye,
                new Vec3(0.0D, 0.0D, -2.1D), frame)).isFalse();
    }

    @Test
    void eyeInsideEntityBoundsAlsoCountsAsOccluded() {
        var bounds = new ObservationValues.Aabb(-1, -1, -1, 1, 1, 1);
        assertThat(ContainerAimOcclusion.intersects(Vec3.ZERO,
                new Vec3(3.0D, 0.0D, 0.0D), bounds)).isTrue();
    }

    @Test
    void selectionMarginRejectsARealRayThatVanillaAngleReconstructionMovesInsideAFrame() {
        var frame = new ObservationValues.Aabb(-0.375D, -0.375D, 2.0D,
                0.375D, 0.375D, 2.0625D);
        var rawBox = new AABB(frame.minX(), frame.minY(), frame.minZ(),
                frame.maxX(), frame.maxY(), frame.maxZ());
        var exactObservedRay = new Vec3(0.562515D, 0.0D, 3.0D);
        float yaw = (float) (Math.toDegrees(Math.atan2(exactObservedRay.z,
                exactObservedRay.x)) - 90.0D);
        // Entity.calculateViewVector uses float radians and Mth's trigonometric lookup table.
        float radians = -yaw * (float) (Math.PI / 180.0D);
        var vanillaRay = new Vec3(Mth.sin(radians), 0.0D, Mth.cos(radians)).scale(3.2D);

        assertThat(rawBox.clip(Vec3.ZERO, exactObservedRay)).isEmpty();
        assertThat(rawBox.clip(Vec3.ZERO, vanillaRay)).isPresent();
        assertThat(ContainerAimOcclusion.intersects(Vec3.ZERO, exactObservedRay, frame)).isTrue();
        // Ordinary centimetre-scale gaps remain usable; the guard is not a frame-wide exclusion.
        assertThat(ContainerAimOcclusion.intersects(Vec3.ZERO,
                new Vec3(0.60D, 0.0D, 3.0D), frame)).isFalse();
    }

    @Test
    void nullStateChestEdgesAreCheckedTangentiallyAndConnectedHalfInteriorRemainsUsable() {
        var dimension = new ObservationValues.ResourceId("minecraft:overworld");
        var block = new ObservationValues.BlockPosition(dimension, 171, 67, -313);
        var eye = new ObservationValues.WorldPosition(dimension, 171.5D, 65.62D, -311.0D);
        java.util.function.DoubleFunction<ObservationRecord.VisibleSurface> surface = x ->
                new ObservationRecord.VisibleSurface(block, ObservationRecord.Face.SOUTH,
                        new ObservationValues.ResourceId("minecraft:chest"),
                        ObservationRecord.ShapeClass.PARTIAL, null,
                        new ObservationValues.WorldPosition(dimension, x, 67.1D, -312.0625D),
                        eye, 1L, 0L);

        assertThat(ContainerAimOcclusion.hasSurfaceClearance(surface.apply(171.06255D))).isFalse();
        assertThat(ContainerAimOcclusion.hasSurfaceClearance(surface.apply(171.09D))).isTrue();
        // A connected half can extend outside the single-chest outline. Do not reject that area.
        assertThat(ContainerAimOcclusion.hasSurfaceClearance(surface.apply(171.02D))).isTrue();
    }
}
