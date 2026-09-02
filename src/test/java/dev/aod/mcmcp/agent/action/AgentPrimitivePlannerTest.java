package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.dsl.ActionDslCompiler;
import dev.aod.mcmcp.agent.navigation.DeterministicAStar;
import dev.aod.mcmcp.agent.navigation.KnownTraversabilityMap;
import dev.aod.mcmcp.agent.navigation.NavCell;
import dev.aod.mcmcp.agent.navigation.RoutePlan;
import dev.aod.mcmcp.agent.navigation.TraversabilityEdge;
import dev.aod.mcmcp.agent.observation.ObservationFrame;
import dev.aod.mcmcp.agent.observation.ObservationRecord;
import dev.aod.mcmcp.agent.observation.ObservationValues;
import dev.aod.mcmcp.agent.observation.PlacementStateResolver;
import dev.aod.mcmcp.brewing.StandardPotionStackSpec;
import dev.aod.mcmcp.redstone.RedstoneSpec;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentPrimitivePlannerTest {
    private static final String DIMENSION = "minecraft:overworld";

    @Test
    void expiredWorkerDeadlineStopsPlanningAsTimeout() {
        UUID session = UUID.randomUUID();
        NavCell start = cell(0);
        var program = new ActionDsl.Program(
                1,
                Optional.empty(),
                Set.of(),
                List.of(new ActionDsl.WaitTicks("hold", 1)));

        assertThatThrownBy(() -> AgentPrimitivePlanner.analyze(
                        program,
                        map(session).snapshot().orElseThrow(),
                        new DeterministicAStar(),
                        new AgentPrimitivePlanner.Pose(start, 0.5, 64, 0.5, 1.62, 0, 0),
                        Optional.empty(),
                        4.5F,
                        () -> false))
                .isInstanceOf(AgentPrimitivePlanner.PlanningException.class)
                .extracting(failure -> ((AgentPrimitivePlanner.PlanningException) failure).code())
                .isEqualTo(AgentPrimitivePlanner.Code.TIMEOUT);
    }

    @Test
    void cropWaitRequiresCurrentPolicyVisibleWheatButNotCurrentMaturity() {
        UUID session = UUID.randomUUID();
        var map = map(session);
        var target = new ActionDsl.Position(DIMENSION, 2, 65, 3);
        var wait = new ActionDsl.WaitUntil(
                "wait", new ActionDsl.CropMatureCondition(target), 200);
        var program = new ActionDsl.Program(
                1, Optional.empty(), Set.of(), List.of(wait));
        var pose = new AgentPrimitivePlanner.Pose(
                cell(0), 0.5, 64, 0.5, 1.62, 0, 0);

        var immature = AgentPrimitivePlanner.analyze(
                program,
                map.snapshot().orElseThrow(),
                new DeterministicAStar(),
                pose,
                Optional.of(frame(List.of(surface(
                        target, ObservationRecord.Face.UP,
                        "minecraft:wheat", false, 0)))),
                4.5F);

        assertThat(immature.worstCase(wait)).contains(
                ActionDslCompiler.intrinsicWaitCost(200));
        assertThat(immature.knownSurfaces()).containsExactly(
                new AgentPrimitivePlanner.KnownSurface(
                        target, ActionDsl.BlockFace.UP, "minecraft:wheat", null,
                        new Vec3(0.5D, 65.62D, 0.5D)));
        var waitWitness = immature.knownSurfaces().iterator().next();
        assertThat(AgentPrimitivePlanner.knownSurface(
                map.snapshot().orElseThrow(),
                Optional.of(frame(List.of(surface(
                        target, ObservationRecord.Face.UP,
                        "minecraft:wheat", false, 0)))),
                waitWitness,
                0L)).isTrue();
        assertThat(AgentPrimitivePlanner.knownSurface(
                map.snapshot().orElseThrow(),
                Optional.of(frame(List.of(surface(
                        target, ObservationRecord.Face.UP,
                        "minecraft:wheat", false, 0, 66.62D)))),
                waitWitness,
                0L)).isFalse();
        assertThatCode(() -> AgentPrimitivePlanner.requireKnownWheatWaitSurface(
                map.snapshot().orElseThrow(),
                Optional.of(frame(List.of(surface(
                        target, ObservationRecord.Face.UP,
                        "minecraft:wheat", true, 0)))),
                List.of(pose),
                target,
                0L)).doesNotThrowAnyException();
        assertThatThrownBy(() -> AgentPrimitivePlanner.requireKnownWheatWaitSurface(
                map.snapshot().orElseThrow(),
                Optional.of(frame(List.of(surface(
                        target, ObservationRecord.Face.UP,
                        "minecraft:stone", null, 0)))),
                List.of(pose),
                target,
                0L)).isInstanceOf(AgentPrimitivePlanner.PlanningException.class);
        assertThatThrownBy(() -> AgentPrimitivePlanner.requireKnownWheatWaitSurface(
                map.snapshot().orElseThrow(),
                Optional.of(frame(List.of(surface(
                        target, ObservationRecord.Face.UP,
                        "minecraft:wheat", null, 0)))),
                List.of(pose),
                target,
                0L)).isInstanceOf(AgentPrimitivePlanner.PlanningException.class);

        var movedPose = new AgentPrimitivePlanner.Pose(
                cell(1), 1.5D, 64.0D, 0.5D, 1.62D, 0.0F, 0.0F);
        assertThatThrownBy(() -> AgentPrimitivePlanner.analyze(
                program,
                map.snapshot().orElseThrow(),
                new DeterministicAStar(),
                movedPose,
                Optional.of(frame(List.of(surface(
                        target, ObservationRecord.Face.UP,
                        "minecraft:wheat", false, 0)))),
                4.5F))
                .isInstanceOf(AgentPrimitivePlanner.PlanningException.class)
                .extracting(failure -> ((AgentPrimitivePlanner.PlanningException) failure).code())
                .isEqualTo(AgentPrimitivePlanner.Code.TARGET_UNKNOWN);

        map.advanceWorldRevision(1, List.of(), List.of());
        assertThatThrownBy(() -> AgentPrimitivePlanner.analyze(
                program,
                map.snapshot().orElseThrow(),
                new DeterministicAStar(),
                pose,
                Optional.of(frame(List.of(surface(
                        target, ObservationRecord.Face.UP,
                        "minecraft:wheat", false, 0)))),
                4.5F,
                0L,
                ignored -> 1L,
                () -> true))
                .isInstanceOf(AgentPrimitivePlanner.PlanningException.class)
                .extracting(failure -> ((AgentPrimitivePlanner.PlanningException) failure).code())
                .isEqualTo(AgentPrimitivePlanner.Code.TARGET_UNKNOWN);
    }

    @Test
    void cropWaitAfterGuaranteedPlantDefersWheatEvidenceToItsJitBind() {
        UUID session = UUID.randomUUID();
        var map = map(session);
        var support = new ActionDsl.Position(DIMENSION, 0, 64, 0);
        var crop = new ActionDsl.Position(DIMENSION, 0, 65, 0);
        var plant = new ActionDsl.PlantKnownWheat(
                "plant", crop, support, "minecraft:wheat_seeds");
        var wait = new ActionDsl.WaitUntil(
                "grow", new ActionDsl.CropMatureCondition(crop), 200);
        var program = new ActionDsl.Program(
                1,
                Optional.empty(),
                Set.of(ActionDsl.Capability.CAMERA, ActionDsl.Capability.BLOCK_PLACE),
                List.of(plant, wait));

        var analysis = AgentPrimitivePlanner.analyze(
                program,
                map.snapshot().orElseThrow(),
                new DeterministicAStar(),
                new AgentPrimitivePlanner.Pose(
                        cell(0), 0.5, 64, 0.5, 1.62, 0, 0),
                Optional.of(frame(List.of(surface(
                        support, ObservationRecord.Face.UP,
                        "minecraft:farmland", null, 0)))),
                4.5F);

        assertThat(analysis.worstCase(plant)).isPresent();
        assertThat(analysis.worstCase(wait)).contains(
                ActionDslCompiler.intrinsicWaitCost(200));
        assertThat(analysis.knownSurfaces())
                .extracting(AgentPrimitivePlanner.KnownSurface::position)
                .contains(support)
                .doesNotContain(crop);

        var waitOnly = new ActionDsl.Program(
                1, Optional.empty(), Set.of(), List.of(wait));
        assertThatThrownBy(() -> AgentPrimitivePlanner.analyze(
                waitOnly,
                map.snapshot().orElseThrow(),
                new DeterministicAStar(),
                new AgentPrimitivePlanner.Pose(
                        cell(0), 0.5, 64, 0.5, 1.62, 0, 0),
                Optional.of(frame(List.of(surface(
                        support, ObservationRecord.Face.UP,
                        "minecraft:farmland", null, 0)))),
                4.5F))
                .isInstanceOf(AgentPrimitivePlanner.PlanningException.class);
    }

    @Test
    void knownBlockPlanRequiresDeliveredExactSupportState() {
        UUID session = UUID.randomUUID();
        var map = map(session);
        var support = new ActionDsl.Position(DIMENSION, 0, 64, 2);
        var target = new ActionDsl.Position(DIMENSION, 0, 65, 2);
        var expectedSupport = new ActionDsl.BlockStateSpec(
                "minecraft:oak_log", Map.of("axis", "x"));
        var plan = new ActionDsl.ApplyKnownBlockPlan(
                "copy",
                target,
                new ActionDsl.BlockPlanTransform(
                        ActionDsl.BlockPlanRotation.DEGREES_0,
                        ActionDsl.BlockPlanMirror.NONE),
                List.of(new ActionDsl.BlockPlanEntry(
                        "cell",
                        new ActionDsl.Offset(0, 0, 0),
                        expectedSupport,
                        "minecraft:oak_log",
                        new ActionDsl.PlacementSupport(
                                support,
                                ActionDsl.BlockFace.UP,
                                Optional.of(expectedSupport),
                                Optional.empty()))));
        var program = new ActionDsl.Program(
                1,
                Optional.empty(),
                Set.of(ActionDsl.Capability.CAMERA, ActionDsl.Capability.BLOCK_PLACE),
                List.of(plan));
        var pose = new AgentPrimitivePlanner.Pose(
                cell(0), 0.5D, 64.0D, 0.5D, 1.62D, 0.0F, 0.0F);

        var accepted = AgentPrimitivePlanner.analyze(
                program,
                map.snapshot().orElseThrow(),
                new DeterministicAStar(),
                pose,
                Optional.of(frame(List.of(surfaceWithState(
                        support,
                        ObservationRecord.Face.UP,
                        "minecraft:oak_log",
                        Map.of("axis", "x"),
                        0L)))),
                4.5F);

        assertThat(accepted.worstCase(plan)).contains(
                ActionDslCompiler.intrinsicKnownBlockPlanCost(1));

        var dimension = new ObservationValues.ResourceId(DIMENSION);
        var edgeHit = new ObservationRecord.VisibleSurface(
                new ObservationValues.BlockPosition(
                        dimension, support.x(), support.y(), support.z()),
                ObservationRecord.Face.UP,
                new ObservationValues.ResourceId("minecraft:oak_log"),
                new ObservationRecord.BlockStateView(
                        new ObservationValues.ResourceId("minecraft:oak_log"),
                        Map.of("axis", "x")),
                new ObservationValues.ResourceId("minecraft:oak_log"),
                ObservationRecord.ShapeClass.OPAQUE,
                null,
                new ObservationValues.WorldPosition(dimension, 1.0D, 65.0D, 2.0D),
                new ObservationValues.WorldPosition(dimension, 0.5D, 65.62D, 0.5D),
                1L,
                0L);
        assertThat(AgentPrimitivePlanner.analyze(
                        program,
                        map.snapshot().orElseThrow(),
                        new DeterministicAStar(),
                        pose,
                        Optional.of(frame(List.of(edgeHit))),
                        4.5F).worstCase(plan))
                .contains(ActionDslCompiler.intrinsicKnownBlockPlanCost(1));

        assertThatThrownBy(() -> AgentPrimitivePlanner.analyze(
                program,
                map.snapshot().orElseThrow(),
                new DeterministicAStar(),
                new AgentPrimitivePlanner.Pose(
                        cell(0), 0.5D, 64.0D, 0.5D, 1.62D, 180.0F, 0.0F),
                Optional.of(frame(List.of(surfaceWithState(
                        support,
                        ObservationRecord.Face.UP,
                        "minecraft:oak_log",
                        Map.of("axis", "x"),
                        0L)))),
                4.5F))
                .isInstanceOf(AgentPrimitivePlanner.PlanningException.class)
                .extracting(failure -> ((AgentPrimitivePlanner.PlanningException) failure).code())
                .isEqualTo(AgentPrimitivePlanner.Code.TARGET_UNKNOWN);
        assertThatThrownBy(() -> AgentPrimitivePlanner.analyze(
                program,
                map.snapshot().orElseThrow(),
                new DeterministicAStar(),
                pose,
                Optional.of(frame(List.of(new ObservationRecord.VisibleSurface(
                        new ObservationValues.BlockPosition(
                                new ObservationValues.ResourceId(DIMENSION),
                                support.x(), support.y(), support.z()),
                        ObservationRecord.Face.UP,
                        new ObservationValues.ResourceId("minecraft:oak_log"),
                        null,
                        null,
                        ObservationRecord.ShapeClass.OPAQUE,
                        null,
                        new ObservationValues.WorldPosition(
                                new ObservationValues.ResourceId(DIMENSION),
                                support.x() + 0.5D, support.y() + 1.0D, support.z() + 0.5D),
                        new ObservationValues.WorldPosition(
                                new ObservationValues.ResourceId(DIMENSION),
                                0.5D, 65.62D, 0.5D),
                        1L,
                        0L)))),
                4.5F))
                .isInstanceOf(AgentPrimitivePlanner.PlanningException.class)
                .extracting(failure -> ((AgentPrimitivePlanner.PlanningException) failure).code())
                .isEqualTo(AgentPrimitivePlanner.Code.TARGET_UNKNOWN);
        assertThatThrownBy(() -> AgentPrimitivePlanner.analyze(
                program,
                map.snapshot().orElseThrow(),
                new DeterministicAStar(),
                pose,
                Optional.of(frame(List.of(surfaceWithState(
                        support,
                        ObservationRecord.Face.UP,
                        "minecraft:oak_log",
                        Map.of("axis", "y"),
                        0L)))),
                4.5F))
                .isInstanceOf(AgentPrimitivePlanner.PlanningException.class)
                .extracting(failure -> ((AgentPrimitivePlanner.PlanningException) failure).code())
                .isEqualTo(AgentPrimitivePlanner.Code.TARGET_UNKNOWN);
    }

    @Test
    void placementStateRefNeedsNoSourceCoordinateButKeepsCurrentSupportProof() {
        UUID session = UUID.randomUUID();
        var map = map(session).snapshot().orElseThrow();
        var support = new ActionDsl.Position(DIMENSION, 0, 64, 2);
        var target = new ActionDsl.Position(DIMENSION, 0, 65, 2);
        var expectedSupport = new ActionDsl.BlockStateSpec(
                "minecraft:oak_log", Map.of("axis", "x"));
        String ref = "psr_0123456789abcdef0123456789abcdef";
        var plan = new ActionDsl.ApplyKnownBlockPlan(
                "copy",
                target,
                new ActionDsl.BlockPlanTransform(
                        ActionDsl.BlockPlanRotation.DEGREES_0,
                        ActionDsl.BlockPlanMirror.NONE),
                List.of(new ActionDsl.BlockPlanEntry(
                        "cell",
                        new ActionDsl.Offset(0, 0, 0),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(ref),
                        new ActionDsl.PlacementSupport(
                                support,
                                ActionDsl.BlockFace.UP,
                                Optional.of(expectedSupport),
                                Optional.empty()))));
        var program = new ActionDsl.Program(
                1, Optional.empty(),
                Set.of(ActionDsl.Capability.CAMERA, ActionDsl.Capability.BLOCK_PLACE),
                List.of(plan));
        var pose = new AgentPrimitivePlanner.Pose(
                cell(0), 0.5D, 64.0D, 0.5D, 1.62D, 0.0F, 0.0F);
        var remembered = new PlacementStateResolver.PlacementState(
                new ObservationRecord.BlockStateView(
                        new ObservationValues.ResourceId("minecraft:oak_planks"), Map.of()),
                new ObservationValues.ResourceId("minecraft:oak_planks"));

        var accepted = AgentPrimitivePlanner.analyze(
                program,
                map,
                new DeterministicAStar(),
                pose,
                Optional.of(frame(List.of(surfaceWithState(
                        support, ObservationRecord.Face.UP,
                        "minecraft:oak_log", Map.of("axis", "x"), 0L)))),
                4.5F,
                map.worldRevision(),
                ignored -> map.worldRevision(),
                () -> true,
                candidate -> candidate.equals(ref)
                        ? Optional.of(remembered) : Optional.empty());

        assertThat(accepted.worstCase(plan))
                .contains(ActionDslCompiler.intrinsicKnownBlockPlanCost(1));
        assertThat(accepted.knownSurfaces())
                .extracting(AgentPrimitivePlanner.KnownSurface::position)
                .containsExactly(support);

        assertThatThrownBy(() -> AgentPrimitivePlanner.analyze(
                program, map, new DeterministicAStar(), pose,
                Optional.of(frame(List.of(surfaceWithState(
                        support, ObservationRecord.Face.UP,
                        "minecraft:oak_log", Map.of("axis", "x"), 0L)))),
                4.5F, map.worldRevision(), ignored -> map.worldRevision(),
                () -> true, PlacementStateResolver.none()))
                .isInstanceOf(AgentPrimitivePlanner.PlanningException.class)
                .extracting(failure -> ((AgentPrimitivePlanner.PlanningException) failure).code())
                .isEqualTo(AgentPrimitivePlanner.Code.TARGET_UNKNOWN);
    }

    @Test
    void knownClearPlanRequiresDeliveredExactStateAtItsTransformedTarget() {
        UUID session = UUID.randomUUID();
        var map = map(session);
        var target = new ActionDsl.Position(DIMENSION, 0, 65, 2);
        var expected = new ActionDsl.BlockStateSpec(
                "minecraft:oak_log", Map.of("axis", "x"));
        var clear = new ActionDsl.ClearKnownBlockPlan(
                "clear",
                target,
                new ActionDsl.BlockPlanTransform(
                        ActionDsl.BlockPlanRotation.DEGREES_0,
                        ActionDsl.BlockPlanMirror.NONE),
                List.of(new ActionDsl.ClearBlockPlanEntry(
                        "beam", new ActionDsl.Offset(0, 0, 0), expected)));
        var program = new ActionDsl.Program(
                1,
                Optional.empty(),
                Set.of(ActionDsl.Capability.CAMERA, ActionDsl.Capability.BLOCK_BREAK),
                List.of(clear));
        var pose = new AgentPrimitivePlanner.Pose(
                cell(0), 0.5D, 64.0D, 0.5D, 1.62D, 0.0F, 0.0F);

        var accepted = AgentPrimitivePlanner.analyze(
                program,
                map.snapshot().orElseThrow(),
                new DeterministicAStar(),
                pose,
                Optional.of(frame(List.of(surfaceWithState(
                        target, ObservationRecord.Face.NORTH,
                        "minecraft:oak_log", Map.of("axis", "x"), 0L)))),
                4.5F);

        assertThat(accepted.worstCase(clear)).contains(
                ActionDslCompiler.intrinsicKnownBlockClearCost(1));
        assertThat(accepted.knownSurfaces())
                .extracting(AgentPrimitivePlanner.KnownSurface::position)
                .contains(target);

        var dimension = new ObservationValues.ResourceId(DIMENSION);
        var edgeHit = new ObservationRecord.VisibleSurface(
                new ObservationValues.BlockPosition(
                        dimension, target.x(), target.y(), target.z()),
                ObservationRecord.Face.NORTH,
                new ObservationValues.ResourceId("minecraft:oak_log"),
                new ObservationRecord.BlockStateView(
                        new ObservationValues.ResourceId("minecraft:oak_log"),
                        Map.of("axis", "x")),
                new ObservationValues.ResourceId("minecraft:oak_log"),
                ObservationRecord.ShapeClass.OPAQUE,
                null,
                new ObservationValues.WorldPosition(
                        dimension, target.x(), target.y(), target.z()),
                new ObservationValues.WorldPosition(dimension, 0.5D, 65.62D, 0.5D),
                1L,
                0L);
        assertThat(AgentPrimitivePlanner.analyze(
                        program,
                        map.snapshot().orElseThrow(),
                        new DeterministicAStar(),
                        pose,
                        Optional.of(frame(List.of(edgeHit))),
                        4.5F).worstCase(clear))
                .contains(ActionDslCompiler.intrinsicKnownBlockClearCost(1));

        assertThatThrownBy(() -> AgentPrimitivePlanner.analyze(
                program,
                map.snapshot().orElseThrow(),
                new DeterministicAStar(),
                pose,
                Optional.of(frame(List.of(surface(
                        target, ObservationRecord.Face.NORTH,
                        "minecraft:oak_log", null, 0L)))),
                4.5F))
                .isInstanceOf(AgentPrimitivePlanner.PlanningException.class)
                .extracting(failure -> ((AgentPrimitivePlanner.PlanningException) failure).code())
                .isEqualTo(AgentPrimitivePlanner.Code.TARGET_UNKNOWN);
    }

    @Test
    void redstoneIdentityRequiresCurrentInertLampAndGlassLeverSupports() {
        UUID session = UUID.randomUUID();
        var map = map(session).snapshot().orElseThrow();
        var anchor = new ActionDsl.Position(DIMENSION, 2, 65, 0);
        var lampSupport = new ActionDsl.Position(DIMENSION, 2, 64, 0);
        var leverSupport = new ActionDsl.Position(DIMENSION, 3, 64, 0);
        var redstone = new ActionDsl.ApplyKnownRedstoneSpec(
                "identity",
                anchor,
                0,
                List.of(
                        new RedstoneSpec.Component(
                                "input", RedstoneSpec.Role.INPUT, "minecraft:lever"),
                        new RedstoneSpec.Component(
                                "output", RedstoneSpec.Role.OUTPUT,
                                "minecraft:redstone_lamp")),
                List.of(
                        new RedstoneSpec.TruthRow(
                                Map.of("input", false), Map.of("output", false)),
                        new RedstoneSpec.TruthRow(
                                Map.of("input", true), Map.of("output", true))),
                new RedstoneSpec.Footprint(2, 1, 1),
                new ActionDsl.RedstoneTiming(5));
        var program = new ActionDsl.Program(
                1,
                Optional.empty(),
                Set.of(
                        ActionDsl.Capability.CAMERA,
                        ActionDsl.Capability.BLOCK_INTERACT,
                        ActionDsl.Capability.BLOCK_PLACE),
                List.of(redstone));
        var pose = new AgentPrimitivePlanner.Pose(
                cell(0), 0.5D, 64.0D, 0.5D, 1.62D, 0.0F, 0.0F);

        var accepted = AgentPrimitivePlanner.analyze(
                program,
                map,
                new DeterministicAStar(),
                pose,
                Optional.of(frame(List.of(
                        surface(lampSupport, ObservationRecord.Face.UP,
                                "minecraft:stone", null, 0L),
                        surface(leverSupport, ObservationRecord.Face.UP,
                                "minecraft:glass", null, 0L)))),
                4.5F);

        assertThat(accepted.worstCase(redstone)).contains(
                ActionDslCompiler.intrinsicKnownRedstoneCost(5));
        assertThat(accepted.mutationAims())
                .containsEntry(
                        "identity/lamp",
                        new AgentPrimitivePlanner.MutationAim(
                                lampSupport,
                                ActionDsl.BlockFace.UP,
                                new Vec3(2.5D, 65.0D, 0.5D)))
                .containsEntry(
                        "identity/lever",
                        new AgentPrimitivePlanner.MutationAim(
                                leverSupport,
                                ActionDsl.BlockFace.UP,
                                new Vec3(3.5D, 65.0D, 0.5D)));
        assertThat(accepted.knownSurfaces()).hasSize(2);

        assertThatThrownBy(() -> AgentPrimitivePlanner.analyze(
                        program,
                        map,
                        new DeterministicAStar(),
                        pose,
                        Optional.of(frame(List.of(
                                surface(lampSupport, ObservationRecord.Face.UP,
                                        "minecraft:farmland", null, 0L),
                                surface(leverSupport, ObservationRecord.Face.UP,
                                        "minecraft:glass", null, 0L)))),
                        4.5F))
                .isInstanceOf(AgentPrimitivePlanner.PlanningException.class)
                .extracting(failure -> ((AgentPrimitivePlanner.PlanningException) failure).code())
                .isEqualTo(AgentPrimitivePlanner.Code.TARGET_UNKNOWN);

        assertThatThrownBy(() -> AgentPrimitivePlanner.analyze(
                        program,
                        map,
                        new DeterministicAStar(),
                        pose,
                        Optional.of(frame(List.of(
                                surface(lampSupport, ObservationRecord.Face.UP,
                                        "minecraft:stone", null, 0L),
                                surface(leverSupport, ObservationRecord.Face.UP,
                                        "minecraft:stone", null, 0L)))),
                        4.5F))
                .isInstanceOf(AgentPrimitivePlanner.PlanningException.class)
                .extracting(failure -> ((AgentPrimitivePlanner.PlanningException) failure).code())
                .isEqualTo(AgentPrimitivePlanner.Code.TARGET_UNKNOWN);
    }

    @Test
    void redstoneFanOutRequiresAndRecordsTheSecondLampSupport() {
        UUID session = UUID.randomUUID();
        var map = map(session).snapshot().orElseThrow();
        var anchor = new ActionDsl.Position(DIMENSION, 2, 65, 0);
        var firstLampSupport = new ActionDsl.Position(DIMENSION, 2, 64, 0);
        var leverSupport = new ActionDsl.Position(DIMENSION, 3, 64, 0);
        var secondLampSupport = new ActionDsl.Position(DIMENSION, 4, 64, 0);
        var redstone = new ActionDsl.ApplyKnownRedstoneSpec(
                "fan_out",
                anchor,
                0,
                List.of(
                        new RedstoneSpec.Component(
                                "input", RedstoneSpec.Role.INPUT, "minecraft:lever"),
                        new RedstoneSpec.Component(
                                "output", RedstoneSpec.Role.OUTPUT,
                                "minecraft:redstone_lamp"),
                        new RedstoneSpec.Component(
                                "output_2", RedstoneSpec.Role.OUTPUT,
                                "minecraft:redstone_lamp")),
                List.of(
                        new RedstoneSpec.TruthRow(
                                Map.of("input", false),
                                Map.of("output", false, "output_2", false)),
                        new RedstoneSpec.TruthRow(
                                Map.of("input", true),
                                Map.of("output", true, "output_2", true))),
                new RedstoneSpec.Footprint(3, 1, 1),
                new ActionDsl.RedstoneTiming(5));
        var program = new ActionDsl.Program(
                1,
                Optional.empty(),
                Set.of(
                        ActionDsl.Capability.CAMERA,
                        ActionDsl.Capability.BLOCK_INTERACT,
                        ActionDsl.Capability.BLOCK_PLACE),
                List.of(redstone));
        var pose = new AgentPrimitivePlanner.Pose(
                cell(0), 0.5D, 64.0D, 0.5D, 1.62D, 0.0F, 0.0F);

        var accepted = AgentPrimitivePlanner.analyze(
                program,
                map,
                new DeterministicAStar(),
                pose,
                Optional.of(frame(List.of(
                        surface(firstLampSupport, ObservationRecord.Face.UP,
                                "minecraft:stone", null, 0L),
                        surface(leverSupport, ObservationRecord.Face.UP,
                                "minecraft:glass", null, 0L),
                        surface(secondLampSupport, ObservationRecord.Face.UP,
                                "minecraft:stone", null, 0L)))),
                4.5F);

        assertThat(accepted.worstCase(redstone)).contains(
                ActionDslCompiler.intrinsicKnownRedstoneCost(5, 2));
        assertThat(accepted.mutationAims()).containsEntry(
                "fan_out/lamp_2",
                new AgentPrimitivePlanner.MutationAim(
                        secondLampSupport,
                        ActionDsl.BlockFace.UP,
                        new Vec3(4.5D, 65.0D, 0.5D)));
        assertThat(accepted.knownSurfaces()).hasSize(3);

        assertThatThrownBy(() -> AgentPrimitivePlanner.analyze(
                        program,
                        map,
                        new DeterministicAStar(),
                        pose,
                        Optional.of(frame(List.of(
                                surface(firstLampSupport, ObservationRecord.Face.UP,
                                        "minecraft:stone", null, 0L),
                                surface(leverSupport, ObservationRecord.Face.UP,
                                        "minecraft:glass", null, 0L)))),
                        4.5F))
                .isInstanceOf(AgentPrimitivePlanner.PlanningException.class)
                .extracting(failure -> ((AgentPrimitivePlanner.PlanningException) failure).code())
                .isEqualTo(AgentPrimitivePlanner.Code.TARGET_UNKNOWN);
    }

    @Test
    void redstoneWireIdentityRequiresThreeCurrentGlassSupports() {
        UUID session = UUID.randomUUID();
        var map = map(session).snapshot().orElseThrow();
        var anchor = new ActionDsl.Position(DIMENSION, 2, 65, 0);
        var lampSupport = new ActionDsl.Position(DIMENSION, 2, 64, 0);
        var wireSupport = new ActionDsl.Position(DIMENSION, 3, 64, 0);
        var leverSupport = new ActionDsl.Position(DIMENSION, 4, 64, 0);
        var redstone = new ActionDsl.ApplyKnownRedstoneSpec(
                "wire_identity",
                anchor,
                0,
                List.of(
                        new RedstoneSpec.Component(
                                "input", RedstoneSpec.Role.INPUT, "minecraft:lever"),
                        new RedstoneSpec.Component(
                                "output", RedstoneSpec.Role.OUTPUT,
                                "minecraft:redstone_lamp"),
                        new RedstoneSpec.Component(
                                "wire", RedstoneSpec.Role.WIRE,
                                "minecraft:redstone_wire")),
                List.of(
                        new RedstoneSpec.TruthRow(
                                Map.of("input", false), Map.of("output", false)),
                        new RedstoneSpec.TruthRow(
                                Map.of("input", true), Map.of("output", true))),
                new RedstoneSpec.Footprint(3, 1, 1),
                new ActionDsl.RedstoneTiming(5));
        var program = new ActionDsl.Program(
                1,
                Optional.empty(),
                Set.of(
                        ActionDsl.Capability.CAMERA,
                        ActionDsl.Capability.BLOCK_INTERACT,
                        ActionDsl.Capability.BLOCK_PLACE),
                List.of(redstone));
        var pose = new AgentPrimitivePlanner.Pose(
                cell(0), 0.5D, 64.0D, 0.5D, 1.62D, 0.0F, 0.0F);

        var accepted = AgentPrimitivePlanner.analyze(
                program,
                map,
                new DeterministicAStar(),
                pose,
                Optional.of(frame(List.of(
                        surface(lampSupport, ObservationRecord.Face.UP,
                                "minecraft:glass", null, 0L),
                        surface(wireSupport, ObservationRecord.Face.UP,
                                "minecraft:glass", null, 0L),
                        surface(leverSupport, ObservationRecord.Face.UP,
                                "minecraft:glass", null, 0L)))),
                4.5F);

        assertThat(accepted.worstCase(redstone)).contains(
                ActionDslCompiler.intrinsicKnownRedstoneCost(5, 1, 1));
        assertThat(accepted.mutationAims()).containsEntry(
                "wire_identity/wire",
                new AgentPrimitivePlanner.MutationAim(
                        wireSupport,
                        ActionDsl.BlockFace.UP,
                        new Vec3(3.5D, 65.0D, 0.5D)));

        assertThatThrownBy(() -> AgentPrimitivePlanner.analyze(
                        program,
                        map,
                        new DeterministicAStar(),
                        pose,
                        Optional.of(frame(List.of(
                                surface(lampSupport, ObservationRecord.Face.UP,
                                        "minecraft:stone", null, 0L),
                                surface(wireSupport, ObservationRecord.Face.UP,
                                        "minecraft:glass", null, 0L),
                                surface(leverSupport, ObservationRecord.Face.UP,
                                        "minecraft:glass", null, 0L)))),
                        4.5F))
                .isInstanceOf(AgentPrimitivePlanner.PlanningException.class)
                .extracting(failure -> ((AgentPrimitivePlanner.PlanningException) failure).code())
                .isEqualTo(AgentPrimitivePlanner.Code.TARGET_UNKNOWN);
    }

    @Test
    void recordsEveryNavigationAndFaceTargetForCommitRevalidation() {
        UUID session = UUID.randomUUID();
        NavCell start = cell(0);
        NavCell navigationTarget = cell(1);
        NavCell faceTarget = cell(2);
        var map = map(session);
        map.observe(confirmed(session, start, navigationTarget));
        map.observe(confirmed(session, navigationTarget, faceTarget));
        var program = new ActionDsl.Program(
                1,
                Optional.empty(),
                Set.of(ActionDsl.Capability.MOVEMENT, ActionDsl.Capability.CAMERA),
                List.of(navigate("move", navigationTarget), face("look", faceTarget)));

        var analysis = AgentPrimitivePlanner.analyze(
                program,
                map.snapshot().orElseThrow(),
                new DeterministicAStar(),
                new AgentPrimitivePlanner.Pose(start, 0.5, 64, 0.5, 1.62, 0, 0),
                Optional.empty(),
                4.5F);

        assertThat(analysis.knownTargets()).containsExactlyInAnyOrder(
                position(navigationTarget), position(faceTarget));
    }

    @Test
    void surfaceApproachChoosesTheShortestKnownInteractionRangeCell() {
        UUID session = UUID.randomUUID();
        NavCell start = cell(0);
        var map = map(session);
        for (int x = 0; x < 4; x++) {
            map.observe(confirmed(session, cell(x), cell(x + 1)));
        }
        var target = new ActionDsl.Position(DIMENSION, 8, 64, 0);
        var approach = new ActionDsl.ApproachKnownSurface(
                "approach", target, "minecraft:dirt");
        var program = new ActionDsl.Program(
                1, Optional.empty(), Set.of(ActionDsl.Capability.MOVEMENT), List.of(approach));

        var analysis = AgentPrimitivePlanner.analyze(
                program,
                map.snapshot().orElseThrow(),
                new DeterministicAStar(),
                new AgentPrimitivePlanner.Pose(
                        start, 0.5D, 64.0D, 0.5D, 1.62D, 0.0F, 0.0F),
                Optional.of(frame(target, ObservationRecord.Face.WEST, "minecraft:dirt", 0L)),
                4.5F);

        assertThat(analysis.worstCase(approach)
                .map(ActionDslCompiler.Cost::distanceBlocks)).contains(6.0D);
        assertThat(analysis.knownSurfaces())
                .extracting(AgentPrimitivePlanner.KnownSurface::position)
                .containsExactly(target);
        AgentPrimitivePlanner.ApproachPlan plan = AgentPrimitivePlanner.requireApproachPlan(
                map.snapshot().orElseThrow(), new DeterministicAStar(), start, target);
        assertThat(plan.anchor()).isEqualTo(cell(4));
        assertThat(plan.route().cells()).containsExactly(
                cell(0), cell(1), cell(2), cell(3), cell(4));
    }

    @Test
    void admitsFourExplicitCardinalLooksUsingTheirCumulativeCameraCost() {
        UUID session = UUID.randomUUID();
        NavCell center = cell(0);
        var map = map(session);
        var targets = List.of(cell(1), new NavCell(DIMENSION, 0, 64, 1),
                cell(-1), new NavCell(DIMENSION, 0, 64, -1));
        targets.forEach(target -> map.observe(confirmed(session, center, target)));
        var nodes = List.<ActionDsl.Node>of(
                face("east", targets.get(0)),
                face("south", targets.get(1)),
                face("west", targets.get(2)),
                face("north", targets.get(3)));
        var program = new ActionDsl.Program(
                1, Optional.empty(), Set.of(ActionDsl.Capability.CAMERA), nodes);
        var request = new ActionDsl.Request(
                1,
                program,
                new ActionDsl.Budget(30_000, 600, 0, 360, 0, 0, 0));
        var snapshot = map.snapshot().orElseThrow();

        var analysis = AgentPrimitivePlanner.analyze(
                program,
                snapshot,
                new DeterministicAStar(),
                new AgentPrimitivePlanner.Pose(center, 0.5, 64, 0.5, 0.5, -90, 0),
                Optional.empty(),
                4.5F);
        var compiled = ActionDslCompiler.compile(
                request, analysis::worstCase, Set.of(ActionDsl.Capability.CAMERA));

        assertThat(compiled.worstCaseCost().cameraDegrees()).isEqualTo(275.5D);
        assertThat(compiled.worstCaseCost().ticks()).isEqualTo(64L);
    }

    @Test
    void cameraCostDoesNotRoundBelowTheFloatRotationEndpoints() {
        var pose = new AgentPrimitivePlanner.Pose(
                new NavCell(DIMENSION, 0, 0, 0),
                0.5D, 0.0D, 0.5D, 1.5D, 46.28893F, 0.0F);

        var cost = AgentPrimitivePlanner.faceCost(
                pose, new ActionDsl.Position(DIMENSION, 1, 1, 1), 4.5F);

        assertThat(cost.cameraDegrees())
                .isEqualTo(Math.abs(-45.0D - (double) pose.yaw())
                        + AgentPrimitivePlanner.CAMERA_QUANTIZATION_RESERVE_DEGREES);
    }

    @Test
    void navigationCostUsesThePriorPrimitiveDestination() {
        UUID session = UUID.randomUUID();
        var map = map(session);
        for (int x = -2; x < 2; x++) {
            NavCell left = cell(x);
            NavCell right = cell(x + 1);
            map.observe(confirmed(session, left, right));
            map.observe(confirmed(session, right, left));
        }
        var nodes = List.<ActionDsl.Node>of(
                navigate("left", cell(-2)),
                navigate("right", cell(2)));
        var program = new ActionDsl.Program(
                1, Optional.empty(), Set.of(ActionDsl.Capability.MOVEMENT), nodes);
        var request = new ActionDsl.Request(
                1,
                program,
                new ActionDsl.Budget(30_000, 600, 16, 0, 0, 0, 0));
        var snapshot = map.snapshot().orElseThrow();

        var analysis = AgentPrimitivePlanner.analyze(
                program,
                snapshot,
                new DeterministicAStar(),
                new AgentPrimitivePlanner.Pose(cell(0), 0.5, 64, 0.5, 1.62, 0, 0),
                Optional.empty(),
                4.5F);
        var compiled = ActionDslCompiler.compile(
                request, analysis::worstCase, Set.of(ActionDsl.Capability.MOVEMENT));

        assertThat(analysis.primitiveCosts().get("left").distanceBlocks()).isEqualTo(3.0D);
        assertThat(analysis.primitiveCosts().get("right").distanceBlocks())
                .isEqualTo(1.5D * (3.0D + Math.hypot(
                        1.0D + Math.sqrt(0.5D), 1.0D)));
        assertThat(compiled.worstCaseCost().distanceBlocks())
                .isEqualTo(1.5D * (5.0D + Math.hypot(
                        1.0D + Math.sqrt(0.5D), 1.0D)));
    }

    @Test
    void navigationCostIncludesTheRealOffsetToTheStartingCellCenter() {
        UUID session = UUID.randomUUID();
        var map = map(session);
        NavCell start = cell(0);
        NavCell target = cell(1);
        map.observe(confirmed(session, start, target));
        RoutePlan route = new DeterministicAStar()
                .findRoute(map.snapshot().orElseThrow(), start, target)
                .route().orElseThrow();

        var cost = AgentPrimitivePlanner.navigationCost(
                route,
                new AgentPrimitivePlanner.Pose(start, 0.01, 64, 0.5, 1.62, 0, 0));

        assertThat(cost.distanceBlocks()).isEqualTo(2.235D);
    }

    @Test
    void sameCellNavigationCentersWithinToleranceAndChargesTheOffset() {
        UUID session = UUID.randomUUID();
        var map = map(session);
        NavCell start = cell(0);
        map.observe(confirmed(session, start, cell(1)));
        var navigate = new ActionDsl.NavigateToKnown(
                "center", position(start), 0.1D);
        var program = new ActionDsl.Program(
                1,
                Optional.empty(),
                Set.of(ActionDsl.Capability.MOVEMENT),
                List.of(navigate));

        var analysis = AgentPrimitivePlanner.analyze(
                program,
                map.snapshot().orElseThrow(),
                new DeterministicAStar(),
                new AgentPrimitivePlanner.Pose(
                        start, 0.01D, 64.0D, 0.5D, 1.62D, 0.0F, 0.0F),
                Optional.empty(),
                4.5F);

        assertThat(analysis.knownTargets()).containsExactly(position(start));
        var cost = analysis.primitiveCosts().get("center");
        assertThat(cost.distanceBlocks())
                .isEqualTo(1.5D * 0.49D);
        assertThat(cost.ticks()).isEqualTo(36L);
        assertThat(cost.durationMillis()).isEqualTo(1_800L);
    }

    @Test
    void visibleItemCollectionSelectsAReachableKnownPickupCellAndBoundsConfirmation() {
        UUID session = UUID.randomUUID();
        var map = map(session);
        map.observe(confirmed(session, cell(0), cell(1)));
        map.observe(confirmed(session, cell(1), cell(0)));
        var target = new ActionDsl.WorldPosition(DIMENSION, 1.8D, 64.1D, 0.5D);
        var collect = new ActionDsl.CollectVisibleItem(
                "collect", "minecraft:oak_log", target);
        var frame = entityFrame(visibleItem("minecraft:oak_log", target, 0L));
        var program = new ActionDsl.Program(
                1, Optional.empty(), Set.of(ActionDsl.Capability.MOVEMENT), List.of(collect));
        var pose = new AgentPrimitivePlanner.Pose(
                cell(0), 0.5D, 64.0D, 0.5D, 1.62D, 0.0F, 0.0F);

        var analysis = AgentPrimitivePlanner.analyze(
                program, map.snapshot().orElseThrow(), new DeterministicAStar(),
                pose, Optional.of(frame), 4.5F);
        var pickup = AgentPrimitivePlanner.requirePickupPlan(
                map.snapshot().orElseThrow(), new DeterministicAStar(), cell(0),
                Optional.of(frame), collect);

        assertThat(pickup.pickupCell()).isEqualTo(cell(1));
        assertThat(pickup.route().cells().getLast()).isEqualTo(cell(1));
        assertThat(analysis.primitiveCosts().get("collect").ticks())
                .isEqualTo(AgentPrimitivePlanner.navigationCost(pickup.route(), pose).ticks()
                        + AgentPrimitivePlanner.PICKUP_CONFIRM_TICKS);
        assertThat(analysis.primitiveCosts().get("collect").interactions()).isZero();

        map.advanceWorldRevision(1L, List.of(), List.of());
        var currentEntity = visibleItem("minecraft:oak_log", target, 1L);
        var staleOther = visibleItem(
                "minecraft:wheat_seeds",
                new ActionDsl.WorldPosition(DIMENSION, 3.0D, 64.1D, 0.5D),
                0L);
        var mixedRevisionFrame = new ObservationFrame(
                "obs-0000000000000002",
                new ObservationValues.ResourceId(DIMENSION),
                1,
                16,
                false,
                List.of(currentEntity, staleOther));
        assertThat(AgentPrimitivePlanner.visibleItemCurrent(
                map.snapshot().orElseThrow(), Optional.of(mixedRevisionFrame), collect)).isTrue();
        assertThat(AgentPrimitivePlanner.visibleItemCurrent(
                map.snapshot().orElseThrow(), Optional.of(mixedRevisionFrame), collect, 5L, 4L))
                .isTrue();
        assertThat(AgentPrimitivePlanner.visibleItemCurrent(
                map.snapshot().orElseThrow(), Optional.of(mixedRevisionFrame), collect, 6L, 4L))
                .isFalse();
        assertThat(AgentPrimitivePlanner.visibleItemPickupCellCurrent(
                map.snapshot().orElseThrow(), Optional.of(mixedRevisionFrame), collect,
                cell(1), 5L, 4L)).isTrue();
        assertThat(AgentPrimitivePlanner.visibleItemPickupCellCurrent(
                map.snapshot().orElseThrow(), Optional.of(mixedRevisionFrame), collect,
                cell(0), 5L, 4L)).isFalse();
    }

    @Test
    void visibleItemBatchBindsDistinctFreshWitnessesAndChargesListedRouteOrder() {
        UUID session = UUID.randomUUID();
        var map = map(session);
        for (int x = 0; x < 5; x++) {
            map.observe(confirmed(session, cell(x), cell(x + 1)));
            map.observe(confirmed(session, cell(x + 1), cell(x)));
        }
        var near = new ActionDsl.CollectTarget(
                "minecraft:wheat",
                new ActionDsl.WorldPosition(DIMENSION, 1.8D, 64.1D, 0.5D));
        var far = new ActionDsl.CollectTarget(
                "minecraft:wheat",
                new ActionDsl.WorldPosition(DIMENSION, 4.8D, 64.1D, 0.5D));
        var frame = entityFrame(List.of(
                visibleItem(near.displayedItem(), near.target(), 0L),
                visibleItem(far.displayedItem(), far.target(), 0L)));
        var forward = new ActionDsl.CollectVisibleItemBatch("drops", List.of(near, far));
        var reverse = new ActionDsl.CollectVisibleItemBatch("drops", List.of(far, near));
        var pose = new AgentPrimitivePlanner.Pose(
                cell(0), 0.5D, 64.0D, 0.5D, 1.62D, 0.0F, 0.0F);

        var forwardAnalysis = AgentPrimitivePlanner.analyze(
                new ActionDsl.Program(
                        1, Optional.empty(), Set.of(ActionDsl.Capability.MOVEMENT),
                        List.of(forward)),
                map.snapshot().orElseThrow(), new DeterministicAStar(), pose,
                Optional.of(frame), 4.5F);
        var reverseAnalysis = AgentPrimitivePlanner.analyze(
                new ActionDsl.Program(
                        1, Optional.empty(), Set.of(ActionDsl.Capability.MOVEMENT),
                        List.of(reverse)),
                map.snapshot().orElseThrow(), new DeterministicAStar(), pose,
                Optional.of(frame), 4.5F);

        assertThat(AgentPrimitivePlanner.collectBatchChild(forward, 0))
                .extracting(ActionDsl.CollectVisibleItem::target)
                .isEqualTo(near.target());
        assertThat(AgentPrimitivePlanner.collectBatchChild(forward, 1))
                .extracting(ActionDsl.CollectVisibleItem::target)
                .isEqualTo(far.target());
        assertThat(forwardAnalysis.primitiveCosts().get("drops").distanceBlocks())
                .isLessThan(reverseAnalysis.primitiveCosts().get("drops").distanceBlocks());
        assertThat(forwardAnalysis.primitiveCosts().get("drops").ticks())
                .isGreaterThanOrEqualTo(2L * AgentPrimitivePlanner.PICKUP_CONFIRM_TICKS);

        List<Optional<ObservationValues.Aabb>> bounds =
                AgentPrimitivePlanner.visibleBatchItemAabbs(
                        map.snapshot().orElseThrow(), Optional.of(frame), forward,
                        0L, frame.frameCompletedTick(), 0L);
        assertThat(bounds).allMatch(Optional::isPresent);
        assertThat(bounds.get(0).orElseThrow().minX())
                .isLessThan(bounds.get(1).orElseThrow().minX());

        var overlappingFirst = new ActionDsl.CollectTarget(
                "minecraft:wheat",
                new ActionDsl.WorldPosition(DIMENSION, 1.9D, 64.1D, 0.5D));
        var overlappingSecond = new ActionDsl.CollectTarget(
                "minecraft:wheat",
                new ActionDsl.WorldPosition(DIMENSION, 2.1D, 64.1D, 0.5D));
        var oneWitnessBatch = new ActionDsl.CollectVisibleItemBatch(
                "ambiguous", List.of(overlappingFirst, overlappingSecond));
        assertThatThrownBy(() -> AgentPrimitivePlanner.analyze(
                new ActionDsl.Program(
                        1, Optional.empty(), Set.of(ActionDsl.Capability.MOVEMENT),
                        List.of(oneWitnessBatch)),
                map.snapshot().orElseThrow(), new DeterministicAStar(), pose,
                Optional.of(entityFrame(visibleItem(
                        "minecraft:wheat",
                        new ActionDsl.WorldPosition(DIMENSION, 2.0D, 64.1D, 0.5D),
                        0L))),
                4.5F))
                .isInstanceOf(AgentPrimitivePlanner.PlanningException.class)
                .extracting(failure -> ((AgentPrimitivePlanner.PlanningException) failure).code())
                .isEqualTo(AgentPrimitivePlanner.Code.TARGET_UNKNOWN);
    }

    @Test
    void visibleItemCollectionChoosesTheFastestReachablePickupCell() {
        UUID session = UUID.randomUUID();
        var map = map(session);
        NavCell start = cell(0);
        NavCell shortPickup = cell(1);
        NavCell longPickup = cell(2);
        NavCell detour0 = new NavCell(DIMENSION, 0, 64, 1);
        NavCell detour1 = new NavCell(DIMENSION, 1, 64, 1);
        NavCell detour2 = new NavCell(DIMENSION, 2, 64, 1);
        map.observe(confirmed(session, start, shortPickup));
        map.observe(confirmed(session, start, detour0));
        map.observe(confirmed(session, detour0, detour1));
        map.observe(confirmed(session, detour1, detour2));
        map.observe(confirmed(session, detour2, longPickup));
        var target = new ActionDsl.WorldPosition(DIMENSION, 2.1D, 64.1D, 0.5D);
        var collect = new ActionDsl.CollectVisibleItem(
                "collect", "minecraft:wheat", target);

        var pickup = AgentPrimitivePlanner.requirePickupPlan(
                map.snapshot().orElseThrow(), new DeterministicAStar(), start,
                Optional.of(entityFrame(visibleItem("minecraft:wheat", target, 0L))), collect);

        assertThat(pickup.pickupCell()).isEqualTo(shortPickup);
        assertThat(pickup.route().edges()).hasSize(1);
    }

    @Test
    void visibleItemRevisionWindowCrossesOnlyNeutralMutations() {
        UUID session = UUID.randomUUID();
        var map = map(session);
        map.observe(confirmed(session, cell(0), cell(1)));
        var target = new ActionDsl.WorldPosition(DIMENSION, 1.8D, 64.1D, 0.5D);
        var collect = new ActionDsl.CollectVisibleItem(
                "collect", "minecraft:wheat", target);
        var oldFrame = entityFrame(visibleItem("minecraft:wheat", target, 0L));
        var currentFrame = entityFrame(visibleItem("minecraft:wheat", target, 1L));
        var futureFrame = entityFrame(visibleItem("minecraft:wheat", target, 2L));
        var program = new ActionDsl.Program(
                1, Optional.empty(), Set.of(ActionDsl.Capability.MOVEMENT), List.of(collect));
        var pose = new AgentPrimitivePlanner.Pose(
                cell(0), 0.5D, 64.0D, 0.5D, 1.62D, 0.0F, 0.0F);

        map.advanceWorldRevision(1L, List.of(), List.of());
        var currentMap = map.snapshot().orElseThrow();

        assertThat(AgentPrimitivePlanner.visibleItemCurrent(
                currentMap, Optional.of(oldFrame), collect,
                0L, 5L, 4L)).isTrue();
        assertThat(AgentPrimitivePlanner.visibleItemCurrent(
                currentMap, Optional.of(oldFrame), collect,
                1L, 5L, 4L)).isFalse();
        assertThat(AgentPrimitivePlanner.visibleItemCurrent(
                currentMap, Optional.of(currentFrame), collect,
                1L, 5L, 4L)).isTrue();
        assertThat(AgentPrimitivePlanner.visibleItemCurrent(
                currentMap, Optional.of(futureFrame), collect,
                0L, 5L, 4L)).isFalse();

        assertThatCode(() -> AgentPrimitivePlanner.analyze(
                program,
                currentMap,
                new DeterministicAStar(),
                pose,
                Optional.of(oldFrame),
                4.5F,
                0L)).doesNotThrowAnyException();
        assertThatThrownBy(() -> AgentPrimitivePlanner.analyze(
                program,
                currentMap,
                new DeterministicAStar(),
                pose,
                Optional.of(oldFrame),
                4.5F,
                1L)).isInstanceOf(AgentPrimitivePlanner.PlanningException.class);
        assertThat(AgentPrimitivePlanner.requirePickupPlan(
                currentMap,
                new DeterministicAStar(),
                cell(0),
                Optional.of(oldFrame),
                collect,
                0L,
                5L,
                4L).pickupCell()).isEqualTo(cell(1));
    }

    @Test
    void surfaceRevisionWindowCrossesOnlyUnrelatedNeutralMutations() {
        UUID session = UUID.randomUUID();
        var map = map(session);
        var target = new ActionDsl.Position(DIMENSION, 3, 65, 0);
        var oldFrame = frame(target, ObservationRecord.Face.WEST, "minecraft:stone", 0L);
        var currentFrame = frame(target, ObservationRecord.Face.WEST, "minecraft:stone", 1L);
        var futureFrame = frame(target, ObservationRecord.Face.WEST, "minecraft:stone", 2L);
        var required = new AgentPrimitivePlanner.KnownSurface(
                target, ActionDsl.BlockFace.WEST, "minecraft:stone");
        map.advanceWorldRevision(1L, List.of(), List.of());
        var currentMap = map.snapshot().orElseThrow();

        assertThat(AgentPrimitivePlanner.knownSurface(
                currentMap, Optional.of(oldFrame), required)).isFalse();
        assertThat(AgentPrimitivePlanner.knownSurface(
                currentMap, Optional.of(oldFrame), required, 0L)).isTrue();
        assertThat(AgentPrimitivePlanner.knownSurface(
                currentMap, Optional.of(oldFrame), required, 1L)).isFalse();
        assertThat(AgentPrimitivePlanner.knownSurface(
                currentMap, Optional.of(currentFrame), required, 1L)).isTrue();
        assertThat(AgentPrimitivePlanner.knownSurface(
                currentMap, Optional.of(futureFrame), required, 0L)).isFalse();

        var face = new ActionDsl.FaceKnownPosition("face", target);
        var program = new ActionDsl.Program(
                1, Optional.empty(), Set.of(ActionDsl.Capability.CAMERA), List.of(face));
        var pose = new AgentPrimitivePlanner.Pose(
                cell(0), 0.5D, 64.0D, 0.5D, 1.62D, 0.0F, 0.0F);
        assertThatCode(() -> AgentPrimitivePlanner.analyze(
                program,
                currentMap,
                new DeterministicAStar(),
                pose,
                Optional.of(oldFrame),
                4.5F,
                0L,
                ignored -> 0L,
                () -> true)).doesNotThrowAnyException();
        assertThatThrownBy(() -> AgentPrimitivePlanner.analyze(
                program,
                currentMap,
                new DeterministicAStar(),
                pose,
                Optional.of(oldFrame),
                4.5F,
                0L,
                ignored -> 1L,
                () -> true)).isInstanceOf(AgentPrimitivePlanner.PlanningException.class);
    }

    @Test
    void visibleItemCollectionRejectsWrongOrUnreachableObservationEvidence() {
        UUID session = UUID.randomUUID();
        var map = map(session);
        map.observe(confirmed(session, cell(0), cell(1)));
        var target = new ActionDsl.WorldPosition(DIMENSION, 1.8D, 64.1D, 0.5D);
        var collect = new ActionDsl.CollectVisibleItem(
                "collect", "minecraft:wheat", target);
        var wrongItem = entityFrame(visibleItem("minecraft:wheat_seeds", target, 0L));

        assertThat(AgentPrimitivePlanner.visibleItemCurrent(
                map.snapshot().orElseThrow(), Optional.of(wrongItem), collect)).isFalse();
        assertThatThrownBy(() -> AgentPrimitivePlanner.requirePickupPlan(
                map.snapshot().orElseThrow(), new DeterministicAStar(), cell(0),
                Optional.of(wrongItem), collect))
                .isInstanceOf(AgentPrimitivePlanner.PlanningException.class)
                .extracting(failure -> ((AgentPrimitivePlanner.PlanningException) failure).code())
                .isEqualTo(AgentPrimitivePlanner.Code.TARGET_UNKNOWN);

        var movedTarget = new ActionDsl.WorldPosition(DIMENSION, 2.8D, 64.1D, 0.5D);
        var movedFrame = entityFrame(visibleItem("minecraft:wheat", movedTarget, 0L));
        assertThat(AgentPrimitivePlanner.visibleItemCurrent(
                map.snapshot().orElseThrow(), Optional.of(movedFrame), collect)).isFalse();

        var newerRevision = entityFrame(visibleItem("minecraft:wheat", target, 1L));
        assertThat(AgentPrimitivePlanner.visibleItemCurrent(
                map.snapshot().orElseThrow(), Optional.of(newerRevision), collect)).isFalse();

        var distantTarget = new ActionDsl.WorldPosition(DIMENSION, 8.5D, 64.1D, 0.5D);
        var distant = new ActionDsl.CollectVisibleItem(
                "collect", "minecraft:wheat", distantTarget);
        var distantFrame = entityFrame(visibleItem("minecraft:wheat", distantTarget, 0L));
        assertThatThrownBy(() -> AgentPrimitivePlanner.requirePickupPlan(
                map.snapshot().orElseThrow(), new DeterministicAStar(), cell(0),
                Optional.of(distantFrame), distant))
                .isInstanceOf(AgentPrimitivePlanner.PlanningException.class)
                .extracting(failure -> ((AgentPrimitivePlanner.PlanningException) failure).code())
                .isEqualTo(AgentPrimitivePlanner.Code.TARGET_UNKNOWN);

        var highTarget = new ActionDsl.WorldPosition(DIMENSION, 0.5D, 66.5D, 0.5D);
        var highCollect = new ActionDsl.CollectVisibleItem(
                "collect", "minecraft:wheat", highTarget);
        assertThatThrownBy(() -> AgentPrimitivePlanner.requirePickupPlan(
                map.snapshot().orElseThrow(), new DeterministicAStar(), cell(0),
                Optional.of(entityFrame(visibleItem("minecraft:wheat", highTarget, 0L))),
                highCollect))
                .isInstanceOf(AgentPrimitivePlanner.PlanningException.class)
                .extracting(failure -> ((AgentPrimitivePlanner.PlanningException) failure).code())
                .isEqualTo(AgentPrimitivePlanner.Code.TARGET_UNKNOWN);

        var blockedMap = map(session);
        blockedMap.observe(blocked(session, cell(0), cell(1)));
        var blockedTarget = new ActionDsl.WorldPosition(DIMENSION, 1.9D, 64.1D, 0.5D);
        var blockedCollect = new ActionDsl.CollectVisibleItem(
                "collect", "minecraft:wheat", blockedTarget);
        var blockedFrame = entityFrame(visibleItem("minecraft:wheat", blockedTarget, 0L));
        assertThatThrownBy(() -> AgentPrimitivePlanner.requirePickupPlan(
                blockedMap.snapshot().orElseThrow(), new DeterministicAStar(), cell(0),
                Optional.of(blockedFrame), blockedCollect))
                .isInstanceOf(AgentPrimitivePlanner.PlanningException.class)
                .extracting(failure -> ((AgentPrimitivePlanner.PlanningException) failure).code())
                .isEqualTo(AgentPrimitivePlanner.Code.TARGET_UNKNOWN);
    }

    @Test
    void breakRequiresTheExactCurrentVisibleFaceAndAccountsForAimAndOneBreak() {
        UUID session = UUID.randomUUID();
        var map = map(session).snapshot().orElseThrow();
        var target = new ActionDsl.Position(DIMENSION, 3, 65, 0);
        var block = new ActionDsl.BreakKnownFace(
                "chop", target, ActionDsl.BlockFace.WEST,
                "minecraft:oak_log", "minecraft:iron_axe");
        var program = new ActionDsl.Program(
                1, Optional.empty(),
                Set.of(ActionDsl.Capability.CAMERA, ActionDsl.Capability.BLOCK_BREAK),
                List.of(block));
        var frame = frame(target, ObservationRecord.Face.WEST, "minecraft:oak_log", 0);

        var analysis = AgentPrimitivePlanner.analyze(
                program, map, new DeterministicAStar(),
                new AgentPrimitivePlanner.Pose(cell(0), 0.5, 64, 0.5, 1.62, 0, 0),
                Optional.of(frame), 4.5F);

        assertThat(analysis.knownSurfaces()).containsExactly(new AgentPrimitivePlanner.KnownSurface(
                target, ActionDsl.BlockFace.WEST, "minecraft:oak_log"));
        assertThat(analysis.mutationAims().get("chop"))
                .isEqualTo(new AgentPrimitivePlanner.MutationAim(
                        target, ActionDsl.BlockFace.WEST,
                        rayHit(target, ObservationRecord.Face.WEST, "minecraft:oak_log")));
        assertThat(analysis.primitiveCosts().get("chop").blocksBroken()).isOne();
        assertThat(analysis.primitiveCosts().get("chop").ticks())
                .isGreaterThan(AgentPrimitivePlanner.BREAK_TICK_UPPER_BOUND
                        + AgentPrimitivePlanner.BREAK_REOBSERVATION_TICKS);
        assertThatThrownBy(() -> AgentPrimitivePlanner.requireKnownBreakSurface(
                map,
                Optional.of(frame(target, ObservationRecord.Face.EAST, "minecraft:oak_log", 0)),
                block))
                .isInstanceOf(AgentPrimitivePlanner.PlanningException.class);
    }

    @Test
    void wheatMutationCostsUseTheSelectedSurfaceAngleAndPreferUpForTilling() {
        UUID session = UUID.randomUUID();
        var map = map(session).snapshot().orElseThrow();
        var target = new ActionDsl.Position(DIMENSION, 3, 64, 0);
        var till = new ActionDsl.TillKnownBlock(
                "till", target, "minecraft:dirt", "minecraft:iron_hoe");
        var program = new ActionDsl.Program(
                1, Optional.empty(),
                Set.of(ActionDsl.Capability.CAMERA, ActionDsl.Capability.BLOCK_INTERACT),
                List.of(till));

        var analysis = AgentPrimitivePlanner.analyze(
                program, map, new DeterministicAStar(),
                new AgentPrimitivePlanner.Pose(cell(0), 0.5, 64, 0.5, 1.62, 0, 0),
                Optional.of(frame(List.of(
                        surface(target, ObservationRecord.Face.NORTH, "minecraft:dirt", null, 0),
                        surface(target, ObservationRecord.Face.UP, "minecraft:dirt", null, 0)))),
                4.5F);

        assertThat(analysis.knownSurfaces()).containsExactly(
                new AgentPrimitivePlanner.KnownSurface(
                        target, ActionDsl.BlockFace.UP, "minecraft:dirt"));
        assertThat(analysis.primitiveCosts().get("till").interactions()).isOne();
        assertThat(analysis.primitiveCosts().get("till").blocksBroken()).isZero();
        assertThat(analysis.mutationAims().get("till").face())
                .isEqualTo(ActionDsl.BlockFace.UP);
        double camera = analysis.primitiveCosts().get("till").cameraDegrees();
        assertThat(camera).isLessThan(360.0D);
        assertThat(analysis.primitiveCosts().get("till").ticks())
                .isEqualTo(AgentPrimitivePlanner.BLOCK_MUTATION_TICK_UPPER_BOUND
                        + (long) Math.ceil(camera / 4.5D));
        assertThatThrownBy(() -> AgentPrimitivePlanner.analyze(
                program, map, new DeterministicAStar(),
                new AgentPrimitivePlanner.Pose(cell(0), 0.5, 64, 0.5, 1.62, 0, 0),
                Optional.of(frame(target, ObservationRecord.Face.UP, "minecraft:stone", 0)),
                4.5F)).isInstanceOf(AgentPrimitivePlanner.PlanningException.class);
        assertThatThrownBy(() -> AgentPrimitivePlanner.analyze(
                program, map, new DeterministicAStar(),
                new AgentPrimitivePlanner.Pose(cell(0), 0.5, 64, 0.5, 1.62, 0, 0),
                Optional.of(frame(target, ObservationRecord.Face.DOWN, "minecraft:dirt", 0)),
                4.5F)).isInstanceOf(AgentPrimitivePlanner.PlanningException.class);
    }

    @Test
    void eightTargetMutationBatchPreservesSubmittedOrderAndChargesOneJointCameraPath() {
        List<ActionDsl.Position> targets = List.of(
                new ActionDsl.Position(DIMENSION, -1, 64, -1),
                new ActionDsl.Position(DIMENSION, 0, 64, -1),
                new ActionDsl.Position(DIMENSION, 1, 64, -1),
                new ActionDsl.Position(DIMENSION, 2, 64, -1),
                new ActionDsl.Position(DIMENSION, -1, 64, 1),
                new ActionDsl.Position(DIMENSION, 0, 64, 1),
                new ActionDsl.Position(DIMENSION, 1, 64, 1),
                new ActionDsl.Position(DIMENSION, 2, 64, 1));
        var shuffled = new java.util.ArrayList<>(targets);
        java.util.Collections.shuffle(shuffled, new java.util.Random(0x5EEDL));

        var canonical = analyzeTillBatch(targets);
        var reverse = analyzeTillBatch(targets.reversed());
        var random = analyzeTillBatch(shuffled);
        assertThat(mutationPositions(canonical.mutationBatchPlans().get("batch")))
                .containsExactlyElementsOf(targets);
        assertThat(mutationPositions(reverse.mutationBatchPlans().get("batch")))
                .containsExactlyElementsOf(targets.reversed());
        assertThat(mutationPositions(random.mutationBatchPlans().get("batch")))
                .containsExactlyElementsOf(shuffled);
        assertThat(List.of(canonical, reverse, random)).allSatisfy(analysis ->
                assertThat(analysis.primitiveCosts().get("batch")).satisfies(cost -> {
                    assertThat(cost.interactions()).isEqualTo(8);
                    assertThat(cost.blocksBroken()).isZero();
                    assertThat(cost.blocksPlaced()).isZero();
                    assertThat(cost.cameraDegrees()).isFinite()
                            .isLessThanOrEqualTo(8.0D * 360.0D);
                    assertThat(cost.ticks()).isGreaterThanOrEqualTo(
                            8L * (AgentPrimitivePlanner.BLOCK_MUTATION_TICK_UPPER_BOUND
                                    + AgentPrimitivePlanner.MUTATION_BATCH_REPROOF_TICKS));
                }));
        var randomBatch = new ActionDsl.TillKnownBatch(
                "batch", shuffled, "minecraft:dirt", "minecraft:iron_hoe");
        var randomRequest = new ActionDsl.Request(
                1,
                new ActionDsl.Program(
                        1,
                        Optional.empty(),
                        Set.of(
                                ActionDsl.Capability.CAMERA,
                                ActionDsl.Capability.BLOCK_INTERACT),
                        List.of(randomBatch)),
                new ActionDsl.Budget(600_000, 12_000, 0, 720, 8, 0, 0));
        assertThat(random.primitiveCosts().get("batch").cameraDegrees())
                .isGreaterThan(720.0D);
        assertThatThrownBy(() -> ActionDslCompiler.compile(
                randomRequest,
                random::worstCase,
                randomRequest.program().capabilities()))
                .isInstanceOf(dev.aod.mcmcp.agent.dsl.ActionDslException.class)
                .extracting(failure -> ((dev.aod.mcmcp.agent.dsl.ActionDslException) failure)
                        .code())
                .isEqualTo(dev.aod.mcmcp.agent.dsl.ActionDslException.Code
                        .PROGRAM_BUDGET_UNPROVABLE);
        assertThat(canonical.mutationBatchPlans().get("batch").steps())
                .allSatisfy(step -> assertThat(step.plannedCost().ticks())
                        .isGreaterThanOrEqualTo(
                                AgentPrimitivePlanner.BLOCK_MUTATION_TICK_UPPER_BOUND
                                        + AgentPrimitivePlanner.MUTATION_BATCH_REPROOF_TICKS));
    }

    @Test
    void tillBatchOccurrenceCostCoversOneSixteenthSettlingBeforeNextReproof() {
        UUID session = UUID.randomUUID();
        var underfoot = new ActionDsl.Position(DIMENSION, 0, 64, 0);
        var nearerRayTarget = new ActionDsl.Position(DIMENSION, 0, 65, 0);
        var batch = new ActionDsl.TillKnownBatch(
                "batch", List.of(underfoot, nearerRayTarget),
                "minecraft:dirt", "minecraft:iron_hoe");
        var program = new ActionDsl.Program(
                1,
                Optional.empty(),
                Set.of(ActionDsl.Capability.CAMERA, ActionDsl.Capability.BLOCK_INTERACT),
                List.of(batch));
        var initial = new AgentPrimitivePlanner.Pose(
                new NavCell(DIMENSION, 0, 65, 0),
                0.5D, 65.0D, 0.5D, 1.62D, -90.0F, 90.0F);
        var analysis = AgentPrimitivePlanner.analyze(
                program,
                map(session).snapshot().orElseThrow(),
                new DeterministicAStar(),
                initial,
                Optional.of(frame(List.of(
                        surface(underfoot, ObservationRecord.Face.UP,
                                "minecraft:dirt", null, 0, 66.62D),
                        surface(nearerRayTarget, ObservationRecord.Face.UP,
                                "minecraft:dirt", null, 0, 66.62D)))),
                4.5F);
        var plan = analysis.mutationBatchPlans().get("batch");
        assertThat(mutationPositions(plan)).containsExactly(underfoot, nearerRayTarget);

        Vec3 firstPoint = plan.steps().get(0).aim().point();
        Vec3 secondPoint = plan.steps().get(1).aim().point();
        ActionDslCompiler.Cost first = AgentPrimitivePlanner.mutationCost(
                initial, firstPoint, 4.5F, 1, 0, 0);
        float firstYaw = yawTo(initial.x(), initial.z(), firstPoint);
        float firstPitch = pitchTo(
                initial.x(), initial.y() + initial.eyeHeight(), initial.z(), firstPoint);
        var settled = new AgentPrimitivePlanner.Pose(
                initial.cell(), initial.x(), initial.y() - 1.0D / 16.0D,
                initial.z(), initial.eyeHeight(), firstYaw, firstPitch);
        ActionDslCompiler.Cost freshSecond = AgentPrimitivePlanner.mutationCost(
                settled, secondPoint, 4.5F, 1, 0, 0);
        ActionDslCompiler.Cost occurrence = analysis.primitiveCosts().get("batch");

        assertThat(occurrence.cameraDegrees()).isLessThanOrEqualTo(720.0D);
        assertThat(first.cameraDegrees() + freshSecond.cameraDegrees())
                .isLessThanOrEqualTo(occurrence.cameraDegrees() + 1.0e-9D);
        assertThat(first.ticks() + freshSecond.ticks())
                .isLessThanOrEqualTo(occurrence.ticks());
        assertThat(first.durationMillis() + freshSecond.durationMillis())
                .isLessThanOrEqualTo(occurrence.durationMillis());
    }

    @Test
    void everyMutationBatchChildRequiresItsOwnFreshCurrentSurfaceProof() {
        UUID session = UUID.randomUUID();
        var knownMap = map(session).snapshot().orElseThrow();
        var support = new ActionDsl.Position(DIMENSION, 1, 64, 0);
        var crop = new ActionDsl.Position(DIMENSION, 1, 65, 0);
        List<ActionDsl.Node> batches = List.of(
                new ActionDsl.TillKnownBatch(
                        "till_batch", List.of(support),
                        "minecraft:dirt", "minecraft:iron_hoe"),
                new ActionDsl.PlantKnownWheatBatch(
                        "plant_batch", List.of(new ActionDsl.PlantPlot(crop, support)),
                        "minecraft:wheat_seeds"),
                new ActionDsl.HarvestKnownWheatBatch(
                        "harvest_batch", List.of(crop)));
        var pose = new AgentPrimitivePlanner.Pose(
                cell(0), 0.5D, 64.0D, 0.5D, 1.62D, 0.0F, 0.0F);

        for (ActionDsl.Node batch : batches) {
            ActionDsl.Node child = AgentPrimitivePlanner.mutationBatchChildren(batch).getFirst();
            ObservationRecord.VisibleSurface witness = switch (child) {
                case ActionDsl.TillKnownBlock till -> surface(
                        till.target(), ObservationRecord.Face.UP,
                        till.expectedBlock(), null, 0);
                case ActionDsl.PlantKnownWheat plant -> surface(
                        plant.support(), ObservationRecord.Face.UP,
                        "minecraft:farmland", null, 0);
                case ActionDsl.HarvestKnownWheat harvest -> surface(
                        harvest.target(), ObservationRecord.Face.UP,
                        "minecraft:wheat", true, 0);
                default -> throw new AssertionError("not a batch child: " + child);
            };
            var program = new ActionDsl.Program(
                    1,
                    Optional.empty(),
                    Set.of(
                            ActionDsl.Capability.CAMERA,
                            ActionDsl.Capability.BLOCK_INTERACT,
                            ActionDsl.Capability.BLOCK_PLACE,
                            ActionDsl.Capability.BLOCK_BREAK),
                    List.of(child));

            var fresh = AgentPrimitivePlanner.analyze(
                    program, knownMap, new DeterministicAStar(), pose,
                    Optional.of(frame(List.of(witness))), 4.5F);
            assertThat(fresh.primitiveCosts()).containsKey(child.id());
            assertThatThrownBy(() -> AgentPrimitivePlanner.analyze(
                    program, knownMap, new DeterministicAStar(), pose,
                    Optional.empty(), 4.5F))
                    .isInstanceOf(AgentPrimitivePlanner.PlanningException.class)
                    .extracting(failure ->
                            ((AgentPrimitivePlanner.PlanningException) failure).code())
                    .isEqualTo(AgentPrimitivePlanner.Code.TARGET_UNKNOWN);
        }

        var incomplete = new ActionDsl.TillKnownBatch(
                "incomplete",
                List.of(support, new ActionDsl.Position(DIMENSION, 2, 64, 0)),
                "minecraft:dirt",
                "minecraft:iron_hoe");
        var incompleteProgram = new ActionDsl.Program(
                1,
                Optional.empty(),
                Set.of(ActionDsl.Capability.CAMERA, ActionDsl.Capability.BLOCK_INTERACT),
                List.of(incomplete));
        assertThatThrownBy(() -> AgentPrimitivePlanner.analyze(
                incompleteProgram, knownMap, new DeterministicAStar(), pose,
                Optional.of(frame(List.of(surface(
                        support, ObservationRecord.Face.UP, "minecraft:dirt", null, 0)))),
                4.5F))
                .isInstanceOf(AgentPrimitivePlanner.PlanningException.class)
                .satisfies(failure -> {
                    var planningFailure = (AgentPrimitivePlanner.PlanningException) failure;
                    assertThat(planningFailure.code())
                            .isEqualTo(AgentPrimitivePlanner.Code.TARGET_UNKNOWN);
                    assertThat(planningFailure.getMessage()).contains("target[1]");
                });
    }

    @Test
    void openFenceGateBindsOneVisibleOakGateRayWitness() {
        UUID session = UUID.randomUUID();
        var map = map(session).snapshot().orElseThrow();
        var target = new ActionDsl.Position(DIMENSION, 3, 64, 0);
        var program = new ActionDsl.Program(
                1, Optional.empty(),
                Set.of(ActionDsl.Capability.CAMERA, ActionDsl.Capability.BLOCK_INTERACT),
                List.of(new ActionDsl.OpenKnownFenceGate("open_gate", target)));

        var analysis = AgentPrimitivePlanner.analyze(
                program, map, new DeterministicAStar(),
                new AgentPrimitivePlanner.Pose(cell(0), 0.5, 64, 0.5, 1.62, 0, 0),
                Optional.of(frame(
                        target, ObservationRecord.Face.SOUTH, "minecraft:oak_fence_gate", 0)),
                4.5F);

        assertThat(analysis.primitiveCosts().get("open_gate").interactions()).isOne();
        assertThat(analysis.mutationAims().get("open_gate"))
                .extracting(
                        AgentPrimitivePlanner.MutationAim::block,
                        AgentPrimitivePlanner.MutationAim::face,
                        AgentPrimitivePlanner.MutationAim::point)
                .containsExactly(
                        target,
                        ActionDsl.BlockFace.SOUTH,
                        new Vec3(3.5D, 64.5D, 1.0D));
        assertThatThrownBy(() -> AgentPrimitivePlanner.analyze(
                program, map, new DeterministicAStar(),
                new AgentPrimitivePlanner.Pose(cell(0), 0.5, 64, 0.5, 1.62, 0, 0),
                Optional.of(frame(
                        target, ObservationRecord.Face.SOUTH, "minecraft:spruce_fence_gate", 0)),
                4.5F)).isInstanceOf(AgentPrimitivePlanner.PlanningException.class);
    }

    @Test
    void passageAndContainerNodesRequireExactVisibleReachableSurfaces() {
        UUID session = UUID.randomUUID();
        var map = map(session).snapshot().orElseThrow();
        var door = new ActionDsl.Position(DIMENSION, 2, 64, 0);
        var chest = new ActionDsl.Position(DIMENSION, 3, 64, 0);
        var program = new ActionDsl.Program(
                1, Optional.empty(), Set.of(
                        ActionDsl.Capability.CAMERA,
                        ActionDsl.Capability.BLOCK_INTERACT,
                        ActionDsl.Capability.INVENTORY_TRANSFER),
                List.of(
                        new ActionDsl.OpenKnownPassage(
                                "open", door, "minecraft:oak_door"),
                        new ActionDsl.InspectKnownContainer(
                                "inspect", chest, "minecraft:chest"),
                        new ActionDsl.TakeKnownContainerStack(
                                "take", chest, "minecraft:chest", "minecraft:wheat_seeds",
                                "default_components_only", 64)));

        var analysis = AgentPrimitivePlanner.analyze(
                program, map, new DeterministicAStar(),
                new AgentPrimitivePlanner.Pose(cell(0), 0.5, 64, 0.5, 1.62, 0, 0),
                Optional.of(frame(List.of(
                        surface(door, ObservationRecord.Face.WEST,
                                "minecraft:oak_door", null, 0),
                        surface(chest, ObservationRecord.Face.WEST,
                                "minecraft:chest", null, 0)))),
                4.5F);

        assertThat(analysis.primitiveCosts().get("open").interactions()).isOne();
        assertThat(analysis.primitiveCosts().get("inspect").interactions()).isOne();
        assertThat(analysis.primitiveCosts().get("take").interactions()).isEqualTo(3);
        assertThat(analysis.primitiveCosts().get("take").ticks())
                .isEqualTo(AgentPrimitivePlanner.CONTAINER_TICK_UPPER_BOUND);
        assertThat(analysis.mutationAims()).containsKeys("open", "inspect", "take");

        assertThatThrownBy(() -> AgentPrimitivePlanner.analyze(
                program, map, new DeterministicAStar(),
                new AgentPrimitivePlanner.Pose(cell(0), 0.5, 64, 0.5, 1.62, 0, 0),
                Optional.of(frame(List.of(
                        surface(door, ObservationRecord.Face.WEST,
                                "minecraft:iron_door", null, 0),
                        surface(chest, ObservationRecord.Face.WEST,
                                "minecraft:chest", null, 0)))),
                4.5F)).isInstanceOf(AgentPrimitivePlanner.PlanningException.class);
    }

    @Test
    void knownRecipeCraftUsesTheExistingBoundedContainerPlan() {
        UUID session = UUID.randomUUID();
        var map = map(session).snapshot().orElseThrow();
        var table = new ActionDsl.Position(DIMENSION, 3, 64, 0);
        var craft = new ActionDsl.CraftKnownRecipe(
                "craft", "abcdefghijklmnopqrstuvwx", "sha256:" + "a".repeat(64),
                "minecraft:oak_planks", "default_components_only", 64,
                "crafting_table", table,
                new ActionDsl.BlockStateSpec("minecraft:crafting_table", Map.of()), 3);
        var program = new ActionDsl.Program(
                1, Optional.empty(),
                Set.of(ActionDsl.Capability.CAMERA, ActionDsl.Capability.INVENTORY_TRANSFER),
                List.of(craft));

        var analysis = AgentPrimitivePlanner.analyze(
                program, map, new DeterministicAStar(),
                new AgentPrimitivePlanner.Pose(cell(0), 0.5, 64, 0.5, 1.62, 0, 0),
                Optional.of(frame(
                        table, ObservationRecord.Face.WEST, "minecraft:crafting_table", 0)),
                4.5F);

        assertThat(analysis.primitiveCosts().get("craft")).isEqualTo(
                new ActionDslCompiler.Cost(30_000, 600, 0,
                        analysis.primitiveCosts().get("craft").cameraDegrees(), 13, 0, 0));
        assertThat(analysis.knownSurfaces()).singleElement().satisfies(surface ->
                assertThat(surface.block()).isEqualTo("minecraft:crafting_table"));
    }

    @Test
    void knownSmeltUsesTheOwnedMenuPlanAndRestoresTheAdmittedPose() {
        UUID session = UUID.randomUUID();
        var map = map(session).snapshot().orElseThrow();
        var furnace = new ActionDsl.Position(DIMENSION, 3, 64, 0);
        var smelt = new ActionDsl.SmeltKnownRecipe(
                "smelt", "abcdefghijklmnopqrstuvwx", "sha256:" + "a".repeat(64),
                "minecraft:iron_ingot", "default_components_only", 1,
                "furnace", furnace,
                new ActionDsl.BlockStateSpec(
                        "minecraft:furnace", Map.of("facing", "north", "lit", "false")),
                "minecraft:coal", "default_components_only", 1);
        var pose = new AgentPrimitivePlanner.Pose(
                cell(0), 0.5D, 64.0D, 0.5D, 1.62D, 0.0F, 0.0F);

        var analysis = AgentPrimitivePlanner.analyze(
                new ActionDsl.Program(
                        1, Optional.empty(),
                        Set.of(ActionDsl.Capability.CAMERA,
                                ActionDsl.Capability.INVENTORY_TRANSFER),
                        List.of(smelt)),
                map, new DeterministicAStar(), pose,
                Optional.of(frame(
                        furnace, ObservationRecord.Face.WEST, "minecraft:furnace", 0)),
                4.5F);

        var cost = analysis.primitiveCosts().get("smelt");
        assertThat(cost.durationMillis()).isEqualTo(120_000L);
        assertThat(cost.ticks()).isEqualTo(ActionDslCompiler.knownSmeltingTicks(1));
        assertThat(cost.interactions()).isEqualTo(7L);
        var centerCost = AgentPrimitivePlanner.mutationCost(
                pose, new Vec3(3.5D, 64.5D, 0.5D), 4.5F, 6, 0, 0);
        assertThat(cost.cameraDegrees()).isEqualTo(centerCost.cameraDegrees() * 2.0D);
        assertThat(analysis.knownSurfaces()).contains(
                new AgentPrimitivePlanner.KnownSurface(
                        furnace, ActionDsl.BlockFace.WEST, "minecraft:furnace"));
    }

    @Test
    void containerCostAndRuntimeAimUseVisibleUpRayHitWhenCenterMayBeOccluded() {
        UUID session = UUID.randomUUID();
        var map = map(session).snapshot().orElseThrow();
        var chest = new ActionDsl.Position(DIMENSION, 3, 64, 0);
        var inspect = new ActionDsl.InspectKnownContainer(
                "inspect", chest, "minecraft:chest");
        var program = new ActionDsl.Program(
                1,
                Optional.empty(),
                Set.of(ActionDsl.Capability.CAMERA, ActionDsl.Capability.INVENTORY_TRANSFER),
                List.of(inspect));
        var pose = new AgentPrimitivePlanner.Pose(
                cell(0), 0.5D, 64.0D, 0.5D, 1.62D, 0.0F, 0.0F);

        var analysis = AgentPrimitivePlanner.analyze(
                program,
                map,
                new DeterministicAStar(),
                pose,
                Optional.of(frame(
                        chest, ObservationRecord.Face.UP, "minecraft:chest", 0)),
                4.5F);

        var actual = analysis.primitiveCosts().get("inspect");
        var centerCost = AgentPrimitivePlanner.mutationCost(
                pose, new Vec3(3.5D, 64.5D, 0.5D), 4.5F, 1, 0, 0);
        var rayHitCost = AgentPrimitivePlanner.mutationCost(
                pose, new Vec3(3.5D, 65.0D, 0.5D), 4.5F, 1, 0, 0);
        assertThat(actual.cameraDegrees()).isEqualTo(rayHitCost.cameraDegrees());
        assertThat(actual.cameraDegrees()).isNotEqualTo(centerCost.cameraDegrees());
        assertThat(analysis.mutationAims().get("inspect"))
                .isEqualTo(new AgentPrimitivePlanner.MutationAim(
                        chest, ActionDsl.BlockFace.UP, new Vec3(3.5D, 65.0D, 0.5D)));
        assertThat(actual.durationMillis()).isEqualTo(30_000L);
        assertThat(actual.ticks()).isEqualTo(AgentPrimitivePlanner.CONTAINER_TICK_UPPER_BOUND);
        assertThat(AgentPrimitivePlanner.CONTAINER_OPERATION_TICK_UPPER_BOUND).isEqualTo(400L);
        assertThat(AgentPrimitivePlanner.CONTAINER_TICK_UPPER_BOUND
                - AgentPrimitivePlanner.CONTAINER_OPERATION_TICK_UPPER_BOUND).isEqualTo(200L);
    }

    @Test
    void brewingRequiresTheVisibleStandAndReservesItsFullTerminalBound() {
        UUID session = UUID.randomUUID();
        var map = map(session).snapshot().orElseThrow();
        var stand = new ActionDsl.Position(DIMENSION, 3, 64, 0);
        var brew = new ActionDsl.BrewKnownPotionBatch(
                "brew",
                stand,
                "minecraft:brewing_stand",
                new StandardPotionStackSpec("minecraft:potion", "minecraft:water", 3),
                "minecraft:nether_wart",
                "minecraft:blaze_powder",
                new StandardPotionStackSpec("minecraft:potion", "minecraft:awkward", 3));
        var program = new ActionDsl.Program(
                1,
                Optional.empty(),
                Set.of(ActionDsl.Capability.CAMERA, ActionDsl.Capability.INVENTORY_TRANSFER),
                List.of(brew));

        var analysis = AgentPrimitivePlanner.analyze(
                program,
                map,
                new DeterministicAStar(),
                new AgentPrimitivePlanner.Pose(
                        cell(0), 0.5D, 64.0D, 0.5D, 1.62D, 0.0F, 0.0F),
                Optional.of(frame(
                        stand, ObservationRecord.Face.WEST, "minecraft:brewing_stand", 0)),
                4.5F);

        var cost = analysis.primitiveCosts().get("brew");
        assertThat(cost.durationMillis()).isEqualTo(70_000L);
        assertThat(cost.ticks()).isEqualTo(AgentPrimitivePlanner.BREWING_TICK_UPPER_BOUND);
        assertThat(cost.interactions()).isEqualTo(16L);
        var admittedPose = new AgentPrimitivePlanner.Pose(
                cell(0), 0.5D, 64.0D, 0.5D, 1.62D, 0.0F, 0.0F);
        var centerCost = AgentPrimitivePlanner.mutationCost(
                admittedPose, new Vec3(3.5D, 64.5D, 0.5D), 4.5F, 16, 0, 0);
        assertThat(cost.cameraDegrees()).isEqualTo(centerCost.cameraDegrees() * 2.0D);
        assertThat(cost.distanceBlocks()).isZero();
        assertThat(cost.blocksBroken()).isZero();
        assertThat(cost.blocksPlaced()).isZero();
        assertThat(analysis.knownSurfaces()).contains(
                new AgentPrimitivePlanner.KnownSurface(
                        stand, ActionDsl.BlockFace.WEST, "minecraft:brewing_stand"));

        assertThatThrownBy(() -> AgentPrimitivePlanner.analyze(
                program,
                map,
                new DeterministicAStar(),
                new AgentPrimitivePlanner.Pose(
                        cell(0), 0.5D, 64.0D, 0.5D, 1.62D, 0.0F, 0.0F),
                Optional.of(frame(
                        stand, ObservationRecord.Face.WEST, "minecraft:stone", 0)),
                4.5F)).isInstanceOf(AgentPrimitivePlanner.PlanningException.class);
    }

    @Test
    void brewingRejectsMoreThanOneWayCameraBoundUnlessPrecededByFace() {
        UUID session = UUID.randomUUID();
        var map = map(session).snapshot().orElseThrow();
        var stand = new ActionDsl.Position(DIMENSION, 3, 64, 0);
        var brew = new ActionDsl.BrewKnownPotionBatch(
                "brew",
                stand,
                "minecraft:brewing_stand",
                new StandardPotionStackSpec("minecraft:potion", "minecraft:water", 3),
                "minecraft:nether_wart",
                "minecraft:blaze_powder",
                new StandardPotionStackSpec("minecraft:potion", "minecraft:awkward", 3));
        var initial = new AgentPrimitivePlanner.Pose(
                cell(0), 0.5D, 64.0D, 0.5D, 1.62D, 90.0F, -90.0F);
        var evidence = Optional.of(frame(
                stand, ObservationRecord.Face.WEST, "minecraft:brewing_stand", 0));

        assertThatThrownBy(() -> AgentPrimitivePlanner.analyze(
                new ActionDsl.Program(
                        1,
                        Optional.empty(),
                        Set.of(
                                ActionDsl.Capability.CAMERA,
                                ActionDsl.Capability.INVENTORY_TRANSFER),
                        List.of(brew)),
                map,
                new DeterministicAStar(),
                initial,
                evidence,
                4.5F))
                .isInstanceOf(AgentPrimitivePlanner.PlanningException.class)
                .hasMessageContaining("face_known_position");

        var admitted = AgentPrimitivePlanner.analyze(
                new ActionDsl.Program(
                        1,
                        Optional.empty(),
                        Set.of(
                                ActionDsl.Capability.CAMERA,
                                ActionDsl.Capability.INVENTORY_TRANSFER),
                        List.of(new ActionDsl.FaceKnownPosition("face", stand), brew)),
                map,
                new DeterministicAStar(),
                initial,
                evidence,
                4.5F);
        assertThat(admitted.primitiveCosts().get("brew").cameraDegrees())
                .isLessThanOrEqualTo(540.0D);
    }

    @Test
    void plantBindsSupportUpWhileHarvestRequiresMatureCropEvidence() {
        UUID session = UUID.randomUUID();
        var map = map(session).snapshot().orElseThrow();
        var support = new ActionDsl.Position(DIMENSION, 3, 64, 0);
        var crop = new ActionDsl.Position(DIMENSION, 3, 65, 0);
        var plant = new ActionDsl.PlantKnownWheat(
                "plant", crop, support, "minecraft:wheat_seeds");
        var harvest = new ActionDsl.HarvestKnownWheat("harvest", crop);
        var program = new ActionDsl.Program(
                1, Optional.empty(), Set.of(
                        ActionDsl.Capability.CAMERA,
                        ActionDsl.Capability.BLOCK_PLACE,
                        ActionDsl.Capability.BLOCK_BREAK),
                List.of(plant, harvest));
        var pose = new AgentPrimitivePlanner.Pose(
                cell(0), 0.5, 64, 0.5, 1.62, 0, 0);

        var analysis = AgentPrimitivePlanner.analyze(
                program, map, new DeterministicAStar(), pose,
                Optional.of(frame(List.of(
                        surface(support, ObservationRecord.Face.NORTH,
                                "minecraft:farmland", null, 0),
                        surface(support, ObservationRecord.Face.UP,
                                "minecraft:farmland", null, 0),
                        surface(crop, ObservationRecord.Face.WEST,
                                "minecraft:wheat", true, 0)))),
                4.5F);

        assertThat(analysis.mutationAims().get("plant"))
                .extracting(
                        AgentPrimitivePlanner.MutationAim::block,
                        AgentPrimitivePlanner.MutationAim::face,
                        AgentPrimitivePlanner.MutationAim::point)
                .containsExactly(
                        support,
                        ActionDsl.BlockFace.UP,
                        new Vec3(3.5D, 64.9375D, 0.5D));
        assertThat(analysis.knownSurfaces()).contains(
                new AgentPrimitivePlanner.KnownSurface(
                        crop, ActionDsl.BlockFace.WEST, "minecraft:wheat", true));
        assertThatThrownBy(() -> AgentPrimitivePlanner.analyze(
                new ActionDsl.Program(
                        1, Optional.empty(), program.capabilities(), List.of(harvest)),
                map, new DeterministicAStar(), pose,
                Optional.of(frame(List.of(surface(
                        crop, ObservationRecord.Face.WEST, "minecraft:wheat", false, 0)))),
                4.5F)).isInstanceOf(AgentPrimitivePlanner.PlanningException.class);
    }

    @Test
    void pillarKeepsTheDeliveredSupportWitnessAcrossTheFinalCenteringStep() {
        UUID session = UUID.randomUUID();
        var map = map(session).snapshot().orElseThrow();
        var support = new ActionDsl.Position(DIMENSION, 0, 63, 0);
        var source = new ActionDsl.Position(DIMENSION, 2, 64, 0);
        var smoothStone = new ActionDsl.BlockStateSpec(
                "minecraft:smooth_stone", Map.of());
        var planks = new ActionDsl.BlockStateSpec("minecraft:oak_planks", Map.of());
        var program = new ActionDsl.Program(
                1,
                Optional.empty(),
                Set.of(
                        ActionDsl.Capability.MOVEMENT,
                        ActionDsl.Capability.CAMERA,
                        ActionDsl.Capability.BLOCK_PLACE),
                List.of(new ActionDsl.PillarUpKnown(
                        "pillar", support, smoothStone, planks, "minecraft:oak_planks")));
        var pose = new AgentPrimitivePlanner.Pose(
                cell(0), 0.5D, 64.0D, 0.5D, 1.62D, 0, 0);
        var priorSupportWitness = surfaceWithStateAtEye(
                support,
                ObservationRecord.Face.UP,
                "minecraft:smooth_stone",
                Map.of(),
                0,
                1.5D,
                65.62D,
                0.5D);

        var analysis = AgentPrimitivePlanner.analyze(
                program,
                map,
                new DeterministicAStar(),
                pose,
                Optional.of(frame(List.of(
                        priorSupportWitness,
                        surfaceWithState(
                                source,
                                ObservationRecord.Face.WEST,
                                "minecraft:oak_planks",
                                Map.of(),
                                0)))),
                4.5F);

        assertThat(analysis.knownSurfaces()).contains(
                new AgentPrimitivePlanner.KnownSurface(
                        support,
                        ActionDsl.BlockFace.UP,
                        "minecraft:smooth_stone",
                        null));
    }

    @Test
    void mutationRejectsAStaleEyeOriginAndAnOutOfReachRayHit() {
        UUID session = UUID.randomUUID();
        var map = map(session).snapshot().orElseThrow();
        var target = new ActionDsl.Position(DIMENSION, 3, 64, 0);
        var program = new ActionDsl.Program(
                1, Optional.empty(),
                Set.of(ActionDsl.Capability.CAMERA, ActionDsl.Capability.BLOCK_INTERACT),
                List.of(new ActionDsl.TillKnownBlock(
                        "till", target, "minecraft:dirt", "minecraft:iron_hoe")));

        assertThatThrownBy(() -> AgentPrimitivePlanner.analyze(
                program, map, new DeterministicAStar(),
                new AgentPrimitivePlanner.Pose(cell(1), 1.5D, 64, 0.5D, 1.62D, 0, 0),
                Optional.of(frame(target, ObservationRecord.Face.UP, "minecraft:dirt", 0)),
                4.5F))
                .isInstanceOf(AgentPrimitivePlanner.PlanningException.class)
                .extracting(failure -> ((AgentPrimitivePlanner.PlanningException) failure).code())
                .isEqualTo(AgentPrimitivePlanner.Code.TARGET_UNKNOWN);

        var distant = new ActionDsl.Position(DIMENSION, 6, 64, 0);
        var distantProgram = new ActionDsl.Program(
                1, Optional.empty(), program.capabilities(),
                List.of(new ActionDsl.TillKnownBlock(
                        "far", distant, "minecraft:dirt", "minecraft:iron_hoe")));
        assertThatThrownBy(() -> AgentPrimitivePlanner.analyze(
                distantProgram, map, new DeterministicAStar(),
                new AgentPrimitivePlanner.Pose(cell(0), 0.5D, 64, 0.5D, 1.62D, 0, 0),
                Optional.of(frame(distant, ObservationRecord.Face.UP, "minecraft:dirt", 0)),
                4.5F))
                .isInstanceOf(AgentPrimitivePlanner.PlanningException.class)
                .extracting(failure -> ((AgentPrimitivePlanner.PlanningException) failure).code())
                .isEqualTo(AgentPrimitivePlanner.Code.TARGET_UNKNOWN);
    }

    @Test
    void replanCostUsesOnlyTheRemainingDistanceToTheFirstWaypoint() {
        UUID session = UUID.randomUUID();
        var map = map(session);
        NavCell start = cell(0);
        NavCell target = cell(1);
        map.observe(confirmed(session, start, target));
        RoutePlan route = new DeterministicAStar()
                .findRoute(map.snapshot().orElseThrow(), start, target)
                .route().orElseThrow();

        var cost = AgentPrimitivePlanner.navigationCost(
                route,
                new AgentPrimitivePlanner.Pose(start, 0.6D, 64, 0.5D, 1.62D, 0, 0));

        assertThat(cost.distanceBlocks()).isEqualTo(1.35D);
    }

    @Test
    void navigationReplanDoesNotChargeReservedProbeTimeTwice() {
        UUID session = UUID.randomUUID();
        var map = map(session);
        NavCell start = cell(0);
        NavCell target = cell(1);
        map.observe(probeAllowed(session, start, target));
        RoutePlan route = new DeterministicAStar()
                .findRoute(map.snapshot().orElseThrow(), start, target)
                .route().orElseThrow();
        var planned = AgentPrimitivePlanner.navigationCost(
                route,
                new AgentPrimitivePlanner.Pose(start, 0.5D, 64, 0.5D, 1.62D, 0, 0));

        var retry = AgentPrimitivePlanner.navigationReplanCost(route, planned);

        assertThat(retry.ticks()).isEqualTo(
                planned.ticks() - RoutePlan.EXTRA_TICKS_PER_PROBE);
        assertThat(retry.durationMillis()).isEqualTo(
                planned.durationMillis() - RoutePlan.EXTRA_TICKS_PER_PROBE * 50L);
        assertThat(retry.distanceBlocks()).isEqualTo(planned.distanceBlocks());
    }

    private static ActionDsl.FaceKnownPosition face(String id, NavCell target) {
        return new ActionDsl.FaceKnownPosition(id, position(target));
    }

    private static AgentPrimitivePlanner.Analysis analyzeTillBatch(
            List<ActionDsl.Position> targets) {
        UUID session = UUID.randomUUID();
        var batch = new ActionDsl.TillKnownBatch(
                "batch", targets, "minecraft:dirt", "minecraft:iron_hoe");
        var program = new ActionDsl.Program(
                1,
                Optional.empty(),
                Set.of(ActionDsl.Capability.CAMERA, ActionDsl.Capability.BLOCK_INTERACT),
                List.of(batch));
        List<ObservationRecord.VisibleSurface> surfaces = targets.stream()
                .map(target -> surface(
                        target, ObservationRecord.Face.UP, "minecraft:dirt", null, 0))
                .toList();
        return AgentPrimitivePlanner.analyze(
                program,
                map(session).snapshot().orElseThrow(),
                new DeterministicAStar(),
                new AgentPrimitivePlanner.Pose(
                        cell(0), 0.5D, 64.0D, 0.5D, 1.62D, 0.0F, 0.0F),
                Optional.of(frame(surfaces)),
                4.5F);
    }

    private static List<ActionDsl.Position> mutationPositions(
            AgentPrimitivePlanner.MutationBatchPlan plan) {
        return plan.steps().stream().map(step -> switch (step.primitive()) {
            case ActionDsl.TillKnownBlock till -> till.target();
            case ActionDsl.PlantKnownWheat plant -> plant.target();
            case ActionDsl.HarvestKnownWheat harvest -> harvest.target();
            default -> throw new AssertionError("not a mutation child: " + step.primitive());
        }).toList();
    }

    private static ActionDsl.NavigateToKnown navigate(String id, NavCell target) {
        return new ActionDsl.NavigateToKnown(id, position(target), 0.75D);
    }

    private static ActionDsl.Position position(NavCell cell) {
        return new ActionDsl.Position(cell.dimension(), cell.x(), cell.y(), cell.z());
    }

    private static KnownTraversabilityMap map(UUID session) {
        var map = new KnownTraversabilityMap();
        map.startSession(session, DIMENSION, 0);
        return map;
    }

    private static NavCell cell(int x) {
        return new NavCell(DIMENSION, x, 64, 0);
    }

    private static TraversabilityEdge confirmed(UUID session, NavCell from, NavCell to) {
        return new TraversabilityEdge(
                session,
                new TraversabilityEdge.Key(from, to),
                TraversabilityEdge.Status.CONFIRMED,
                TraversabilityEdge.TargetSupport.CONFIRMED,
                TraversabilityEdge.Clearance.CONFIRMED,
                TraversabilityEdge.Transition.CONFIRMED,
                TraversabilityEdge.Fluid.NONE,
                TraversabilityEdge.Hazard.NONE,
                TraversabilityEdge.Provenance.LOCAL_VOLUME,
                from,
                1,
                0);
    }

    private static TraversabilityEdge probeAllowed(UUID session, NavCell from, NavCell to) {
        return new TraversabilityEdge(
                session,
                new TraversabilityEdge.Key(from, to),
                TraversabilityEdge.Status.PROBE_ALLOWED,
                TraversabilityEdge.TargetSupport.CONFIRMED,
                TraversabilityEdge.Clearance.CONFIRMED,
                TraversabilityEdge.Transition.PARTIAL,
                TraversabilityEdge.Fluid.NONE,
                TraversabilityEdge.Hazard.NONE,
                TraversabilityEdge.Provenance.LOCAL_VOLUME,
                from,
                1,
                0);
    }

    private static TraversabilityEdge blocked(UUID session, NavCell from, NavCell to) {
        return new TraversabilityEdge(
                session,
                new TraversabilityEdge.Key(from, to),
                TraversabilityEdge.Status.BLOCKED,
                TraversabilityEdge.TargetSupport.ABSENT,
                TraversabilityEdge.Clearance.UNKNOWN,
                TraversabilityEdge.Transition.UNKNOWN,
                TraversabilityEdge.Fluid.NONE,
                TraversabilityEdge.Hazard.NONE,
                TraversabilityEdge.Provenance.LOCAL_VOLUME,
                from,
                1,
                0);
    }

    private static ObservationFrame frame(
            ActionDsl.Position target,
            ObservationRecord.Face face,
            String block,
            long revision) {
        var dimension = new ObservationValues.ResourceId(target.dimension());
        return frame(List.of(surface(target, face, block, null, revision)));
    }

    private static ObservationRecord.VisibleEntity visibleItem(
            String item, ActionDsl.WorldPosition target, long revision) {
        var dimension = new ObservationValues.ResourceId(target.dimension());
        var position = new ObservationValues.WorldPosition(
                dimension, target.x(), target.y(), target.z());
        return new ObservationRecord.VisibleEntity(
                new ObservationValues.ResourceId("minecraft:item"),
                new ObservationValues.ResourceId(item),
                position,
                new ObservationValues.Vector(0.0D, 0.0D, 0.0D),
                new ObservationValues.Aabb(
                        target.x() - 0.125D, target.y(), target.z() - 0.125D,
                        target.x() + 0.125D, target.y() + 0.25D, target.z() + 0.125D),
                ObservationRecord.EntityHazardClass.PASSIVE,
                new ObservationValues.WorldPosition(dimension, 0.5D, 65.62D, 0.5D),
                1L,
                revision);
    }

    private static ObservationFrame entityFrame(ObservationRecord.VisibleEntity entity) {
        return entityFrame(List.of(entity));
    }

    private static ObservationFrame entityFrame(
            List<ObservationRecord.VisibleEntity> entities) {
        var dimension = new ObservationValues.ResourceId(DIMENSION);
        return new ObservationFrame(
                "obs-0000000000000001", dimension, 1, 16, false,
                List.copyOf(entities));
    }

    private static ObservationRecord.VisibleSurface surface(
            ActionDsl.Position target,
            ObservationRecord.Face face,
            String block,
            Boolean cropMature,
            long revision) {
        return surface(target, face, block, cropMature, revision, 65.62D);
    }

    private static ObservationRecord.VisibleSurface surface(
            ActionDsl.Position target,
            ObservationRecord.Face face,
            String block,
            Boolean cropMature,
            long revision,
            double eyeY) {
        var dimension = new ObservationValues.ResourceId(target.dimension());
        var eye = new ObservationValues.WorldPosition(dimension, 0.5, eyeY, 0.5);
        Vec3 hit = rayHit(target, face, block);
        return new ObservationRecord.VisibleSurface(
                new ObservationValues.BlockPosition(
                        dimension, target.x(), target.y(), target.z()),
                face,
                new ObservationValues.ResourceId(block),
                ObservationRecord.ShapeClass.OPAQUE,
                cropMature,
                new ObservationValues.WorldPosition(
                        dimension, hit.x, hit.y, hit.z),
                eye,
                1,
                revision);
    }

    private static ObservationRecord.VisibleSurface surfaceWithState(
            ActionDsl.Position target,
            ObservationRecord.Face face,
            String block,
            Map<String, String> properties,
            long revision) {
        return surfaceWithStateAtEye(
                target, face, block, properties, revision, 0.5D, 65.62D, 0.5D);
    }

    private static ObservationRecord.VisibleSurface surfaceWithStateAtEye(
            ActionDsl.Position target,
            ObservationRecord.Face face,
            String block,
            Map<String, String> properties,
            long revision,
            double eyeX,
            double eyeY,
            double eyeZ) {
        var dimension = new ObservationValues.ResourceId(target.dimension());
        Vec3 hit = rayHit(target, face, block);
        return new ObservationRecord.VisibleSurface(
                new ObservationValues.BlockPosition(
                        dimension, target.x(), target.y(), target.z()),
                face,
                new ObservationValues.ResourceId(block),
                new ObservationRecord.BlockStateView(
                        new ObservationValues.ResourceId(block), properties),
                new ObservationValues.ResourceId(block),
                ObservationRecord.ShapeClass.OPAQUE,
                null,
                new ObservationValues.WorldPosition(dimension, hit.x, hit.y, hit.z),
                new ObservationValues.WorldPosition(dimension, eyeX, eyeY, eyeZ),
                1L,
                revision);
    }

    private static float yawTo(double x, double z, Vec3 target) {
        return (float) (Math.toDegrees(Math.atan2(target.z - z, target.x - x)) - 90.0D);
    }

    private static float pitchTo(double x, double eyeY, double z, Vec3 target) {
        return (float) -Math.toDegrees(Math.atan2(
                target.y - eyeY, Math.hypot(target.x - x, target.z - z)));
    }

    private static Vec3 rayHit(
            ActionDsl.Position target, ObservationRecord.Face face, String block) {
        double x = target.x() + 0.5D;
        double y = target.y() + 0.5D;
        double z = target.z() + 0.5D;
        if ("minecraft:farmland".equals(block) && face == ObservationRecord.Face.UP) {
            y = target.y() + 0.9375D;
        } else {
            switch (face) {
                case DOWN -> y = target.y();
                case UP -> y = target.y() + 1.0D;
                case NORTH -> z = target.z();
                case SOUTH -> z = target.z() + 1.0D;
                case WEST -> x = target.x();
                case EAST -> x = target.x() + 1.0D;
            }
        }
        return new Vec3(x, y, z);
    }

    private static ObservationFrame frame(
            List<ObservationRecord.VisibleSurface> surfaces) {
        var dimension = new ObservationValues.ResourceId(DIMENSION);
        return new ObservationFrame(
                "obs-0000000000000001", dimension, 1, 16, false,
                new java.util.ArrayList<ObservationRecord>(surfaces));
    }
}
