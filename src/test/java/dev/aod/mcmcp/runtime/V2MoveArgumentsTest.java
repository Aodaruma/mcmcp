package dev.aod.mcmcp.runtime;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class V2MoveArgumentsTest {
    @Test
    void acceptsUnobservedCoordinatesAndFiniteJobBounds() {
        var request = V2MoveArguments.parse(Map.of(
                "x", 100_000, "y", 64, "z", -100_000,
                "max_ticks", 900, "max_distance", 120.0D), "minecraft:overworld");
        assertThat(request.goal().x()).isEqualTo(100_000);
        assertThat(request.goal().z()).isEqualTo(-100_000);
        assertThat(request.maxTicks()).isEqualTo(900);
        assertThat(request.maxDistance()).isEqualTo(120.0D);
        assertThat(request.tolerance()).isEqualTo(0.25D);
    }

    @Test
    void rejectsMissingCoordinatesUnboundedMotionAndExtraKeys() {
        assertThatThrownBy(() -> V2MoveArguments.parse(
                Map.of("x", 1, "y", 64), "minecraft:overworld"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> V2MoveArguments.parse(
                Map.of("x", 1, "y", 64, "z", 0, "max_distance", 257),
                "minecraft:overworld"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> V2MoveArguments.parse(
                Map.of("x", 1, "y", 64, "z", 0, "command", "op"),
                "minecraft:overworld"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> V2MoveArguments.parse(
                Map.of("x", 1, "y", 64, "z", 0, "tolerance", 0.5D),
                "minecraft:overworld"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
