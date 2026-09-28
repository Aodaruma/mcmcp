package dev.aod.mcmcp.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class V2BreakArgumentsTest {
    @Test
    void singleUnknownTargetNeedsOnlyCoordinates() {
        var request = V2BreakArguments.parse(Map.of("x", 8, "y", 64, "z", 9),
                "minecraft:overworld");
        assertThat(request.region().size()).isEqualTo(1);
        assertThat(request.maxBlocks()).isEqualTo(1);
        assertThat(request.accepts("minecraft:deepslate")).isTrue();
        assertThat(request.advance()).isFalse();
    }

    @Test
    void boxConditionsAreAppliedToNewlyObservedBlocks() {
        var request = V2BreakArguments.parse(Map.of(
                "x", 8, "y", 64, "z", 9,
                "dx", -2, "dy", 0, "dz", 1,
                "include_blocks", List.of("minecraft:stone", "minecraft:deepslate"),
                "exclude_blocks", List.of("minecraft:stone"),
                "max_blocks", 3, "advance", true), "minecraft:overworld");
        assertThat(request.region().size()).isEqualTo(6);
        assertThat(request.maxBlocks()).isEqualTo(3);
        assertThat(request.accepts("minecraft:deepslate")).isTrue();
        assertThat(request.accepts("minecraft:stone")).isFalse();
        assertThat(request.accepts("minecraft:dirt")).isFalse();
    }

    @Test
    void rejectsOversizeDuplicateConditionAndUnknownProperties() {
        assertThatThrownBy(() -> V2BreakArguments.parse(Map.of(
                "x", 0, "y", 64, "z", 0, "dx", 4096), "minecraft:overworld"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> V2BreakArguments.parse(Map.of(
                "x", 0, "y", 64, "z", 0,
                "include_blocks", List.of("minecraft:stone", "minecraft:stone")),
                "minecraft:overworld"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> V2BreakArguments.parse(Map.of(
                "x", 0, "y", 64, "z", 0, "command", "op"), "minecraft:overworld"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
