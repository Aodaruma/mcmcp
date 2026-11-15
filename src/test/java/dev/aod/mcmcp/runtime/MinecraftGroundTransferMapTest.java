package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.navigation.*;
import dev.aod.mcmcp.agent.safety.GroundTraversal;
import dev.aod.mcmcp.agent.safety.Locomotion;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MinecraftGroundTransferMapTest {
    private static final UUID SESSION = UUID.fromString("00000000-0000-0000-0000-000000000098");
    private static final NavCell NORTH = cell(199), RUNG = cell(200), SOUTH = cell(201);

    @Test
    void safeActualTakeoffCanBeBlockedAfterCenteringWithinTheOldTolerance() {
        for (boolean mirrored : List.of(false, true)) {
            var initial = body(mirrored ? 405 - 202.42891572207378 : 202.42891572207378,
                    199.38901230735303);
            var stopped = body(mirrored ? 405 - 202.58053958960556 : 202.58053958960556,
                    199.5406361748848);
            var ladder = ladderShape(mirrored);
            assertThat(jumpCorridor(MinecraftGroundTransferMap.sourceBody(initial, NORTH, NORTH))
                    .intersects(ladder)).isFalse();
            assertThat(Math.hypot(stopped.getCenter().x - 202.5, stopped.getCenter().z - 199.5))
                    .isLessThan(0.12);
            assertThat(jumpCorridor(MinecraftGroundTransferMap.sourceBody(stopped, NORTH, NORTH))
                    .intersects(ladder)).isTrue();
            assertThat(jumpCorridor(body(202.5, 199.5)).intersects(ladder)).isFalse();
        }
    }

    @Test
    void refreshedEvidenceDoesNotReintroduceAnUnsafeSourceArcInsteadOfTheLadderRoute() {
        var stopped = body(202.58053958960556, 199.5406361748848);
        var ladder = ladderShape(false);
        for (int tick = 1; tick <= 9; tick++) {
            // Collision-only model of the dry, loaded fixture: all other arc proofs are unchanged.
            var actualMap = planningMap(MinecraftGroundTransferMap.sourceBody(stopped, NORTH, NORTH), ladder, tick, true);
            assertThat(route(actualMap).cells()).containsExactly(NORTH, RUNG, SOUTH);
            assertThat(route(actualMap).edges()).allMatch(edge -> edge.locomotion() == Locomotion.LADDER);
            // The old centered source recreates the same preferred jump at every observation tick.
            assertThat(route(planningMap(body(202.5, 199.5), ladder, tick, true)).edges())
                    .extracting(TraversabilityEdge::locomotion).containsExactly(Locomotion.GAP_JUMP);
        }
    }

    @Test
    void safeLiveArcAndAnOrdinaryOpenGapRemainAvailable() {
        var initial = body(202.42891572207378, 199.38901230735303);
        assertThat(route(planningMap(MinecraftGroundTransferMap.sourceBody(initial, NORTH, NORTH),
                ladderShape(false), 1, true)).edges())
                .extracting(TraversabilityEdge::locomotion).containsExactly(Locomotion.GAP_JUMP);
        var offCenter = body(202.58053958960556, 199.5406361748848);
        var distantObstacle = ladderShape(false).move(2, 0, 0);
        assertThat(route(planningMap(MinecraftGroundTransferMap.sourceBody(offCenter, NORTH, NORTH),
                distantObstacle, 1, false)).edges())
                .extracting(TraversabilityEdge::locomotion).containsExactly(Locomotion.GAP_JUMP);
    }

    @Test
    void blockedArcDoesNotInventAMissingLadderExit() {
        var stopped = body(202.58053958960556, 199.5406361748848);
        var map = planningMap(MinecraftGroundTransferMap.sourceBody(stopped, NORTH, NORTH),
                ladderShape(false), 1, false);
        assertThat(new DeterministicAStar().findRoute(map, NORTH, SOUTH).found()).isFalse();
    }

    @Test
    void futureWaypointsStillUseCellCentersWithTheActualBodyDimensions() {
        var actual = new AABB(202.1, 208.25, 199.2, 202.7, 209.75, 199.8);
        assertThat(MinecraftGroundTransferMap.sourceBody(actual, NORTH, NORTH)).isSameAs(actual);
        var future = MinecraftGroundTransferMap.sourceBody(actual, NORTH, SOUTH);
        assertThat(future.getCenter().x).isEqualTo(202.5);
        assertThat(future.getCenter().z).isEqualTo(201.5);
        assertThat(future.minY).isEqualTo(208);
        assertThat(future.getYsize()).isEqualTo(actual.getYsize());
        assertThat(future.getXsize()).isEqualTo(actual.getXsize());
    }

    @Test
    void ballisticStartRechecksLiveProofBeforeInputWithoutSchedulingACenteringRoute() throws Exception {
        var node = new ClassNode();
        try (var stream = getClass().getResourceAsStream("/dev/aod/mcmcp/runtime/MinecraftV2MoveDriver.class")) {
            new ClassReader(stream).accept(node, 0);
        }
        var chunk = node.methods.stream().filter(method -> method.name.equals("beginChunk")).findFirst().orElseThrow();
        assertThat(calls(chunk)).doesNotContain("dev/aod/mcmcp/agent/navigation/RoutePlan#<init>");
        var tick = node.methods.stream().filter(method -> method.name.equals("tickRoute")).findFirst().orElseThrow();
        var calls = calls(tick);
        assertThat(calls.indexOf("dev/aod/mcmcp/agent/safety/GroundTraversal#discover")).isGreaterThanOrEqualTo(0);
        assertThat(calls.indexOf("dev/aod/mcmcp/agent/safety/GroundTraversal#discover"))
                .isLessThan(calls.indexOf("dev/aod/mcmcp/runtime/MinecraftGroundTraversalExecutor#tick"));
        assertThat(calls).contains("dev/aod/mcmcp/agent/navigation/FallDamageBudget#begin");
    }

    private static List<String> calls(org.objectweb.asm.tree.MethodNode method) {
        var result = new ArrayList<String>();
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) result.add(call.owner + "#" + call.name);
        }
        return result;
    }

    private static KnownTraversabilitySnapshot planningMap(AABB source, AABB obstacle, int tick, boolean exit) {
        var map = new KnownTraversabilityMap();
        map.startSession(SESSION, NORTH.dimension(), 0);
        map.observe(edge(NORTH, RUNG, Locomotion.LADDER, tick));
        if (exit) map.observe(edge(RUNG, SOUTH, Locomotion.LADDER, tick));
        var snapshot = map.snapshot().orElseThrow();
        if (jumpCorridor(source).intersects(obstacle)) return snapshot;
        var jump = edge(NORTH, SOUTH, Locomotion.GAP_JUMP, tick);
        return snapshot.withGroundTransfers(List.of(jump), Map.of(jump.key(), 0.0), 0);
    }

    private static RoutePlan route(KnownTraversabilitySnapshot map) {
        return new DeterministicAStar().findRoute(map, NORTH, SOUTH).route().orElseThrow();
    }

    private static TraversabilityEdge edge(NavCell from, NavCell to, Locomotion mode, int tick) {
        boolean jump = mode.ballistic();
        return new TraversabilityEdge(SESSION, new TraversabilityEdge.Key(from, to),
                jump ? TraversabilityEdge.Status.CONFIRMED : TraversabilityEdge.Status.PROBE_ALLOWED,
                to.equals(RUNG) ? TraversabilityEdge.TargetSupport.ABSENT : TraversabilityEdge.TargetSupport.CONFIRMED,
                TraversabilityEdge.Clearance.CONFIRMED,
                jump ? TraversabilityEdge.Transition.CONFIRMED : TraversabilityEdge.Transition.PARTIAL,
                TraversabilityEdge.Fluid.NONE, TraversabilityEdge.Hazard.NONE,
                TraversabilityEdge.Provenance.LOCAL_VOLUME, NORTH, tick, 0, mode);
    }

    private static AABB jumpCorridor(AABB source) {
        var landing = source.move(202.5 - source.getCenter().x, 0, 201.5 - source.getCenter().z);
        var plan = new GroundTraversal(source, landing, Locomotion.GAP_JUMP, 0);
        return plan.start().minmax(plan.landing()).expandTowards(0, GroundTraversal.JUMP_APEX, 0);
    }

    private static AABB ladderShape(boolean mirrored) {
        return mirrored ? new AABB(202, 201, 200, 202.1875, 215, 201)
                : new AABB(202.8125, 201, 200, 203, 215, 201);
    }

    private static AABB body(double x, double z) {
        return new AABB(x - 0.3, 208, z - 0.3, x + 0.3, 209.8, z + 0.3);
    }

    private static NavCell cell(int z) { return new NavCell("minecraft:overworld", 202, 208, z); }
}
