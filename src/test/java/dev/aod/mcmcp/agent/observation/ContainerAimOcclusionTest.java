package dev.aod.mcmcp.agent.observation;

import net.minecraft.world.phys.Vec3;
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
}
