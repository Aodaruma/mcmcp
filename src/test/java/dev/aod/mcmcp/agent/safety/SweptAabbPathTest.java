package dev.aod.mcmcp.agent.safety;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SweptAabbPathTest {
    private static final AABB UNIT = new AABB(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D);

    @Test
    void tieUsesXBeforeZAndEachSweepStartsAtPriorEndpoint() {
        var segments = SweptAabbPath.segments(
                UNIT,
                new Vec3(1.0D, 0.0D, 1.0D),
                new Vec3(0.75D, 0.0D, 0.5D));

        assertThat(segments).extracting(SweptAabbPath.AxisSegment::axis)
                .containsExactly(SweptAabbPath.Axis.X, SweptAabbPath.Axis.Z);
        assertThat(segments.get(0).start()).isEqualTo(UNIT);
        assertThat(segments.get(1).start()).isEqualTo(segments.get(0).end());
        assertThat(segments.get(1).end()).isEqualTo(UNIT.move(0.75D, 0.0D, 0.5D));
    }

    @Test
    void largerZUsesZBeforeXAfterY() {
        assertThat(SweptAabbPath.axisOrder(new Vec3(0.25D, 0.4D, -0.75D)))
                .containsExactly(
                        SweptAabbPath.Axis.Y,
                        SweptAabbPath.Axis.Z,
                        SweptAabbPath.Axis.X);
    }

    @Test
    void diagonalEnvelopeCornerIsNotPartOfAnyAxisSweep() {
        var delta = new Vec3(1.0D, 0.0D, 1.0D);
        var envelopeOnlyCorner = new AABB(
                0.1D, 0.1D, 1.5D,
                0.2D, 0.2D, 1.6D);

        assertThat(UNIT.expandTowards(delta).intersects(envelopeOnlyCorner)).isTrue();
        assertThat(SweptAabbPath.segments(UNIT, delta, delta))
                .noneMatch(segment -> segment.swept().intersects(envelopeOnlyCorner));
    }

    @Test
    void negativeResolvedComponentsExpandInTheirOwnDirection() {
        var segments = SweptAabbPath.segments(
                UNIT,
                new Vec3(-1.0D, 0.0D, -0.5D),
                new Vec3(-0.75D, 0.0D, -0.25D));

        assertThat(segments.get(0).swept().minX).isEqualTo(-0.75D);
        assertThat(segments.get(1).swept().minZ).isEqualTo(-0.25D);
        assertThat(segments.get(1).end()).isEqualTo(UNIT.move(-0.75D, 0.0D, -0.25D));
    }
}
