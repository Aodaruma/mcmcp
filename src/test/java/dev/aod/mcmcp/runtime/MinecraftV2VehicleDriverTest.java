package dev.aod.mcmcp.runtime;

import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MinecraftV2VehicleDriverTest {
    @Test
    void quarterSpeedPulsesSettleAcrossVanillaHorseSpeedsWithoutTeleportingOrErasingMomentum() {
        for (double movementSpeed : new double[]{0.1125, 0.225, 0.3375}) {
            double position = 0.8, velocity = -0.3;
            int stableTicks = 0;
            for (int tick = 0; tick < 180 && stableTicks < 4; tick++) {
                boolean near = Math.abs(position) <= 0.1;
                stableTicks = near && Math.abs(velocity) < 0.025 ? stableTicks + 1 : 0;
                if (!near && MinecraftV2VehicleDriver.horseFinePulse(Math.abs(velocity), tick))
                    velocity -= Math.signum(position) * movementSpeed * 0.25;
                position += velocity;
                velocity *= 0.546;
            }
            assertThat(stableTicks).as("movement speed %s", movementSpeed).isEqualTo(4);
            assertThat(Math.abs(position)).isLessThanOrEqualTo(0.1);
        }
    }
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
