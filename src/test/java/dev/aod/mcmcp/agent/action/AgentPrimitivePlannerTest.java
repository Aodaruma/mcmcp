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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
                        1.0D + Math.sqrt(0.5D), 0.75D)));
        assertThat(compiled.worstCaseCost().distanceBlocks())
                .isEqualTo(1.5D * (5.0D + Math.hypot(
                        1.0D + Math.sqrt(0.5D), 0.75D)));
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

    private static ObservationFrame frame(
            ActionDsl.Position target,
            ObservationRecord.Face face,
            String block,
            long revision) {
        var dimension = new ObservationValues.ResourceId(target.dimension());
        var eye = new ObservationValues.WorldPosition(dimension, 0.5, 65.62, 0.5);
        var surface = new ObservationRecord.VisibleSurface(
                new ObservationValues.BlockPosition(
                        dimension, target.x(), target.y(), target.z()),
                face,
                new ObservationValues.ResourceId(block),
                ObservationRecord.ShapeClass.OPAQUE,
                eye,
                1,
                revision);
        return new ObservationFrame(
                "obs-0000000000000001", dimension, 1, 16, false, List.of(surface));
    }
}
