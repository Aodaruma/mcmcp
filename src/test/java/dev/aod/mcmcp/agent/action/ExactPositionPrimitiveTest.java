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
}
