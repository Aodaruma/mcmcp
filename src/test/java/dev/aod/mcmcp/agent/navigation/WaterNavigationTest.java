package dev.aod.mcmcp.agent.navigation;

import dev.aod.mcmcp.agent.safety.LocalObservationVolume;
import dev.aod.mcmcp.agent.safety.Locomotion;
import dev.aod.mcmcp.agent.safety.ObservationRecord;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static dev.aod.mcmcp.agent.safety.ObservationRecord.*;
import static org.assertj.core.api.Assertions.*;

class WaterNavigationTest {
    @Test
    void publishesWaterAndBankTargetsAndRoutesInThreeDimensions() {
        var session = UUID.randomUUID();
        var origin = new Point(-2.5, 62.9, -3.5);
        var mid = new Point(-1.5, 62.9, -3.5);
        var surface = new Point(-1.5, 63.9, -3.5);
        var bank = new Point(-0.5, 64.9, -3.5);
        var current = new ObservationRecord(10, 3, 0, origin, origin, origin, Support.ABSENT,
                Clearance.CLEAR, Transition.STATIONARY, Fluid.WATER, false, Hazard.NONE,
                LoadedState.LOADED, Drop.AIRBORNE_OR_SWIMMING, false);
        var source = new LocalObservationVolume.Snapshot(10, 3, origin, current, List.of(
                record(origin, mid, Fluid.WATER, Support.ABSENT, Locomotion.WATER),
                record(mid, surface, Fluid.WATER, Support.ABSENT, Locomotion.WATER),
                record(surface, bank, Fluid.NONE, Support.PRESENT, Locomotion.WATER)));
        var projection = LocalObservationProjector.project(source, session, "minecraft:overworld", 3, 62);
        assertThat(projection.currentSafety()).isEqualTo(LocalObservationProjector.CurrentSafety.CONTINUE);
        assertThat(projection.edges()).hasSize(3).allMatch(TraversabilityEdge::destination);
        assertThat(projection.records().stream().filter(r -> r instanceof
                dev.aod.mcmcp.agent.observation.ObservationRecord.Traversability)).hasSize(3);
        var map = new KnownTraversabilityMap();
        map.startSession(session, "minecraft:overworld", 3);
        projection.edges().forEach(map::observe);
        var snapshot = map.snapshot().orElseThrow();
        var start = projection.edges().getFirst().key().from();
        var end = projection.edges().getLast().key().to();
        var route = new DeterministicAStar().findRoute(snapshot, start, end).route().orElseThrow();
        assertThat(route.edges()).hasSize(3);
        assertThat(route.tickUpperBound()).isEqualTo(20 + 3 * (16 + 20 + 40));
        assertThat(new DeterministicAStar().findRoute(snapshot, start,
                new NavCell("minecraft:overworld", 2, 63, -4)).found()).isFalse();
    }

    @Test
    void fluidDoesNotBecomeTraversableWithoutAnExplicitWaterProof() {
        var origin = new Point(0.5, 64.9, 0.5);
        var to = new Point(1.5, 64.9, 0.5);
        var current = new ObservationRecord(10, 3, 0, origin, origin, origin, Support.PRESENT,
                Clearance.CLEAR, Transition.STATIONARY, Fluid.NONE, false, Hazard.NONE,
                LoadedState.LOADED, Drop.SUPPORTED, false);
        for (var mode : List.of(Locomotion.GROUND, Locomotion.LADDER, Locomotion.SCAFFOLDING)) {
            var projection = LocalObservationProjector.project(new LocalObservationVolume.Snapshot(
                    10, 3, origin, current, List.of(record(origin, to, Fluid.WATER, Support.PRESENT, mode))),
                    UUID.randomUUID(), "minecraft:overworld", 3, 64);
            assertThat(projection.edges()).allMatch(edge -> !edge.traversable());
        }
        assertThatThrownBy(() -> new TraversabilityEdge(UUID.randomUUID(),
                new TraversabilityEdge.Key(new NavCell("minecraft:overworld", 0, 64, 0),
                        new NavCell("minecraft:overworld", 1, 64, 0)),
                TraversabilityEdge.Status.PROBE_ALLOWED, TraversabilityEdge.TargetSupport.ABSENT,
                TraversabilityEdge.Clearance.CONFIRMED, TraversabilityEdge.Transition.PARTIAL,
                TraversabilityEdge.Fluid.LAVA, TraversabilityEdge.Hazard.NONE,
                TraversabilityEdge.Provenance.LOCAL_VOLUME, new NavCell("minecraft:overworld", 0, 64, 0),
                10, 3, Locomotion.WATER)).isInstanceOf(IllegalArgumentException.class);
    }

    private static ObservationRecord record(Point from, Point to, Fluid fluid, Support support, Locomotion mode) {
        return new ObservationRecord(10, 3, 1, from, to, to, support, Clearance.CLEAR,
                Transition.PROBE_ALLOWED, fluid, false, Hazard.NONE, LoadedState.LOADED,
                support == Support.PRESENT ? Drop.SUPPORTED : Drop.AIRBORNE_OR_SWIMMING, false, mode);
    }
}
