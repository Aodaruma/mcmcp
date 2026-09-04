package dev.aod.mcmcp.observation;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlockPlanStateTransformerTest {
    @Test
    void rotatesAxisAndSixteenStepRotationThroughVanillaStateRules() {
        var quarterTurn = new BlockPlan.Transform(90, "none");

        assertThat(transform("minecraft:oak_log", Map.of("axis", "x"), quarterTurn))
                .containsEntry("axis", "z");
        assertThat(transform("minecraft:oak_sign", Map.of("rotation", "0"), quarterTurn))
                .containsEntry("rotation", "4");
        assertThat(transform("minecraft:rail", Map.of("shape", "north_south"), quarterTurn))
                .containsEntry("shape", "east_west");
    }

    @Test
    void mirrorsHandedDoorAndStairPropertiesWhenFacingMakesResultUnambiguous() {
        var mirrorX = new BlockPlan.Transform(0, "x");

        assertThat(transform(
                "minecraft:oak_door",
                Map.of("facing", "south", "hinge", "left"),
                mirrorX))
                .containsEntry("facing", "south")
                .containsEntry("hinge", "right");
        assertThat(transform(
                "minecraft:oak_stairs",
                Map.of("facing", "east", "shape", "inner_left"),
                mirrorX))
                .containsEntry("facing", "west")
                .containsEntry("shape", "inner_right");
    }

    @Test
    void rejectsAPropertyProjectionWhenOmittedStateWouldChangeItsResult() {
        var transform = new BlockPlan.Transform(0, "x");

        assertThatThrownBy(() -> BlockPlanStateTransformer.requireUniqueProjection(
                "minecraft:oak_stairs",
                transform,
                "state",
                java.util.List.of(
                        Map.of("shape", "inner_left"),
                        Map.of("shape", "inner_right"))))
                .isInstanceOfSatisfying(BlockPlanValidationException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("ambiguous_state_transform");
                    assertThat(failure.path()).isEqualTo("state.properties");
                    assertThat(failure.details())
                            .containsEntry("block", "minecraft:oak_stairs")
                            .containsEntry("mirror", "x");
                });
    }

    @Test
    void rejectsUnknownPropertiesAndValuesWithStructuredPaths() {
        assertThatThrownBy(() -> transform(
                "minecraft:oak_stairs",
                Map.of("imaginary", "north"),
                new BlockPlan.Transform(0, "none")))
                .isInstanceOfSatisfying(BlockPlanValidationException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("unknown_block_property");
                    assertThat(failure.path()).isEqualTo("state.properties.imaginary");
                });

        assertThatThrownBy(() -> transform(
                "minecraft:oak_stairs",
                Map.of("facing", "up"),
                new BlockPlan.Transform(0, "none")))
                .isInstanceOfSatisfying(BlockPlanValidationException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("invalid_block_property_value");
                    assertThat(failure.path()).isEqualTo("state.properties.facing");
                });
    }

    @Test
    void transformsOnlyCompleteStatesForPlanApplication() {
        var transformedLog = BlockPlanStateTransformer.transformFull(
                new BlockStateView("minecraft:oak_log", Map.of("axis", "x")),
                new BlockPlan.Transform(90, "none"),
                "expected[0].state");

        assertThat(transformedLog.properties()).containsExactlyEntriesOf(Map.of("axis", "z"));

        var transformedAir = BlockPlanStateTransformer.transformFull(
                new BlockStateView("minecraft:air", Map.of()),
                new BlockPlan.Transform(270, "z"));
        assertThat(transformedAir)
                .isEqualTo(new BlockStateView("minecraft:air", Map.of()));
    }

    @Test
    void fullTransformRejectsOmittedRegisteredPropertiesWithoutSupplyingDefaults() {
        assertThatThrownBy(() -> BlockPlanStateTransformer.transformFull(
                new BlockStateView("minecraft:oak_door", Map.of("facing", "north")),
                new BlockPlan.Transform(0, "none"),
                "expected[2].state"))
                .isInstanceOfSatisfying(BlockPlanValidationException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("incomplete_block_state");
                    assertThat(failure.path()).isEqualTo("expected[2].state.properties");
                    assertThat(failure.details())
                            .containsEntry("block", "minecraft:oak_door")
                            .containsEntry("actual_property_count", 1);
                    assertThat((String) failure.details().get("missing_properties"))
                            .contains("hinge", "half", "open", "powered");
                });
    }

    @Test
    void fourQuarterTurnsAndTwoMirrorsRestoreExplicitConstraints() {
        var original = new BlockStateView(
                "minecraft:oak_stairs",
                Map.of("facing", "east", "shape", "outer_right", "half", "top"));
        var rotated = original;
        for (int count = 0; count < 4; count++) {
            rotated = BlockPlanStateTransformer.transform(
                    rotated, new BlockPlan.Transform(90, "none"));
        }
        assertThat(rotated).isEqualTo(original);

        var mirrored = BlockPlanStateTransformer.transform(
                original, new BlockPlan.Transform(0, "z"));
        mirrored = BlockPlanStateTransformer.transform(
                mirrored, new BlockPlan.Transform(0, "z"));
        assertThat(mirrored).isEqualTo(original);
    }

    @Test
    void transformsCompleteStraightInnerAndOuterStairsWithExactHandedness() {
        Map<String, String> straight = Map.of(
                "facing", "north", "half", "bottom", "shape", "straight",
                "waterlogged", "false");
        Map<String, String> inner = Map.of(
                "facing", "north", "half", "bottom", "shape", "inner_left",
                "waterlogged", "false");
        Map<String, String> outer = Map.of(
                "facing", "north", "half", "bottom", "shape", "outer_left",
                "waterlogged", "false");

        assertThat(transformFull(straight, new BlockPlan.Transform(90, "x")))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "facing", "east", "half", "bottom", "shape", "straight",
                        "waterlogged", "false"));
        assertThat(transformFull(inner, new BlockPlan.Transform(90, "x")))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "facing", "east", "half", "bottom", "shape", "inner_right",
                        "waterlogged", "false"));
        assertThat(transformFull(outer, new BlockPlan.Transform(90, "x")))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "facing", "east", "half", "bottom", "shape", "outer_right",
                        "waterlogged", "false"));
    }

    private static Map<String, String> transform(
            String block,
            Map<String, String> properties,
            BlockPlan.Transform transform) {
        return BlockPlanStateTransformer.transform(
                new BlockStateView(block, properties), transform).properties();
    }

    private static Map<String, String> transformFull(
            Map<String, String> properties, BlockPlan.Transform transform) {
        return BlockPlanStateTransformer.transformFull(
                new BlockStateView("minecraft:oak_stairs", properties), transform).properties();
    }
}
