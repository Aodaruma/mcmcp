package dev.aodaruma.craftagent.observation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlockPlanParserTest {
    @Test
    void exposesSourceAndNormalizedStateForFutureApplyReuse() {
        BlockPlan plan = BlockPlan.parse(Map.of(
                "anchor", Map.of(
                        "dimension", "minecraft:overworld", "x", 4, "y", 70, "z", 9),
                "transform", Map.of("rotation", 270, "mirror", "z"),
                "expected", List.of(Map.of(
                        "id", "hopper",
                        "offset", Map.of("x", 1, "y", 0, "z", 2),
                        "state", Map.of(
                                "block", "minecraft:hopper",
                                "properties", Map.of("facing", "north", "enabled", "true"))))));

        var expected = plan.requireExpected("hopper");
        assertThat(expected.rawOffset()).isEqualTo(new BlockPlan.Offset(1, 0, 2));
        assertThat(expected.transformedOffset()).isEqualTo(new BlockPlan.Offset(-2, 0, -1));
        assertThat(expected.sourceState().properties())
                .containsEntry("facing", "north")
                .containsEntry("enabled", "true");
        assertThat(expected.state().properties())
                .containsEntry("facing", "east")
                .containsEntry("enabled", "true");
        assertThat(expected.worldPosition())
                .isEqualTo(new BlockPosition("minecraft:overworld", 2, 70, 8));
    }

    @Test
    void rejectsUnknownRuntimeBlockInsteadOfTreatingItAsAnOpaqueGuess() {
        assertThatThrownBy(() -> BlockPlan.parse(Map.of(
                "anchor", Map.of(
                        "dimension", "minecraft:overworld", "x", 0, "y", 64, "z", 0),
                "expected", List.of(Map.of(
                        "id", "unknown",
                        "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "state", Map.of("block", "missing:block"))))))
                .isInstanceOfSatisfying(BlockPlanValidationException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("unknown_block");
                    assertThat(failure.path()).isEqualTo("expected[0].state.block");
                });
    }

    @Test
    void matchingUsesNormalizedSubsetWithoutRequiringOmittedProperties() {
        var expected = BlockPlan.parse(Map.of(
                "anchor", Map.of(
                        "dimension", "minecraft:overworld", "x", 0, "y", 64, "z", 0),
                "transform", Map.of("rotation", 90, "mirror", "none"),
                "expected", List.of(Map.of(
                        "id", "stairs",
                        "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "state", Map.of(
                                "block", "minecraft:oak_stairs",
                                "properties", Map.of("facing", "north"))))))
                .expected().getFirst();

        assertThat(expected.matches(new BlockStateView(
                "minecraft:oak_stairs",
                Map.of(
                        "facing", "east",
                        "half", "bottom",
                        "shape", "straight",
                        "waterlogged", "false"))))
                .isTrue();
        assertThat(expected.matches(new BlockStateView(
                "minecraft:oak_stairs",
                Map.of("facing", "west"))))
                .isFalse();
    }
}
