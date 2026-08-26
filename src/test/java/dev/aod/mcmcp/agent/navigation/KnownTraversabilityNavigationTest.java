package dev.aod.mcmcp.agent.navigation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnownTraversabilityNavigationTest {
    @Test
    void mapIsHardCappedAndRejectsDelayedOldRevisionEvidence() {
        var map = new KnownTraversabilityMap();
        var session = UUID.randomUUID();
        map.startSession(session, OVERWORLD, 0);
        for (int x = 0; x <= KnownTraversabilityMap.MAX_EDGES; x++) {
            map.observe(confirmed(
                    session, cell(x, 64, 0), cell(x + 1, 64, 0), 1, 0,
                    TraversabilityEdge.Provenance.LOCAL_VOLUME));
        }

        assertThat(map.size()).isEqualTo(KnownTraversabilityMap.MAX_EDGES);
        assertThat(map.snapshot().orElseThrow().edge(new TraversabilityEdge.Key(
                cell(0, 64, 0), cell(1, 64, 0)))).isEmpty();

        map.advanceWorldRevision(1, List.of(), List.of());
        assertThat(map.observe(confirmed(
                session, cell(0, 64, 0), cell(1, 64, 0), 99_999, 0,
                TraversabilityEdge.Provenance.CONTACT))).isFalse();
    }

    @Test
    void snapshotIsReusedUntilAnAcceptedMutation() {
        var map = new KnownTraversabilityMap();
        var session = UUID.randomUUID();
        map.startSession(session, OVERWORLD, 0);

        KnownTraversabilitySnapshot empty = map.snapshot().orElseThrow();
        assertThat(map.snapshot().orElseThrow()).isSameAs(empty);

        var from = cell(0, 64, 0);
        var to = cell(1, 64, 0);
        map.observe(confirmed(
                session, from, to, 1, 0, TraversabilityEdge.Provenance.CONTACT));
        KnownTraversabilitySnapshot populated = map.snapshot().orElseThrow();
        assertThat(populated).isNotSameAs(empty);
        assertThat(map.snapshot().orElseThrow()).isSameAs(populated);

        assertThat(map.observe(probe(session, from, to, 2, 0))).isFalse();
        assertThat(map.snapshot().orElseThrow()).isSameAs(populated);

        map.advanceWorldRevision(1, List.of(), List.of());
        assertThat(map.snapshot().orElseThrow()).isNotSameAs(populated);
    }

    @Test
    void freshHypothesisDoesNotDowngradeContactAtTheSameWorldRevision() {
        var map = new KnownTraversabilityMap();
        var session = UUID.randomUUID();
        map.startSession(session, OVERWORLD, 0);
        var from = cell(0, 64, 0);
        var to = cell(1, 64, 0);
        assertThat(map.observe(confirmed(
                session, from, to, 1, 0, TraversabilityEdge.Provenance.CONTACT))).isTrue();
        assertThat(map.observe(probe(session, from, to, 2, 0))).isFalse();
        assertThat(map.snapshot().orElseThrow()
                .edge(new TraversabilityEdge.Key(from, to)).orElseThrow().status())
                .isEqualTo(TraversabilityEdge.Status.CONFIRMED);
    }

    @Test
    void unrelatedRevisionCannotDowngradeContactButAffectedContactCanBeReobserved() {
        var map = new KnownTraversabilityMap();
        var session = UUID.randomUUID();
        map.startSession(session, OVERWORLD, 0);
        var from = cell(0, 64, 0);
        var to = cell(1, 64, 0);
        var key = new TraversabilityEdge.Key(from, to);
        map.observe(confirmed(
                session, from, to, 1, 0, TraversabilityEdge.Provenance.CONTACT));

        map.advanceWorldRevision(1, List.of(), List.of());
        assertThat(map.observe(probe(session, from, to, 2, 1))).isFalse();
        assertThat(map.snapshot().orElseThrow().edge(key).orElseThrow().status())
                .isEqualTo(TraversabilityEdge.Status.CONFIRMED);

        map.advanceWorldRevision(2, List.of(), List.of(key));
        assertThat(map.observe(probe(session, from, to, 3, 2))).isTrue();
        assertThat(map.snapshot().orElseThrow().edge(key).orElseThrow().status())
                .isEqualTo(TraversabilityEdge.Status.PROBE_ALLOWED);
    }

    @Test
    void staleEvidenceDoesNotKeepACellKnown() {
        var map = new KnownTraversabilityMap();
        var session = UUID.randomUUID();
        map.startSession(session, OVERWORLD, 0);
        var from = cell(0, 64, 0);
        var to = cell(1, 64, 0);
        var edge = confirmed(session, from, to, 1, 0,
                TraversabilityEdge.Provenance.LOCAL_VOLUME);
        map.observe(edge);
        assertThat(map.snapshot().orElseThrow().containsCell(to)).isTrue();

        map.advanceWorldRevision(1, List.of(), List.of(edge.key()));

        assertThat(map.snapshot().orElseThrow().containsCell(to)).isFalse();
    }

    private static final String OVERWORLD = "minecraft:overworld";

    @Test
    void sessionAndDimensionBoundariesClearEverythingAndSnapshotsStayImmutable() {
        UUID firstSession = UUID.randomUUID();
        var map = new KnownTraversabilityMap();
        map.startSession(firstSession, OVERWORLD, 0);
        NavCell start = cell(0, 64, 0);
        NavCell next = cell(1, 64, 0);
        map.observe(confirmed(firstSession, start, next, 1, 0,
                TraversabilityEdge.Provenance.LOCAL_VOLUME));

        KnownTraversabilitySnapshot first = map.snapshot().orElseThrow();
        assertThat(first.edges()).hasSize(1);
        assertThatThrownBy(() -> first.edges().put(
                new TraversabilityEdge.Key(next, cell(2, 64, 0)),
                confirmed(firstSession, next, cell(2, 64, 0), 2, 0,
                        TraversabilityEdge.Provenance.LOCAL_VOLUME)))
                .isInstanceOf(UnsupportedOperationException.class);

        map.observe(confirmed(firstSession, next, cell(2, 64, 0), 2, 0,
                TraversabilityEdge.Provenance.LOCAL_VOLUME));
        assertThat(first.edges()).hasSize(1);
        assertThat(map.snapshot().orElseThrow().edges()).hasSize(2);

        map.startSession(firstSession, OVERWORLD, 2);
        assertThat(map.snapshot().orElseThrow().edges()).isEmpty();

        UUID secondSession = UUID.randomUUID();
        map.startSession(secondSession, "minecraft:the_nether", 0);
        assertThat(map.snapshot().orElseThrow().edges()).isEmpty();
        assertThat(map.snapshot().orElseThrow().worldSessionId()).isEqualTo(secondSession);
        assertThat(map.snapshot().orElseThrow().dimension()).isEqualTo("minecraft:the_nether");

        map.clearWorld();
        assertThat(map.snapshot()).isEmpty();
        assertThat(map.size()).isZero();
    }

    @Test
    void onlyLocalVolumeOrContactCanPromoteAnEdge() {
        UUID session = UUID.randomUUID();
        var map = boundMap(session);
        NavCell start = cell(0, 64, 0);
        NavCell next = cell(1, 64, 0);

        assertThatThrownBy(() -> map.observe(confirmed(
                session, start, next, 1, 0,
                TraversabilityEdge.Provenance.OMNIDIRECTIONAL_VISUAL)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("visual and sound");
        assertThatThrownBy(() -> map.observe(confirmed(
                session, start, next, 1, 0, TraversabilityEdge.Provenance.SOUND)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(map.size()).isZero();

        assertThat(map.observe(probe(session, start, next, 2, 0))).isTrue();
        assertThat(map.snapshot().orElseThrow()
                .edge(new TraversabilityEdge.Key(start, next)).orElseThrow().status())
                .isEqualTo(TraversabilityEdge.Status.PROBE_ALLOWED);
        assertThat(map.observe(confirmed(
                session, start, next, 3, 0, TraversabilityEdge.Provenance.CONTACT))).isTrue();
        assertThat(map.snapshot().orElseThrow()
                .edge(new TraversabilityEdge.Key(start, next)).orElseThrow().status())
                .isEqualTo(TraversabilityEdge.Status.CONFIRMED);

        assertThat(map.observe(blocked(session, start, next, 1, 0))).isFalse();
        assertThatThrownBy(() -> map.observe(confirmed(
                UUID.randomUUID(), start, next, 4, 0,
                TraversabilityEdge.Provenance.LOCAL_VOLUME)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("world session");
        assertThatThrownBy(() -> new TraversabilityEdge(
                session,
                new TraversabilityEdge.Key(start, next),
                TraversabilityEdge.Status.CONFIRMED,
                TraversabilityEdge.TargetSupport.UNKNOWN,
                TraversabilityEdge.Clearance.CONFIRMED,
                TraversabilityEdge.Transition.CONFIRMED,
                TraversabilityEdge.Fluid.NONE,
                TraversabilityEdge.Hazard.NONE,
                TraversabilityEdge.Provenance.LOCAL_VOLUME,
                start,
                1,
                0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void revisionInvalidationStalesOnlyAffectedCellsAndEdges() {
        UUID session = UUID.randomUUID();
        var map = boundMap(session);
        NavCell a = cell(0, 64, 0);
        NavCell b = cell(1, 64, 0);
        NavCell c = cell(2, 64, 0);
        NavCell x = cell(0, 64, 4);
        NavCell y = cell(1, 64, 4);
        var ab = new TraversabilityEdge.Key(a, b);
        var bc = new TraversabilityEdge.Key(b, c);
        var xy = new TraversabilityEdge.Key(x, y);
        map.observe(confirmed(session, a, b, 1, 0, TraversabilityEdge.Provenance.LOCAL_VOLUME));
        map.observe(confirmed(session, b, c, 1, 0, TraversabilityEdge.Provenance.LOCAL_VOLUME));
        map.observe(confirmed(session, x, y, 1, 0, TraversabilityEdge.Provenance.LOCAL_VOLUME));

        assertThat(map.advanceWorldRevision(1, List.of(b), List.of())).isEqualTo(2);
        KnownTraversabilitySnapshot afterCell = map.snapshot().orElseThrow();
        assertThat(afterCell.edge(ab).orElseThrow().status()).isEqualTo(TraversabilityEdge.Status.STALE);
        assertThat(afterCell.edge(bc).orElseThrow().status()).isEqualTo(TraversabilityEdge.Status.STALE);
        assertThat(afterCell.edge(xy).orElseThrow().status()).isEqualTo(TraversabilityEdge.Status.CONFIRMED);

        assertThat(map.observe(confirmed(
                session, b, c, 2, 1, TraversabilityEdge.Provenance.CONTACT))).isTrue();
        assertThat(map.advanceWorldRevision(2, List.of(), List.of(xy))).isEqualTo(1);
        KnownTraversabilitySnapshot finalSnapshot = map.snapshot().orElseThrow();
        assertThat(finalSnapshot.edge(ab).orElseThrow().status())
                .isEqualTo(TraversabilityEdge.Status.STALE);
        assertThat(finalSnapshot.edge(bc).orElseThrow().status())
                .isEqualTo(TraversabilityEdge.Status.CONFIRMED);
        assertThat(finalSnapshot.edge(xy).orElseThrow().status())
                .isEqualTo(TraversabilityEdge.Status.STALE);
    }

    @Test
    void aStarHasDeterministicTieBreakIndependentOfInsertionOrder() {
        UUID session = UUID.randomUUID();
        NavCell start = cell(0, 64, 0);
        NavCell north = cell(0, 64, 1);
        NavCell east = cell(1, 64, 0);
        NavCell target = cell(1, 64, 1);
        List<TraversabilityEdge> edges = List.of(
                confirmed(session, start, east, 1, 0, TraversabilityEdge.Provenance.LOCAL_VOLUME),
                confirmed(session, east, target, 1, 0, TraversabilityEdge.Provenance.LOCAL_VOLUME),
                confirmed(session, start, north, 1, 0, TraversabilityEdge.Provenance.LOCAL_VOLUME),
                confirmed(session, north, target, 1, 0, TraversabilityEdge.Provenance.LOCAL_VOLUME));
        var forward = boundMap(session);
        edges.forEach(forward::observe);
        var reverse = boundMap(session);
        edges.reversed().forEach(reverse::observe);

        var pathfinder = new DeterministicAStar();
        var first = pathfinder.findRoute(forward.snapshot().orElseThrow(), start, target);
        var second = pathfinder.findRoute(reverse.snapshot().orElseThrow(), start, target);

        assertThat(first.found()).isTrue();
        assertThat(first.route()).isEqualTo(second.route());
        assertThat(first.expandedCells()).isEqualTo(second.expandedCells());
        assertThat(first.route().orElseThrow().cells()).containsExactly(start, north, target);
    }

    @Test
    void probeEdgesAreAllowedAndExplicitWhileBlockedAndStaleEdgesAreForbidden() {
        UUID session = UUID.randomUUID();
        NavCell start = cell(0, 64, 0);
        NavCell middle = cell(1, 64, 0);
        NavCell target = cell(2, 64, 0);
        var map = boundMap(session);
        map.observe(confirmed(
                session, start, middle, 1, 0, TraversabilityEdge.Provenance.LOCAL_VOLUME));
        map.observe(probe(session, middle, target, 1, 0));

        RoutePlan route = new DeterministicAStar()
                .findRoute(map.snapshot().orElseThrow(), start, target).route().orElseThrow();
        assertThat(route.usesProbeAllowed()).isTrue();
        assertThat(route.worldSessionId()).isEqualTo(session);
        assertThat(route.dimension()).isEqualTo(OVERWORLD);
        assertThat(route.worldRevision()).isZero();
        assertThat(route.probeEdgeCount()).isEqualTo(1);
        assertThat(route.edges().get(1).status()).isEqualTo(TraversabilityEdge.Status.PROBE_ALLOWED);
        assertThat(route.tickUpperBound()).isEqualTo(
                RoutePlan.BASE_SETTLE_TICKS
                        + 2L * RoutePlan.TICKS_PER_TRANSITION
                        + RoutePlan.EXTRA_TICKS_PER_PROBE);
        assertThat(route.toDslPrimitiveCost().distanceBlocks()).isEqualTo(2);
        assertThat(route.toDslPrimitiveCost().ticks()).isEqualTo(route.tickUpperBound());
        assertThatThrownBy(() -> route.cells().add(cell(3, 64, 0)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new RoutePlan(
                route.worldSessionId(),
                route.dimension(),
                route.worldRevision(),
                route.cells(),
                route.edges(),
                route.distanceBlocks(),
                route.probeEdgeCount(),
                0,
                0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("execution bounds");

        var forbidden = boundMap(session);
        forbidden.observe(blocked(session, start, middle, 1, 0));
        forbidden.observe(confirmed(
                session, middle, target, 1, 0, TraversabilityEdge.Provenance.LOCAL_VOLUME));
        var blockedResult = new DeterministicAStar()
                .findRoute(forbidden.snapshot().orElseThrow(), start, target);
        assertThat(blockedResult.route()).isEmpty();
        assertThat(blockedResult.failure()).contains(DeterministicAStar.FailureReason.NO_PATH);

        var stale = boundMap(session);
        var firstEdge = confirmed(
                session, start, middle, 1, 0, TraversabilityEdge.Provenance.LOCAL_VOLUME);
        stale.observe(firstEdge);
        stale.observe(confirmed(
                session, middle, target, 1, 0, TraversabilityEdge.Provenance.LOCAL_VOLUME));
        stale.advanceWorldRevision(1, List.of(), List.of(firstEdge.key()));
        var staleResult = new DeterministicAStar()
                .findRoute(stale.snapshot().orElseThrow(), start, target);
        assertThat(staleResult.route()).isEmpty();
        assertThat(staleResult.failure()).contains(DeterministicAStar.FailureReason.NO_PATH);
    }

    @Test
    void enforcesSameDimensionAndThirtyTwoBlockRouteLimit() {
        UUID session = UUID.randomUUID();
        var map = boundMap(session);
        List<NavCell> chain = new ArrayList<>();
        for (int x = 0; x <= 33; x++) chain.add(cell(x, 64, 0));
        for (int x = 0; x < 33; x++) {
            map.observe(confirmed(
                    session, chain.get(x), chain.get(x + 1), x, 0,
                    TraversabilityEdge.Provenance.LOCAL_VOLUME));
        }
        var pathfinder = new DeterministicAStar();
        RoutePlan maximum = pathfinder.findRoute(
                map.snapshot().orElseThrow(), chain.getFirst(), chain.get(32)).route().orElseThrow();
        assertThat(maximum.distanceBlocks()).isEqualTo(32);
        assertThat(maximum.tickUpperBound()).isEqualTo(532);

        var tooFar = pathfinder.findRoute(
                map.snapshot().orElseThrow(), chain.getFirst(), chain.get(33));
        assertThat(tooFar.failure()).contains(DeterministicAStar.FailureReason.TARGET_TOO_FAR);
        var wrongDimension = pathfinder.findRoute(
                map.snapshot().orElseThrow(), chain.getFirst(),
                new NavCell("minecraft:the_nether", 1, 64, 0));
        assertThat(wrongDimension.failure())
                .contains(DeterministicAStar.FailureReason.DIMENSION_MISMATCH);
    }

    @Test
    void diagonalEdgesCannotCutAnUnknownCorner() {
        UUID session = UUID.randomUUID();
        NavCell start = cell(0, 64, 0);
        NavCell xSide = cell(1, 64, 0);
        NavCell zSide = cell(0, 64, 1);
        NavCell target = cell(1, 64, 1);
        var map = boundMap(session);
        map.observe(confirmed(
                session, start, target, 1, 0, TraversabilityEdge.Provenance.CONTACT));
        var pathfinder = new DeterministicAStar();

        assertThat(pathfinder.findRoute(map.snapshot().orElseThrow(), start, target).failure())
                .contains(DeterministicAStar.FailureReason.NO_PATH);

        map.observe(confirmed(
                session, start, xSide, 2, 0, TraversabilityEdge.Provenance.LOCAL_VOLUME));
        map.observe(probe(session, start, zSide, 2, 0));
        assertThat(pathfinder.findRoute(map.snapshot().orElseThrow(), start, target).failure())
                .contains(DeterministicAStar.FailureReason.NO_PATH);

        map.observe(confirmed(
                session, start, zSide, 3, 0, TraversabilityEdge.Provenance.CONTACT));
        RoutePlan route = pathfinder.findRoute(
                map.snapshot().orElseThrow(), start, target).route().orElseThrow();
        assertThat(route.cells()).containsExactly(start, target);
        assertThat(route.distanceBlocks()).isEqualTo(Math.sqrt(2));
    }

    @Test
    void returnsSearchLimitBeforeExpandingPastTheFixedBound() {
        UUID session = UUID.randomUUID();
        NavCell start = cell(0, 64, 0);
        NavCell middle = cell(1, 64, 0);
        NavCell target = cell(2, 64, 0);
        var map = boundMap(session);
        map.observe(confirmed(
                session, start, middle, 1, 0, TraversabilityEdge.Provenance.LOCAL_VOLUME));
        map.observe(confirmed(
                session, middle, target, 1, 0, TraversabilityEdge.Provenance.LOCAL_VOLUME));

        var result = new DeterministicAStar().findRoute(
                map.snapshot().orElseThrow(), start, target, 1);
        assertThat(result.failure()).contains(DeterministicAStar.FailureReason.SEARCH_LIMIT);
        assertThat(result.expandedCells()).containsExactly(start);
        assertThat(DeterministicAStar.MAX_EXPANDED_NODES).isEqualTo(2_048);
    }

    @Test
    void aStarHonorsWorkerCancellationBeforeExpanding() {
        UUID session = UUID.randomUUID();
        NavCell start = cell(0, 64, 0);
        NavCell target = cell(1, 64, 0);
        var map = boundMap(session);
        map.observe(confirmed(
                session, start, target, 1, 0, TraversabilityEdge.Provenance.LOCAL_VOLUME));

        var result = new DeterministicAStar().findRoute(
                map.snapshot().orElseThrow(), start, target, () -> false,
                () -> { throw new AssertionError("cancelled search expanded a node"); });

        assertThat(result.failure()).contains(DeterministicAStar.FailureReason.SEARCH_CANCELLED);
        assertThat(result.expandedCells()).isEmpty();
    }

    private static KnownTraversabilityMap boundMap(UUID session) {
        var map = new KnownTraversabilityMap();
        map.startSession(session, OVERWORLD, 0);
        return map;
    }

    private static NavCell cell(int x, int y, int z) {
        return new NavCell(OVERWORLD, x, y, z);
    }

    private static TraversabilityEdge confirmed(
            UUID session,
            NavCell from,
            NavCell to,
            long tick,
            long revision,
            TraversabilityEdge.Provenance provenance) {
        return edge(
                session, from, to, TraversabilityEdge.Status.CONFIRMED,
                TraversabilityEdge.TargetSupport.CONFIRMED,
                TraversabilityEdge.Clearance.CONFIRMED,
                TraversabilityEdge.Transition.CONFIRMED,
                TraversabilityEdge.Fluid.NONE,
                TraversabilityEdge.Hazard.NONE,
                provenance,
                tick,
                revision);
    }

    private static TraversabilityEdge probe(
            UUID session, NavCell from, NavCell to, long tick, long revision) {
        return edge(
                session, from, to, TraversabilityEdge.Status.PROBE_ALLOWED,
                TraversabilityEdge.TargetSupport.CONFIRMED,
                TraversabilityEdge.Clearance.CONFIRMED,
                TraversabilityEdge.Transition.PARTIAL,
                TraversabilityEdge.Fluid.NONE,
                TraversabilityEdge.Hazard.NONE,
                TraversabilityEdge.Provenance.LOCAL_VOLUME,
                tick,
                revision);
    }

    private static TraversabilityEdge blocked(
            UUID session, NavCell from, NavCell to, long tick, long revision) {
        return edge(
                session, from, to, TraversabilityEdge.Status.BLOCKED,
                TraversabilityEdge.TargetSupport.ABSENT,
                TraversabilityEdge.Clearance.UNKNOWN,
                TraversabilityEdge.Transition.UNKNOWN,
                TraversabilityEdge.Fluid.NONE,
                TraversabilityEdge.Hazard.CAUTION,
                TraversabilityEdge.Provenance.LOCAL_VOLUME,
                tick,
                revision);
    }

    private static TraversabilityEdge edge(
            UUID session,
            NavCell from,
            NavCell to,
            TraversabilityEdge.Status status,
            TraversabilityEdge.TargetSupport support,
            TraversabilityEdge.Clearance clearance,
            TraversabilityEdge.Transition transition,
            TraversabilityEdge.Fluid fluid,
            TraversabilityEdge.Hazard hazard,
            TraversabilityEdge.Provenance provenance,
            long tick,
            long revision) {
        return new TraversabilityEdge(
                session,
                new TraversabilityEdge.Key(from, to),
                status,
                support,
                clearance,
                transition,
                fluid,
                hazard,
                provenance,
                from,
                tick,
                revision);
    }
}
