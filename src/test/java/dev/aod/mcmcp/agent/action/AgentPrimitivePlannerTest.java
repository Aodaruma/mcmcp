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
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;
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

        assertThat(compiled.worstCaseCost().cameraDegrees()).isEqualTo(274.5D);
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
                .isEqualTo(Math.abs(-45.0D - (double) pose.yaw()));
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
        assertThat(analysis.mutationAims()).containsKey("open").doesNotContainKeys("inspect", "take");

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
    void containerCostUsesTheAdaptersBlockCenterRatherThanTheAdmissionRayHit() {
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
                        chest, ObservationRecord.Face.WEST, "minecraft:chest", 0)),
                4.5F);

        var actual = analysis.primitiveCosts().get("inspect");
        var centerCost = AgentPrimitivePlanner.mutationCost(
                pose, new Vec3(3.5D, 64.5D, 0.5D), 4.5F, 1, 0, 0);
        var rayHitCost = AgentPrimitivePlanner.mutationCost(
                pose, new Vec3(3.0D, 64.5D, 0.5D), 4.5F, 1, 0, 0);
        assertThat(actual.cameraDegrees()).isEqualTo(centerCost.cameraDegrees());
        assertThat(actual.cameraDegrees()).isNotEqualTo(rayHitCost.cameraDegrees());
        assertThat(actual.durationMillis()).isEqualTo(20_000L);
        assertThat(actual.ticks()).isEqualTo(AgentPrimitivePlanner.CONTAINER_TICK_UPPER_BOUND);
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
        var dimension = new ObservationValues.ResourceId(DIMENSION);
        return new ObservationFrame(
                "obs-0000000000000001", dimension, 1, 16, false,
                List.of(entity));
    }

    private static ObservationRecord.VisibleSurface surface(
            ActionDsl.Position target,
            ObservationRecord.Face face,
            String block,
            Boolean cropMature,
            long revision) {
        var dimension = new ObservationValues.ResourceId(target.dimension());
        var eye = new ObservationValues.WorldPosition(dimension, 0.5, 65.62, 0.5);
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
