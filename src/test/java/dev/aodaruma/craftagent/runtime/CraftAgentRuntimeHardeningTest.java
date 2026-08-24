package dev.aodaruma.craftagent.runtime;

import dev.aodaruma.craftagent.routine.ActionBounds;
import dev.aodaruma.craftagent.routine.ApplyBlockPlanOperation;
import dev.aodaruma.craftagent.routine.ApplyBlockPlanRequest;
import dev.aodaruma.craftagent.routine.ApplyBlockPlanStep;
import dev.aodaruma.craftagent.routine.BlockTarget;
import dev.aodaruma.craftagent.routine.BlockStateFingerprint;
import dev.aodaruma.craftagent.routine.BreakBlockRequest;
import dev.aodaruma.craftagent.routine.InteractBlockRequest;
import dev.aodaruma.craftagent.routine.InteractEntityRequest;
import dev.aodaruma.craftagent.routine.NavigateToRequest;
import dev.aodaruma.craftagent.routine.PlaceBlockRequest;
import dev.aodaruma.craftagent.routine.RoutineEventRing;
import dev.aodaruma.craftagent.routine.RoutineManager;
import dev.aodaruma.craftagent.routine.RoutineProgress;
import dev.aodaruma.craftagent.routine.SemanticActionPort;
import dev.aodaruma.craftagent.routine.RoutineSnapshot;
import dev.aodaruma.craftagent.routine.RoutineState;
import dev.aodaruma.craftagent.routine.StationaryBreakGoal;
import dev.aodaruma.craftagent.routine.StationaryBreakPort;
import dev.aodaruma.craftagent.routine.StationaryBreakRequest;
import dev.aodaruma.craftagent.safety.LocalArmingState;
import dev.aodaruma.craftagent.voice.VoiceChatSafetyController;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class CraftAgentRuntimeHardeningTest {
    @Test
    void creativeEditAndSurvivalRoutineAdmissionsAreMutuallyExclusive() {
        var editThenRoutine = CraftAgentRuntime.mapFailure(catchThrowable(() ->
                CraftAgentRuntime.requireNoConcurrentWorldMutation(true, false)));
        var routineThenEdit = CraftAgentRuntime.mapFailure(catchThrowable(() ->
                CraftAgentRuntime.requireNoConcurrentWorldMutation(false, true)));

        assertThat(editThenRoutine.failure().code()).isEqualTo("busy");
        assertThat(editThenRoutine.failure().retryable()).isTrue();
        assertThat(routineThenEdit.failure().code()).isEqualTo("busy");
        assertThat(routineThenEdit.failure().retryable()).isTrue();
        assertThatCode(() -> CraftAgentRuntime.requireNoConcurrentWorldMutation(false, false))
                .doesNotThrowAnyException();
    }

    @Test
    void localPriorityStopRequestsBeforeItDrainsSynchronously() {
        var calls = new ArrayList<String>();

        CraftAgentRuntime.runPriorityStop(
                () -> calls.add("request"),
                () -> calls.add("drain"));

        assertThat(calls).containsExactly("request", "drain");
    }

    @Test
    void lifecycleFenceClearsEveryAutomationPortIncludingBlockPlans() {
        var calls = new ArrayList<String>();

        CraftAgentRuntime.clearAutomationPortSessions(
                () -> calls.add("stationary_break"),
                () -> calls.add("semantic_action"),
                () -> calls.add("apply_block_plan"));

        assertThat(calls).containsExactly(
                "stationary_break", "semantic_action", "apply_block_plan");

        calls.clear();
        CraftAgentRuntime.clearAutomationPortSessions(
                () -> {
                    calls.add("stationary_failed");
                    throw new IllegalStateException("fixture");
                },
                () -> calls.add("semantic_after_failure"),
                () -> calls.add("plan_after_failure"));
        assertThat(calls).containsExactly(
                "stationary_failed", "semantic_after_failure", "plan_after_failure");
    }

    @Test
    void manualInputOrPauseReleasesBeforeTheRoutineCanTickAgain() {
        var calls = new ArrayList<String>();

        boolean stopped = CraftAgentRuntime.runPriorityEventStopIfRequired(
                true,
                () -> calls.add("event_stop_requested"),
                () -> calls.add("active_and_pending_released"));
        if (!stopped) {
            calls.add("routine_tick");
        }

        assertThat(stopped).isTrue();
        assertThat(calls).containsExactly(
                "event_stop_requested", "active_and_pending_released");
    }

    @Test
    void pauseFalseDoesNotStopOrBlockTheNextRoutineTick() {
        var calls = new ArrayList<String>();

        boolean stopped = CraftAgentRuntime.runPriorityEventStopIfRequired(
                false,
                () -> calls.add("request"),
                () -> calls.add("drain"));
        if (!stopped) {
            calls.add("routine_tick");
        }

        assertThat(stopped).isFalse();
        assertThat(calls).containsExactly("routine_tick");
    }

    @Test
    void explicitArmingLockStopsBeforeTheNextRoutineTick() {
        var arming = new LocalArmingState();
        var sessionId = UUID.randomUUID();
        arming.arm(sessionId, Set.of("navigate_to"));
        arming.lock("local_ui_disabled");
        var locked = arming.snapshot(sessionId);
        var calls = new ArrayList<String>();

        var mayTick = CraftAgentRuntime.enforceActiveRoutineArming(
                locked,
                () -> calls.add("request:local_arming_locked"),
                () -> calls.add("drain"));
        if (mayTick) {
            calls.add("routine_tick");
        }

        assertThat(mayTick).isFalse();
        assertThat(locked.lastLockReason()).isEqualTo("local_ui_disabled");
        assertThat(calls).containsExactly("request:local_arming_locked", "drain");
    }

    @Test
    void routineWallClockDeadlineIsIndependentFromTheUnlimitedLocalArm() {
        var routineId = UUID.randomUUID();
        long negativeStart = -Duration.ofSeconds(100).toNanos();
        var negativeClock = CraftAgentRuntime.RoutineWallClockDeadline.start(
                routineId, 30, negativeStart);
        long nearWrap = Long.MAX_VALUE - Duration.ofSeconds(1).toNanos();
        var wrappedClock = CraftAgentRuntime.RoutineWallClockDeadline.start(
                routineId, 1, nearWrap);

        assertThat(negativeClock.durationNanos()).isEqualTo(Duration.ofSeconds(35).toNanos());
        assertThat(negativeClock.allows(
                routineId, negativeStart + Duration.ofSeconds(35).toNanos() - 1)).isTrue();
        assertThat(negativeClock.allows(
                routineId, negativeStart + Duration.ofSeconds(35).toNanos())).isFalse();
        assertThat(wrappedClock.allows(
                routineId, nearWrap + Duration.ofSeconds(5).toNanos())).isTrue();
        assertThat(wrappedClock.allows(
                routineId, nearWrap + Duration.ofSeconds(6).toNanos())).isFalse();
        assertThat(negativeClock.allows(UUID.randomUUID(), negativeStart)).isFalse();
    }

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
    void checksPendingFinalizationBeforeSemanticReplayCanPurgeAnExpiredTerminalRoutine() {
        var retries = new FinalizationRetryQueue();
        var routines = new RoutineManager(
                unusedStationaryBreakPort(), unusedSemanticActionPort());
        var key = UUID.randomUUID().toString();
        var target = new BlockTarget("minecraft:overworld", 1, 64, 2);
        var bounds = new ActionBounds(
                target.dimension(), target, target, 1, 30, false);
        var request = new NavigateToRequest(target, 0.5D, bounds);
        var receipt = routines.startSemanticAction(key, "same-request", request, 10);
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
                CraftAgentRuntime.replaySemanticActionAfterFinalizationGate(
                        retries,
                        routines,
                        key,
                        "same-request",
                        request,
                        10 + RoutineManager.DEFAULT_TERMINAL_TTL_TICKS));

        assertThat(CraftAgentRuntime.mapFailure(blocked).failure().code())
                .isEqualTo("unsafe_state");
        assertThat(routines.getRoutine(receipt.routineId(), 0, 1).state())
                .isEqualTo(RoutineState.CANCELLED);
    }

    @Test
    void advertisesExactlyTheThirteenPhaseSixRoutineKindsWithKindSpecificSchemas() {
        var catalog = CraftAgentRuntime.routineCatalog();

        assertThat(catalog).containsEntry("catalog_version", "phase-6");
        @SuppressWarnings("unchecked")
        var entries = (List<Map<String, Object>>) catalog.get("routines");
        assertThat(entries).hasSize(13);
        assertThat(entries).extracting(entry -> entry.get("kind"))
                .containsExactly(
                        "stationary_break",
                        "navigate_to",
                        "break_block",
                        "place_block",
                        "interact_block",
                        "interact_entity",
                        "apply_block_plan",
                        "craft_items",
                        "transfer_items",
                        "tend_crop_area",
                        "harvest_tree_area",
                        "sleep_at_bed",
                        "survey_area");
        assertThat(entries).allSatisfy(entry -> {
            assertThat(entry.get("input_schema")).isInstanceOf(Map.class);
            assertThat((List<?>) entry.get("postconditions"))
                    .isNotEmpty()
                    .allSatisfy(postcondition -> assertThat((String) postcondition)
                            .hasSizeLessThanOrEqualTo(160));
        });
        assertThat(entries.getFirst().get("input_schema"))
                .isNotSameAs(entries.get(1).get("input_schema"));
    }

    @Test
    void parsesBoundedSurveyAndUsesOrderIndependentPhaseFiveIdentity() {
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("waypoints", List.of(Map.of(
                "id", "start", "target", targetMap(), "look_at", targetMap())));
        parameters.put("samples", List.of(Map.of(
                "id", "sample-0", "position", targetMap())));
        parameters.put("goal", Map.of("minimum_observed_samples", 1));
        parameters.put("assessment", "coverage_only");

        var parsed = CraftAgentRuntime.phaseFiveRequestArgument(
                startArguments("survey_area", parameters, 128, 600, false),
                "minecraft:overworld");
        var reordered = new LinkedHashMap<String, Object>();
        reordered.put("assessment", "coverage_only");
        reordered.put("goal", Map.of("minimum_observed_samples", 1));
        reordered.put("samples", parameters.get("samples"));
        reordered.put("waypoints", parameters.get("waypoints"));
        var same = CraftAgentRuntime.phaseFiveRequestArgument(
                startArguments("survey_area", reordered, 128, 600, false),
                "minecraft:overworld");

        assertThat(parsed.request().kind()).isEqualTo("survey_area");
        assertThat(parsed.request().expectedUnits()).isEqualTo(1);
        assertThat(parsed.request().progressUnit()).isEqualTo("cells");
        assertThat(parsed.targets()).containsExactly(new BlockTarget(
                "minecraft:overworld", 1, 64, 2));
        assertThat(parsed.requestIdentity()).isEqualTo(same.requestIdentity())
                .matches("sha256:[0-9a-f]{64}");

        var impossibleGoal = new LinkedHashMap<>(parameters);
        impossibleGoal.put("goal", Map.of("minimum_observed_samples", 2));
        assertThatThrownBy(() -> CraftAgentRuntime.phaseFiveRequestArgument(
                startArguments("survey_area", impossibleGoal, 128, 600, false),
                "minecraft:overworld"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimum_observed_samples");
    }

    @Test
    void strictlyParsesAllFiveClosedSemanticActionBranches() {
        assertThat(CraftAgentRuntime.semanticActionArgument(
                startArguments("navigate_to", Map.of(
                        "target", targetMap(),
                        "horizontal_tolerance_blocks", 0.5D), 1, 120, false),
                "minecraft:overworld"))
                .isInstanceOf(NavigateToRequest.class);
        assertThat(CraftAgentRuntime.semanticActionArgument(
                startArguments("break_block", Map.of(
                        "target", targetMap(),
                        "expected_before", blockState("minecraft:stone"),
                        "expected_after", blockState("minecraft:air")), 0, 30, true),
                "minecraft:overworld"))
                .isInstanceOf(BreakBlockRequest.class);
        assertThat(CraftAgentRuntime.semanticActionArgument(
                startArguments("place_block", Map.of(
                        "target", targetMap(),
                        "expected_before", blockState("minecraft:air"),
                        "item", "minecraft:stone",
                        "expected_after", blockState("minecraft:stone")), 0, 30, false),
                "minecraft:overworld"))
                .isInstanceOf(PlaceBlockRequest.class);
        assertThat(CraftAgentRuntime.semanticActionArgument(
                startArguments("interact_block", Map.of(
                        "target", targetMap(),
                        "expected_before", Map.of(
                                "block", "minecraft:lever",
                                "properties", Map.of("powered", "false")),
                        "expected_after", Map.of(
                                "block", "minecraft:lever",
                                "properties", Map.of("powered", "true"))), 0, 30, false),
                "minecraft:overworld"))
                .isInstanceOf(InteractBlockRequest.class);
        assertThat(CraftAgentRuntime.semanticActionArgument(
                startArguments("interact_entity", Map.of(
                        "entity_ref", "abcdefghijklmnopqrstuvwx",
                        "expected_type", "minecraft:cow",
                        "hand", "main_hand",
                        "held_item", "minecraft:bucket",
                        "goal", Map.of(
                                "item", "minecraft:milk_bucket",
                                "minimum_inventory_count", 1)), 0, 30, false),
                "minecraft:overworld"))
                .isInstanceOf(InteractEntityRequest.class);
    }

    @Test
    void rejectsHybridUnknownAndFractionalSemanticArgumentsAtTheRuntimeBoundary() {
        var hybrid = new LinkedHashMap<String, Object>(Map.of(
                "target", targetMap(),
                "horizontal_tolerance_blocks", 0.5D));
        hybrid.put("expected_after", blockState("minecraft:air"));
        var fractionalBounds = boundsMap(1, 120, false);
        fractionalBounds.put("max_duration_seconds", 1.5D);

        assertThat(CraftAgentRuntime.mapFailure(catchThrowable(() ->
                CraftAgentRuntime.semanticActionArgument(
                        startArguments("navigate_to", hybrid, 1, 120, false),
                        "minecraft:overworld"))).failure().code())
                .isEqualTo("invalid_argument");
        assertThat(CraftAgentRuntime.mapFailure(catchThrowable(() ->
                CraftAgentRuntime.semanticActionArgument(
                        rawStartArguments("navigate_to", Map.of(
                                "target", targetMap(),
                                "horizontal_tolerance_blocks", 0.5D), fractionalBounds),
                        "minecraft:overworld"))).failure().code())
                .isEqualTo("invalid_argument");
        assertThat(CraftAgentRuntime.mapFailure(catchThrowable(() ->
                CraftAgentRuntime.semanticActionArgument(
                        startArguments("unknown_action", Map.of(), 0, 30, false),
                        "minecraft:overworld"))).failure().code())
                .isEqualTo("invalid_argument");
    }

    @Test
    void rejectsUnsupportedEntityInteractionDuringParsingBeforeVoiceAdmission() {
        var unsupported = startArguments("interact_entity", Map.of(
                "entity_ref", "abcdefghijklmnopqrstuvwx",
                "expected_type", "minecraft:pig",
                "hand", "main_hand",
                "held_item", "minecraft:bucket",
                "goal", Map.of(
                        "item", "minecraft:milk_bucket",
                        "minimum_inventory_count", 1)), 0, 30, false);

        var mapped = CraftAgentRuntime.mapFailure(catchThrowable(() ->
                CraftAgentRuntime.semanticActionArgument(unsupported, "minecraft:overworld")));

        assertThat(mapped.failure().code()).isEqualTo("invalid_argument");
        assertThat(mapped.failure().message()).contains("only supports adult cow milking");
    }

    @Test
    void rejectsNonAirBreakPostconditionDuringParsingBeforeVoiceAdmission() {
        var unsupported = startArguments("break_block", Map.of(
                "target", targetMap(),
                "expected_before", blockState("minecraft:stone"),
                "expected_after", blockState("minecraft:cobblestone")), 0, 30, true);

        var mapped = CraftAgentRuntime.mapFailure(catchThrowable(() ->
                CraftAgentRuntime.semanticActionArgument(unsupported, "minecraft:overworld")));

        assertThat(mapped.failure().code()).isEqualTo("invalid_argument");
        assertThat(mapped.failure().message()).contains("only supports minecraft:air");
    }

    @Test
    void rejectsUnsafePhaseTwoAndThreeBreakSourcesBeforeVoiceAdmission() {
        for (var unsafe : List.of(
                "minecraft:tnt",
                "minecraft:infested_stone",
                "minecraft:chest",
                "minecraft:ice",
                "example:stone")) {
            assertThatThrownBy(() -> CraftAgentRuntime.validateStationaryBreakAllowedBlocks(
                    Set.of("minecraft:cobblestone", unsafe)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("closed safe allowlist");

            var request = startArguments("break_block", Map.of(
                    "target", targetMap(),
                    "expected_before", blockState(unsafe),
                    "expected_after", blockState("minecraft:air")), 0, 30, true);
            var mapped = CraftAgentRuntime.mapFailure(catchThrowable(() ->
                    CraftAgentRuntime.semanticActionArgument(
                            request, "minecraft:overworld")));
            assertThat(mapped.failure().code()).isEqualTo("invalid_argument");
            assertThat(mapped.failure().message()).contains("closed safe allowlist");
        }

        assertThatCode(() -> CraftAgentRuntime.validateStationaryBreakAllowedBlocks(
                Set.of("minecraft:cobblestone", "minecraft:stone")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsInteractBlockWithoutASameBlockPropertyTransitionDuringParsing() {
        var noPropertyTransition = startArguments("interact_block", Map.of(
                "target", targetMap(),
                "expected_before", blockState("minecraft:lever"),
                "expected_after", blockState("minecraft:lever")), 0, 30, false);
        var differentBlock = startArguments("interact_block", Map.of(
                "target", targetMap(),
                "expected_before", Map.of(
                        "block", "minecraft:lever",
                        "properties", Map.of("powered", "false")),
                "expected_after", Map.of(
                        "block", "minecraft:oak_trapdoor",
                        "properties", Map.of("open", "true"))), 0, 30, false);

        for (var invalid : List.of(noPropertyTransition, differentBlock)) {
            var mapped = CraftAgentRuntime.mapFailure(catchThrowable(() ->
                    CraftAgentRuntime.semanticActionArgument(invalid, "minecraft:overworld")));
            assertThat(mapped.failure().code()).isEqualTo("invalid_argument");
        }
    }

    @Test
    void canonicalSemanticIdentityIsOrderIndependentAndCoversBoundsAndProperties() {
        var firstProperties = new LinkedHashMap<String, String>();
        firstProperties.put("waterlogged", "false");
        firstProperties.put("facing", "north");
        var secondProperties = new LinkedHashMap<String, String>();
        secondProperties.put("facing", "north");
        secondProperties.put("waterlogged", "false");
        var target = new BlockTarget("minecraft:overworld", 1, 64, 2);
        var first = new PlaceBlockRequest(
                target,
                new BlockStateFingerprint("minecraft:air", Map.of()),
                "minecraft:oak_stairs",
                new BlockStateFingerprint("minecraft:oak_stairs", firstProperties),
                new ActionBounds(target.dimension(), target, target, 0, 30, false));
        var second = new PlaceBlockRequest(
                target,
                new BlockStateFingerprint("minecraft:air", Map.of()),
                "minecraft:oak_stairs",
                new BlockStateFingerprint("minecraft:oak_stairs", secondProperties),
                new ActionBounds(target.dimension(), target, target, 0, 30, false));
        var changedDuration = new PlaceBlockRequest(
                target,
                second.expectedBefore(),
                second.item(),
                second.expectedAfter(),
                new ActionBounds(target.dimension(), target, target, 0, 29, false));

        assertThat(CraftAgentRuntime.semanticActionIdentity(first))
                .matches("sha256:[0-9a-f]{64}")
                .isEqualTo(CraftAgentRuntime.semanticActionIdentity(second))
                .isNotEqualTo(CraftAgentRuntime.semanticActionIdentity(changedDuration))
                .isEqualTo(CraftAgentRuntime.semanticActionIdentity(
                        first, GoalContinuationSession.FINISH_GOAL))
                .isNotEqualTo(CraftAgentRuntime.semanticActionIdentity(
                        first, GoalContinuationSession.CONTINUE_GOAL));
    }

    @Test
    void defaultsCompletionToFinish() {
        var omitted = Map.<String, Object>of();
        var explicitContinue = Map.<String, Object>of(
                "completion_intent", GoalContinuationSession.CONTINUE_GOAL);

        assertThat(CraftAgentRuntime.completionIntentArgument(omitted))
                .isEqualTo(GoalContinuationSession.FINISH_GOAL);
        assertThat(CraftAgentRuntime.completionIntentArgument(explicitContinue))
                .isEqualTo(GoalContinuationSession.CONTINUE_GOAL);
        assertThatThrownBy(() -> CraftAgentRuntime.completionIntentArgument(
                Map.of("completion_intent", "continue_forever")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void safeStayRequiresAStableHealthyScreenFreeCheckpoint() {
        assertThat(CraftAgentRuntime.safeStayFailure(
                true, true, true, false, 20.0F, 0.0D,
                false, true, true, true))
                .isNull();
        assertThat(CraftAgentRuntime.safeStayFailure(
                true, true, true, false, 20.0F, 0.0D,
                false, true, true, false))
                .isEqualTo("safe_stay_visible_hostile");
        assertThat(CraftAgentRuntime.safeStayFailure(
                true, true, true, false, 5.0F, 0.0D,
                false, true, true, true))
                .isEqualTo("safe_stay_low_health");
    }

    @Test
    void strictlyParsesAllFourClosedApplyBlockPlanOperations() {
        var verify = CraftAgentRuntime.applyBlockPlanArgument(
                applyPlanArguments("verify_only", fullState("minecraft:air", Map.of()),
                        fullState("minecraft:air", Map.of()), null, false),
                "minecraft:overworld");
        var breakToAir = CraftAgentRuntime.applyBlockPlanArgument(
                applyPlanArguments("break_to_air", fullState("minecraft:stone", Map.of()),
                        fullState("minecraft:air", Map.of()), null, true),
                "minecraft:overworld");
        var place = CraftAgentRuntime.applyBlockPlanArgument(
                applyPlanArguments("place", fullState("minecraft:air", Map.of()),
                        fullState("minecraft:stone", Map.of()), "minecraft:stone", false),
                "minecraft:overworld");
        var replace = CraftAgentRuntime.applyBlockPlanArgument(
                applyPlanArguments("replace", fullState("minecraft:dirt", Map.of()),
                        fullState("minecraft:stone", Map.of()), "minecraft:stone", true),
                "minecraft:overworld");

        assertThat(List.of(verify, breakToAir, place, replace))
                .extracting(parsed -> parsed.request().steps().getFirst().operation())
                .containsExactly(
                        ApplyBlockPlanOperation.VERIFY_ONLY,
                        ApplyBlockPlanOperation.BREAK_TO_AIR,
                        ApplyBlockPlanOperation.PLACE,
                        ApplyBlockPlanOperation.REPLACE);
        assertThat(place.request().requiredResources()).containsEntry("minecraft:stone", 1);
        assertThat(place.resourceEstimate())
                .containsEntry("break_operations", 0)
                .containsEntry("place_operations", 1);
    }

    @Test
    void applyBlockPlanParserRequiresCompleteRuntimeBlockStatesBeforeAdmission() {
        var incompleteStairs = fullState(
                "minecraft:oak_stairs", Map.of("facing", "north"));
        var arguments = applyPlanArguments(
                "place",
                fullState("minecraft:air", Map.of()),
                incompleteStairs,
                "minecraft:oak_stairs",
                false);

        var mapped = CraftAgentRuntime.mapFailure(catchThrowable(() ->
                CraftAgentRuntime.applyBlockPlanArgument(arguments, "minecraft:overworld")));

        assertThat(mapped.failure().code()).isEqualTo("invalid_argument");
        assertThat(mapped.failure().message()).contains("complete runtime BlockState");
        assertThat(mapped.failure().details())
                .containsEntry("plan_validation_code", "incomplete_block_state")
                .containsEntry("path", "entries[0].expected_after.properties");
    }

    @Test
    void applyBlockPlanIdentityCoversRawTransformPhaseAndBounds() {
        var first = applyPlanArguments(
                "verify_only", fullState("minecraft:air", Map.of()),
                fullState("minecraft:air", Map.of()), null, false);
        var changedTransform = deepCopy(first);
        @SuppressWarnings("unchecked")
        var parameters = (Map<String, Object>) changedTransform.get("parameters");
        parameters.put("transform", Map.of("rotation", 90, "mirror", "none"));
        var changedDuration = deepCopy(first);
        @SuppressWarnings("unchecked")
        var bounds = (Map<String, Object>) changedDuration.get("bounds");
        bounds.put("max_duration_seconds", 29);

        var parsed = CraftAgentRuntime.applyBlockPlanArgument(first, "minecraft:overworld");
        var transformed = CraftAgentRuntime.applyBlockPlanArgument(
                changedTransform, "minecraft:overworld");
        var shorter = CraftAgentRuntime.applyBlockPlanArgument(
                changedDuration, "minecraft:overworld");

        assertThat(parsed.requestIdentity()).matches("sha256:[0-9a-f]{64}")
                .isNotEqualTo(transformed.requestIdentity())
                .isNotEqualTo(shorter.requestIdentity());
    }

    @Test
    void applyBlockPlanCanonicalizesFullPropertyOrderAndTransformsDirectionalState() {
        var firstProperties = new LinkedHashMap<String, String>();
        firstProperties.put("waterlogged", "false");
        firstProperties.put("shape", "straight");
        firstProperties.put("half", "bottom");
        firstProperties.put("facing", "north");
        var secondProperties = new LinkedHashMap<String, String>();
        secondProperties.put("facing", "north");
        secondProperties.put("half", "bottom");
        secondProperties.put("shape", "straight");
        secondProperties.put("waterlogged", "false");
        var first = applyPlanArguments(
                "verify_only",
                fullState("minecraft:oak_stairs", firstProperties),
                fullState("minecraft:oak_stairs", firstProperties),
                null,
                false);
        var second = applyPlanArguments(
                "verify_only",
                fullState("minecraft:oak_stairs", secondProperties),
                fullState("minecraft:oak_stairs", secondProperties),
                null,
                false);
        var rotated = deepCopy(first);
        @SuppressWarnings("unchecked")
        var rotatedParameters = (Map<String, Object>) rotated.get("parameters");
        rotatedParameters.put("transform", Map.of("rotation", 90, "mirror", "none"));

        var firstParsed = CraftAgentRuntime.applyBlockPlanArgument(
                first, "minecraft:overworld");
        var secondParsed = CraftAgentRuntime.applyBlockPlanArgument(
                second, "minecraft:overworld");
        var rotatedParsed = CraftAgentRuntime.applyBlockPlanArgument(
                rotated, "minecraft:overworld");

        assertThat(firstParsed.requestIdentity()).isEqualTo(secondParsed.requestIdentity());
        assertThat(rotatedParsed.request().steps().getFirst().expectedAfter().properties())
                .containsEntry("facing", "east")
                .containsEntry("half", "bottom")
                .containsEntry("shape", "straight")
                .containsEntry("waterlogged", "false");
    }

    @Test
    void rejectsDoorBedAndDoubleHeightItemsBeforePlanAdmission() {
        var target = new BlockTarget("minecraft:overworld", 1, 64, 2);
        var executionBounds = new ActionBounds(
                target.dimension(), target, target, 0, 30, false);

        for (var itemAndBlock : Map.of(
                "minecraft:oak_door", "minecraft:oak_door",
                "minecraft:red_bed", "minecraft:red_bed").entrySet()) {
            var request = new ApplyBlockPlanRequest(
                    "fixture", 1, 1,
                    List.of(new ApplyBlockPlanStep(
                            "cell-0",
                            ApplyBlockPlanOperation.PLACE,
                            target,
                            new BlockStateFingerprint("minecraft:air", Map.of()),
                            new BlockStateFingerprint(itemAndBlock.getValue(), Map.of()),
                            Optional.of(itemAndBlock.getKey()))),
                    executionBounds);

            assertThatThrownBy(() -> CraftAgentRuntime.validateApplyBlockPlanItems(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("multi-cell mutation");
        }

        var stone = new ApplyBlockPlanRequest(
                "fixture", 1, 1,
                List.of(new ApplyBlockPlanStep(
                        "cell-0",
                        ApplyBlockPlanOperation.PLACE,
                        target,
                        new BlockStateFingerprint("minecraft:air", Map.of()),
                        new BlockStateFingerprint("minecraft:stone", Map.of()),
                        Optional.of("minecraft:stone"))),
                executionBounds);
        assertThatCode(() -> CraftAgentRuntime.validateApplyBlockPlanItems(stone))
                .doesNotThrowAnyException();

        var breakDoor = new ApplyBlockPlanRequest(
                "fixture", 1, 1,
                List.of(new ApplyBlockPlanStep(
                        "door-break",
                        ApplyBlockPlanOperation.BREAK_TO_AIR,
                        target,
                        new BlockStateFingerprint("minecraft:oak_door", Map.of()),
                        new BlockStateFingerprint("minecraft:air", Map.of()),
                        Optional.empty())),
                new ActionBounds(target.dimension(), target, target, 0, 30, true));
        assertThatThrownBy(() -> CraftAgentRuntime.validateApplyBlockPlanItems(breakDoor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("multi-cell mutation");

    }

    @Test
    void rejectsSolidBucketPlacementBeforePlanAdmission() {
        var target = new BlockTarget("minecraft:overworld", 1, 64, 2);
        var request = new ApplyBlockPlanRequest(
                "fixture", 1, 1,
                List.of(new ApplyBlockPlanStep(
                        "powder-snow",
                        ApplyBlockPlanOperation.PLACE,
                        target,
                        new BlockStateFingerprint("minecraft:air", Map.of()),
                        new BlockStateFingerprint("minecraft:powder_snow", Map.of()),
                        Optional.of("minecraft:powder_snow_bucket"))),
                new ActionBounds(target.dimension(), target, target, 0, 30, false));

        assertThatThrownBy(() -> CraftAgentRuntime.validateApplyBlockPlanItems(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different container item");
    }

    @Test
    void rejectsUnsafeSourceMutationBeforePlanAdmission() {
        var target = new BlockTarget("minecraft:overworld", 1, 64, 2);
        for (var unsafe : List.of(
                "minecraft:tnt",
                "minecraft:infested_stone",
                "minecraft:hopper",
                "minecraft:ice",
                "example:stone")) {
            for (var operation : List.of(
                    ApplyBlockPlanOperation.BREAK_TO_AIR,
                    ApplyBlockPlanOperation.REPLACE)) {
                var request = new ApplyBlockPlanRequest(
                        "fixture", 1, 1,
                        List.of(new ApplyBlockPlanStep(
                                "unsafe-source",
                                operation,
                                target,
                                new BlockStateFingerprint(unsafe, Map.of()),
                                operation == ApplyBlockPlanOperation.BREAK_TO_AIR
                                        ? new BlockStateFingerprint("minecraft:air", Map.of())
                                        : new BlockStateFingerprint("minecraft:stone", Map.of()),
                                operation == ApplyBlockPlanOperation.REPLACE
                                        ? Optional.of("minecraft:stone")
                                        : Optional.empty())),
                        new ActionBounds(target.dimension(), target, target, 0, 30, true));

                assertThatThrownBy(() -> CraftAgentRuntime.validateApplyBlockPlanItems(request))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("closed safe allowlist");
            }
        }

        var placeEmptyHopper = new ApplyBlockPlanRequest(
                "fixture", 1, 1,
                List.of(new ApplyBlockPlanStep(
                        "new-hopper",
                        ApplyBlockPlanOperation.PLACE,
                        target,
                        new BlockStateFingerprint("minecraft:air", Map.of()),
                        new BlockStateFingerprint(
                                "minecraft:hopper",
                                Map.of("enabled", "true", "facing", "down")),
                        Optional.of("minecraft:hopper"))),
                new ActionBounds(target.dimension(), target, target, 0, 30, false));
        assertThatCode(() -> CraftAgentRuntime.validateApplyBlockPlanItems(placeEmptyHopper))
                .doesNotThrowAnyException();
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

    private static Map<String, Object> startArguments(
            String kind,
            Map<String, Object> parameters,
            int maxTravelBlocks,
            int maxDurationSeconds,
            boolean allowBreak) {
        return rawStartArguments(
                kind,
                parameters,
                boundsMap(maxTravelBlocks, maxDurationSeconds, allowBreak));
    }

    private static Map<String, Object> rawStartArguments(
            String kind,
            Map<String, Object> parameters,
            Map<String, Object> bounds) {
        return Map.of(
                "kind", kind,
                "parameters", parameters,
                "bounds", bounds,
                "completion_intent", "finish_goal",
                "idempotency_key", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    }

    private static LinkedHashMap<String, Object> boundsMap(
            int maxTravelBlocks,
            int maxDurationSeconds,
            boolean allowBreak) {
        var result = new LinkedHashMap<String, Object>();
        result.put("dimension", "minecraft:overworld");
        result.put("region", Map.of("min", positionMap(), "max", positionMap()));
        result.put("max_travel_blocks", maxTravelBlocks);
        result.put("max_duration_seconds", maxDurationSeconds);
        result.put("allow_break", allowBreak);
        return result;
    }

    private static Map<String, Object> targetMap() {
        return Map.of(
                "dimension", "minecraft:overworld",
                "x", 1,
                "y", 64,
                "z", 2);
    }

    private static Map<String, Object> positionMap() {
        return Map.of("x", 1, "y", 64, "z", 2);
    }

    private static Map<String, Object> blockState(String block) {
        return Map.of("block", block);
    }

    private static Map<String, Object> fullState(
            String block,
            Map<String, String> properties) {
        return Map.of("block", block, "properties", properties);
    }

    private static Map<String, Object> applyPlanArguments(
            String operation,
            Map<String, Object> expectedBefore,
            Map<String, Object> expectedAfter,
            String item,
            boolean allowBreak) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("id", "cell-0");
        entry.put("offset", Map.of("x", 0, "y", 0, "z", 0));
        entry.put("operation", operation);
        entry.put("expected_before", expectedBefore);
        entry.put("expected_after", expectedAfter);
        if (item != null) {
            entry.put("item", item);
        }
        return rawStartArguments(
                ApplyBlockPlanRequest.KIND,
                new LinkedHashMap<>(Map.of(
                        "anchor", targetMap(),
                        "transform", Map.of("rotation", 0, "mirror", "none"),
                        "phase", Map.of("id", "foundation", "index", 1, "total", 2),
                        "entries", List.of(entry))),
                boundsMap(0, 30, allowBreak));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> source) {
        var result = new LinkedHashMap<String, Object>();
        for (var entry : source.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> map) {
                value = deepCopy((Map<String, Object>) map);
            }
            else if (value instanceof List<?> list) {
                var copy = new ArrayList<>();
                for (var item : list) {
                    copy.add(item instanceof Map<?, ?> map
                            ? deepCopy((Map<String, Object>) map)
                            : item);
                }
                value = copy;
            }
            result.put(entry.getKey(), value);
        }
        return result;
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

    private static SemanticActionPort unusedSemanticActionPort() {
        return (SemanticActionPort) Proxy.newProxyInstance(
                SemanticActionPort.class.getClassLoader(),
                new Class<?>[]{SemanticActionPort.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("retire")
                            || method.getName().equals("release")) {
                        return null;
                    }
                    throw new AssertionError("Unexpected semantic port call: " + method.getName());
                });
    }
}
