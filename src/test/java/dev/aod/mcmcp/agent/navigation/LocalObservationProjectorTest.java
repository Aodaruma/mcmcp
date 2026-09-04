package dev.aod.mcmcp.agent.navigation;

import dev.aod.mcmcp.agent.safety.LocalObservationVolume;
import dev.aod.mcmcp.agent.safety.Locomotion;
import dev.aod.mcmcp.agent.safety.ObservationRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LocalObservationProjectorTest {
    private static final String OVERWORLD = "minecraft:overworld";

    @Test
    void keepsClimbableTransitInternalAndPublishesOnlyFloorBackedLandings() {
        for (var locomotion : List.of(Locomotion.LADDER, Locomotion.SCAFFOLDING)) {
            var center = new ObservationRecord.Point(0.5D, 64.9D, 0.5D);
            var upperRung = new ObservationRecord.Point(0.5D, 65.9D, 0.5D);
            var landing = new ObservationRecord.Point(1.5D, 65.9D, 0.5D);
            var current = record(10, 3, 0, center, center, center,
                    ObservationRecord.Transition.STATIONARY,
                    ObservationRecord.Clearance.CLEAR,
                    ObservationRecord.Hazard.NONE);
            var rungTransition = climbableRecord(
                    center, upperRung, ObservationRecord.Support.ABSENT, locomotion);
            var landingTransition = climbableRecord(
                    upperRung, landing, ObservationRecord.Support.PRESENT, locomotion);
            var snapshot = new LocalObservationVolume.Snapshot(
                    10, 3, center, current, List.of(rungTransition, landingTransition));

            var projection = LocalObservationProjector.project(
                    snapshot, UUID.randomUUID(), OVERWORLD, 3, 64.0D);

            assertThat(projection.edges()).hasSize(2);
            assertThat(projection.edges().getFirst()).satisfies(edge -> {
                assertThat(edge.locomotion()).isEqualTo(locomotion);
                assertThat(edge.destination()).isFalse();
                assertThat(edge.status()).isEqualTo(TraversabilityEdge.Status.PROBE_ALLOWED);
            });
            assertThat(projection.edges().getLast().destination()).isTrue();
            assertThat(projection.records()).singleElement().satisfies(record ->
                    assertThat(record).isInstanceOf(
                            dev.aod.mcmcp.agent.observation.ObservationRecord.Traversability.class));
        }
    }

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
            assertThat(projection.records()).singleElement().satisfies(record -> {
                var target = ((dev.aod.mcmcp.agent.observation.ObservationRecord.Traversability)
                        record).navigationTarget();
                assertThat(new NavCell(target.dimension().value(), target.x(), target.y(), target.z()))
                        .isEqualTo(projection.edges().getFirst().key().to());
            });
        }
    }

    @Test
    void projectsSafeAdjacentOneBlockJumpAsSupportedClearNavigationTarget() {
        var start = new ObservationRecord.Point(0.5D, 64.9D, 0.5D);
        var requested = new ObservationRecord.Point(1.5D, 64.9D, 0.5D);
        var landing = new ObservationRecord.Point(1.5D, 65.9D, 0.5D);
        var current = record(
                10, 3, 0, start, start, start,
                ObservationRecord.Transition.STATIONARY,
                ObservationRecord.Clearance.CLEAR,
                ObservationRecord.Hazard.NONE);
        var jump = record(
                10, 3, 1, start, requested, landing,
                ObservationRecord.Transition.PROBE_ALLOWED,
                ObservationRecord.Clearance.CLEAR,
                ObservationRecord.Hazard.NONE);

        var projection = LocalObservationProjector.project(
                new LocalObservationVolume.Snapshot(10, 3, start, current, List.of(jump)),
                UUID.randomUUID(), OVERWORLD, 3, 64.0D);

        assertThat(projection.records()).singleElement().satisfies(record -> {
            var edge = (dev.aod.mcmcp.agent.observation.ObservationRecord.Traversability) record;
            assertThat(edge.navigationTarget()).isEqualTo(
                    new dev.aod.mcmcp.agent.observation.ObservationValues.BlockPosition(
                            new dev.aod.mcmcp.agent.observation.ObservationValues.ResourceId(
                                    OVERWORLD),
                            1, 65, 0));
            assertThat(edge.targetSupport()).isEqualTo(
                    dev.aod.mcmcp.agent.observation.ObservationRecord.TargetSupport.CONFIRMED);
            assertThat(edge.transitionClearance()).isEqualTo(
                    dev.aod.mcmcp.agent.observation.ObservationRecord.TransitionClearance.CONFIRMED);
        });
    }

    @Test
    void postMutationDerivedPassageReconnectsAFiveBlockExitForEveryPassageKind() {
        for (String passageKind : List.of("gate", "door", "trapdoor")) {
            UUID session = UUID.randomUUID();
            var center = new ObservationRecord.Point(0.5D, 64.9D, 0.5D);
            var currentAtRevisionZero = record(
                    10, 0, 0, center, center, center,
                    ObservationRecord.Transition.STATIONARY,
                    ObservationRecord.Clearance.CLEAR,
                    ObservationRecord.Hazard.NONE);
            var closedPassage = record(
                    10,
                    0,
                    1,
                    center,
                    new ObservationRecord.Point(1.5D, 64.9D, 0.5D),
                    center,
                    ObservationRecord.Transition.BLOCKED,
                    ObservationRecord.Clearance.BLOCKED,
                    ObservationRecord.Hazard.COLLISION);
            var closedProjection = LocalObservationProjector.project(
                    new LocalObservationVolume.Snapshot(
                            10, 0, center, currentAtRevisionZero, List.of(closedPassage)),
                    session,
                    OVERWORLD,
                    0,
                    64.0D);
            var map = new KnownTraversabilityMap();
            map.startSession(session, OVERWORLD, 0);
            closedProjection.edges().forEach(map::observe);

            var start = new NavCell(OVERWORLD, 0, 64, 0);
            var exit = new NavCell(OVERWORLD, 5, 64, 0);
            assertThat(new DeterministicAStar()
                    .findRoute(map.snapshot().orElseThrow(), start, exit).route())
                    .as("closed %s", passageKind)
                    .isEmpty();

            map.advanceWorldRevision(
                    1,
                    List.of(start, new NavCell(OVERWORLD, 1, 64, 0)),
                    List.of());
            var currentAtRevisionOne = record(
                    20, 1, 0, center, center, center,
                    ObservationRecord.Transition.STATIONARY,
                    ObservationRecord.Clearance.CLEAR,
                    ObservationRecord.Hazard.NONE);
            var passageChain = new java.util.ArrayList<ObservationRecord>();
            for (int step = 1; step <= 5; step++) {
                passageChain.add(record(
                        20,
                        1,
                        step,
                        new ObservationRecord.Point(step - 0.5D, 64.9D, 0.5D),
                        new ObservationRecord.Point(step + 0.5D, 64.9D, 0.5D),
                        new ObservationRecord.Point(step + 0.5D, 64.9D, 0.5D),
                        ObservationRecord.Transition.PROBE_ALLOWED,
                        ObservationRecord.Clearance.CLEAR,
                        ObservationRecord.Hazard.NONE));
            }
            var reopenedProjection = LocalObservationProjector.project(
                    new LocalObservationVolume.Snapshot(
                            20, 1, center, currentAtRevisionOne, passageChain),
                    session,
                    OVERWORLD,
                    1,
                    64.0D);

            assertThat(reopenedProjection.records())
                    .as("derived-only public records for %s", passageKind)
                    .hasSize(5)
                    .allMatch(record -> record instanceof
                            dev.aod.mcmcp.agent.observation.ObservationRecord.Traversability);
            assertThat(reopenedProjection.edges())
                    .as("five-block connected feet-space for %s", passageKind)
                    .hasSize(5)
                    .allMatch(edge -> edge.provenance()
                            == TraversabilityEdge.Provenance.LOCAL_VOLUME);
            reopenedProjection.edges().forEach(edge -> assertThat(map.observe(edge)).isTrue());

            var route = new DeterministicAStar()
                    .findRoute(map.snapshot().orElseThrow(), start, exit)
                    .route()
                    .orElseThrow();
            assertThat(route.cells())
                    .as("reconnected route through open %s", passageKind)
                    .containsExactly(
                            new NavCell(OVERWORLD, 0, 64, 0),
                            new NavCell(OVERWORLD, 1, 64, 0),
                            new NavCell(OVERWORLD, 2, 64, 0),
                            new NavCell(OVERWORLD, 3, 64, 0),
                            new NavCell(OVERWORLD, 4, 64, 0),
                            new NavCell(OVERWORLD, 5, 64, 0));
        }
    }

    @Test
    void alternativeSafeEdgeToReachedCellKeepsADeeperDiagonalRoutable() {
        UUID session = UUID.randomUUID();
        var start = new ObservationRecord.Point(0.5D, 64.9D, 0.5D);
        var middle = new ObservationRecord.Point(1.5D, 64.9D, 0.5D);
        var sharedSide = new ObservationRecord.Point(1.5D, 64.9D, 1.5D);
        var eastSide = new ObservationRecord.Point(2.5D, 64.9D, 0.5D);
        var target = new ObservationRecord.Point(2.5D, 64.9D, 1.5D);
        var current = record(
                10, 3, 0, start, start, start,
                ObservationRecord.Transition.STATIONARY,
                ObservationRecord.Clearance.CLEAR,
                ObservationRecord.Hazard.NONE);
        var transitions = List.of(
                contact(start, middle, 1),
                contact(start, sharedSide, 1),
                contact(middle, eastSide, 2),
                contact(middle, sharedSide, 2),
                contact(middle, target, 2));

        var projection = LocalObservationProjector.project(
                new LocalObservationVolume.Snapshot(10, 3, start, current, transitions),
                session,
                OVERWORLD,
                3,
                64.0D);

        var sharedCell = new NavCell(OVERWORLD, 1, 64, 1);
        assertThat(projection.edges())
                .filteredOn(edge -> edge.key().to().equals(sharedCell))
                .hasSize(2);
        var map = new KnownTraversabilityMap();
        map.startSession(session, OVERWORLD, 3);
        projection.edges().forEach(map::observe);
        assertThat(new DeterministicAStar()
                .findRoute(
                        map.snapshot().orElseThrow(),
                        new NavCell(OVERWORLD, 0, 64, 0),
                        new NavCell(OVERWORLD, 2, 64, 1))
                .route().orElseThrow().cells())
                .containsExactly(
                        new NavCell(OVERWORLD, 0, 64, 0),
                        new NavCell(OVERWORLD, 1, 64, 0),
                        new NavCell(OVERWORLD, 2, 64, 1));
    }

    private static ObservationRecord contact(
            ObservationRecord.Point from, ObservationRecord.Point to, int depth) {
        return record(
                10,
                3,
                depth,
                from,
                to,
                to,
                ObservationRecord.Transition.CONTACT,
                ObservationRecord.Clearance.CLEAR,
                ObservationRecord.Hazard.NONE);
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

    private static ObservationRecord climbableRecord(
            ObservationRecord.Point from,
            ObservationRecord.Point to,
            ObservationRecord.Support support,
            Locomotion locomotion) {
        return new ObservationRecord(
                10L,
                3L,
                1,
                from,
                to,
                to,
                support,
                ObservationRecord.Clearance.CLEAR,
                ObservationRecord.Transition.PROBE_ALLOWED,
                ObservationRecord.Fluid.NONE,
                false,
                ObservationRecord.Hazard.NONE,
                ObservationRecord.LoadedState.LOADED,
                support == ObservationRecord.Support.PRESENT
                        ? ObservationRecord.Drop.SUPPORTED
                        : ObservationRecord.Drop.AIRBORNE_OR_SWIMMING,
                false,
                locomotion);
    }
}
