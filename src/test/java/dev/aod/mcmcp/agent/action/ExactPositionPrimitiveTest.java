package dev.aod.mcmcp.agent.action;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ExactPositionPrimitiveTest {
    @Test void arrivalUsesTheRequestedFeetPositionRatherThanItsCellCenter() {
        var target = new Vec3(-1.85, 64.5, 2.15);
        assertThat(ExactPositionPrimitive.reached(new Vec3(-1.5, 64, 2.5), target, 0.1, 0)).isFalse();
        assertThat(ExactPositionPrimitive.reached(new Vec3(-1.82, 64.5, 2.17), target, 0.1, 0)).isTrue();
        assertThat(ExactPositionPrimitive.reached(new Vec3(-1.85, 64.71, 2.15), target, 0.1, 0)).isFalse();
    }

    @Test void boundedFlightPulsesReachTheFractionalTargetFromObservedOscillationPositions() {
        var target = new Vec3(308.75, 103.6, 305.25);
        for (var initial : java.util.List.of(new Vec3(308.7971, 103.48, 305.3922),
                new Vec3(308.7972, 103.6039, 305.3666))) {
            for (double impulse : new double[] {0.05, 0.10, 0.15}) {
                var current = initial;
                for (int tick = 0; tick < 20 && !ExactPositionPrimitive.reached(current, target, 0.1, 0); tick++) {
                    assertThat(ExactPositionPrimitive.fineFlightApproach(current, target)).isTrue();
                    var keys = MinecraftActionPrimitiveExecutor.steering(current.x, current.z, -90,
                            target.x, target.z, 0.1);
                    current = current.add(MinecraftActionPrimitiveExecutor.commandDirection(-90, keys).scale(impulse));
                }
                assertThat(ExactPositionPrimitive.reached(current, target, 0.1, 0)).isTrue();
            }
        }
        assertThat(ExactPositionPrimitive.fineFlightApproach(target.add(1, 0, 0), target)).isFalse();
    }
}
