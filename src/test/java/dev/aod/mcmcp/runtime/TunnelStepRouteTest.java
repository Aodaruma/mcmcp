package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.navigation.NavCell;
import dev.aod.mcmcp.agent.navigation.RoutePlan;
import dev.aod.mcmcp.agent.navigation.TraversabilityEdge;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TunnelStepRouteTest {
    @Test
    void newlyExcavatedProbeEdgeReceivesTheProbeExecutionBudget() {
        var session = UUID.randomUUID();
        var from = new NavCell("minecraft:overworld", 0, 64, 0);
        var to = new NavCell("minecraft:overworld", 1, 64, 0);
        var edge = edge(session, from, to, TraversabilityEdge.Status.PROBE_ALLOWED);

        RoutePlan route = McmcpRuntime.tunnelStepRoute(session, from.dimension(), 0L,
                from, to, edge);

        assertThat(route.usesProbeAllowed()).isTrue();
        assertThat(route.probeEdgeCount()).isEqualTo(1);
        assertThat(route.tickUpperBound()).isEqualTo(56L);
        assertThat(route.durationMillisUpperBound()).isEqualTo(2_800L);
    }

    @Test
    void contactedEdgeKeepsTheOrdinaryExecutionBudget() {
        var session = UUID.randomUUID();
        var from = new NavCell("minecraft:overworld", 0, 64, 0);
        var to = new NavCell("minecraft:overworld", 1, 64, 0);
        var edge = edge(session, from, to, TraversabilityEdge.Status.CONFIRMED);

        RoutePlan route = McmcpRuntime.tunnelStepRoute(session, from.dimension(), 0L,
                from, to, edge);

        assertThat(route.usesProbeAllowed()).isFalse();
        assertThat(route.probeEdgeCount()).isZero();
        assertThat(route.tickUpperBound()).isEqualTo(36L);
    }

    private static TraversabilityEdge edge(UUID session, NavCell from, NavCell to,
            TraversabilityEdge.Status status) {
        return new TraversabilityEdge(session, new TraversabilityEdge.Key(from, to), status,
                TraversabilityEdge.TargetSupport.CONFIRMED,
                TraversabilityEdge.Clearance.CONFIRMED,
                status == TraversabilityEdge.Status.PROBE_ALLOWED
                        ? TraversabilityEdge.Transition.PARTIAL
                        : TraversabilityEdge.Transition.CONFIRMED,
                TraversabilityEdge.Fluid.NONE,
                TraversabilityEdge.Hazard.NONE,
                TraversabilityEdge.Provenance.LOCAL_VOLUME,
                from, 1L, 0L);
    }
}
