package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.agent.navigation.NavCell;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class V2BlockApproachTest {
    @Test
    void chestBelowRoofRequiresAnOperableFloorNotTheNearestFeetCoordinate() {
        var chest = List.of(new Vec3(97.5, 47.875, -0.5));
        assertThat(V2BlockApproach.operable(cell(99, 52, -4), 1.62, 4.5, chest, (eye, hit) -> true)).isFalse();
        assertThat(V2BlockApproach.operable(cell(96, 47, -1), 1.62, 4.5, chest, (eye, hit) -> true)).isTrue();
    }

    @Test
    void rangeIncludesFractionalSupportAndNavigationToleranceAndNativeReach() {
        var point = List.of(new Vec3(3.5, 64.5, 0.5));
        var start = cell(0, 64, 0);
        assertThat(V2BlockApproach.operable(start, 1.62, 4.5, point, (eye, hit) -> true)).isTrue();
        assertThat(V2BlockApproach.operable(start, 1.62, 3.5, point, (eye, hit) -> true)).isFalse();
        assertThat(V2BlockApproach.operable(start, 1.62, 100, List.of(new Vec3(5, 64, 0.5)), (eye, hit) -> true)).isFalse();
    }

    @Test
    void occlusionOrMissingEvidenceCannotBecomeAnInteractionEndpoint() {
        var start = cell(0, 64, 0);
        assertThat(V2BlockApproach.operable(start, 1.62, 4.5, List.of(), (eye, hit) -> true)).isFalse();
        var checks = new AtomicInteger();
        assertThat(V2BlockApproach.operable(start, 1.62, 4.5,
                List.of(new Vec3(1, 65, 0.5)), (eye, hit) -> { checks.incrementAndGet(); return false; })).isFalse();
        assertThat(checks).hasValue(1);
    }

    @Test
    void hypotheticalHighSupportDoesNotRequireLookingThroughALowCeiling() {
        assertThat(V2BlockApproach.operable(cell(0, 64, 0), 1.62, 4.5,
                List.of(new Vec3(1, 65, 0.5)), (eye, hit) -> eye.y < 66)).isTrue();
    }

    private static NavCell cell(int x, int y, int z) { return new NavCell("minecraft:overworld", x, y, z); }
}
