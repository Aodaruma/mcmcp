package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.agent.navigation.NavCell;
import dev.aod.mcmcp.agent.safety.Locomotion;
import dev.aod.mcmcp.routine.MovementInputLease.MovementKey;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class FlightNavigationTest {
    @Test void onlyCreativeAndSpectatorWithServerFlightPermissionCanUseAirRoutes() {
        assertThat(Locomotion.aerialMode(true, false, true)).isEqualTo(Locomotion.FLIGHT);
        assertThat(Locomotion.aerialMode(false, true, true)).isEqualTo(Locomotion.SPECTATOR);
        assertThat(Locomotion.aerialMode(true, false, false)).isEqualTo(Locomotion.GROUND);
        assertThat(Locomotion.aerialMode(false, true, false)).isEqualTo(Locomotion.GROUND);
        assertThat(Locomotion.aerialMode(false, false, true)).isEqualTo(Locomotion.GROUND);
    }

    @Test void ascentPulsesNeverToggleOffVanillaFlight() {
        var sequence = new FlightInputSequence();
        var jump = Set.of(MovementKey.JUMP, MovementKey.FORWARD);
        assertThat(sequence.apply(jump, 10)).contains(MovementKey.JUMP);
        assertThat(sequence.apply(jump, 11)).contains(MovementKey.JUMP);
        assertThat(sequence.apply(Set.of(MovementKey.CROUCH), 12)).containsExactly(MovementKey.CROUCH);
        for (long tick = 13; tick < 18; tick++)
            assertThat(sequence.apply(jump, tick)).containsExactly(MovementKey.FORWARD);
        assertThat(sequence.apply(jump, 18)).contains(MovementKey.JUMP);
    }

    @Test void ascentAndDescentBrakeBeforePassingTheRequestedHeight() {
        for (var mode : Set.of(Locomotion.FLIGHT, Locomotion.SPECTATOR)) {
            assertThat(MinecraftActionPrimitiveExecutor.withVerticalInput(Set.of(), 1, 1, .6, mode, false, 0))
                    .containsExactly(MovementKey.JUMP);
            assertThat(MinecraftActionPrimitiveExecutor.withVerticalInput(Set.of(), -1, -1, .6, mode, false, 0))
                    .containsExactly(MovementKey.CROUCH);
            assertThat(MinecraftActionPrimitiveExecutor.withVerticalInput(Set.of(), 1, .1, .6, mode, false, .2))
                    .containsExactly(MovementKey.CROUCH);
            assertThat(MinecraftActionPrimitiveExecutor.withVerticalInput(Set.of(), -1, -.1, .6, mode, false, -.2))
                    .containsExactly(MovementKey.JUMP);
            assertThat(MinecraftActionPrimitiveExecutor.requiresNavigationMovementSafety(mode, 0)).isTrue();
        }
    }

    @Test void aerialArrivalRequiresHeightAndHorizontalTolerance() {
        var target = new NavCell("minecraft:overworld", -2, 100, -3);
        assertThat(MinecraftActionPrimitiveExecutor.aerialWaypointReached(new Vec3(-1.5, 100.125, -2.5), target, .25)).isTrue();
        assertThat(MinecraftActionPrimitiveExecutor.aerialWaypointReached(new Vec3(-1.5, 100.8, -2.5), target, .25)).isFalse();
        assertThat(MinecraftActionPrimitiveExecutor.aerialWaypointReached(new Vec3(-1.1, 100.25, -2.5), target, .25)).isFalse();
    }

    @Test void vanillaFlightImpulsesSettleBetweenDiscreteHeightsWithoutOscillating() {
        for (double target : new double[] {.2, 1.2, 1., -.8, -1., 4.2}) {
            double position = 0, velocity = 0;
            int stable = 0;
            var sequence = new FlightInputSequence();
            for (int tick = 0; tick < 80 && stable < 3; tick++) {
                var keys = sequence.apply(MinecraftActionPrimitiveExecutor.withVerticalInput(Set.of(),
                        Double.compare(target, position), target - position, .6,
                        Locomotion.FLIGHT, false, velocity), tick);
                velocity += keys.contains(MovementKey.JUMP) ? .15 : keys.contains(MovementKey.CROUCH) ? -.15 : 0;
                if (Math.abs(velocity) < .003) velocity = 0;
                position += velocity;
                velocity *= .6;
                stable = Math.abs(target - position) <= .19 && Math.abs(velocity) < .02 ? stable + 1 : 0;
            }
            assertThat(stable).as("vanilla impulse/drag, height %s", target).isEqualTo(3);
        }
    }
}
