package dev.aod.mcmcp.agent.observation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class NearbyEntityCandidatesTest {
    private record Candidate(int id, double distance) { }

    @Test
    void distantWarehouseFramesCannotHideTheFrameInFrontOfThePlayer() {
        var entities = new ArrayList<>(IntStream.range(0, 200)
                .mapToObj(id -> new Candidate(id, 12)).toList());
        var targetFrame = new Candidate(200, 3);
        entities.add(targetFrame); // Vanilla's iteration order need not be nearest first.

        var candidates = collect(entities, 16, 4.5, new AtomicInteger());

        assertThat(candidates).hasSize(OmnidirectionalObserver.MAX_NEARBY_ENTITIES + 1);
        assertThat(candidates.subList(0, OmnidirectionalObserver.MAX_NEARBY_ENTITIES))
                .contains(targetFrame);
        assertThat(candidates).doesNotHaveDuplicates();
    }

    @Test
    void anAlreadyFullNearQueryDoesNotAppendBeyondTheTruncationSentinel() {
        var entities = IntStream.range(0, 200)
                .mapToObj(id -> new Candidate(id, 3)).toList();
        var queries = new AtomicInteger();

        assertThat(collect(entities, 16, 4.5, queries))
                .hasSize(OmnidirectionalObserver.MAX_NEARBY_ENTITIES + 1);
        assertThat(queries).hasValue(1);
    }

    @Test
    void entityPartsCannotBeAppendedAfterTheNativeQueryHasReachedItsCap() {
        var candidates = OmnidirectionalObserver.<Candidate>collectEntityCandidates(16, 4.5,
                candidate -> candidate.distance() * candidate.distance(), (radius, predicate, destination) -> {
                    for (int id = 0; id < OmnidirectionalObserver.MAX_NEARBY_ENTITIES + 1; id++) {
                        var candidate = new Candidate(id, 2);
                        if (predicate.test(candidate)) destination.add(candidate);
                    }
                    // NeoForge checks dragon parts after the normal query has reached its maximum.
                    var part = new Candidate(200, 3);
                    if (predicate.test(part)) destination.add(part);
                });

        assertThat(candidates).hasSize(OmnidirectionalObserver.MAX_NEARBY_ENTITIES + 1);
        assertThat(candidates).doesNotContain(new Candidate(200, 3));
    }

    @Test
    void rangeBoundaryIsIncludedOnceAndDistantVisibleCandidatesStillFillTheBudget() {
        var entities = List.of(new Candidate(0, 4.5), new Candidate(1, 16),
                new Candidate(2, 16.001), new Candidate(3, 2));

        assertThat(collect(entities, 16, 4.5, new AtomicInteger()))
                .containsExactly(entities.get(0), entities.get(3), entities.get(1));
    }

    @Test
    void shortFogStillExcludesEvenReachableFramesAndSkipsTheOuterQuery() {
        var entities = List.of(new Candidate(0, 1.001), new Candidate(1, 1));
        var queries = new AtomicInteger();

        assertThat(collect(entities, 1, 4.5, queries)).containsExactly(entities.get(1));
        assertThat(queries).hasValue(1);
    }

    private static List<Candidate> collect(
            List<Candidate> entities, double radius, double interactionRange, AtomicInteger queries) {
        return OmnidirectionalObserver.collectEntityCandidates(radius, interactionRange,
                candidate -> candidate.distance() * candidate.distance(), (queryRadius, predicate, destination) -> {
                    queries.incrementAndGet();
                    assertThat(queryRadius).isLessThanOrEqualTo(radius);
                    if (queries.get() == 1) assertThat(queryRadius).isEqualTo(Math.min(radius, interactionRange));
                    // Match Level.getEntities' cumulative maximum, including its append-before-stop.
                    for (var entity : entities) {
                        if (predicate.test(entity)) {
                            destination.add(entity);
                            if (destination.size() >= OmnidirectionalObserver.MAX_NEARBY_ENTITIES + 1) return;
                        }
                    }
                });
    }
}
