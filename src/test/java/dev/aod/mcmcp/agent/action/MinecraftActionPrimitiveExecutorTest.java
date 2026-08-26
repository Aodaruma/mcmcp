package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.navigation.DeterministicAStar;
import dev.aod.mcmcp.agent.navigation.KnownTraversabilityMap;
import dev.aod.mcmcp.agent.navigation.KnownTraversabilitySnapshot;
import dev.aod.mcmcp.agent.navigation.NavCell;
import dev.aod.mcmcp.agent.navigation.RoutePlan;
import dev.aod.mcmcp.agent.navigation.TraversabilityEdge;
import dev.aod.mcmcp.routine.MovementInputLease;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MinecraftActionPrimitiveExecutorTest {
    private static final String DIMENSION = "minecraft:overworld";

    @Test
    void aimsInsideTheDeclaredBlockFaceRatherThanAtTheBlockCenter() {
        var target = new ActionDsl.Position(DIMENSION, 10, 64, 20);

        var west = MinecraftActionPrimitiveExecutor.blockFaceAimPoint(
                target, ActionDsl.BlockFace.WEST);
        var up = MinecraftActionPrimitiveExecutor.blockFaceAimPoint(
                target, ActionDsl.BlockFace.UP);

        assertThat(west.x).isEqualTo(10.001D);
        assertThat(west.y).isEqualTo(64.5D);
        assertThat(up.y).isEqualTo(64.999D);
    }

    @Test
    void rechecksDiagonalCornerProofBeforeMovement() {
        UUID session = UUID.randomUUID();
        var map = new KnownTraversabilityMap();
        map.startSession(session, DIMENSION, 4);
        NavCell start = cell(0, 64, 0);
        NavCell target = cell(1, 64, 1);
        NavCell xSide = cell(1, 64, 0);
        NavCell zSide = cell(0, 64, 1);
        map.observe(edge(session, start, target, TraversabilityEdge.Status.CONFIRMED, 1));
        map.observe(edge(session, start, xSide, TraversabilityEdge.Status.CONFIRMED, 1));
        map.observe(edge(session, start, zSide, TraversabilityEdge.Status.CONFIRMED, 1));

        RoutePlan route = new DeterministicAStar()
                .findRoute(map.snapshot().orElseThrow(), start, target)
                .route().orElseThrow();
        assertThat(MinecraftActionPrimitiveExecutor.edgeDecision(
                route, 0, map.snapshot().orElseThrow()))
                .isEqualTo(MinecraftActionPrimitiveExecutor.EdgeDecision.CONFIRMED);

        map.observe(edge(session, start, xSide, TraversabilityEdge.Status.BLOCKED, 2));
        assertThat(MinecraftActionPrimitiveExecutor.edgeDecision(
                route, 0, map.snapshot().orElseThrow()))
                .isEqualTo(MinecraftActionPrimitiveExecutor.EdgeDecision.REPLAN);
    }

    @Test
    void permitsOnlyProbeThatWasIncludedInCompiledRoute() {
        UUID session = UUID.randomUUID();
        NavCell start = cell(0, 64, 0);
        NavCell target = cell(1, 64, 0);
        var probeMap = new KnownTraversabilityMap();
        probeMap.startSession(session, DIMENSION, 4);
        probeMap.observe(edge(session, start, target, TraversabilityEdge.Status.PROBE_ALLOWED, 1));
        RoutePlan probeRoute = route(probeMap.snapshot().orElseThrow(), start, target);
        assertThat(MinecraftActionPrimitiveExecutor.edgeDecision(
                probeRoute, 0, probeMap.snapshot().orElseThrow()))
                .isEqualTo(MinecraftActionPrimitiveExecutor.EdgeDecision.PROBE);

        var confirmedMap = new KnownTraversabilityMap();
        confirmedMap.startSession(session, DIMENSION, 4);
        confirmedMap.observe(edge(session, start, target, TraversabilityEdge.Status.CONFIRMED, 1));
        RoutePlan confirmedRoute = route(confirmedMap.snapshot().orElseThrow(), start, target);
        confirmedMap.observe(edge(session, start, target, TraversabilityEdge.Status.PROBE_ALLOWED, 2));
        assertThat(MinecraftActionPrimitiveExecutor.edgeDecision(
                confirmedRoute, 0, confirmedMap.snapshot().orElseThrow()))
                .isEqualTo(MinecraftActionPrimitiveExecutor.EdgeDecision.REPLAN);
    }

    @Test
    void fencesEveryPlanToItsExactWorldRevision() {
        UUID session = UUID.randomUUID();
        var map = new KnownTraversabilityMap();
        map.startSession(session, DIMENSION, 9);
        KnownTraversabilitySnapshot snapshot = map.snapshot().orElseThrow();
        assertThat(MinecraftActionPrimitiveExecutor.boundaryDecision(
                session, DIMENSION, 9, snapshot))
                .isEqualTo(MinecraftActionPrimitiveExecutor.BoundaryDecision.CURRENT);
        map.advanceWorldRevision(10, List.of(), List.of());
        assertThat(MinecraftActionPrimitiveExecutor.boundaryDecision(
                session, DIMENSION, 9, map.snapshot().orElseThrow()))
                .isEqualTo(MinecraftActionPrimitiveExecutor.BoundaryDecision.REVISION_CHANGED);
    }

    @Test
    void unrelatedWorldRevisionDoesNotInvalidateAnUnchangedRouteEdge() {
        UUID session = UUID.randomUUID();
        NavCell start = cell(0, 64, 0);
        NavCell target = cell(1, 64, 0);
        var map = new KnownTraversabilityMap();
        map.startSession(session, DIMENSION, 4);
        map.observe(edge(session, start, target, TraversabilityEdge.Status.CONFIRMED, 1));
        RoutePlan route = route(map.snapshot().orElseThrow(), start, target);

        map.advanceWorldRevision(5, List.of(), List.of());

        assertThat(MinecraftActionPrimitiveExecutor.edgeDecision(
                route, 0, map.snapshot().orElseThrow()))
                .isEqualTo(MinecraftActionPrimitiveExecutor.EdgeDecision.CONFIRMED);
    }

    @Test
    void boundsViewChangesAndAcceptsOnlyTheSweptRouteCorridor() {
        float configuredLimit = 4.5F;
        assertThatThrownBy(() -> new MinecraftActionPrimitiveExecutor(0.0F))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(MinecraftActionPrimitiveExecutor.boundedYawDelta(
                179.0F, -179.0F, configuredLimit))
                .isEqualTo(2.0F);
        assertThat(MinecraftActionPrimitiveExecutor.boundedPitchDelta(
                0.0F, 80.0F, configuredLimit))
                .isEqualTo(configuredLimit);
        var diagonal = new TraversabilityEdge.Key(cell(0, 64, 0), cell(1, 64, 1));
        assertThat(MinecraftActionPrimitiveExecutor.insideRouteCorridor(
                1.0D, 64.0D, 1.0D, diagonal)).isTrue();
        assertThat(MinecraftActionPrimitiveExecutor.insideRouteCorridor(
                0.5D, 64.0D, 2.0D, diagonal)).isFalse();
        assertThat(MinecraftActionPrimitiveExecutor.steering(
                0.5D, 0.5D, 0.0F, cell(0, 64, 1)))
                .containsExactly(MovementInputLease.MovementKey.FORWARD);
        assertThat(MinecraftActionPrimitiveExecutor.steering(
                0.5D, 0.5D, 0.0F, cell(1, 64, 0)))
                .containsExactly(MovementInputLease.MovementKey.LEFT);
        assertThat(MinecraftActionPrimitiveExecutor.steering(
                0.4D, 0.4D, 0.0F, cell(0, 64, 0), 0.10D))
                .isNotEmpty();
        assertThat(MinecraftActionPrimitiveExecutor.steering(
                0.4D, 0.5D, 0.0F, 0.2D, 0.5D, 0.05D))
                .containsExactly(MovementInputLease.MovementKey.RIGHT);
        assertThat(MinecraftActionPrimitiveExecutor.steering(
                0.4D, 0.5D, 0.0F, 0.4D, 0.5D, 0.05D))
                .isEmpty();
        var diagonalCommand = MinecraftActionPrimitiveExecutor.commandDirection(
                30.0F,
                java.util.Set.of(
                        MovementInputLease.MovementKey.FORWARD,
                        MovementInputLease.MovementKey.LEFT));
        assertThat(diagonalCommand.horizontalDistance()).isCloseTo(
                1.0D, org.assertj.core.data.Offset.offset(1.0e-12D));
        assertThat(MinecraftActionPrimitiveExecutor.commandDirection(
                0.0F, java.util.Set.of(MovementInputLease.MovementKey.FORWARD)))
                .isEqualTo(new net.minecraft.world.phys.Vec3(0.0D, 0.0D, 1.0D));
        assertThat(MinecraftActionPrimitiveExecutor.commandDirection(
                0.0F, java.util.Set.of(MovementInputLease.MovementKey.LEFT)))
                .isEqualTo(new net.minecraft.world.phys.Vec3(1.0D, 0.0D, 0.0D));
        assertThat(MinecraftActionPrimitiveExecutor.navigationOutputAllowed(35, 36)).isTrue();
        assertThat(MinecraftActionPrimitiveExecutor.navigationOutputAllowed(36, 36)).isFalse();
        var waypoint = cell(1, 64, 0);
        assertThat(MinecraftActionPrimitiveExecutor.waypointReached(
                1.5D, 64.7D, 0.5D, waypoint, 0.75D)).isTrue();
        assertThat(MinecraftActionPrimitiveExecutor.waypointReached(
                0.99D, 64.0D, 0.5D, waypoint, 0.75D)).isFalse();
        assertThat(MinecraftActionPrimitiveExecutor.waypointReached(
                1.5D, 63.99D, 0.5D, waypoint, 0.75D)).isFalse();
    }

    private static RoutePlan route(
            KnownTraversabilitySnapshot snapshot, NavCell start, NavCell target) {
        return new DeterministicAStar().findRoute(snapshot, start, target).route().orElseThrow();
    }

    private static NavCell cell(int x, int y, int z) {
        return new NavCell(DIMENSION, x, y, z);
    }

    private static TraversabilityEdge edge(
            UUID session,
            NavCell from,
            NavCell to,
            TraversabilityEdge.Status status,
            long tick) {
        return new TraversabilityEdge(
                session,
                new TraversabilityEdge.Key(from, to),
                status,
                status == TraversabilityEdge.Status.BLOCKED
                        ? TraversabilityEdge.TargetSupport.ABSENT
                        : TraversabilityEdge.TargetSupport.CONFIRMED,
                TraversabilityEdge.Clearance.CONFIRMED,
                status == TraversabilityEdge.Status.PROBE_ALLOWED
                        ? TraversabilityEdge.Transition.PARTIAL
                        : TraversabilityEdge.Transition.CONFIRMED,
                TraversabilityEdge.Fluid.NONE,
                TraversabilityEdge.Hazard.NONE,
                TraversabilityEdge.Provenance.LOCAL_VOLUME,
                from,
                tick,
                4);
    }
}
