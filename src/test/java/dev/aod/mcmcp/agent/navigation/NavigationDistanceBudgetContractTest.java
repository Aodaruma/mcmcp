package dev.aod.mcmcp.agent.navigation;

import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.dsl.ActionDslCompiler;
import dev.aod.mcmcp.agent.dsl.ActionDslValidator;
import dev.aod.mcmcp.agent.safety.LocalObservationVolume;
import dev.aod.mcmcp.agent.safety.ObservationRecord;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NavigationDistanceBudgetContractTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final ActionDsl.Budget PUBLIC_MAXIMUM = new ActionDsl.Budget(
            ActionDslValidator.MAX_ACTION_DURATION_MILLIS,
            ActionDslValidator.MAX_ACTION_TICKS,
            NavigationDistanceBudget.MAX_DISTANCE_BLOCKS,
            ActionDslValidator.MAX_ACTION_CAMERA_DEGREES,
            ActionDslValidator.MAX_INTERACTIONS,
            ActionDslValidator.MAX_BLOCKS_BROKEN,
            ActionDslValidator.MAX_BLOCKS_PLACED);

    @Test
    void everyAStarRouteFitsThePublicMaximumForEveryBoundedStartPose() {
        int returnedRoutes = 0;

        for (int[] direction : adjacentDirections()) {
            UUID session = UUID.randomUUID();
            NavCell start = new NavCell(OVERWORLD, 0, 64, 0);
            var map = map(session);
            NavCell previous = start;
            for (int step = 1; step <= 24; step++) {
                NavCell next = start.offset(
                        direction[0] * step, direction[1] * step, direction[2] * step);
                map.observe(confirmed(session, previous, next));
                if (direction[0] != 0 && direction[2] != 0) {
                    map.observe(confirmed(session, previous, new NavCell(
                            OVERWORLD, next.x(), previous.y(), previous.z())));
                    map.observe(confirmed(session, previous, new NavCell(
                            OVERWORLD, previous.x(), previous.y(), next.z())));
                }
                previous = next;
            }
            KnownTraversabilitySnapshot snapshot = map.snapshot().orElseThrow();
            for (int step = 0; step <= 24; step++) {
                NavCell target = start.offset(
                        direction[0] * step, direction[1] * step, direction[2] * step);
                var result = new DeterministicAStar().findRoute(snapshot, start, target);
                if (result.route().isEmpty()) continue;
                returnedRoutes++;
                RoutePlan route = result.route().orElseThrow();
                for (AgentPrimitivePlanner.Pose pose : boundedStartPoses(start)) {
                    ActionDslCompiler.Cost cost =
                            AgentPrimitivePlanner.navigationCost(route, pose);
                    assertThat(cost.distanceBlocks())
                            .as("direction=%s step=%s pose=%s",
                                    List.of(direction[0], direction[1], direction[2]), step, pose)
                            .isLessThanOrEqualTo(NavigationDistanceBudget.MAX_DISTANCE_BLOCKS);
                    assertThatCode(() ->
                            ActionDslCompiler.requireWithinBudget(cost, PUBLIC_MAXIMUM))
                            .doesNotThrowAnyException();
                }
            }
        }
        assertThat(returnedRoutes).isGreaterThan(26);
    }

    @Test
    void everyNavigationTargetInAFreshLocalProjectionCompilesAtThePublicMaximum() {
        UUID session = UUID.randomUUID();
        var center = new ObservationRecord.Point(0.5D, 64.9D, 0.5D);
        var current = safetyRecord(center, center, 0);
        var transitions = new ArrayList<ObservationRecord>();
        for (int[] direction : adjacentDirections()) {
            transitions.add(safetyRecord(
                    center,
                    new ObservationRecord.Point(
                            center.x() + direction[0],
                            center.y() + direction[1],
                            center.z() + direction[2]),
                    1));
        }
        for (int step = 2; step <= 6; step++) {
            transitions.add(safetyRecord(
                    new ObservationRecord.Point(step - 0.5D, 64.9D, 0.5D),
                    new ObservationRecord.Point(step + 0.5D, 64.9D, 0.5D),
                    step));
        }
        LocalObservationProjector.Projection projection = LocalObservationProjector.project(
                new LocalObservationVolume.Snapshot(
                        10L, 0L, center, current, transitions),
                session, OVERWORLD, 0L, 64.0D);
        var map = map(session);
        projection.edges().forEach(map::observe);
        KnownTraversabilitySnapshot snapshot = map.snapshot().orElseThrow();
        var startPose = new AgentPrimitivePlanner.Pose(
                new NavCell(OVERWORLD, 0, 64, 0),
                0.5D, 64.0D, 0.5D, 1.62D, 0.0F, 0.0F);

        assertThat(projection.records()).isNotEmpty();
        for (var record : projection.records()) {
            var traversability = (dev.aod.mcmcp.agent.observation.ObservationRecord.Traversability)
                    record;
            var published = traversability.navigationTarget();
            var target = new ActionDsl.Position(
                    published.dimension().value(), published.x(), published.y(), published.z());
            var navigate = new ActionDsl.NavigateToKnown("go", target, 0.75D);
            var program = new ActionDsl.Program(
                    1, Optional.empty(), Set.of(ActionDsl.Capability.MOVEMENT), List.of(navigate));
            var analysis = AgentPrimitivePlanner.analyze(
                    program, snapshot, new DeterministicAStar(), startPose,
                    Optional.empty(), 4.5F);
            var request = new ActionDsl.Request(1, program, PUBLIC_MAXIMUM);

            assertThatCode(() -> ActionDslCompiler.compile(
                    request, analysis::worstCase, Set.of(ActionDsl.Capability.MOVEMENT)))
                    .as("published navigation target %s", target)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void rejectsAStartPoseOutsideTheEnvelopeReservedByAStar() {
        UUID session = UUID.randomUUID();
        NavCell start = new NavCell(OVERWORLD, 0, 64, 0);
        NavCell target = start.offset(1, 0, 0);
        var map = map(session);
        map.observe(confirmed(session, start, target));
        RoutePlan route = new DeterministicAStar()
                .findRoute(map.snapshot().orElseThrow(), start, target)
                .route().orElseThrow();
        var outside = new AgentPrimitivePlanner.Pose(
                start, start.x() - 0.01D, start.y(), start.z() + 0.5D,
                1.62D, 0.0F, 0.0F);

        assertThatThrownBy(() -> AgentPrimitivePlanner.navigationCost(route, outside))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bounded starting-cell envelope");
    }

    private static List<AgentPrimitivePlanner.Pose> boundedStartPoses(NavCell start) {
        double maximumHorizontalError =
                NavigationDistanceBudget.MAX_HORIZONTAL_POSITION_ERROR;
        var poses = new ArrayList<AgentPrimitivePlanner.Pose>(9);
        for (int x = 0; x <= 1; x++) {
            for (int y = 0; y <= 1; y++) {
                for (int z = 0; z <= 1; z++) {
                    poses.add(new AgentPrimitivePlanner.Pose(
                            start, start.x() + x, start.y() + y, start.z() + z,
                            1.62D, 0.0F, 0.0F,
                            maximumHorizontalError, 1.0D, 1.0D, 0.0D));
                }
            }
        }
        poses.add(new AgentPrimitivePlanner.Pose(
                start, start.x() + 0.5D, start.y(), start.z() + 0.5D,
                1.62D, 0.0F, 0.0F));
        return List.copyOf(poses);
    }

    private static List<int[]> adjacentDirections() {
        var directions = new ArrayList<int[]>(26);
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (x != 0 || y != 0 || z != 0) {
                        directions.add(new int[] {x, y, z});
                    }
                }
            }
        }
        return List.copyOf(directions);
    }

    private static KnownTraversabilityMap map(UUID session) {
        var map = new KnownTraversabilityMap();
        map.startSession(session, OVERWORLD, 0L);
        return map;
    }

    private static TraversabilityEdge confirmed(
            UUID session, NavCell from, NavCell to) {
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
                10L,
                0L);
    }

    private static ObservationRecord safetyRecord(
            ObservationRecord.Point from,
            ObservationRecord.Point to,
            int depth) {
        return new ObservationRecord(
                10L,
                0L,
                depth,
                from,
                to,
                to,
                ObservationRecord.Support.PRESENT,
                ObservationRecord.Clearance.CLEAR,
                depth == 0
                        ? ObservationRecord.Transition.STATIONARY
                        : ObservationRecord.Transition.CONTACT,
                ObservationRecord.Fluid.NONE,
                false,
                ObservationRecord.Hazard.NONE,
                ObservationRecord.LoadedState.LOADED,
                ObservationRecord.Drop.SUPPORTED,
                false);
    }
}
