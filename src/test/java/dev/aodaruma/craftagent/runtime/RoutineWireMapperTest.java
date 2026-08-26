package dev.aodaruma.craftagent.runtime;

import dev.aodaruma.craftagent.routine.BlockTarget;
import dev.aodaruma.craftagent.routine.RoutineEventRing;
import dev.aodaruma.craftagent.routine.RoutineCheckpoint;
import dev.aodaruma.craftagent.routine.RoutineEffect;
import dev.aodaruma.craftagent.routine.RoutineFailure;
import dev.aodaruma.craftagent.routine.RoutineProgress;
import dev.aodaruma.craftagent.routine.RoutineSnapshot;
import dev.aodaruma.craftagent.routine.RoutineState;
import dev.aodaruma.craftagent.routine.RoutineStep;
import dev.aodaruma.craftagent.routine.RoutineVerification;
import dev.aodaruma.craftagent.routine.RoutineWait;
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

    @Test
    void mapsTypedRoutineStateWithoutGuessingStationaryBreakFields() {
        var snapshot = new RoutineSnapshot(
                UUID.fromString("00000000-0000-0000-0000-000000000043"),
                "interact_block",
                RoutineState.WAITING,
                "wait_server_sync",
                false,
                new RoutineProgress(0, 1, "interactions"),
                new RoutineStep("interact_block", Map.of(
                        "target", Map.of(
                                "dimension", "minecraft:overworld", "x", 4, "y", 65, "z", 8),
                        "expected_after", Map.of("block", "minecraft:lever", "properties", Map.of(
                                "powered", "true")))),
                new RoutineCheckpoint(3, 91),
                new RoutineVerification(0, 1, 1),
                List.of(new RoutineEffect(
                        "block_toggled",
                        Map.of("powered", false),
                        Map.of("powered", true),
                        RoutineEffect.Verification.CONFIRMED)),
                new RoutineWait("server_sync", 120, "server confirms the expected block state"),
                92,
                Map.of("attempts", 1),
                null,
                false,
                null,
                new RoutineEventRing.EventPage(List.of(), false, false, 1, 0));

        var wire = RoutineWireMapper.toMap(snapshot);

        assertThat(wire).containsEntry("kind", "interact_block");
        assertThat(wire).containsEntry("resources", null);
        assertThat(payload(wire, "current_step"))
                .containsEntry("kind", "interact_block")
                .containsEntry("target", snapshot.currentStep().fields().get("target"))
                .containsEntry("expected_after", snapshot.currentStep().fields().get("expected_after"));
        assertThat(payload(wire, "checkpoint")).containsExactlyInAnyOrderEntriesOf(Map.of(
                "seq", 3L,
                "observation_revision", 91L));
        assertThat(payload(wire, "verification")).containsExactlyInAnyOrderEntriesOf(Map.of(
                "confirmed", 0,
                "expected", 1,
                "unknown", 1));
        assertThat(payload(wire, "wait")).containsEntry("reason", "server_sync");
        assertThat((List<?>) wire.get("effects")).singleElement().satisfies(effect ->
                assertThat(((Map<?, ?>) effect).get("verification")).isEqualTo("confirmed"));
    }

    @Test
    void mapsPlanCellAndSortedResourceSynchronizationWithoutLeakingDiagnostics() {
        var snapshot = new RoutineSnapshot(
                UUID.fromString("00000000-0000-0000-0000-000000000044"),
                "apply_block_plan",
                RoutineState.WAITING,
                "wait_prepare",
                false,
                new RoutineProgress(1, 2, "cells"),
                new RoutineStep("plan_cell", Map.of(
                        "step_index", 1,
                        "phase_id", "foundation",
                        "cell_id", "stone-1",
                        "operation", "place",
                        "target", Map.of(
                                "dimension", "minecraft:overworld", "x", 4, "y", 65, "z", 8),
                        "expected_after", Map.of(
                                "block", "minecraft:stone", "properties", Map.of()),
                        "child_stage", "place",
                        "item", "minecraft:stone")),
                new RoutineCheckpoint(1, 90),
                new RoutineVerification(1, 2, 0),
                List.of(),
                new RoutineWait("bounded_preparation", 120, "bounded aim is ready"),
                92,
                Map.of("resource_plan", Map.of(
                        "planned", Map.of("minecraft:stone", 2, "minecraft:dirt", 1),
                        "remaining", Map.of("minecraft:stone", 1),
                        "available", Map.of("minecraft:stone", 12, "minecraft:dirt", 3),
                        "server_synchronized", true,
                        "basis_observation_revision", 91L)),
                null,
                false,
                null,
                new RoutineEventRing.EventPage(List.of(), false, false, 1, 0));

        var wire = RoutineWireMapper.toMap(snapshot);

        assertThat(payload(wire, "current_step"))
                .containsEntry("kind", "plan_cell")
                .containsEntry("operation", "place")
                .containsEntry("cell_id", "stone-1");
        var resources = payload(wire, "resources");
        assertThat(resources)
                .containsEntry("server_synchronized", true)
                .containsEntry("basis_observation_revision", 91L);
        var plannedItems = ((List<?>) resources.get("planned")).stream()
                .map(item -> (String) ((Map<?, ?>) item).get("item"))
                .toList();
        assertThat(plannedItems)
                .containsExactly("minecraft:dirt", "minecraft:stone");
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payload(Map<String, Object> wire, String key) {
        return (Map<String, Object>) wire.get(key);
    }
}
