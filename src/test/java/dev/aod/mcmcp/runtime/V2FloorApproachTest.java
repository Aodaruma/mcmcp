package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.navigation.*;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class V2FloorApproachTest {
    private static final UUID SESSION = UUID.randomUUID();
    private static final NavCell TARGET = new NavCell("minecraft:overworld", -719, 79, 248);
    private static final NavCell START = feet(-721, 247), CORNER = feet(-720, 247), SUPPORT = feet(-720, 248);

    @Test void displacedBuilderReturnsToTheWitnessedSupportInsteadOfTheEmptyPlacementCell() {
        var map = map(edge(START, CORNER), edge(CORNER, SUPPORT));
        var route = plan(new CoordinateGoalPlanner(SESSION, TARGET), map);
        assertThat(route.status()).isEqualTo(CoordinateGoalPlanner.Status.KNOWN_GOAL_ROUTE);
        assertThat(route.route().orElseThrow().cells()).containsExactly(START, CORNER, SUPPORT);
        assertThat(route.route().orElseThrow().cells()).doesNotContain(TARGET);
    }

    @Test void occupiedSupportWaitsWithoutExploringAndBecomesReachableWhenTheBodyLeaves() {
        var terrain = map(edge(START, CORNER), edge(CORNER, SUPPORT));
        var blocked = terrain.excludingEdges(edge -> edge.key().to().equals(SUPPORT));
        var planner = new CoordinateGoalPlanner(SESSION, TARGET);
        assertThat(plan(planner, blocked).status()).isEqualTo(CoordinateGoalPlanner.Status.BLOCKED);
        assertThat(plan(planner, blocked).route()).isEmpty();
        assertThat(plan(planner, terrain).route().orElseThrow().cells()).containsExactly(START, CORNER, SUPPORT);
    }

    @Test void unknownDiagonalOrOtherFloorDoesNotBecomeAStandingSupport() {
        assertThat(plan(new CoordinateGoalPlanner(SESSION, TARGET), map(edge(START, CORNER))).status())
                .isEqualTo(CoordinateGoalPlanner.Status.BLOCKED);
        assertThat(MinecraftV2PlaceDriver.floorSupportCell(TARGET, TARGET)).isFalse();
        assertThat(MinecraftV2PlaceDriver.floorSupportCell(TARGET, CORNER)).isFalse();
        assertThat(MinecraftV2PlaceDriver.floorSupportCell(TARGET,
                new NavCell("minecraft:the_nether", -720, 80, 248))).isFalse();
    }

    private static CoordinateGoalPlanner.Result plan(CoordinateGoalPlanner planner, KnownTraversabilitySnapshot map) {
        return planner.plan(map, START, SESSION, 0, CoordinateGoalPlanner.Budget.DEFAULT, () -> false,
                cell -> MinecraftV2PlaceDriver.floorSupportCell(TARGET, cell), false);
    }
    private static NavCell feet(int x, int z) { return new NavCell(TARGET.dimension(), x, 80, z); }
    private static KnownTraversabilitySnapshot map(TraversabilityEdge... edges) {
        var map = new KnownTraversabilityMap();
        map.startSession(SESSION, TARGET.dimension(), 0);
        for (var edge : edges) map.observe(edge);
        return map.snapshot().orElseThrow();
    }
    private static TraversabilityEdge edge(NavCell from, NavCell to) {
        return new TraversabilityEdge(SESSION, new TraversabilityEdge.Key(from, to),
                TraversabilityEdge.Status.CONFIRMED, TraversabilityEdge.TargetSupport.CONFIRMED,
                TraversabilityEdge.Clearance.CONFIRMED, TraversabilityEdge.Transition.CONFIRMED,
                TraversabilityEdge.Fluid.NONE, TraversabilityEdge.Hazard.NONE,
                TraversabilityEdge.Provenance.LOCAL_VOLUME, from, 1, 0);
    }
}
