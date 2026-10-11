package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.navigation.NavCell;
import dev.aod.mcmcp.routine.BlockStateFingerprint;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class V2TrialArgumentsTest {
    private static final NavCell ORIGIN = new NavCell("minecraft:overworld", 0, 64, 0);

    @Test
    void pathMutationIsOptInAndBridgeMaterialCannotEnableItImplicitly() {
        var base = Map.<String, Object>of("x", 4, "y", 64, "z", 0);
        assertThat(V2MoveArguments.parse(base, ORIGIN).clearPath()).isFalse();
        var args = new java.util.HashMap<>(base);
        args.put("bridge_block", "minecraft:cobblestone");
        assertThatThrownBy(() -> V2MoveArguments.parse(args, ORIGIN)).isInstanceOf(IllegalArgumentException.class);
        args.put("clear_path", true);
        assertThat(V2MoveArguments.parse(args, ORIGIN).bridgeBlock()).isEqualTo("minecraft:cobblestone");
        args.put("stop_when", Map.of("type", "item", "item", "minecraft:stone", "count", 3));
        assertThat(V2MoveArguments.parse(args, ORIGIN).stopWhen()).isInstanceOf(V2StopCondition.Item.class);
    }

    @Test
    void pathEditsStayInImmediateCardinalCellsTowardGoalAtTheSameHeight() {
        assertThat(MinecraftV2PathDriver.candidates(ORIGIN, new NavCell(ORIGIN.dimension(), 5, 64, 2)))
                .containsExactly(new NavCell(ORIGIN.dimension(), 1, 64, 0), new NavCell(ORIGIN.dimension(), 0, 64, 1));
        assertThat(MinecraftV2PathDriver.candidates(ORIGIN, new NavCell(ORIGIN.dimension(), 5, 65, 0))).isEmpty();
        assertThat(MinecraftV2PathDriver.candidates(ORIGIN, ORIGIN)).isEmpty();
    }

    @Test
    void unknownAndMismatchedBlocksDoNotSatisfyTheCondition() {
        var condition = V2StopCondition.parse(Map.of("type", "block", "x", 2, "y", 64, "z", 0,
                "block", "minecraft:air"));
        assertThat(condition.matches(context(null, 0, "none"))).isFalse();
        assertThat(condition.matches(context(new BlockStateFingerprint("minecraft:stone", Map.of()), 0, "none"))).isFalse();
        assertThat(condition.matches(context(new BlockStateFingerprint("minecraft:air", Map.of()), 0, "none"))).isTrue();
        var stateCondition = V2StopCondition.parse(Map.of("type", "block", "x", 2, "y", 64, "z", 0,
                "block", "minecraft:oak_fence_gate", "properties", Map.of("open", "true")));
        assertThat(stateCondition.matches(context(new BlockStateFingerprint("minecraft:oak_fence_gate", Map.of("open", "false")), 0, "none"))).isFalse();
    }

    @Test
    void itemAndScreenConditionsUseOnlySpecifiedComparison() {
        for (String comparison : List.of("at_least", "at_most", "equals")) {
            var condition = V2StopCondition.parse(Map.of("type", "item", "item", "minecraft:stone", "count", 3, "comparison", comparison));
            assertThat(condition.matches(context(null, 3, "none"))).isTrue();
            assertThat(condition.matches(context(null, 4, "none"))).isEqualTo(comparison.equals("at_least"));
            assertThat(condition.matches(context(null, 2, "none"))).isEqualTo(comparison.equals("at_most"));
        }
        var screen = V2StopCondition.parse(Map.of("type", "screen", "screen", "container"));
        assertThat(screen.matches(context(null, 0, "container"))).isTrue();
        assertThat(screen.matches(context(null, 0, "none"))).isFalse();
    }

    @Test
    void malformedOrAmbiguousConditionsAreRejectedBeforeUse() {
        for (var args : List.of(Map.of("type", "item", "item", "stone"),
                Map.of("type", "screen", "screen", "arbitrary"),
                Map.of("type", "item", "item", "minecraft:stone", "count", -1),
                Map.of("type", "position", "x", 1, "y", 64, "z", 0, "item", "minecraft:stone"),
                Map.of("type", "block", "x", 1, "y", 64, "z", 0),
                Map.of("type", "java"))) {
            assertThatThrownBy(() -> V2StopCondition.parse((Map) args)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void bedCompanionsFollowFacingAndRetainTheirOwnPart() {
        for (var direction : Map.of("north", new int[]{0, -1}, "south", new int[]{0, 1},
                "east", new int[]{1, 0}, "west", new int[]{-1, 0}).entrySet()) {
            var foot = new BlockStateFingerprint("minecraft:red_bed", Map.of("facing", direction.getKey(), "part", "foot"));
            var other = V2PlacementFootprint.companion(ORIGIN, foot, V2PlacementFootprint.Kind.BED);
            assertThat(other.position()).isEqualTo(new NavCell(ORIGIN.dimension(), direction.getValue()[0], 64, direction.getValue()[1]));
            assertThat(other.state().properties().get("part")).isEqualTo("head");
            assertThat(V2PlacementFootprint.companion(other.position(), other.state(), V2PlacementFootprint.Kind.BED).position()).isEqualTo(ORIGIN);
        }
    }

    @Test
    void doorAndPlantCompanionsAreAboveTheLowerHalfAndRejectOverflow() {
        var lower = new BlockStateFingerprint("minecraft:oak_door", Map.of("half", "lower", "facing", "east"));
        var upper = V2PlacementFootprint.companion(ORIGIN, lower, V2PlacementFootprint.Kind.DOUBLE);
        assertThat(upper.position().y()).isEqualTo(65);
        assertThat(upper.state().properties()).containsEntry("half", "upper").containsEntry("facing", "east");
        assertThatThrownBy(() -> V2PlacementFootprint.companion(new NavCell(ORIGIN.dimension(), 0, Integer.MAX_VALUE, 0),
                lower, V2PlacementFootprint.Kind.DOUBLE)).isInstanceOf(ArithmeticException.class);
        assertThat(V2PlacementFootprint.companion(ORIGIN, lower, V2PlacementFootprint.Kind.SINGLE)).isNull();
    }

    private V2StopCondition.Context context(BlockStateFingerprint block, int count, String screen) {
        return new V2StopCondition.Context() {
            public Vec3 position() { return new Vec3(0.5, 64, 0.5); }
            public BlockStateFingerprint visibleBlock(int x, int y, int z) { return block; }
            public int itemCount(String item) { return count; }
            public String screen() { return screen; }
        };
    }
}
