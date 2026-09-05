package dev.aod.mcmcp.routine;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerAimGateTest {
    private static final BlockPos TARGET = new BlockPos(164, 67, -300);
    private static final Vec3 POINT = new Vec3(164.5D, 67.5D, -300.0D);

    @Test
    void entityInFrontNeverAuthorizesUseAndStopsAfterOneBoundedWait() {
        var gate = new ContainerAimGate();
        HitResult entity = new HitResult(POINT) {
            @Override public Type getType() { return Type.ENTITY; }
        };
        assertThat(gate.observe(10L, true, entity, TARGET))
                .isEqualTo(ContainerAimGate.Decision.WAIT);
        assertThat(gate.observe(49L, true, entity, TARGET))
                .isEqualTo(ContainerAimGate.Decision.WAIT);
        assertThat(gate.observe(50L, true, entity, TARGET))
                .isEqualTo(ContainerAimGate.Decision.OCCLUDED);
        assertThat(gate.observe(200L, true, entity, TARGET))
                .isEqualTo(ContainerAimGate.Decision.OCCLUDED);
    }

    @Test
    void cameraTravelDoesNotConsumeTheCrosshairWaitAndAnOldHitCannotOpenImmediately() {
        var gate = new ContainerAimGate();
        var hit = new BlockHitResult(POINT, Direction.NORTH, TARGET, false);
        assertThat(gate.observe(10L, false, hit, TARGET))
                .isEqualTo(ContainerAimGate.Decision.WAIT);
        assertThat(gate.observe(100L, true, hit, TARGET))
                .isEqualTo(ContainerAimGate.Decision.WAIT);
        assertThat(gate.observe(100L, true, hit, TARGET))
                .isEqualTo(ContainerAimGate.Decision.WAIT);
        assertThat(gate.observe(101L, true, hit, TARGET))
                .isEqualTo(ContainerAimGate.Decision.OPEN);
    }

    @Test
    void staleMissOrNeighborCanRecoverOnlyToTheExactNormalBlockHit() {
        var gate = new ContainerAimGate();
        var miss = BlockHitResult.miss(POINT, Direction.NORTH, TARGET);
        var neighbor = new BlockHitResult(POINT, Direction.NORTH, TARGET.east(), false);
        assertThat(gate.observe(10L, true, miss, TARGET))
                .isEqualTo(ContainerAimGate.Decision.WAIT);
        assertThat(gate.observe(11L, true, neighbor, TARGET))
                .isEqualTo(ContainerAimGate.Decision.WAIT);
        assertThat(gate.observe(12L, true, null, TARGET))
                .isEqualTo(ContainerAimGate.Decision.WAIT);
        assertThat(gate.observe(13L, true,
                new BlockHitResult(POINT, Direction.NORTH, TARGET, false), TARGET))
                .isEqualTo(ContainerAimGate.Decision.OPEN);
    }
}
