package dev.aodaruma.craftagent.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CraftAgentToolRegistryTest {
    @Test
    void advertisesOnlyThePhaseGatedCatalogWithClosedSchemas() {
        CraftAgentToolRegistry registry = new CraftAgentToolRegistry(
                (command, context) -> CompletableFuture.completedFuture(
                        McpRuntimePort.RuntimeReply.success(McpTestFixtures.statusData())),
                Duration.ofSeconds(1));

        List<McpSchema.Tool> tools = registry.specifications().stream()
                .map(McpStatelessServerFeatures.SyncToolSpecification::tool)
                .toList();

        assertThat(tools).extracting(McpSchema.Tool::name)
                .containsExactly(
                        "get_status", "get_snapshot", "compare_block_plan", "list_routines", "get_routine",
                        "start_routine", "cancel_routine", "emergency_stop");
        for (McpSchema.Tool tool : tools) {
            assertThat(tool.inputSchema()).containsEntry("additionalProperties", false);
            assertThat(tool.outputSchema()).containsEntry("additionalProperties", false);
            assertEveryObjectSchemaControlsAdditionalProperties(tool.inputSchema());
            assertEveryObjectSchemaControlsAdditionalProperties(tool.outputSchema());
            assertThat(tool.annotations().idempotentHint()).isTrue();
            assertThat(tool.annotations().openWorldHint()).isTrue();
        }
        assertThat(tools.subList(0, 5)).allSatisfy(tool ->
                assertThat(tool.annotations().readOnlyHint()).isTrue());
        assertThat(tools.subList(5, 8)).allSatisfy(tool ->
                assertThat(tool.annotations().readOnlyHint()).isFalse());
        assertThat(tools.get(5).annotations().destructiveHint()).isTrue();
        assertThat(List.of(tools.get(0), tools.get(1), tools.get(2), tools.get(3), tools.get(4),
                tools.get(6), tools.get(7))).allSatisfy(tool ->
                assertThat(tool.annotations().destructiveHint()).isFalse());
    }

    @Test
    void emitsTheSameJsonAsTextAndStructuredContent() {
        AtomicReference<McpRuntimePort.RuntimeCommand> received = new AtomicReference<>();
        CraftAgentToolRegistry registry = new CraftAgentToolRegistry((command, context) -> {
            received.set(command);
            return CompletableFuture.completedFuture(
                    McpRuntimePort.RuntimeReply.success(McpTestFixtures.statusData()));
        }, Duration.ofSeconds(1));

        McpSchema.CallToolResult result = invoke(registry, "get_status", Map.of());

        assertThat(result.isError()).isFalse();
        assertThat(received.get()).isInstanceOf(McpRuntimePort.GetStatus.class);
        assertThat(result.structuredContent()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> envelope = (Map<String, Object>) result.structuredContent();
        assertThat(envelope)
                .containsEntry("ok", true)
                .containsEntry("tool", "get_status")
                .containsKey("request_id")
                .containsEntry("data", McpTestFixtures.statusData());
        assertThat(result.content()).singleElement().isInstanceOfSatisfying(
                McpSchema.TextContent.class,
                text -> assertThat(text.text())
                        .contains("\"ok\":true")
                        .contains("\"tool\":\"get_status\"")
                        .contains(envelope.get("request_id").toString()));
    }

    @Test
    void timeoutCancelsTheSharedContextSoLateRuntimeWorkCannotStart() {
        AtomicReference<RuntimeCallContext> receivedContext = new AtomicReference<>();
        CompletableFuture<McpRuntimePort.RuntimeReply> never = new CompletableFuture<>();
        CraftAgentToolRegistry registry = new CraftAgentToolRegistry((command, context) -> {
            receivedContext.set(context);
            return never;
        }, Duration.ofMillis(20));

        McpSchema.CallToolResult result = invoke(registry, "get_status", Map.of());

        assertThat(result.isError()).isTrue();
        assertThat(receivedContext.get()).isNotNull();
        assertThat(receivedContext.get().isCancelled()).isTrue();
        assertThat(receivedContext.get().canBeginWork()).isFalse();
        assertThat(result.structuredContent().toString()).contains("runtime_timeout");
    }

    @Test
    void emergencyReasonRejectsControlCharactersBeforeRuntimeDispatch() {
        AtomicInteger calls = new AtomicInteger();
        CraftAgentToolRegistry registry = new CraftAgentToolRegistry((command, context) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(McpRuntimePort.RuntimeReply.success(Map.of()));
        }, Duration.ofSeconds(1));

        McpSchema.CallToolResult result = invoke(
                registry, "emergency_stop", Map.of("reason", "forged\nlog"));

        assertThat(result.isError()).isTrue();
        assertThat(result.structuredContent().toString()).contains("invalid_argument");
        assertThat(calls).hasValue(0);
    }

    @Test
    void acceptsEveryClosedReleasedRoutineBranchAndKeepsTheToolCountFixed() {
        AtomicInteger calls = new AtomicInteger();
        CraftAgentToolRegistry registry = new CraftAgentToolRegistry((command, context) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(McpRuntimePort.RuntimeReply.success(Map.of()));
        }, Duration.ofSeconds(1));

        for (Map<String, Object> arguments : validRoutineArguments()) {
            assertThat(invoke(registry, "start_routine", arguments).isError())
                    .as("valid routine arguments %s", arguments.get("kind"))
                    .isFalse();
        }

        assertThat(calls).hasValue(7);
        assertThat(registry.specifications()).hasSize(8);
    }

    @Test
    void rejectsUnknownHybridAndMalformedRoutineArgumentsBeforeRuntimeDispatch() {
        AtomicInteger calls = new AtomicInteger();
        CraftAgentToolRegistry registry = new CraftAgentToolRegistry((command, context) -> {
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
            assertThat(result.structuredContent().toString()).contains("invalid_argument");
        }
        assertThat(calls).hasValue(0);
    }

    @Test
    void rejectsUnsupportedEntityInteractionsBeforeRuntimeDispatch() {
        AtomicInteger calls = new AtomicInteger();
        CraftAgentToolRegistry registry = new CraftAgentToolRegistry((command, context) -> {
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
            assertThat(result.structuredContent().toString()).contains("invalid_argument");
        }

        Map<String, Object> wrongGoal = new java.util.LinkedHashMap<>(interactEntityArguments());
        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = new java.util.LinkedHashMap<>(
                (Map<String, Object>) wrongGoal.get("parameters"));
        parameters.put("goal", Map.of("item", "minecraft:beef", "minimum_inventory_count", 1));
        wrongGoal.put("parameters", parameters);
        McpSchema.CallToolResult result = invoke(registry, "start_routine", wrongGoal);
        assertThat(result.isError()).isTrue();
        assertThat(result.structuredContent().toString()).contains("invalid_argument");
        assertThat(calls).hasValue(0);
    }

    @Test
    void rejectsMalformedPhaseFourPlansBeforeRuntimeDispatch() {
        AtomicInteger calls = new AtomicInteger();
        CraftAgentToolRegistry registry = new CraftAgentToolRegistry((command, context) -> {
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
            assertThat(result.structuredContent().toString()).contains("invalid_argument");
        }
        assertThat(calls).hasValue(0);
    }

    private static List<Map<String, Object>> validRoutineArguments() {
        return List.of(
                stationaryBreakArguments(), navigateArguments(), breakArguments(), placeArguments(),
                interactBlockArguments(), interactEntityArguments(), applyBlockPlanArguments());
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
            CraftAgentToolRegistry registry, String name, Map<String, Object> arguments) {
        McpStatelessServerFeatures.SyncToolSpecification specification = registry.specifications().stream()
                .filter(candidate -> candidate.tool().name().equals(name))
                .findFirst()
                .orElseThrow();
        return specification.callHandler().apply(
                McpTransportContext.EMPTY, new McpSchema.CallToolRequest(name, arguments));
    }

    @SuppressWarnings("unchecked")
    private static void assertEveryObjectSchemaControlsAdditionalProperties(Object value) {
        if (value instanceof Map<?, ?> map) {
            if ("object".equals(map.get("type"))) {
                assertThat(map.containsKey("additionalProperties"))
                        .as("object schema %s controls additional properties", map)
                        .isTrue();
            }
            map.values().forEach(CraftAgentToolRegistryTest::assertEveryObjectSchemaControlsAdditionalProperties);
        }
        else if (value instanceof Iterable<?> iterable) {
            iterable.forEach(CraftAgentToolRegistryTest::assertEveryObjectSchemaControlsAdditionalProperties);
        }
    }
}
