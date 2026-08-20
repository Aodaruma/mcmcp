package dev.aodaruma.craftagent.runtime;

import dev.aodaruma.craftagent.routine.BlockTarget;
import dev.aodaruma.craftagent.routine.RoutineEventRing;
import dev.aodaruma.craftagent.routine.RoutineFailure;
import dev.aodaruma.craftagent.routine.RoutineProgress;
import dev.aodaruma.craftagent.routine.RoutineSnapshot;
import dev.aodaruma.craftagent.routine.RoutineState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RoutineWireMapperTest {
    @Test
    void routineFailureDoesNotMakeSuccessfulCleanupLookLikeFinalizationFailure() {
        var routineFailure = failure(RoutineFailure.Scope.ROUTINE, "WORLD_UNAVAILABLE");
        var wire = RoutineWireMapper.toMap(snapshot(
                RoutineState.FAILED, false, routineFailure, true, null));

        assertThat(finalization(wire))
                .containsEntry("status", "succeeded")
                .containsEntry("phase", "release")
                .containsEntry("failure", null);
        assertThat(failurePayload(wire)).containsEntry("scope", "routine");
    }

    @Test
    void onlyAnExplicitFinalizationScopedOutcomeMapsToFinalizationFailure() {
        var cleanupFailure = failure(
                RoutineFailure.Scope.FINALIZATION, "VOICECHAT_RESTORE_FAILED");
        var wire = RoutineWireMapper.toMap(snapshot(
                RoutineState.CANCELLED, false, null, true, cleanupFailure));

        assertThat(finalization(wire))
                .containsEntry("status", "failed")
                .containsEntry("phase", "release");
        assertThat(failurePayload(finalization(wire)))
                .containsEntry("scope", "finalization")
                .containsEntry("code", "VOICECHAT_RESTORE_FAILED");
        assertThat(wire).containsEntry("failure", null);
    }

    @Test
    void runtimeBuildsSeparateInputAndVoiceCleanupEvidence() {
        var cancelled = snapshot(RoutineState.CANCELLED, false, null, false, null);

        assertThat(CraftAgentRuntime.finalizationFailure(cancelled, true, true, null)).isNull();

        var inputFailure = CraftAgentRuntime.finalizationFailure(
                cancelled, false, true, null);
        assertThat(inputFailure.code()).isEqualTo("INPUT_RELEASE_FAILED");
        assertThat(inputFailure.scope()).isEqualTo(RoutineFailure.Scope.FINALIZATION);
        assertThat(inputFailure.observed()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "inputs_released", false,
                "voicechat_restored", true));

        var voiceFailure = CraftAgentRuntime.finalizationFailure(
                cancelled, true, false, "voicechat_restore_readback_mismatch");
        assertThat(voiceFailure.code()).isEqualTo("VOICECHAT_RESTORE_FAILED");
        assertThat(voiceFailure.observed()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "inputs_released", true,
                "voicechat_restored", false,
                "voicechat_failure", "voicechat_restore_readback_mismatch"));
        assertThat(voiceFailure.evidence())
                .containsEntry("goal_verified", false)
                .containsEntry("terminal_state", "CANCELLED");
    }

    @Test
    void cancelReplayKeepsInputReleaseSeparateFromVoiceRestore() {
        var unfinished = snapshot(RoutineState.CANCELLED, false, null, false, null);
        var voiceFailure = CraftAgentRuntime.finalizationFailure(
                unfinished, true, false, "voicechat_restore_readback_mismatch");
        var inputFailure = CraftAgentRuntime.finalizationFailure(
                unfinished, false, true, null);

        assertThat(CraftAgentRuntime.finalizationReleasedInputs(snapshot(
                RoutineState.CANCELLED, false, null, true, voiceFailure))).isTrue();
        assertThat(CraftAgentRuntime.finalizationReleasedInputs(snapshot(
                RoutineState.CANCELLED, false, null, true, inputFailure))).isFalse();
        assertThat(CraftAgentRuntime.finalizationReleasedInputs(unfinished)).isFalse();
    }

    private static RoutineSnapshot snapshot(
            RoutineState state,
            boolean goalVerified,
            RoutineFailure failure,
            boolean finalizationCompleted,
            RoutineFailure finalizationFailure) {
        return new RoutineSnapshot(
                UUID.fromString("00000000-0000-0000-0000-000000000042"),
                "stationary_break",
                state,
                state.name().toLowerCase(java.util.Locale.ROOT),
                goalVerified,
                new RoutineProgress(1, 2, "items"),
                new BlockTarget("minecraft:overworld", 1, 64, 2),
                null,
                42,
                7,
                Map.of(
                        "inventory_server_synchronized", true,
                        "verified_breaks", 1,
                        "attempts", 2),
                failure,
                finalizationCompleted,
                finalizationFailure,
                new RoutineEventRing.EventPage(List.of(), false, false, 1, 0));
    }

    private static RoutineFailure failure(RoutineFailure.Scope scope, String code) {
        return new RoutineFailure(
                RoutineFailure.Category.EXTERNAL,
                code,
                false,
                RoutineFailure.Recovery.USER,
                scope,
                2,
                Map.of(),
                Map.of(),
                Map.of(),
                List.of("player"),
                true);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> finalization(Map<String, Object> wire) {
        return (Map<String, Object>) wire.get("finalization");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> failurePayload(Map<String, Object> wire) {
        return (Map<String, Object>) wire.get("failure");
    }
}
