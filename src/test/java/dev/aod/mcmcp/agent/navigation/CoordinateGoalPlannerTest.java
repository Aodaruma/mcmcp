package dev.aod.mcmcp.agent.navigation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.aod.mcmcp.agent.navigation.CoordinateGoalPlanner.Status.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CoordinateGoalPlannerTest {
    private static final UUID SESSION = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final CoordinateGoalPlanner.Budget DEFAULT = CoordinateGoalPlanner.Budget.DEFAULT;

    @Test
    void unknownFarGoalReturnsOnlyReachableKnownSegmentAndDoesNotReissueIt() {
        var map = map(edge(cell(0, 0), cell(1, 0)), edge(cell(1, 0), cell(2, 0)));
        var planner = new CoordinateGoalPlanner(SESSION, cell(10000, 0));
        var result = plan(planner, map, cell(0, 0));
        assertThat(result.status()).isEqualTo(PARTIAL_WAYPOINT);
        assertThat(result.route().orElseThrow().cells()).containsExactly(cell(0, 0), cell(1, 0), cell(2, 0));
        assertThat(plan(planner, map, cell(0, 0)).status()).isEqualTo(BLOCKED);
        assertThat(plan(planner, map, cell(2, 0)).route()).isEmpty();
        // New observations can extend the segment without a block mutation/revision change.
        map.observe(edge(cell(2, 0), cell(3, 0)));
        assertThat(plan(planner, map, cell(2, 0)).route().orElseThrow().cells())
                .containsExactly(cell(2, 0), cell(3, 0));
    }

    @Test
    void distinguishesKnownGoalRouteFromActualArrivalAndUnknownStart() {
        var map = map(edge(cell(0, 0), cell(1, 0)));
        var planner = new CoordinateGoalPlanner(SESSION, cell(1, 0));
        assertThat(plan(planner, map, cell(0, 0)).status()).isEqualTo(KNOWN_GOAL_ROUTE);
        var reached = plan(planner, map, cell(1, 0));
        assertThat(reached.status()).isEqualTo(REACHED_KNOWN_GOAL);
        assertThat(reached.route().orElseThrow().edges()).isEmpty();
        assertThat(plan(new CoordinateGoalPlanner(SESSION, cell(9, 0)), map(), cell(9, 0)).status())
                .isEqualTo(BLOCKED);
    }

    @Test
    void routesAroundBlockedEdgeAndSkipsUnreachableClosestCandidate() {
        var map = map(blocked(cell(0, 0), cell(1, 0)),
                edge(cell(0, 0), cell(0, 1)), edge(cell(0, 1), cell(1, 1)),
                edge(cell(1, 1), cell(2, 1)), edge(cell(8, 0), cell(9, 0)));
        var result = plan(new CoordinateGoalPlanner(SESSION, cell(100, 0)), map, cell(0, 0));
        assertThat(result.status()).isEqualTo(PARTIAL_WAYPOINT);
        assertThat(result.route().orElseThrow().cells())
                .containsExactly(cell(0, 0), cell(0, 1), cell(1, 1), cell(2, 1));
        assertThat(result.candidates()).isEqualTo(1);
    }

    @Test
    void diagonalCannotCutUnknownCornerButUsesExistingProofWhenAvailable() {
        var diagonal = edge(cell(0, 0), cell(1, 1));
        var map = map(diagonal);
        var planner = new CoordinateGoalPlanner(SESSION, cell(100, 100));
        assertThat(plan(planner, map, cell(0, 0)).status()).isEqualTo(BLOCKED);
        map.observe(edge(cell(0, 0), cell(1, 0)));
        map.observe(edge(cell(0, 0), cell(0, 1)));
        assertThat(plan(planner, map, cell(0, 0)).route().orElseThrow().edges()).containsExactly(diagonal);
    }

    @Test
    void staleDirectDiagonalProofCannotBypassAStarGate() {
        var ordinary = edge(cell(0, 0), cell(1, 1));
        var diagonal = new TraversabilityEdge(SESSION, ordinary.key(), ordinary.status(),
                ordinary.targetSupport(), ordinary.clearance(), ordinary.transition(), ordinary.fluid(),
                ordinary.hazard(), ordinary.provenance(), ordinary.observerPosition(), 1, 0,
                ordinary.locomotion(), true);
        var map = map(diagonal);
        assertThat(plan(new CoordinateGoalPlanner(SESSION, cell(100, 100)), map, cell(0, 0)).status())
                .isEqualTo(PARTIAL_WAYPOINT);
        map.advanceWorldRevision(1, List.of(), List.of());
        assertThat(plan(new CoordinateGoalPlanner(SESSION, cell(100, 100)), map, cell(0, 0)).status())
                .isEqualTo(BLOCKED);
    }

    @Test
    void deterministicCandidateTieBreakIgnoresInsertionOrder() {
        var a = edge(cell(0, 0), cell(1, 0));
        var b = edge(cell(0, 0), cell(0, 1));
        var first = plan(new CoordinateGoalPlanner(SESSION, cell(100, 100)), map(a, b), cell(0, 0));
        var second = plan(new CoordinateGoalPlanner(SESSION, cell(100, 100)), map(b, a), cell(0, 0));
        assertThat(first).isEqualTo(second);
        assertThat(first.route().orElseThrow().cells()).containsExactly(cell(0, 0), cell(0, 1));
    }

    @Test
    void detourCanMoveAwayFromGoalButWaitsForFreshEvidenceBeforeContinuing() {
        var map = map(edge(cell(0, 0), cell(-1, 0)), edge(cell(0, 0), cell(0, 1)));
        var planner = new CoordinateGoalPlanner(SESSION, cell(100, 0));
        var result = plan(planner, map, cell(0, 0));
        assertThat(result.status()).isEqualTo(PARTIAL_WAYPOINT);
        assertThat(result.route().orElseThrow().cells()).containsExactly(cell(0, 0), cell(0, 1));
        assertThat(plan(planner, map, cell(0, 1)).status()).isEqualTo(BLOCKED);
        assertThat(plan(planner, map, cell(0, 0)).status()).isEqualTo(BLOCKED);

        // Freshly observed space around the corner permits another bounded segment.
        map.observe(edge(cell(0, 1), cell(1, 1)));
        assertThat(plan(planner, map, cell(0, 1)).route().orElseThrow().cells())
                .containsExactly(cell(0, 1), cell(1, 1));
        assertThat(plan(planner, map, cell(1, 1)).status()).isEqualTo(BLOCKED);
    }

    @Test
    void evidenceUpdateDoesNotReissuePreviouslyVisitedDeadEnd() {
        var map = map(edge(cell(0, 0), cell(0, 1)), edge(cell(0, 1), cell(0, 0)));
        var planner = new CoordinateGoalPlanner(SESSION, cell(100, 0));
        assertThat(plan(planner, map, cell(0, 0)).route().orElseThrow().cells())
                .containsExactly(cell(0, 0), cell(0, 1));
        map.observe(edge(cell(0, 0), cell(-1, 0)));
        var next = plan(planner, map, cell(0, 1));
        assertThat(next.status()).isEqualTo(PARTIAL_WAYPOINT);
        assertThat(next.route().orElseThrow().cells())
                .containsExactly(cell(0, 1), cell(0, 0), cell(-1, 0));
        assertThat(plan(planner, map, cell(-1, 0)).status()).isEqualTo(BLOCKED);
    }

    @Test
    void finiteBudgetsStopWithoutReturningUncheckedOrPartialSearchRoutes() {
        var map = map(edge(cell(0, 0), cell(1, 0)), edge(cell(1, 0), cell(2, 0)));
        for (var budget : List.of(new CoordinateGoalPlanner.Budget(1, 64, 2048),
                new CoordinateGoalPlanner.Budget(4096, 0, 2048),
                new CoordinateGoalPlanner.Budget(4096, 64, 0),
                new CoordinateGoalPlanner.Budget(4096, 64, 1))) {
            var result = new CoordinateGoalPlanner(SESSION, cell(100, 0)).plan(
                    map.snapshot().orElseThrow(), cell(0, 0), SESSION, 0, budget, () -> false);
            assertThat(result.status()).isEqualTo(LIMIT);
            assertThat(result.route()).isEmpty();
            assertThat(result.expansions()).isLessThanOrEqualTo(budget.expansions());
        }
        assertThatThrownBy(() -> new CoordinateGoalPlanner.Budget(4097, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void disconnectedVisibleTerrainDoesNotExhaustCandidateBudget() {
        var map = map(edge(cell(0, 0), cell(1, 0)));
        for (int x = 80; x < 170; x++) {
            map.observe(edge(cell(x, 2), cell(x + 1, 2)));
        }
        var result = new CoordinateGoalPlanner(SESSION, cell(200, 0)).plan(
                map.snapshot().orElseThrow(), cell(0, 0), SESSION, 0,
                new CoordinateGoalPlanner.Budget(4096, 1, 2048), () -> false);
        assertThat(result.status()).isEqualTo(PARTIAL_WAYPOINT);
        assertThat(result.route().orElseThrow().cells()).containsExactly(cell(0, 0), cell(1, 0));
        assertThat(result.candidates()).isEqualTo(1);
    }

    @Test
    void cancellationDuringPlanningAndInterruptionDoNotConsumeProgress() {
        var map = map(edge(cell(0, 0), cell(1, 0)), edge(cell(1, 0), cell(2, 0)));
        var planner = new CoordinateGoalPlanner(SESSION, cell(100, 0));
        var polls = new AtomicInteger();
        var result = planner.plan(map.snapshot().orElseThrow(), cell(0, 0), SESSION, 0,
                DEFAULT, () -> polls.incrementAndGet() >= 7);
        assertThat(result.status()).isEqualTo(CANCELLED);
        assertThat(result.expansions()).isZero();
        assertThat(result.route()).isEmpty();
        Thread.currentThread().interrupt();
        try {
            assertThat(plan(planner, map, cell(0, 0)).status()).isEqualTo(CANCELLED);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(plan(planner, map, cell(0, 0)).status()).isEqualTo(PARTIAL_WAYPOINT);
    }

    @Test
    void longKnownCorridorStillRespectsExistingRouteDistanceBudget() {
        var map = map();
        for (int x = 0; x < 30; x++) map.observe(edge(cell(x, 0), cell(x + 1, 0)));
        var result = plan(new CoordinateGoalPlanner(SESSION, cell(10000, 0)), map, cell(0, 0));
        assertThat(result.status()).isEqualTo(PARTIAL_WAYPOINT);
        var route = result.route().orElseThrow();
        assertThat(route.cells().getLast().x())
                .isEqualTo((int) Math.floor(DeterministicAStar.MAX_ROUTE_DISTANCE_BLOCKS));
        assertThat(NavigationDistanceBudget.searchCostFits(NavigationDistanceBudget.centerlineRouteCost(route)))
                .isTrue();
    }

    @Test
    void cancellationInsideAStarRemainsTerminalEvenWhenSignalIsTransient() {
        var map = map(edge(cell(0, 0), cell(1, 0)), edge(cell(1, 0), cell(2, 0)));
        var polls = new AtomicInteger();
        var result = new CoordinateGoalPlanner(SESSION, cell(100, 0)).plan(
                map.snapshot().orElseThrow(), cell(0, 0), SESSION, 0,
                DEFAULT, () -> polls.incrementAndGet() == 7);
        assertThat(result.status()).isEqualTo(CANCELLED);
        assertThat(result.route()).isEmpty();
    }

    @Test
    void rejectsBoundaryMismatchRollbackAndStaleEdges() {
        var map = map(edge(cell(0, 0), cell(1, 0)));
        var snapshot = map.snapshot().orElseThrow();
        var planner = new CoordinateGoalPlanner(SESSION, cell(100, 0));
        assertThat(planner.plan(snapshot, cell(0, 0), UUID.randomUUID(), 0, DEFAULT, () -> false).status())
                .isEqualTo(WORLD_MISMATCH);
        assertThat(planner.plan(snapshot, cell(0, 0), SESSION, 1, DEFAULT, () -> false).status())
                .isEqualTo(STALE_MAP);
        assertThat(planner.plan(snapshot, new NavCell("other", 0, 64, 0), SESSION, 0,
                DEFAULT, () -> false).status()).isEqualTo(WORLD_MISMATCH);
        var otherWorld = new KnownTraversabilitySnapshot(UUID.randomUUID(), "overworld", 0, snapshot.edges());
        assertThat(planner.plan(otherWorld, cell(0, 0), SESSION, 0, DEFAULT, () -> false).status())
                .isEqualTo(WORLD_MISMATCH);
        map.advanceWorldRevision(1, List.of(cell(1, 0)), List.of());
        assertThat(plan(planner, map, cell(0, 0)).status()).isEqualTo(BLOCKED);
        assertThat(planner.plan(snapshot, cell(0, 0), SESSION, 0, DEFAULT, () -> false).status())
                .isEqualTo(STALE_MAP);
    }

    @Test
    void rejectsForeignOrFutureEvidenceEvenInMalformedSnapshot() {
        var edge = edge(cell(0, 0), cell(1, 0));
        var foreign = new TraversabilityEdge(UUID.randomUUID(), edge.key(), edge.status(),
                edge.targetSupport(), edge.clearance(), edge.transition(), edge.fluid(), edge.hazard(),
                edge.provenance(), edge.observerPosition(), 1, 0);
        var future = new TraversabilityEdge(SESSION, edge.key(), edge.status(),
                edge.targetSupport(), edge.clearance(), edge.transition(), edge.fluid(), edge.hazard(),
                edge.provenance(), edge.observerPosition(), 1, 1);
        for (var invalid : List.of(foreign, future)) {
            var edges = new TreeMap<TraversabilityEdge.Key, TraversabilityEdge>();
            edges.put(invalid.key(), invalid);
            var result = new CoordinateGoalPlanner(SESSION, cell(100, 0)).plan(
                    new KnownTraversabilitySnapshot(SESSION, "overworld", 0, edges), cell(0, 0),
                    SESSION, 0, DEFAULT, () -> false);
            assertThat(result.status()).isEqualTo(invalid == foreign ? WORLD_MISMATCH : STALE_MAP);
            assertThat(result.route()).isEmpty();
        }
    }

    private static CoordinateGoalPlanner.Result plan(CoordinateGoalPlanner planner,
            KnownTraversabilityMap map, NavCell start) {
        var snapshot = map.snapshot().orElseThrow();
        return planner.plan(snapshot, start, SESSION, snapshot.worldRevision(), DEFAULT, () -> false);
    }

    private static KnownTraversabilityMap map(TraversabilityEdge... edges) {
        var map = new KnownTraversabilityMap();
        map.startSession(SESSION, "overworld", 0);
        for (var edge : edges) map.observe(edge);
        return map;
    }

    private static NavCell cell(int x, int z) {
        return new NavCell("overworld", x, 64, z);
    }

    private static TraversabilityEdge edge(NavCell from, NavCell to) {
        return new TraversabilityEdge(SESSION, new TraversabilityEdge.Key(from, to),
                TraversabilityEdge.Status.CONFIRMED, TraversabilityEdge.TargetSupport.CONFIRMED,
                TraversabilityEdge.Clearance.CONFIRMED, TraversabilityEdge.Transition.CONFIRMED,
                TraversabilityEdge.Fluid.NONE, TraversabilityEdge.Hazard.NONE,
                TraversabilityEdge.Provenance.LOCAL_VOLUME, from, 1, 0);
    }

    private static TraversabilityEdge blocked(NavCell from, NavCell to) {
        return new TraversabilityEdge(SESSION, new TraversabilityEdge.Key(from, to),
                TraversabilityEdge.Status.BLOCKED, TraversabilityEdge.TargetSupport.ABSENT,
                TraversabilityEdge.Clearance.UNKNOWN, TraversabilityEdge.Transition.UNKNOWN,
                TraversabilityEdge.Fluid.NONE, TraversabilityEdge.Hazard.CAUTION,
                TraversabilityEdge.Provenance.LOCAL_VOLUME, from, 1, 0);
    }
}
