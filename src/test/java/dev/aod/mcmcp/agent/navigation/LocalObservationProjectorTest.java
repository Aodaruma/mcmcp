package dev.aod.mcmcp.agent.navigation;

import dev.aod.mcmcp.agent.safety.LocalObservationVolume;
import dev.aod.mcmcp.agent.safety.ObservationRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LocalObservationProjectorTest {
    @Test
    void keepsRequestedEdgeWhenVanillaResolvedOnlyAMicroStep() {
        var center = new ObservationRecord.Point(0.5, 64.9, 0.5);
        var current = record(10, 3, 0, center, center, center,
                ObservationRecord.Transition.STATIONARY,
                ObservationRecord.Clearance.CLEAR,
                ObservationRecord.Hazard.NONE);
        var contact = record(
                10,
                3,
                1,
                center,
                new ObservationRecord.Point(1.5, 64.9, 0.5),
                new ObservationRecord.Point(0.6, 64.9, 0.5),
                ObservationRecord.Transition.CONTACT,
                ObservationRecord.Clearance.CLEAR,
                ObservationRecord.Hazard.NONE);
        var snapshot = new LocalObservationVolume.Snapshot(
                10, 3, center, current, List.of(contact));

        var projection = LocalObservationProjector.project(
                snapshot, UUID.randomUUID(), "minecraft:overworld", 3, 64.0D);

        assertThat(projection.edges()).singleElement().satisfies(edge -> {
            assertThat(edge.key().from()).isEqualTo(
                    new NavCell("minecraft:overworld", 0, 64, 0));
            assertThat(edge.key().to()).isEqualTo(
                    new NavCell("minecraft:overworld", 1, 64, 0));
            assertThat(edge.status()).isEqualTo(TraversabilityEdge.Status.CONFIRMED);
            assertThat(edge.provenance()).isEqualTo(TraversabilityEdge.Provenance.CONTACT);
        });
        assertThat(projection.records()).hasSize(1);
        assertThat(projection.currentSafety())
                .isEqualTo(LocalObservationProjector.CurrentSafety.CONTINUE);
    }

    @Test
    void staleOrUnknownSnapshotsNeverPromoteEdges() {
        var center = new ObservationRecord.Point(0.5, 64.9, 0.5);
        var current = record(10, 2, 0, center, center, center,
                ObservationRecord.Transition.STATIONARY,
                ObservationRecord.Clearance.CLEAR,
                ObservationRecord.Hazard.NONE);
        var snapshot = new LocalObservationVolume.Snapshot(
                10, 2, center, current, List.of());

        var projection = LocalObservationProjector.project(
                snapshot, UUID.randomUUID(), "minecraft:overworld", 3, 64.0D);

        assertThat(projection.edges()).isEmpty();
        assertThat(projection.records()).isEmpty();
        assertThat(projection.currentSafety())
                .isEqualTo(LocalObservationProjector.CurrentSafety.REPLAN);
    }

    @Test
    void projectsShallowLandingsIntoTheFeetCellForEachPlayerPoseHeight() {
        for (double height : List.of(1.8D, 1.5D)) {
            double halfHeight = height * 0.5D;
            var center = new ObservationRecord.Point(0.5D, 56.0D + halfHeight, 0.5D);
            var current = record(10, 3, 0, center, center, center,
                    ObservationRecord.Transition.STATIONARY,
                    ObservationRecord.Clearance.CLEAR,
                    ObservationRecord.Hazard.NONE);
            var landing = record(
                    10,
                    3,
                    1,
                    center,
                    new ObservationRecord.Point(1.5D, center.y(), 0.5D),
                    new ObservationRecord.Point(1.5D, center.y() - 0.0625D, 0.5D),
                    ObservationRecord.Transition.PROBE_ALLOWED,
                    ObservationRecord.Clearance.CLEAR,
                    ObservationRecord.Hazard.NONE);
            var snapshot = new LocalObservationVolume.Snapshot(
                    10, 3, center, current, List.of(landing));

            var projection = LocalObservationProjector.project(
                    snapshot, UUID.randomUUID(), "minecraft:overworld", 3, 56.0D);

            assertThat(projection.edges()).singleElement().satisfies(edge -> {
                assertThat(edge.key().from()).isEqualTo(
                        new NavCell("minecraft:overworld", 0, 56, 0));
                assertThat(edge.key().to()).isEqualTo(
                        new NavCell("minecraft:overworld", 1, 55, 0));
                assertThat(edge.status()).isEqualTo(
                        TraversabilityEdge.Status.PROBE_ALLOWED);
                assertThat(edge.targetSupport()).isEqualTo(
                        TraversabilityEdge.TargetSupport.CONFIRMED);
            });
        }
    }

    private static ObservationRecord record(
            long tick,
            long revision,
            int depth,
            ObservationRecord.Point from,
            ObservationRecord.Point requestedTo,
            ObservationRecord.Point to,
            ObservationRecord.Transition transition,
            ObservationRecord.Clearance clearance,
            ObservationRecord.Hazard hazard) {
        return new ObservationRecord(
                tick,
                revision,
                depth,
                from,
                requestedTo,
                to,
                ObservationRecord.Support.PRESENT,
                clearance,
                transition,
                ObservationRecord.Fluid.NONE,
                false,
                hazard,
                ObservationRecord.LoadedState.LOADED,
                ObservationRecord.Drop.SUPPORTED,
                false);
    }
}
