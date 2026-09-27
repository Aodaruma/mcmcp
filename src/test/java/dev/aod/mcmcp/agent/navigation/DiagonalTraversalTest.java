package dev.aod.mcmcp.agent.navigation;

import dev.aod.mcmcp.agent.safety.Locomotion;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiagonalTraversalTest {
    @Test
    void routesAcrossTouchingCornersWithoutInventingWalkableSideCells() {
        for (int dx : new int[]{-1, 1}) {
            for (int dz : new int[]{-1, 1}) {
                var session = UUID.randomUUID();
                var from = cell(-3, 64, -4);
                var to = cell(-3 + dx, 64, -4 + dz);
                var map = map(session);
                var edge = edge(session, from, to, true, 1);
                map.observe(edge);
                var snapshot = map.snapshot().orElseThrow();
                var route = new DeterministicAStar().findRoute(snapshot, from, to).route().orElseThrow();
                assertThat(route.cells()).containsExactly(from, to);
                assertThat(DiagonalTraversal.requiredSides(snapshot, edge)).isEmpty();
                assertThat(snapshot.edges()).hasSize(1);
                assertThat(route.tickUpperBound()).isEqualTo(96);
            }
        }
    }

    @Test
    void unknownAndOldDirectProofCannotReplaceCornerEvidence() {
        var session = UUID.randomUUID();
        var from = cell(0, 64, 0);
        var to = cell(1, 64, 1);
        var map = map(session);
        map.observe(edge(session, from, to, false, 1));
        assertThat(new DeterministicAStar().findRoute(map.snapshot().orElseThrow(), from, to).route()).isEmpty();
        map.observe(edge(session, from, to, true, 2));
        assertThat(new DeterministicAStar().findRoute(map.snapshot().orElseThrow(), from, to).route()).isPresent();
        // Even a change outside the endpoint cells expires the complete corridor proof.
        map.advanceWorldRevision(1, List.of(cell(1, 64, 0)), List.of());
        assertThat(new DeterministicAStar().findRoute(map.snapshot().orElseThrow(), from, to).route()).isEmpty();
    }

    @Test
    void newerContactCannotRetainAWithdrawnCorridorProof() {
        var session = UUID.randomUUID();
        var from = cell(0, 64, 0);
        var to = cell(1, 64, 1);
        var map = map(session);
        map.observe(new TraversabilityEdge(session, new TraversabilityEdge.Key(from, to),
                TraversabilityEdge.Status.CONFIRMED, TraversabilityEdge.TargetSupport.CONFIRMED,
                TraversabilityEdge.Clearance.CONFIRMED, TraversabilityEdge.Transition.CONFIRMED,
                TraversabilityEdge.Fluid.NONE, TraversabilityEdge.Hazard.NONE,
                TraversabilityEdge.Provenance.CONTACT, from, 1, 0, Locomotion.GROUND, true));
        assertThat(map.observe(edge(session, from, to, false, 2))).isTrue();
        assertThat(new DeterministicAStar().findRoute(map.snapshot().orElseThrow(), from, to).route()).isEmpty();
    }

    @Test
    void directProofCannotAuthorizeADiagonalJump() {
        var session = UUID.randomUUID();
        assertThatThrownBy(() -> edge(session, cell(0, 64, 0), cell(1, 65, 1), true, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static KnownTraversabilityMap map(UUID session) {
        var map = new KnownTraversabilityMap();
        map.startSession(session, "minecraft:overworld", 0);
        return map;
    }

    private static NavCell cell(int x, int y, int z) {
        return new NavCell("minecraft:overworld", x, y, z);
    }

    private static TraversabilityEdge edge(UUID session, NavCell from, NavCell to, boolean proof, long tick) {
        return new TraversabilityEdge(session, new TraversabilityEdge.Key(from, to),
                TraversabilityEdge.Status.PROBE_ALLOWED, TraversabilityEdge.TargetSupport.CONFIRMED,
                TraversabilityEdge.Clearance.CONFIRMED, TraversabilityEdge.Transition.PARTIAL,
                TraversabilityEdge.Fluid.NONE, TraversabilityEdge.Hazard.NONE,
                TraversabilityEdge.Provenance.LOCAL_VOLUME, from, tick, 0, Locomotion.GROUND, proof);
    }
}
