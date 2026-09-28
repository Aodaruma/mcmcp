package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.navigation.NavCell;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class V2MoveArgumentsTest {
    private static final NavCell ORIGIN = new NavCell("minecraft:overworld", 10, 64, -20);

    @Test
    void acceptsUnobservedCoordinatesAndFiniteJobBounds() {
        var request = V2MoveArguments.parse(Map.of(
                "x", 100_000, "y", 64, "z", -100_000,
                "max_ticks", 900, "max_distance", 120.0D), ORIGIN);
        assertThat(request.goal().x()).isEqualTo(100_000);
        assertThat(request.goal().z()).isEqualTo(-100_000);
        assertThat(request.maxTicks()).isEqualTo(900);
        assertThat(request.maxDistance()).isEqualTo(120.0D);
        assertThat(request.tolerance()).isEqualTo(0.25D);
        assertThat(request.arrivalRadius()).isZero();
    }

    @Test
    void acceptsBoundedArrivalRadiusAroundAnUnobservedCoordinate() {
        var request = V2MoveArguments.parse(Map.of(
                "x", 20, "y", 64, "z", -20, "arrival_radius", 2.0D), ORIGIN);
        assertThat(request.arrivalRadius()).isEqualTo(2.0D);
        assertThatThrownBy(() -> V2MoveArguments.parse(Map.of(
                "x", 20, "y", 64, "z", -20, "arrival_radius", 17.0D), ORIGIN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> V2MoveArguments.parse(Map.of(
                "x", 20, "y", 64, "z", -20, "arrival_radius", -1.0D), ORIGIN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolvesCompassAndVerticalDistancesFromCurrentPlayerCell() {
        assertThat(V2MoveArguments.parse(Map.of(
                "direction", "northeast", "distance", 5), ORIGIN).goal())
                .isEqualTo(new NavCell("minecraft:overworld", 15, 64, -25));
        assertThat(V2MoveArguments.parse(Map.of(
                "direction", "up", "distance", 3), ORIGIN).goal())
                .isEqualTo(new NavCell("minecraft:overworld", 10, 67, -20));
    }

    @Test
    void rejectsMixedOrIncompleteGoalsUnboundedMotionAndExtraKeys() {
        assertThatThrownBy(() -> V2MoveArguments.parse(
                Map.of("x", 1, "y", 64), ORIGIN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> V2MoveArguments.parse(
                Map.of("x", 1, "y", 64, "z", 0, "max_distance", 257),
                ORIGIN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> V2MoveArguments.parse(
                Map.of("x", 1, "y", 64, "z", 0, "command", "op"),
                ORIGIN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> V2MoveArguments.parse(
                Map.of("x", 1, "y", 64, "z", 0, "tolerance", 0.5D),
                ORIGIN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> V2MoveArguments.parse(
                Map.of("x", 1, "y", 64, "z", 0,
                        "direction", "north", "distance", 2), ORIGIN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> V2MoveArguments.parse(
                Map.of("direction", "north"), ORIGIN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> V2MoveArguments.parse(
                Map.of("direction", "forward", "distance", 2), ORIGIN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> V2MoveArguments.parse(
                Map.of("direction", "east", "distance", 257), ORIGIN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> V2MoveArguments.parse(
                Map.of("direction", "east", "distance", 1),
                new NavCell("minecraft:overworld", Integer.MAX_VALUE, 64, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
