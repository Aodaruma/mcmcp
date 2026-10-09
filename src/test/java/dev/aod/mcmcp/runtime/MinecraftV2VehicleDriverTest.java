package dev.aod.mcmcp.runtime;

import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MinecraftV2VehicleDriverTest {
    @Test
    void stationaryGroundGravityIsIgnoredButAirborneOrHorizontalMotionIsNot() {
        var gravity = new net.minecraft.world.phys.Vec3(0, -0.0784, 0);
        assertThat(MinecraftV2VehicleDriver.motionSpeedSquared(gravity, true)).isZero();
        assertThat(MinecraftV2VehicleDriver.motionSpeedSquared(gravity, false)).isGreaterThan(0.0025);
        assertThat(MinecraftV2VehicleDriver.motionSpeedSquared(new net.minecraft.world.phys.Vec3(0.1, -0.0784, 0), true))
                .isGreaterThan(0.0025);
        assertThat(MinecraftV2VehicleDriver.motionSpeedSquared(new net.minecraft.world.phys.Vec3(0, 0.1, 0), true))
                .isGreaterThan(0.0025);
    }
    @Test
    void ridingVolumeRetainsRiderHeadAndSidesWithoutTreatingSeatOffsetAsFloorCollision() {
        var vehicle = new AABB(0, 1.0625, 0, 1, 1.7625, 1);
        var rider = new AABB(-0.1, 0.65, 0.2, 0.7, 2.45, 0.8);
        assertThat(MinecraftV2VehicleDriver.ridingVolume(vehicle, rider))
                .isEqualTo(new AABB(-0.1, 1.0625, 0, 1, 2.45, 1));
    }
}
