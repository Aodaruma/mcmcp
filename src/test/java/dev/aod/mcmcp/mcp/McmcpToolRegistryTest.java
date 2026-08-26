package dev.aod.mcmcp.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class McmcpToolRegistryTest {
    @Test
    void advertisesOnlyThePhaseGatedCatalogWithClosedSchemas() {
        McmcpToolRegistry registry = new McmcpToolRegistry(
                (command, context) -> CompletableFuture.completedFuture(
                        McpRuntimePort.RuntimeReply.success(McpTestFixtures.statusData())),
                Duration.ofSeconds(1));

        List<McpSchema.Tool> tools = registry.specifications().stream()
                .map(McpStatelessServerFeatures.SyncToolSpecification::tool)
                .toList();

        assertThat(tools).extracting(McpSchema.Tool::name)
                .containsExactly(
                        "get_status", "get_snapshot", "compare_block_plan", "list_routines", "get_routine",
                        "start_routine", "cancel_routine", "emergency_stop", "get_recipes",
                        "capture_creative_region", "edit_creative_world");
        for (McpSchema.Tool tool : tools) {
            assertThat(tool.inputSchema()).containsEntry("additionalProperties", false);
            assertEveryObjectSchemaControlsAdditionalProperties(tool.inputSchema());
            assertThat(tool.outputSchema()).isNull();
            assertThat(tool.annotations().idempotentHint()).isTrue();
            assertThat(tool.annotations().openWorldHint()).isTrue();
        }
        assertThat(List.of(tools.get(0), tools.get(1), tools.get(2), tools.get(3), tools.get(4),
                tools.get(8))).allSatisfy(tool ->
                assertThat(tool.annotations().readOnlyHint()).isTrue());
        assertThat(List.of(tools.get(5), tools.get(6), tools.get(7), tools.get(9), tools.get(10))).allSatisfy(tool ->
                assertThat(tool.annotations().readOnlyHint()).isFalse());
        assertThat(List.of(tools.get(5), tools.get(10))).allSatisfy(tool ->
                assertThat(tool.annotations().destructiveHint()).isTrue());
        assertThat(List.of(tools.get(0), tools.get(1), tools.get(2), tools.get(3), tools.get(4),
                tools.get(6), tools.get(7), tools.get(8), tools.get(9))).allSatisfy(tool ->
                assertThat(tool.annotations().destructiveHint()).isFalse());
    }

    @Test
    void dispatchesOnlyTypedCreativeWorldEdits() {
        AtomicReference<McpRuntimePort.RuntimeCommand> received = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        McmcpToolRegistry registry = new McmcpToolRegistry((command, context) -> {
            calls.incrementAndGet();
            received.set(command);
            return CompletableFuture.completedFuture(McpRuntimePort.RuntimeReply.success(Map.of()));
        }, Duration.ofSeconds(1));
        Map<String, Object> arguments = McpTestFixtures.fields(
                "operation", "set_block",
                "position", Map.of(
                        "dimension", "minecraft:overworld", "x", 1, "y", 64, "z", 2),
                "state", Map.of("block", "minecraft:stone", "properties", Map.of()),
                "idempotency_key", "3d7d9ed2-3bab-4a5a-a7aa-9b59b4f43243");

        McpSchema.CallToolResult result = invoke(registry, "edit_creative_world", arguments);

        assertThat(result.isError()).isFalse();
        assertThat(received.get()).isInstanceOfSatisfying(
                McpRuntimePort.EditCreativeWorld.class,
                command -> assertThat(command.arguments()).isEqualTo(arguments));

        McpSchema.CallToolResult rawCommand = invoke(registry, "edit_creative_world", Map.of(
                "operation", "command",
                "command", "ban @a"));
        assertThat(rawCommand.isError()).isTrue();
        assertThat(resultText(rawCommand)).contains("invalid_argument");
        assertThat(calls).hasValue(1);
    }

    @Test
    void dispatchesOnlyTheClosedCreativeRegionCapture() {
        AtomicReference<McpRuntimePort.RuntimeCommand> received = new AtomicReference<>();
        McmcpToolRegistry registry = new McmcpToolRegistry((command, context) -> {
            received.set(command);
            return CompletableFuture.completedFuture(McpRuntimePort.RuntimeReply.success(Map.of()));
        }, Duration.ofSeconds(1));
        Map<String, Object> arguments = McpTestFixtures.fields(
                "operation", "start",
                "region", McpTestFixtures.fields(
                        "dimension", "minecraft:overworld",
                        "min", Map.of("x", 0, "y", 64, "z", 0),
                        "max", Map.of("x", 7, "y", 71, "z", 7)),
                "include_entities", true,
                "idempotency_key", "3d7d9ed2-3bab-4a5a-a7aa-9b59b4f43243");

        McpSchema.CallToolResult result = invoke(registry, "capture_creative_region", arguments);

        assertThat(result.isError()).isFalse();
        assertThat(received.get()).isInstanceOfSatisfying(
                McpRuntimePort.CaptureCreativeRegion.class,
                command -> assertThat(command.arguments()).isEqualTo(arguments));

        Map<String, Object> statusArguments = Map.of(
                "operation", "status",
                "job_id", "123e4567-e89b-42d3-a456-426614174000");
        McpSchema.CallToolResult statusResult = invoke(
                registry, "capture_creative_region", statusArguments);

        assertThat(statusResult.isError()).isFalse();
        assertThat(received.get()).isInstanceOfSatisfying(
                McpRuntimePort.CaptureCreativeRegion.class,
                command -> assertThat(command.arguments()).isEqualTo(statusArguments));
    }

    @Test
    void emitsJsonOnceAsTextContent() {
        AtomicReference<McpRuntimePort.RuntimeCommand> received = new AtomicReference<>();
        McmcpToolRegistry registry = new McmcpToolRegistry((command, context) -> {
            received.set(command);
            return CompletableFuture.completedFuture(
                    McpRuntimePort.RuntimeReply.success(McpTestFixtures.statusData()));
        }, Duration.ofSeconds(1));

        McpSchema.CallToolResult result = invoke(registry, "get_status", Map.of());

        assertThat(result.isError()).isFalse();
        assertThat(received.get()).isInstanceOf(McpRuntimePort.GetStatus.class);
        assertThat(result.structuredContent()).isNull();
        assertThat(result.content()).singleElement().isInstanceOfSatisfying(
                McpSchema.TextContent.class,
                text -> assertThat(text.text())
                        .contains("\"ok\":true")
                        .contains("\"tool\":\"get_status\"")
                        .contains("\"request_id\"")
                        .contains("\"world_session_id\":\"session-test\""));
    }

    @Test
    void dispatchesOnlyTheClosedClientKnownRecipeQuery() {
        AtomicReference<McpRuntimePort.RuntimeCommand> received = new AtomicReference<>();
        McmcpToolRegistry registry = new McmcpToolRegistry((command, context) -> {
            received.set(command);
            return CompletableFuture.completedFuture(McpRuntimePort.RuntimeReply.success(Map.of()));
        }, Duration.ofSeconds(1));
        Map<String, Object> arguments = McpTestFixtures.fields(
                "query", Map.of("kind", "result_item", "item", "minecraft:hopper"),
                "max_results", 16);

        McpSchema.CallToolResult result = invoke(registry, "get_recipes", arguments);

        assertThat(result.isError()).isFalse();
        assertThat(received.get()).isInstanceOfSatisfying(
                McpRuntimePort.GetRecipes.class,
                command -> assertThat(command.arguments()).isEqualTo(arguments));
    }

    @Test
    void dispatchesAnOptionalRoutineCatalogKindFilter() {
        AtomicReference<McpRuntimePort.RuntimeCommand> received = new AtomicReference<>();
        McmcpToolRegistry registry = new McmcpToolRegistry((command, context) -> {
            received.set(command);
            return CompletableFuture.completedFuture(McpRuntimePort.RuntimeReply.success(Map.of()));
        }, Duration.ofSeconds(1));

        Map<String, Object> arguments = Map.of("kind", "tend_crop_area");
        assertThat(invoke(registry, "list_routines", arguments).isError()).isFalse();
        assertThat(received.get()).isInstanceOfSatisfying(
                McpRuntimePort.ListRoutines.class,
                command -> assertThat(command.arguments()).isEqualTo(arguments));
        assertThat(invoke(registry, "list_routines", Map.of("kind", "unknown")).isError()).isTrue();
    }

    @Test
    void timeoutCancelsTheSharedContextSoLateRuntimeWorkCannotStart() {
        AtomicReference<RuntimeCallContext> receivedContext = new AtomicReference<>();
        CompletableFuture<McpRuntimePort.RuntimeReply> never = new CompletableFuture<>();
        McmcpToolRegistry registry = new McmcpToolRegistry((command, context) -> {
            receivedContext.set(context);
            return never;
        }, Duration.ofMillis(20));

        McpSchema.CallToolResult result = invoke(registry, "get_status", Map.of());

        assertThat(result.isError()).isTrue();
        assertThat(receivedContext.get()).isNotNull();
        assertThat(receivedContext.get().isCancelled()).isTrue();
        assertThat(receivedContext.get().canBeginWork()).isFalse();
        assertThat(resultText(result)).contains("runtime_timeout");
    }

    @Test
    void emergencyReasonRejectsControlCharactersBeforeRuntimeDispatch() {
        AtomicInteger calls = new AtomicInteger();
        McmcpToolRegistry registry = new McmcpToolRegistry((command, context) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(McpRuntimePort.RuntimeReply.success(Map.of()));
        }, Duration.ofSeconds(1));

        McpSchema.CallToolResult result = invoke(
                registry, "emergency_stop", Map.of("reason", "forged\nlog"));

        assertThat(result.isError()).isTrue();
        assertThat(resultText(result)).contains("invalid_argument");
        assertThat(calls).hasValue(0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void acceptsEveryReleasedRoutineBranchWithEveryPhaseSixCompletionIntent() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        McmcpToolRegistry registry = new McmcpToolRegistry((command, context) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(McpRuntimePort.RuntimeReply.success(Map.of()));
        }, Duration.ofSeconds(1));

        McpSchema.Tool startTool = registry.specifications().stream()
                .map(McpStatelessServerFeatures.SyncToolSpecification::tool)
                .filter(tool -> tool.name().equals("start_routine"))
                .findFirst()
                .orElseThrow();
        assertThat(startTool.inputSchema()).doesNotContainKey("oneOf");
        assertThat(McpJsonDefaults.getMapper().writeValueAsString(startTool.inputSchema())
                .getBytes(StandardCharsets.UTF_8).length).isLessThan(1_200);
        Map<String, Object> properties = (Map<String, Object>) startTool.inputSchema().get("properties");
        Map<String, Object> completionIntent = (Map<String, Object>) properties.get("completion_intent");
        assertThat(completionIntent)
                .containsEntry("enum", List.of("finish_goal", "continue_goal"))
                .containsEntry("default", "finish_goal");

        for (Map<String, Object> explicitFinish : validRoutineArguments()) {
            Map<String, Object> explicitContinue = new java.util.LinkedHashMap<>(explicitFinish);
            explicitContinue.put("completion_intent", "continue_goal");
            Map<String, Object> omitted = new java.util.LinkedHashMap<>(explicitFinish);
            omitted.remove("completion_intent");

            for (Map<String, Object> arguments : List.of(explicitFinish, explicitContinue, omitted)) {
                assertThat(invoke(registry, "start_routine", arguments).isError())
                        .as("valid routine arguments %s with intent %s",
                                arguments.get("kind"), arguments.get("completion_intent"))
                        .isFalse();
            }
        }

        assertThat(calls).hasValue(39);
    }

    @Test
    void rejectsUnknownHybridAndMalformedRoutineArgumentsBeforeRuntimeDispatch() {
        AtomicInteger calls = new AtomicInteger();
        McmcpToolRegistry registry = new McmcpToolRegistry((command, context) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(McpRuntimePort.RuntimeReply.success(Map.of()));
        }, Duration.ofSeconds(1));

        Map<String, Object> unknown = new java.util.LinkedHashMap<>(navigateArguments());
        unknown.put("kind", "fly_to");

        Map<String, Object> hybrid = new java.util.LinkedHashMap<>(breakArguments());
        @SuppressWarnings("unchecked")
        Map<String, Object> hybridParameters = new java.util.LinkedHashMap<>(
                (Map<String, Object>) hybrid.get("parameters"));
        hybridParameters.put("item", "minecraft:cobblestone");
        hybrid.put("parameters", hybridParameters);

        Map<String, Object> malformedRef = new java.util.LinkedHashMap<>(interactEntityArguments());
        @SuppressWarnings("unchecked")
        Map<String, Object> malformedParameters = new java.util.LinkedHashMap<>(
                (Map<String, Object>) malformedRef.get("parameters"));
        malformedParameters.put("entity_ref", "too-short");
        malformedRef.put("parameters", malformedParameters);

        Map<String, Object> travellingAction = new java.util.LinkedHashMap<>(placeArguments());
        @SuppressWarnings("unchecked")
        Map<String, Object> travellingBounds = new java.util.LinkedHashMap<>(
                (Map<String, Object>) travellingAction.get("bounds"));
        travellingBounds.put("max_travel_blocks", 1);
        travellingAction.put("bounds", travellingBounds);

        Map<String, Object> nonAirBreak = new java.util.LinkedHashMap<>(breakArguments());
        @SuppressWarnings("unchecked")
        Map<String, Object> nonAirBreakParameters = new java.util.LinkedHashMap<>(
                (Map<String, Object>) nonAirBreak.get("parameters"));
        nonAirBreakParameters.put("expected_after", Map.of("block", "minecraft:stone"));
        nonAirBreak.put("parameters", nonAirBreakParameters);

        for (Map<String, Object> arguments : List.of(
                unknown, hybrid, malformedRef, travellingAction, nonAirBreak)) {
            McpSchema.CallToolResult result = invoke(registry, "start_routine", arguments);
            assertThat(result.isError()).as("invalid arguments %s", arguments).isTrue();
            assertThat(resultText(result)).contains("invalid_argument");
        }
        assertThat(calls).hasValue(0);
    }

    @Test
    void rejectsOpenIncompleteAndUnknownIntentVariantsOfEveryReleasedBranch() {
        AtomicInteger calls = new AtomicInteger();
        McmcpToolRegistry registry = new McmcpToolRegistry((command, context) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(McpRuntimePort.RuntimeReply.success(Map.of()));
        }, Duration.ofSeconds(1));

        for (Map<String, Object> valid : validRoutineArguments()) {
            Map<String, Object> open = new java.util.LinkedHashMap<>(valid);
            Map<String, Object> openParameters = new java.util.LinkedHashMap<>(parameters(open));
            openParameters.put("workflow", "free_form");
            open.put("parameters", openParameters);

            Map<String, Object> incomplete = new java.util.LinkedHashMap<>(valid);
            Map<String, Object> incompleteParameters = new java.util.LinkedHashMap<>(parameters(incomplete));
            incompleteParameters.remove(incompleteParameters.keySet().iterator().next());
            incomplete.put("parameters", incompleteParameters);

            Map<String, Object> unknownIntent = new java.util.LinkedHashMap<>(valid);
            unknownIntent.put("completion_intent", "continue_forever");

            for (Map<String, Object> invalid : List.of(open, incomplete, unknownIntent)) {
                McpSchema.CallToolResult result = invoke(registry, "start_routine", invalid);
                assertThat(result.isError()).as("invalid routine arguments %s", valid.get("kind")).isTrue();
                assertThat(resultText(result)).contains("invalid_argument");
            }
        }
        assertThat(calls).hasValue(0);
    }

    @Test
    void rejectsUnsupportedEntityInteractionsBeforeRuntimeDispatch() {
        AtomicInteger calls = new AtomicInteger();
        McmcpToolRegistry registry = new McmcpToolRegistry((command, context) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(McpRuntimePort.RuntimeReply.success(Map.of()));
        }, Duration.ofSeconds(1));

        for (Map.Entry<String, String> unsupported : Map.of(
                "expected_type", "minecraft:pig",
                "held_item", "minecraft:shears").entrySet()) {
            Map<String, Object> arguments = new java.util.LinkedHashMap<>(interactEntityArguments());
            @SuppressWarnings("unchecked")
            Map<String, Object> parameters = new java.util.LinkedHashMap<>(
                    (Map<String, Object>) arguments.get("parameters"));
            parameters.put(unsupported.getKey(), unsupported.getValue());
            arguments.put("parameters", parameters);

            McpSchema.CallToolResult result = invoke(registry, "start_routine", arguments);
            assertThat(result.isError()).as("unsupported %s", unsupported.getKey()).isTrue();
            assertThat(resultText(result)).contains("invalid_argument");
        }

        Map<String, Object> wrongGoal = new java.util.LinkedHashMap<>(interactEntityArguments());
        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = new java.util.LinkedHashMap<>(
                (Map<String, Object>) wrongGoal.get("parameters"));
        parameters.put("goal", Map.of("item", "minecraft:beef", "minimum_inventory_count", 1));
        wrongGoal.put("parameters", parameters);
        McpSchema.CallToolResult result = invoke(registry, "start_routine", wrongGoal);
        assertThat(result.isError()).isTrue();
        assertThat(resultText(result)).contains("invalid_argument");
        assertThat(calls).hasValue(0);
    }

    @Test
    void rejectsMalformedPhaseFourPlansBeforeRuntimeDispatch() {
        AtomicInteger calls = new AtomicInteger();
        McmcpToolRegistry registry = new McmcpToolRegistry((command, context) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(McpRuntimePort.RuntimeReply.success(Map.of()));
        }, Duration.ofSeconds(1));

        Map<String, Object> nonAirBreak = mutableApplyArguments();
        Map<String, Object> breakParameters = parameters(nonAirBreak);
        Map<String, Object> breakEntry = firstEntry(breakParameters);
        breakEntry.put("operation", "break_to_air");
        breakEntry.put("expected_before", Map.of(
                "block", "minecraft:stone", "properties", Map.of()));
        breakEntry.put("expected_after", Map.of(
                "block", "minecraft:stone", "properties", Map.of()));
        bounds(nonAirBreak).put("allow_break", true);

        Map<String, Object> missingProperties = mutableApplyArguments();
        firstEntry(parameters(missingProperties)).put(
                "expected_after", Map.of("block", "minecraft:air"));

        Map<String, Object> tooMany = mutableApplyArguments();
        var entries = new java.util.ArrayList<Map<String, Object>>();
        for (int index = 0; index < 65; index++) {
            entries.add(McpTestFixtures.fields(
                    "id", "cell-" + index,
                    "offset", Map.of("x", index, "y", 0, "z", 0),
                    "operation", "verify_only",
                    "expected_before", Map.of(
                            "block", "minecraft:air", "properties", Map.of()),
                    "expected_after", Map.of(
                            "block", "minecraft:air", "properties", Map.of())));
        }
        parameters(tooMany).put("entries", entries);

        for (var invalid : List.of(nonAirBreak, missingProperties, tooMany)) {
            McpSchema.CallToolResult result = invoke(registry, "start_routine", invalid);
            assertThat(result.isError()).isTrue();
            assertThat(resultText(result)).contains("invalid_argument");
        }
        assertThat(calls).hasValue(0);
    }

    private static List<Map<String, Object>> validRoutineArguments() {
        return List.of(
                stationaryBreakArguments(), navigateArguments(), breakArguments(), placeArguments(),
                interactBlockArguments(), interactEntityArguments(), applyBlockPlanArguments(),
                craftItemsArguments(), transferItemsArguments(), tendCropAreaArguments(),
                harvestTreeAreaArguments(), sleepAtBedArguments(), surveyAreaArguments());
    }

    private static Map<String, Object> stationaryBreakArguments() {
        return McpTestFixtures.fields(
                "kind", "stationary_break",
                "parameters", McpTestFixtures.fields(
                        "target", dimensionPosition(10, 64, -3),
                        "allowed_blocks", List.of("minecraft:cobblestone"),
                        "goal", Map.of("item", "minecraft:cobblestone", "minimum_inventory_count", 4),
                        "regeneration_timeout_seconds", 3),
                "bounds", bounds(0, 30, true),
                "completion_intent", "finish_goal",
                "idempotency_key", "f47ac10b-58cc-4372-a567-0e02b2c3d479");
    }

    private static Map<String, Object> navigateArguments() {
        return routineArguments(
                "navigate_to",
                McpTestFixtures.fields(
                        "target", dimensionPosition(12, 64, -3),
                        "horizontal_tolerance_blocks", 0.5),
                bounds(16, 60, false));
    }

    private static Map<String, Object> breakArguments() {
        return routineArguments(
                "break_block",
                transitionParameters("minecraft:stone", "minecraft:air"),
                bounds(0, 15, true));
    }

    private static Map<String, Object> placeArguments() {
        Map<String, Object> parameters = new java.util.LinkedHashMap<>(
                transitionParameters("minecraft:air", "minecraft:cobblestone"));
        parameters.put("item", "minecraft:cobblestone");
        return routineArguments("place_block", parameters, bounds(0, 15, false));
    }

    private static Map<String, Object> interactBlockArguments() {
        return routineArguments(
                "interact_block",
                McpTestFixtures.fields(
                        "target", dimensionPosition(10, 64, -3),
                        "expected_before", McpTestFixtures.fields(
                                "block", "minecraft:lever", "properties", Map.of("powered", "false")),
                        "expected_after", McpTestFixtures.fields(
                                "block", "minecraft:lever", "properties", Map.of("powered", "true"))),
                bounds(0, 10, false));
    }

    private static Map<String, Object> interactEntityArguments() {
        return routineArguments(
                "interact_entity",
                McpTestFixtures.fields(
                        "entity_ref", "AbCdEfGhIjKlMnOpQrStUvWx",
                        "expected_type", "minecraft:cow",
                        "hand", "main_hand",
                        "held_item", "minecraft:bucket",
                        "goal", Map.of("item", "minecraft:milk_bucket", "minimum_inventory_count", 1)),
                bounds(0, 10, false));
    }

    private static Map<String, Object> applyBlockPlanArguments() {
        return routineArguments(
                "apply_block_plan",
                McpTestFixtures.fields(
                        "anchor", dimensionPosition(10, 64, -3),
                        "transform", Map.of("rotation", 0, "mirror", "none"),
                        "phase", Map.of("id", "foundation", "index", 1, "total", 2),
                        "entries", List.of(McpTestFixtures.fields(
                                "id", "clearance-0",
                                "offset", Map.of("x", 0, "y", 0, "z", 0),
                                "operation", "verify_only",
                                "expected_before", Map.of(
                                        "block", "minecraft:air", "properties", Map.of()),
                                "expected_after", Map.of(
                                        "block", "minecraft:air", "properties", Map.of())))),
                bounds(0, 30, false));
    }

    private static Map<String, Object> craftItemsArguments() {
        return routineArguments(
                "craft_items",
                McpTestFixtures.fields(
                        "recipe_ref", "AbCdEfGhIjKlMnOpQrStUvWx",
                        "recipe_fingerprint", sha256('a'),
                        "goal", McpTestFixtures.fields(
                                "item", "minecraft:crafting_table",
                                "stack_policy", "default_components_only",
                                "minimum_inventory_count", 1),
                        "station", McpTestFixtures.fields(
                                "kind", "crafting_table",
                                "target", dimensionPosition(11, 64, -3),
                                "expected_state", fullState("minecraft:crafting_table")),
                        "max_crafts", 1),
                bounds(4, 60, false));
    }

    private static Map<String, Object> transferItemsArguments() {
        return routineArguments(
                "transfer_items",
                McpTestFixtures.fields(
                        "container", McpTestFixtures.fields(
                                "target", dimensionPosition(11, 64, -3),
                                "expected_state", fullState("minecraft:chest")),
                        "direction", "player_to_container",
                        "stack", McpTestFixtures.fields(
                                "item", "minecraft:cobblestone",
                                "stack_policy", "default_components_only"),
                        "goal", Map.of("minimum_destination_count", 16),
                        "max_transfer_count", 16),
                bounds(8, 90, false));
    }

    private static Map<String, Object> tendCropAreaArguments() {
        return routineArguments(
                "tend_crop_area",
                McpTestFixtures.fields(
                        "crop_adapter", "wheat",
                        "plots", List.of(McpTestFixtures.fields(
                                "id", "plot-1",
                                "crop_position", dimensionPosition(10, 64, -3),
                                "support_position", dimensionPosition(10, 63, -3),
                                "expected_support_state", fullState("minecraft:farmland"))),
                        "goal", McpTestFixtures.fields(
                                "minimum_harvested_plots", 1,
                                "replant", true,
                                "collect_drops", true),
                        "wait_policy", "no_wait"),
                bounds(16, 180, true));
    }

    private static Map<String, Object> harvestTreeAreaArguments() {
        return routineArguments(
                "harvest_tree_area",
                McpTestFixtures.fields(
                        "trees", List.of(McpTestFixtures.fields(
                                "id", "oak-1",
                                "logs", List.of(McpTestFixtures.fields(
                                        "position", dimensionPosition(10, 64, -3),
                                        "expected_state", fullState("minecraft:oak_log"))),
                                "support", McpTestFixtures.fields(
                                        "position", dimensionPosition(10, 63, -3),
                                        "expected_state", fullState("minecraft:dirt")),
                                "sapling", McpTestFixtures.fields(
                                        "item", "minecraft:oak_sapling",
                                        "expected_after_state", fullState("minecraft:oak_sapling")),
                                "growth_clearance", List.of(McpTestFixtures.fields(
                                        "position", dimensionPosition(10, 65, -3),
                                        "expected_state", fullState("minecraft:air"))))),
                        "collect_drops", true),
                bounds(16, 240, true));
    }

    private static Map<String, Object> sleepAtBedArguments() {
        return routineArguments(
                "sleep_at_bed",
                McpTestFixtures.fields(
                        "bed", McpTestFixtures.fields(
                                "foot_position", dimensionPosition(10, 64, -3),
                                "expected_foot_state", fullState("minecraft:red_bed"),
                                "head_position", dimensionPosition(10, 64, -2),
                                "expected_head_state", fullState("minecraft:red_bed")),
                        "return_policy", "start_checkpoint"),
                bounds(32, 300, false));
    }

    private static Map<String, Object> surveyAreaArguments() {
        return routineArguments(
                "survey_area",
                McpTestFixtures.fields(
                        "waypoints", List.of(McpTestFixtures.fields(
                                "id", "waypoint-1",
                                "target", dimensionPosition(10, 64, -3),
                                "look_at", dimensionPosition(12, 64, -3))),
                        "samples", List.of(McpTestFixtures.fields(
                                "id", "sample-1",
                                "position", dimensionPosition(12, 63, -3))),
                        "goal", Map.of("minimum_observed_samples", 1),
                        "assessment", "spawn_surface_prediction"),
                bounds(32, 300, false));
    }

    private static Map<String, Object> transitionParameters(String before, String after) {
        return McpTestFixtures.fields(
                "target", dimensionPosition(10, 64, -3),
                "expected_before", Map.of("block", before),
                "expected_after", Map.of("block", after));
    }

    private static Map<String, Object> routineArguments(
            String kind, Map<String, Object> parameters, Map<String, Object> bounds) {
        return McpTestFixtures.fields(
                "kind", kind,
                "parameters", parameters,
                "bounds", bounds,
                "completion_intent", "finish_goal",
                "idempotency_key", "f47ac10b-58cc-4372-a567-0e02b2c3d479");
    }

    private static Map<String, Object> bounds(int maxTravel, int maxDuration, boolean allowBreak) {
        return McpTestFixtures.fields(
                "dimension", "minecraft:overworld",
                "region", Map.of(
                        "min", Map.of("x", 0, "y", 60, "z", -10),
                        "max", Map.of("x", 20, "y", 70, "z", 10)),
                "max_travel_blocks", maxTravel,
                "max_duration_seconds", maxDuration,
                "allow_break", allowBreak);
    }

    private static Map<String, Object> dimensionPosition(int x, int y, int z) {
        return Map.of("dimension", "minecraft:overworld", "x", x, "y", y, "z", z);
    }

    private static Map<String, Object> fullState(String block) {
        return Map.of("block", block, "properties", Map.of());
    }

    private static String sha256(char digit) {
        return "sha256:" + String.valueOf(digit).repeat(64);
    }

    private static Map<String, Object> mutableApplyArguments() {
        var result = new java.util.LinkedHashMap<String, Object>(applyBlockPlanArguments());
        @SuppressWarnings("unchecked")
        var originalParameters = (Map<String, Object>) result.get("parameters");
        var copiedParameters = new java.util.LinkedHashMap<String, Object>(originalParameters);
        @SuppressWarnings("unchecked")
        var originalEntries = (List<Map<String, Object>>) originalParameters.get("entries");
        copiedParameters.put("entries", new java.util.ArrayList<>(List.of(
                new java.util.LinkedHashMap<>(originalEntries.getFirst()))));
        result.put("parameters", copiedParameters);
        @SuppressWarnings("unchecked")
        var originalBounds = (Map<String, Object>) result.get("bounds");
        result.put("bounds", new java.util.LinkedHashMap<>(originalBounds));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parameters(Map<String, Object> arguments) {
        return (Map<String, Object>) arguments.get("parameters");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstEntry(Map<String, Object> parameters) {
        return ((List<Map<String, Object>>) parameters.get("entries")).getFirst();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bounds(Map<String, Object> arguments) {
        return (Map<String, Object>) arguments.get("bounds");
    }

    private static McpSchema.CallToolResult invoke(
            McmcpToolRegistry registry, String name, Map<String, Object> arguments) {
        McpStatelessServerFeatures.SyncToolSpecification specification = registry.specifications().stream()
                .filter(candidate -> candidate.tool().name().equals(name))
                .findFirst()
                .orElseThrow();
        return specification.callHandler().apply(
                McpTransportContext.EMPTY, new McpSchema.CallToolRequest(name, arguments));
    }

    private static String resultText(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().getFirst()).text();
    }

    @SuppressWarnings("unchecked")
    private static void assertEveryObjectSchemaControlsAdditionalProperties(Object value) {
        if (value instanceof Map<?, ?> map) {
            if ("object".equals(map.get("type"))) {
                assertThat(map.containsKey("additionalProperties"))
                        .as("object schema %s controls additional properties", map)
                        .isTrue();
            }
            map.values().forEach(McmcpToolRegistryTest::assertEveryObjectSchemaControlsAdditionalProperties);
        }
        else if (value instanceof Iterable<?> iterable) {
            iterable.forEach(McmcpToolRegistryTest::assertEveryObjectSchemaControlsAdditionalProperties);
        }
    }
}
