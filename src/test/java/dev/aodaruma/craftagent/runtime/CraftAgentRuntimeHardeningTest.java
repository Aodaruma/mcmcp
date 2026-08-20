package dev.aodaruma.craftagent.runtime;

import dev.aodaruma.craftagent.routine.ActionBounds;
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
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

class CraftAgentRuntimeHardeningTest {
    @Test
    void localPriorityStopRequestsBeforeItDrainsSynchronously() {
        var calls = new ArrayList<String>();

        CraftAgentRuntime.runPriorityStop(
                () -> calls.add("request"),
                () -> calls.add("drain"));

        assertThat(calls).containsExactly("request", "drain");
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
    void wallClockArmingExpiryDuringAPauseOrLowTpsGapStopsBeforeTheNextRoutineTick() {
        var arming = new LocalArmingState();
        var sessionId = UUID.randomUUID();
        long armedAtNanos = 1_000L;
        var duration = Duration.ofSeconds(10);
        arming.arm(sessionId, Set.of("navigate_to"), duration, armedAtNanos);
        var expired = arming.snapshot(
                sessionId,
                Math.addExact(armedAtNanos, duration.toNanos()));
        var calls = new ArrayList<String>();

        var mayTick = CraftAgentRuntime.enforceActiveRoutineArming(
                expired,
                () -> calls.add("request:" + CraftAgentRuntime.activeArmingStopReason(expired)),
                () -> calls.add("drain"));
        if (mayTick) {
            calls.add("routine_tick");
        }

        assertThat(mayTick).isFalse();
        assertThat(expired.lastLockReason()).isEqualTo("expired");
        assertThat(calls).containsExactly("request:local_arming_expired", "drain");
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
    void advertisesExactlyTheSixReleasedRoutineKindsWithKindSpecificSchemas() {
        var catalog = CraftAgentRuntime.routineCatalog();

        assertThat(catalog).containsEntry("catalog_version", "phase-3");
        @SuppressWarnings("unchecked")
        var entries = (List<Map<String, Object>>) catalog.get("routines");
        assertThat(entries).hasSize(6);
        assertThat(entries).extracting(entry -> entry.get("kind"))
                .containsExactly(
                        "stationary_break",
                        "navigate_to",
                        "break_block",
                        "place_block",
                        "interact_block",
                        "interact_entity");
        assertThat(entries).allSatisfy(entry -> {
            assertThat(entry.get("input_schema")).isInstanceOf(Map.class);
            assertThat((List<?>) entry.get("postconditions")).isNotEmpty();
        });
        assertThat(entries.getFirst().get("input_schema"))
                .isNotSameAs(entries.get(1).get("input_schema"));
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
                .isNotEqualTo(CraftAgentRuntime.semanticActionIdentity(changedDuration));
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
