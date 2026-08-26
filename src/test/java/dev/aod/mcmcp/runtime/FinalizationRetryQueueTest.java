package dev.aod.mcmcp.runtime;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class FinalizationRetryQueueTest {
    @Test
    void releasesAgainAndRetainsARecordFailureUntilTheNextAttemptSucceeds() {
        var queue = new FinalizationRetryQueue();
        var routineId = UUID.randomUUID();
        var recordAttempts = new AtomicInteger();
        var emergencyReleases = new AtomicInteger();
        var retryIncident = new AtomicReference<FinalizationRetryQueue.Incident>();

        var first = queue.attempt(
                routineId,
                100,
                incident -> {
                    assertThat(incident).isNull();
                    queue.rememberCleanupOutcome(
                            routineId, true, false, "voicechat_disconnected");
                    recordAttempts.incrementAndGet();
                    throw new IllegalStateException("fixture record failure");
                },
                () -> emergencyReleases.incrementAndGet());

        assertThat(first.success()).isFalse();
        assertThat(first.emergencyReleaseAttempted()).isFalse();
        assertThat(first.emergencyRelease()).isNull();
        assertThat(first.incidentCode())
                .isEqualTo("finalization_record_exception_IllegalStateException");
        assertThat(queue.pendingRoutineIds(119)).isEmpty();
        assertThat(queue.pendingRoutineIds(120)).containsExactly(routineId);

        var second = queue.attempt(
                routineId,
                120,
                incident -> {
                    retryIncident.set(incident);
                    recordAttempts.incrementAndGet();
                    return "recorded";
                },
                () -> emergencyReleases.incrementAndGet());

        assertThat(second.success()).isTrue();
        assertThat(second.value()).isEqualTo("recorded");
        assertThat(retryIncident.get().boundaryFailureCode())
                .isEqualTo(first.incidentCode());
        assertThat(retryIncident.get().previousVoiceFailureCode())
                .isEqualTo("voicechat_disconnected");
        assertThat(recordAttempts).hasValue(2);
        assertThat(emergencyReleases).hasValue(0);
        assertThat(queue.pendingRoutineIds(Long.MAX_VALUE)).isEmpty();
    }

    @Test
    void isolatesAnEmergencyReleaseExceptionAndStillKeepsTheRetry() {
        var queue = new FinalizationRetryQueue();
        var routineId = UUID.randomUUID();

        var attempt = queue.attempt(
                routineId,
                10,
                ignored -> {
                    throw new IllegalArgumentException("record");
                },
                () -> {
                    throw new IllegalStateException("release");
                });

        assertThat(attempt.success()).isFalse();
        assertThat(attempt.emergencyReleaseAttempted()).isTrue();
        assertThat(attempt.emergencyRelease()).isNull();
        assertThat(attempt.failure().getSuppressed())
                .singleElement()
                .isInstanceOf(IllegalStateException.class);
        assertThat(queue.contains(routineId)).isTrue();
    }

    @Test
    void continuesRecordOnlyProbesAfterTheCleanupRetryBudgetUntilRecordingSucceeds() {
        var queue = new FinalizationRetryQueue();
        var routineId = UUID.randomUUID();
        var cleanupCalls = new AtomicInteger();
        long clientTick = 0;

        FinalizationRetryQueue.Attempt<Object, Integer> attempt = null;
        for (int index = 0; index < FinalizationRetryQueue.MAX_AUTOMATIC_ATTEMPTS; index++) {
            long attemptTick = clientTick;
            attempt = queue.attempt(
                    routineId,
                    attemptTick,
                    incident -> {
                        if (incident == null) {
                            cleanupCalls.incrementAndGet();
                            queue.rememberCleanupOutcome(routineId, true, true, null);
                        }
                        throw new IllegalStateException("persistent record failure");
                    },
                    () -> cleanupCalls.incrementAndGet());
            clientTick = attempt.incident().nextRetryClientTick();
        }

        assertThat(attempt).isNotNull();
        assertThat(attempt.incident().failedAttempts())
                .isEqualTo(FinalizationRetryQueue.MAX_AUTOMATIC_ATTEMPTS);
        assertThat(queue.pendingRoutineIds(clientTick)).containsExactly(routineId);
        assertThat(queue.contains(routineId)).isTrue();
        assertThat(cleanupCalls).hasValue(1);

        var recovered = queue.attempt(
                routineId,
                clientTick,
                incident -> {
                    assertThat(incident.failedAttempts())
                            .isEqualTo(FinalizationRetryQueue.MAX_AUTOMATIC_ATTEMPTS);
                    return "recorded";
                },
                () -> cleanupCalls.incrementAndGet());

        assertThat(recovered.success()).isTrue();
        assertThat(recovered.value()).isEqualTo("recorded");
        assertThat(queue.hasPending()).isFalse();
        assertThat(queue.pendingRoutineIds(Long.MAX_VALUE)).isEmpty();
        assertThat(cleanupCalls).hasValue(1);
    }
}
