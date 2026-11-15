package dev.aod.mcmcp.runtime;

import org.junit.jupiter.api.Test;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import static org.assertj.core.api.Assertions.assertThat;

class ObservedEntityObstaclesTest {
    private final AABB trader = new AABB(310.55, 121, 310.2, 311.15, 122.9, 310.8);

    @Test void bodyBlocksDirectApproachButLeavesTheSideRouteOpen() {
        assertThat(blocks(new Vec3(309.5, 121, 310.5), new Vec3(310.5, 121, 310.5))).isTrue();
        assertThat(blocks(new Vec3(309.5, 121, 310.5), new Vec3(309.5, 121, 311.5))).isFalse();
        assertThat(blocks(new Vec3(309.5, 121, 311.5), new Vec3(310.5, 121, 311.5))).isFalse();
    }
    @Test void contactAllowsEscapingButNotWalkingThroughTheMob() {
        assertThat(blocks(new Vec3(310.5, 121, 310.5), new Vec3(309.5, 121, 310.5))).isFalse();
        assertThat(blocks(new Vec3(310.5, 121, 310.5), new Vec3(311.5, 121, 310.5))).isTrue();
        assertThat(blocks(new Vec3(310.5, 121, 310.5), new Vec3(312.5, 121, 310.5))).isTrue();
        assertThat(blocks(new Vec3(309.5, 124, 310.5), new Vec3(310.5, 124, 310.5))).isFalse();
    }
    private boolean blocks(Vec3 from, Vec3 to) { return ObservedEntityObstacles.blocks(trader, from, to, 0.8, 1.8); }
}
