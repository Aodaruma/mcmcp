package dev.aod.mcmcp.routine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnownConstructionRequestTest {
    private static final String DIMENSION = "minecraft:overworld";
    private static final BlockStateFingerprint AIR =
            new BlockStateFingerprint("minecraft:air", Map.of());
    private static final BlockStateFingerprint STONE =
            new BlockStateFingerprint("minecraft:stone", Map.of());

    @Test
    void acceptsVisibleAndEarlierConfirmedExactSupports() {
        var first = target(0, 65, 0);
        var ground = target(0, 64, 0);
        var second = target(0, 66, 0);
        var entries = List.of(
                step("base", first, PlacementSupportWitness.visible(ground, "up", STONE)),
                step("top", second, PlacementSupportWitness.confirmedDependency(
                        first, "up", STONE, "base")));

        var request = new KnownConstructionRequest(
                "copy", entries, bounds(target(0, 64, 0), target(0, 66, 0)));

        assertThat(request.entries()).containsExactlyElementsOf(entries);
        assertThat(request.requiredResources()).containsEntry("minecraft:stone", 2);
    }

    @Test
    void rejectsUnsafePartialNonAdjacentAndForwardDependencyPlans() {
        var first = target(0, 65, 0);
        var ground = target(0, 64, 0);
        var bounds = bounds(target(0, 63, 0), target(2, 67, 2));

        assertThatThrownBy(() -> new KnownConstructionRequest(
                "copy",
                List.of(new ApplyBlockPlanStep(
                        "log", ApplyBlockPlanOperation.PLACE, first, AIR,
                        new BlockStateFingerprint("minecraft:oak_log", Map.of()),
                        Optional.of("minecraft:oak_log"),
                        Optional.of(PlacementSupportWitness.visible(ground, "up", STONE)))),
                bounds)).isInstanceOf(
                        SafeConstructionBlockPolicy.UnsafeConstructionBlockException.class);

        assertThatThrownBy(() -> new KnownConstructionRequest(
                "copy",
                List.of(step("base", first,
                        PlacementSupportWitness.visible(target(2, 64, 0), "up", STONE))),
                bounds)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("point to");

        var lowerDoor = new BlockStateFingerprint("minecraft:oak_door", Map.of(
                "facing", "east", "half", "lower", "hinge", "right",
                "open", "false", "powered", "false"));
        assertThatThrownBy(() -> new KnownConstructionRequest(
                "copy",
                List.of(step("base", first,
                        PlacementSupportWitness.visible(ground, "up", lowerDoor))),
                bounds)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("support");

        assertThatThrownBy(() -> new KnownConstructionRequest(
                "copy",
                List.of(
                        step("base", first, PlacementSupportWitness.confirmedDependency(
                                ground, "up", STONE, "later")),
                        step("later", target(0, 66, 0),
                                PlacementSupportWitness.confirmedDependency(
                                        first, "up", STONE, "base"))),
                bounds)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("earlier");
    }

    @Test
    void rejectsMoreThanEightEntriesAndUnmarkedBreakOperation() {
        var entries = new ArrayList<ApplyBlockPlanStep>();
        for (int index = 0; index < 9; index++) {
            entries.add(step("cell" + index, target(index, 65, 0),
                    PlacementSupportWitness.visible(target(index, 64, 0), "up", STONE)));
        }
        assertThatThrownBy(() -> new KnownConstructionRequest(
                "copy", entries, bounds(target(0, 64, 0), target(8, 65, 0))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 8");

        var breakBounds = new ActionBounds(
                DIMENSION, target(0, 64, 0), target(0, 65, 0), 0, 15, true);
        var breakStep = new ApplyBlockPlanStep(
                "break", ApplyBlockPlanOperation.BREAK_TO_AIR, target(0, 65, 0),
                STONE, AIR, Optional.empty());
        assertThatThrownBy(() -> new KnownConstructionRequest(
                new ApplyBlockPlanRequest("copy", 1, 1, List.of(breakStep), breakBounds)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsHomogeneousSafeConstructionClear() {
        var breakBounds = new ActionBounds(
                DIMENSION, target(0, 65, 0), target(0, 65, 0), 0, 15, true);
        var breakStep = new ApplyBlockPlanStep(
                "break", ApplyBlockPlanOperation.BREAK_TO_AIR, target(0, 65, 0),
                STONE, AIR, Optional.empty());

        var request = new KnownConstructionRequest(new ApplyBlockPlanRequest(
                "clear", 1, 1, List.of(breakStep), breakBounds,
                ApplyBlockPlanRequest.BreakSafety.SAFE_CONSTRUCTION_BLOCK));

        assertThat(request.breakOnly()).isTrue();
        assertThat(request.requiredResources()).isEmpty();

        var door = new BlockStateFingerprint("minecraft:oak_door", Map.of(
                "facing", "east", "half", "lower", "hinge", "right",
                "open", "false", "powered", "false"));
        var doorBreak = new ApplyBlockPlanStep(
                "door", ApplyBlockPlanOperation.BREAK_TO_AIR, target(0, 65, 0),
                door, AIR, Optional.empty());
        assertThatThrownBy(() -> new KnownConstructionRequest(new ApplyBlockPlanRequest(
                "clear", 1, 1, List.of(doorBreak), breakBounds,
                ApplyBlockPlanRequest.BreakSafety.SAFE_CONSTRUCTION_BLOCK)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("multi-cell doors");
    }

    @Test
    void acceptsOneLowerDoorPlacementWithAnExplicitSupport() {
        var door = new BlockStateFingerprint("minecraft:oak_door", Map.of(
                "facing", "east", "half", "lower", "hinge", "right",
                "open", "false", "powered", "false"));
        var ground = target(0, 64, 0);
        var placed = target(0, 65, 0);
        var step = new ApplyBlockPlanStep(
                "door", ApplyBlockPlanOperation.PLACE, placed, AIR, door,
                Optional.of("minecraft:oak_door"),
                Optional.of(PlacementSupportWitness.visible(ground, "up", STONE)));

        assertThat(new KnownConstructionRequest(
                "door", List.of(step), bounds(ground, target(0, 66, 0))).requiredResources())
                .containsEntry("minecraft:oak_door", 1);
    }

    private static ApplyBlockPlanStep step(
            String id, BlockTarget target, PlacementSupportWitness support) {
        return new ApplyBlockPlanStep(
                id, ApplyBlockPlanOperation.PLACE, target, AIR, STONE,
                Optional.of("minecraft:stone"), Optional.of(support));
    }

    private static ActionBounds bounds(BlockTarget minimum, BlockTarget maximum) {
        return new ActionBounds(DIMENSION, minimum, maximum, 0, 120, false);
    }

    private static BlockTarget target(int x, int y, int z) {
        return new BlockTarget(DIMENSION, x, y, z);
    }
}
