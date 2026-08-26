package dev.aod.mcmcp.observation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlockPlanComparatorTest {
    @Test
    void appliesMirrorAndClockwiseRotationDeterministically() {
        var comparator = new BlockPlanComparator(nullService(), new WorldMemory());
        var plan = comparator.parse(Map.of(
                "anchor", Map.of("dimension", "minecraft:overworld", "x", 100, "y", 64, "z", 200),
                "transform", Map.of("rotation", 90, "mirror", "x"),
                "expected", List.of(Map.of(
                        "id", "step",
                        "offset", Map.of("x", 2, "y", 1, "z", 3),
                        "state", Map.of("block", "minecraft:oak_stairs", "properties", Map.of("facing", "south"))))));

        assertThat(plan.expected().getFirst().worldPosition())
                .isEqualTo(new BlockPosition("minecraft:overworld", 97, 65, 198));
        assertThat(plan.expected().getFirst().sourceState().properties())
                .containsEntry("facing", "south");
        assertThat(plan.expected().getFirst().state().properties())
                .containsEntry("facing", "west");
        assertThat(plan.hash()).isEqualTo(
                "sha256:9e7fa1e4b010a57a2f0e4f38ee2cae9dfb5a122fd4ccbe5ac8b533e7a19a81df");
    }

    @Test
    void rejectsDuplicateCoordinates() {
        var comparator = new BlockPlanComparator(nullService(), new WorldMemory());
        var item = Map.<String, Object>of(
                "id", "one",
                "offset", Map.of("x", 0, "y", 0, "z", 0),
                "state", Map.of("block", "minecraft:stone"));
        var duplicate = Map.<String, Object>of(
                "id", "two",
                "offset", Map.of("x", 0, "y", 0, "z", 0),
                "state", Map.of("block", "minecraft:stone"));

        assertThatThrownBy(() -> comparator.parse(Map.of(
                "anchor", Map.of("dimension", "minecraft:overworld", "x", 0, "y", 64, "z", 0),
                "expected", List.of(item, duplicate))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void rejectsTransformedCoordinatesOutsideAdvertisedOutputRange() {
        var comparator = new BlockPlanComparator(nullService(), new WorldMemory());

        assertThatThrownBy(() -> comparator.parse(Map.of(
                "anchor", Map.of("dimension", "minecraft:overworld", "x", 29_999_999, "y", 64, "z", 0),
                "expected", List.of(Map.of(
                        "id", "outside",
                        "offset", Map.of("x", 1, "y", 0, "z", 0),
                        "state", Map.of("block", "minecraft:stone"))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("world position");
    }

    private static MinecraftObservationService nullService() {
        return new MinecraftObservationService(new WorldMemory());
    }
}
