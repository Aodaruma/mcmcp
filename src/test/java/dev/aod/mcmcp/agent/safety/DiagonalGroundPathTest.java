package dev.aod.mcmcp.agent.safety;

import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DiagonalGroundPathTest {
    @Test
    void fourDirectionsAndNegativeCoordinatesKeepContinuousFootprintSupport() {
        for (int x : new int[]{-12, 0, 19}) {
            for (int dx : new int[]{-1, 1}) {
                for (int dz : new int[]{-1, 1}) {
                    var start = box(x + 0.5, 64, -3.5);
                    var end = start.move(dx, 0, dz);
                    var path = DiagonalGroundPath.between(start, end).orElseThrow();
                    assertThat(path.fromSupport().getX()).isEqualTo(x);
                    assertThat(path.fromSupport().getZ()).isEqualTo(-4);
                    assertThat(path.toSupport().getX()).isEqualTo(x + dx);
                    assertThat(path.toSupport().getZ()).isEqualTo(-4 + dz);
                    assertThat(path.corridor().minY).isEqualTo(64);
                }
            }
        }
    }

    @Test
    void allowsAnOffCenterStartButRejectsGapsVerticalStepsAndPartialHeightSupports() {
        var start = box(0.95, 64, 0.05);
        assertThat(DiagonalGroundPath.between(start, box(1.5, 64, 1.5))).isPresent();
        assertThat(DiagonalGroundPath.between(start, box(2.5, 64, 2.5))).isEmpty();
        assertThat(DiagonalGroundPath.between(start, box(1.5, 65, 1.5))).isEmpty();
        assertThat(DiagonalGroundPath.between(start.move(0, 0.5, 0), box(1.5, 64.5, 1.5))).isEmpty();
        assertThat(DiagonalGroundPath.between(start, box(1.5, 64, 0.5))).isEmpty();
    }

    @Test
    void rejectsFootprintsThatLoseSupportBetweenTheTwoPlatforms() {
        var tiny = new AABB(0.88, 64, 0.01, 1.0, 65.8, 0.13);
        var end = new AABB(1.44, 64, 1.44, 1.56, 65.8, 1.56);
        assertThat(DiagonalGroundPath.between(tiny, end)).isEmpty();
    }

    @Test
    void crouchingStillRequiresStandingHeadroomInTheCorridor() {
        var start = new AABB(0.2, 64, 0.2, 0.8, 65.5, 0.8);
        var corridor = DiagonalGroundPath.between(start, start.move(1, 0, 1))
                .orElseThrow().corridor();
        assertThat(corridor.maxY).isEqualTo(65.8);
        assertThat(corridor.minX).isLessThan(start.minX);
        assertThat(corridor.maxZ).isGreaterThan(start.maxZ + 1);
    }

    private static AABB box(double x, double y, double z) {
        return new AABB(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3);
    }

    @Test
    void aShortContactCrossingStillChecksBothCompleteCellCentres() {
        var path = DiagonalGroundPath.between(box(0.99, 64, 0.99), box(1.01, 64, 1.01))
                .orElseThrow();
        assertThat(path.corridor().minX).isLessThanOrEqualTo(0.2);
        assertThat(path.corridor().minZ).isLessThanOrEqualTo(0.2);
        assertThat(path.corridor().maxX).isGreaterThanOrEqualTo(1.8);
        assertThat(path.corridor().maxZ).isGreaterThanOrEqualTo(1.8);
    }
}
