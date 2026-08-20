package dev.aodaruma.craftagent.routine;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RoutineEventRingTest {
    @Test
    void keepsMonotonicSequenceAndReportsAnExpiredCursor() {
        var ring = new RoutineEventRing(2);
        ring.append(RoutineEventType.PHASE_STARTED, 10, 20, Map.of("phase", "one"));
        ring.append(RoutineEventType.STEP_VERIFIED, 11, 21, Map.of());
        ring.append(RoutineEventType.CHECKPOINT, 12, 22, Map.of());

        var expired = ring.page(0, 8);

        assertThat(expired.events()).extracting(RoutineEvent::seq).containsExactly(2L, 3L);
        assertThat(expired.eventsTruncated()).isTrue();
        assertThat(expired.oldestRetainedSeq()).isEqualTo(2);
        assertThat(expired.latestSeq()).isEqualTo(3);

        var current = ring.page(2, 8);
        assertThat(current.events()).extracting(RoutineEvent::seq).containsExactly(3L);
        assertThat(current.eventsTruncated()).isFalse();
    }

    @Test
    void distinguishesPageLimitFromRingTruncation() {
        var ring = new RoutineEventRing(4);
        ring.append(RoutineEventType.PHASE_STARTED, 1, 1, Map.of());
        ring.append(RoutineEventType.STEP_VERIFIED, 2, 2, Map.of());

        var page = ring.page(0, 1);

        assertThat(page.events()).hasSize(1);
        assertThat(page.eventsTruncated()).isFalse();
        assertThat(page.hasMore()).isTrue();
    }
}
