package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.observation.BlockPlanValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McmcpRuntimeConstructionTest {
    private static final String DIMENSION = "minecraft:overworld";

    @Test
    void convertsRotationAndEarlierDependencyIntoClosedConstructionRequest() {
        var ground = position(0, 64, 0);
        var base = position(0, 65, 0);
        var plan = new ActionDsl.ApplyKnownBlockPlan(
                "copy",
                base,
                new ActionDsl.BlockPlanTransform(
                        ActionDsl.BlockPlanRotation.DEGREES_90,
                        ActionDsl.BlockPlanMirror.NONE),
                List.of(
                        entry(
                                "base",
                                new ActionDsl.Offset(0, 0, 0),
                                new ActionDsl.PlacementSupport(
                                        ground,
                                        ActionDsl.BlockFace.UP,
                                        Optional.of(new ActionDsl.BlockStateSpec(
                                                "minecraft:stone", Map.of())),
                                        Optional.empty())),
                        entry(
                                "top",
                                new ActionDsl.Offset(0, 1, 0),
                                new ActionDsl.PlacementSupport(
                                        base,
                                        ActionDsl.BlockFace.UP,
                                        Optional.empty(),
                                        Optional.of("base")))));

        var request = McmcpRuntime.constructionRequest(plan);

        assertThat(request.entries()).hasSize(2);
        assertThat(request.entries())
                .allSatisfy(step -> {
                    assertThat(step.expectedBefore().blockId()).isEqualTo("minecraft:air");
                    assertThat(step.expectedAfter().properties()).containsEntry("axis", "z");
                    assertThat(step.requiredItemId()).contains("minecraft:oak_log");
                });
        assertThat(request.entries().get(1).supportWitness().orElseThrow()
                .confirmedDependencyEntryId()).contains("base");
        assertThat(request.bounds().maxDurationSeconds()).isEqualTo(30);
        assertThat(request.bounds().allowBreak()).isFalse();
    }

    @Test
    void rejectsAStateThatWasNotCopiedCompletely() {
        var support = position(0, 64, 0);
        var plan = new ActionDsl.ApplyKnownBlockPlan(
                "copy",
                position(0, 65, 0),
                new ActionDsl.BlockPlanTransform(
                        ActionDsl.BlockPlanRotation.DEGREES_0,
                        ActionDsl.BlockPlanMirror.NONE),
                List.of(new ActionDsl.BlockPlanEntry(
                        "base",
                        new ActionDsl.Offset(0, 0, 0),
                        new ActionDsl.BlockStateSpec("minecraft:oak_log", Map.of()),
                        "minecraft:oak_log",
                        new ActionDsl.PlacementSupport(
                                support,
                                ActionDsl.BlockFace.UP,
                                Optional.of(new ActionDsl.BlockStateSpec(
                                        "minecraft:stone", Map.of())),
                                Optional.empty()))));

        assertThatThrownBy(() -> McmcpRuntime.constructionRequest(plan))
                .isInstanceOf(BlockPlanValidationException.class)
                .extracting(failure -> ((BlockPlanValidationException) failure).code())
                .isEqualTo("incomplete_block_state");
    }

    @Test
    void convertsTransformedExactClearStateIntoConstructionBreaks() {
        var plan = new ActionDsl.ClearKnownBlockPlan(
                "clear",
                position(10, 65, 10),
                new ActionDsl.BlockPlanTransform(
                        ActionDsl.BlockPlanRotation.DEGREES_90,
                        ActionDsl.BlockPlanMirror.NONE),
                List.of(new ActionDsl.ClearBlockPlanEntry(
                        "beam",
                        new ActionDsl.Offset(1, 0, 0),
                        new ActionDsl.BlockStateSpec(
                                "minecraft:oak_log", Map.of("axis", "x")))));

        var request = McmcpRuntime.constructionRequest(plan);

        assertThat(request.breakOnly()).isTrue();
        assertThat(request.plan().breakSafety())
                .isEqualTo(dev.aod.mcmcp.routine.ApplyBlockPlanRequest.BreakSafety
                        .SAFE_CONSTRUCTION_BLOCK);
        assertThat(request.entries()).singleElement().satisfies(step -> {
            assertThat(step.operation())
                    .isEqualTo(dev.aod.mcmcp.routine.ApplyBlockPlanOperation.BREAK_TO_AIR);
            assertThat(step.target()).isEqualTo(
                    new dev.aod.mcmcp.routine.BlockTarget(DIMENSION, 10, 65, 11));
            assertThat(step.expectedBefore().properties()).containsEntry("axis", "z");
            assertThat(step.expectedAfter().blockId()).isEqualTo("minecraft:air");
            assertThat(step.requiredItemId()).isEmpty();
        });
        assertThat(request.bounds().allowBreak()).isTrue();
        assertThat(request.bounds().maxDurationSeconds()).isEqualTo(15);
    }

    private static ActionDsl.BlockPlanEntry entry(
            String id,
            ActionDsl.Offset offset,
            ActionDsl.PlacementSupport support) {
        return new ActionDsl.BlockPlanEntry(
                id,
                offset,
                new ActionDsl.BlockStateSpec("minecraft:oak_log", Map.of("axis", "x")),
                "minecraft:oak_log",
                support);
    }

    private static ActionDsl.Position position(int x, int y, int z) {
        return new ActionDsl.Position(DIMENSION, x, y, z);
    }
}
