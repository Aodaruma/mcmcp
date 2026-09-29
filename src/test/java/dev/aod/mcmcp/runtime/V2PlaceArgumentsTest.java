package dev.aod.mcmcp.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class V2PlaceArgumentsTest {
    @Test
    void singlePlacementNeedsOnlyCoordinatesAndBlock() {
        var request = V2PlaceArguments.parse(Map.of(
                "x", 8, "y", 64, "z", 9, "block", "minecraft:stone"),
                "minecraft:overworld");
        assertThat(request.region().size()).isEqualTo(1);
        assertThat(request.itemId()).isEqualTo("minecraft:stone");
        assertThat(request.properties()).isEmpty();
        assertThat(request.acceptsTarget("minecraft:air")).isTrue();
        assertThat(request.acceptsTarget("minecraft:cave_air")).isTrue();
        assertThat(request.acceptsTarget("minecraft:dirt")).isFalse();
    }

    @Test
    void signedBoxAndOptionalStateAndConditionAreRetained() {
        var request = V2PlaceArguments.parse(Map.ofEntries(
                Map.entry("x", 8), Map.entry("y", 64), Map.entry("z", 9),
                Map.entry("dx", -2), Map.entry("dz", 1),
                Map.entry("block", "minecraft:oak_stairs"),
                Map.entry("item", "minecraft:oak_stairs"),
                Map.entry("properties", Map.of("facing", "north", "half", "bottom")),
                Map.entry("replace_blocks", List.of("minecraft:air", "minecraft:short_grass")),
                Map.entry("max_blocks", 3), Map.entry("advance", true)),
                "minecraft:overworld");
        assertThat(request.region().size()).isEqualTo(6);
        assertThat(request.maxBlocks()).isEqualTo(3);
        assertThat(request.properties()).containsEntry("facing", "north");
        assertThat(request.acceptsTarget("minecraft:short_grass")).isTrue();
        assertThat(request.acceptsTarget("minecraft:stone")).isFalse();
    }

    @Test
    void rejectsInvalidBoundsConditionsAndUnknownKeys() {
        var base = Map.<String, Object>of(
                "x", 0, "y", 64, "z", 0, "block", "minecraft:stone");
        assertThatThrownBy(() -> V2PlaceArguments.parse(Map.of(
                "x", 0, "y", 64, "z", 0, "block", "minecraft:stone", "dx", 4096),
                "minecraft:overworld")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> V2PlaceArguments.parse(Map.of(
                "x", 0, "y", 64, "z", 0, "block", "minecraft:stone",
                "replace_blocks", List.of("minecraft:air", "minecraft:air")),
                "minecraft:overworld")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> V2PlaceArguments.parse(Map.of(
                "x", 0, "y", 64, "z", 0, "block", "minecraft:stone",
                "properties", Map.of("facing", "NORTH")),
                "minecraft:overworld")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> V2PlaceArguments.parse(Map.of(
                "x", 0, "y", 64, "z", 0, "block", "example:other"),
                "minecraft:overworld")).isInstanceOf(IllegalArgumentException.class);
        var extra = new java.util.LinkedHashMap<>(base);
        extra.put("command", "op");
        assertThatThrownBy(() -> V2PlaceArguments.parse(extra, "minecraft:overworld"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
