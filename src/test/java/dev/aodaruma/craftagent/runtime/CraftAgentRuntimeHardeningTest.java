package dev.aodaruma.craftagent.runtime;

import dev.aodaruma.craftagent.routine.BlockTarget;
import dev.aodaruma.craftagent.routine.BlockStateFingerprint;
import dev.aodaruma.craftagent.routine.RoutineEventRing;
import dev.aodaruma.craftagent.routine.RoutineManager;
import dev.aodaruma.craftagent.routine.RoutineProgress;
import dev.aodaruma.craftagent.routine.RoutineSnapshot;
import dev.aodaruma.craftagent.routine.RoutineState;
import dev.aodaruma.craftagent.routine.StationaryBreakGoal;
import dev.aodaruma.craftagent.routine.StationaryBreakPort;
import dev.aodaruma.craftagent.routine.StationaryBreakRequest;
import dev.aodaruma.craftagent.voice.VoiceChatSafetyController;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

class CraftAgentRuntimeHardeningTest {
    @Test
    void retainsVoiceOwnershipAfterAFailedEndSoFinalizationCanRetryRecovery() {
        var routineId = UUID.randomUUID();
        var failed = new CraftAgentRuntime.VoiceEndOutcome(
                false, "restore_readback_mismatch", true, true, false);
        var restored = new CraftAgentRuntime.VoiceEndOutcome(
                true, null, true, true, true);

        assertThat(CraftAgentRuntime.voiceRoutineAfterEnd(routineId, routineId, failed))
                .isEqualTo(routineId);
        assertThat(CraftAgentRuntime.voiceRoutineAfterEnd(routineId, routineId, restored))
                .isNull();
    }

    @Test
    void exposesBeginRollbackAndEndDiagnosticsAsSchemaSafeScalars() {
        var begin = new VoiceChatSafetyController.BeginResult(
                false,
                true,
                true,
                null,
                "mute_readback_mismatch",
                true,
                false,
                "restore_readback_mismatch");
        var details = new java.util.LinkedHashMap<>(
                CraftAgentRuntime.voiceBeginFailureDetails(begin));
        var end = new CraftAgentRuntime.VoiceEndOutcome(
                false, "voicechat_disconnected", true, true, false);

        var mapped = CraftAgentRuntime.mapFailure(
                CraftAgentRuntime.withVoiceEndFailureDiagnostics(
                        new ClientCommandInbox.CommandTimeoutException("start_routine"),
                        end));

        details.forEach((key, value) -> assertThat(value)
                .as("scalar diagnostic %s", key)
                .isInstanceOfAny(String.class, Number.class, Boolean.class));
        assertThat(details)
                .containsEntry("reason", "mute_readback_mismatch")
                .containsEntry("voice.rollback_attempted", true)
                .containsEntry("voice.rollback_restored", false)
                .containsEntry("voice.rollback_failure", "restore_readback_mismatch");
        assertThat(mapped.failure().code()).isEqualTo("timeout");
        assertThat(mapped.failure().message()).isEqualTo("The client-thread deadline expired");
        assertThat(mapped.failure().retryable()).isTrue();
        assertThat(mapped.failure().details())
                .containsEntry("voice.end_succeeded", false)
                .containsEntry("voice.end_session_existed", true)
                .containsEntry("voice.end_restore_attempted", true)
                .containsEntry("voice.end_restored", false)
                .containsEntry("voice.end_failure", "voicechat_disconnected");
    }

    @Test
    void turnsARetriedRecordExceptionIntoAVisibleFinalizationFailure() {
        var failure = CraftAgentRuntime.finalizationFailure(
                terminalSnapshot(),
                true,
                true,
                null,
                "finalization_record_exception_IllegalStateException",
                false,
                "voicechat_disconnected");

        assertThat(failure.code()).isEqualTo("FINALIZATION_BOUNDARY_FAILED");
        assertThat(failure.observed())
                .containsEntry(
                        "boundary_failure",
                        "finalization_record_exception_IllegalStateException")
                .containsEntry("previous_voicechat_failure", "voicechat_disconnected")
                .containsEntry("inputs_released", true)
                .containsEntry("voicechat_restored", true);
        assertThat(failure.evidence()).containsEntry("finalization_retry", true);
    }

    @Test
    void blocksNewAdmissionUntilPendingFinalizationRecordingRecovers() {
        var retries = new FinalizationRetryQueue();
        var routineId = UUID.randomUUID();
        var first = retries.attempt(
                routineId,
                10,
                ignored -> {
                    retries.rememberCleanupOutcome(routineId, true, true, null);
                    throw new IllegalStateException("record failed");
                },
                () -> "unused");
        assertThat(first.success()).isFalse();

        var mapped = CraftAgentRuntime.mapFailure(
                catchThrowable(() -> CraftAgentRuntime.requireNoPendingFinalizations(retries)));

        assertThat(mapped.failure().code()).isEqualTo("unsafe_state");
        assertThat(mapped.failure().retryable()).isTrue();
        assertThat(mapped.failure().details())
                .containsEntry("reason", "finalization_pending")
                .containsEntry("pending_finalizations", 1);

        var recovered = retries.attempt(
                routineId,
                first.incident().nextRetryClientTick(),
                ignored -> "recorded",
                () -> "unused");
        assertThat(recovered.success()).isTrue();
        assertThatCode(() -> CraftAgentRuntime.requireNoPendingFinalizations(retries))
                .doesNotThrowAnyException();
    }

    @Test
    void checksPendingFinalizationBeforeReplayCanPurgeAnExpiredTerminalRoutine() {
        var retries = new FinalizationRetryQueue();
        var routines = new RoutineManager(unusedStationaryBreakPort());
        var key = UUID.randomUUID().toString();
        var request = new StationaryBreakRequest(
                new BlockTarget("minecraft:overworld", 1, 64, 2),
                new BlockStateFingerprint("minecraft:cobblestone", Map.of()),
                new StationaryBreakGoal("minecraft:cobblestone", 1),
                100,
                5,
                20);
        var receipt = routines.startStationaryBreak(key, "same-request", request, 10);
        routines.cancelRoutine(receipt.routineId(), "test", 0, 1);
        retries.attempt(
                receipt.routineId(),
                10,
                ignored -> {
                    retries.rememberCleanupOutcome(receipt.routineId(), false, false, "pending");
                    throw new IllegalStateException("record failed");
                },
                Object::new);

        var blocked = catchThrowable(() ->
                CraftAgentRuntime.replayStationaryBreakAfterFinalizationGate(
                        retries,
                        routines,
                        key,
                        "same-request",
                        10 + RoutineManager.DEFAULT_TERMINAL_TTL_TICKS));

        assertThat(CraftAgentRuntime.mapFailure(blocked).failure().code())
                .isEqualTo("unsafe_state");
        assertThat(routines.getRoutine(receipt.routineId(), 0, 1).state())
                .isEqualTo(RoutineState.CANCELLED);
    }

    @Test
    void stopsRetryingGlobalCleanupAfterTheAutomaticBudget() {
        var retries = new FinalizationRetryQueue();
        var routineId = UUID.randomUUID();
        var cleanupAttempts = new AtomicInteger();
        long clientTick = 0;
        FinalizationRetryQueue.Attempt<Object, Object> attempt = null;

        for (int index = 0; index < FinalizationRetryQueue.MAX_AUTOMATIC_ATTEMPTS; index++) {
            long attemptTick = clientTick;
            attempt = retries.attempt(
                    routineId,
                    attemptTick,
                    incident -> {
                        if (CraftAgentRuntime.shouldRetryFinalizationCleanup(incident)) {
                            cleanupAttempts.incrementAndGet();
                        }
                        throw new IllegalStateException("record failed");
                    },
                    Object::new);
            clientTick = attempt.incident().nextRetryClientTick();
        }

        assertThat(attempt).isNotNull();
        assertThat(CraftAgentRuntime.shouldRetryFinalizationCleanup(attempt.incident())).isFalse();

        retries.attempt(
                routineId,
                clientTick,
                incident -> {
                    if (CraftAgentRuntime.shouldRetryFinalizationCleanup(incident)) {
                        cleanupAttempts.incrementAndGet();
                    }
                    return "recorded";
                },
                Object::new);

        assertThat(cleanupAttempts)
                .hasValue(FinalizationRetryQueue.MAX_AUTOMATIC_ATTEMPTS);
    }

    private static RoutineSnapshot terminalSnapshot() {
        return new RoutineSnapshot(
                UUID.randomUUID(),
                "stationary_break",
                RoutineState.CANCELLED,
                "cancelled",
                false,
                new RoutineProgress(0, 1, "items"),
                new BlockTarget("minecraft:overworld", 1, 64, 2),
                null,
                10,
                1,
                Map.of("attempts", 0),
                null,
                false,
                null,
                new RoutineEventRing.EventPage(List.of(), false, false, 1, 0));
    }

    private static StationaryBreakPort unusedStationaryBreakPort() {
        return (StationaryBreakPort) Proxy.newProxyInstance(
                StationaryBreakPort.class.getClassLoader(),
                new Class<?>[]{StationaryBreakPort.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("retire")) {
                        return null;
                    }
                    throw new AssertionError("Unexpected port call: " + method.getName());
                });
    }
}
