package dev.aod.mcmcp.mcp;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import dev.aod.mcmcp.agent.action.AgentActionStore;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolCatalogTest {
    @Test
    void startReceiptIsConfirmedOnlyAfterDeliveryOrAbandonedOnWriteFailure() throws Exception {
        var commands = new ArrayList<McpRuntimePort.RuntimeCommand>();
        var registry = new McmcpToolRegistry((command, context) -> {
            commands.add(command);
            return CompletableFuture.completedFuture(McpRuntimePort.RuntimeReply.success(
                    toolResult(command)));
        }, Duration.ofSeconds(1));
        var start = new McpToolCatalog().inputSchema("agent_start_action")
                .getAsJsonArray("examples").get(0).getAsJsonObject();

        var delivered = registry.prepareCall("agent_start_action", start);
        assertThat(commands).singleElement().isInstanceOf(McpRuntimePort.StartAction.class);
        registry.confirmDelivery(delivered);
        assertThat(commands.getLast()).isInstanceOf(McpRuntimePort.ConfirmActionDelivery.class);

        commands.clear();
        var lost = registry.prepareCall("agent_start_action", start);
        registry.abandonDelivery(lost);
        assertThat(commands).extracting(Object::getClass).containsExactly(
                McpRuntimePort.StartAction.class,
                McpRuntimePort.AbandonActionDelivery.class);
    }

    @Test
    void shippedCatalogIsTheNormativeFileAndHasTheFixedFiveTools() throws Exception {
        var file = JsonParser.parseReader(Files.newBufferedReader(
                Path.of(System.getProperty("mcmcp.projectDir"), "docs", "MCMCP_MCP_Tool_Catalog.json"),
                StandardCharsets.UTF_8));
        try (var stream = getClass().getResourceAsStream(McpToolCatalog.RESOURCE);
             var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            assertThat(JsonParser.parseReader(reader)).isEqualTo(file);
        }

        var catalog = new McpToolCatalog();
        List<String> names = catalog.listResult().getAsJsonArray("tools").asList().stream()
                .map(tool -> tool.getAsJsonObject().get("name").getAsString())
                .toList();
        assertThat(names).containsExactlyElementsOf(McpToolCatalog.REQUIRED_NAMES);
    }

    @Test
    void catalogSchemasDriveInputAndOutputValidation() {
        var catalog = new McpToolCatalog();
        var actionSchema = catalog.inputSchema("agent_start_action");
        assertThat(CatalogSchemaValidator.matches(
                actionSchema, actionSchema.getAsJsonArray("examples").get(0))).isTrue();

        var invalid = actionSchema.getAsJsonArray("examples").get(0).getAsJsonObject().deepCopy();
        invalid.addProperty("raw_key", "attack");
        assertThat(CatalogSchemaValidator.matches(actionSchema, invalid)).isFalse();

        var stateSchema = catalog.listResult().getAsJsonArray("tools").get(0)
                .getAsJsonObject().getAsJsonObject("outputSchema");
        var state = new GsonBuilder().serializeNulls().create().toJsonTree(McpTestFixtures.state());
        assertThat(CatalogSchemaValidator.matches(stateSchema, state)).as(state.toString()).isTrue();
    }

    @Test
    void catalogClosesAndBoundsCropMaturityWaits() {
        var schema = new McpToolCatalog().inputSchema("agent_start_action");
        var request = schema.getAsJsonArray("examples").get(0).getAsJsonObject().deepCopy();
        var wait = JsonParser.parseString("""
                {"id":"await_mature","op":"wait_until",
                 "condition":{"type":"crop_mature","target":{
                   "dimension":"minecraft:overworld","x":10,"y":65,"z":10}},
                 "max_ticks":12000}
                """).getAsJsonObject();
        request.getAsJsonObject("program").add("body", new com.google.gson.JsonArray());
        request.getAsJsonObject("program").getAsJsonArray("body").add(wait);
        request.getAsJsonObject("budget").addProperty("max_duration_ms", 600_000);
        request.getAsJsonObject("budget").addProperty("max_ticks", 12_000);
        assertThat(CatalogSchemaValidator.matches(schema, request)).isTrue();

        var unsupported = request.deepCopy();
        unsupported.getAsJsonObject("program").getAsJsonArray("body").get(0)
                .getAsJsonObject().getAsJsonObject("condition")
                .addProperty("type", "expression");
        assertThat(CatalogSchemaValidator.matches(schema, unsupported)).isFalse();

        var unbounded = request.deepCopy();
        unbounded.getAsJsonObject("program").getAsJsonArray("body").get(0)
                .getAsJsonObject().addProperty("max_ticks", 12_001);
        assertThat(CatalogSchemaValidator.matches(schema, unbounded)).isFalse();
    }

    @Test
    void catalogAdmitsOnlyTheClosedFenceGateOpenNodeShape() {
        var schema = new McpToolCatalog().inputSchema("agent_start_action");
        var request = schema.getAsJsonArray("examples").get(0).getAsJsonObject().deepCopy();
        var program = request.getAsJsonObject("program");
        program.add("capabilities", JsonParser.parseString(
                "[\"camera\",\"block_interact\"]"));
        program.add("body", JsonParser.parseString("""
                [{"id":"open_gate","op":"open_known_fence_gate","target":{
                  "dimension":"minecraft:overworld","x":-11,"y":56,"z":-15}}]
                """));
        var budget = request.getAsJsonObject("budget");
        budget.addProperty("max_distance_blocks", 0);
        budget.addProperty("max_camera_degrees", 360);
        budget.addProperty("max_interactions", 1);
        assertThat(CatalogSchemaValidator.matches(schema, request)).isTrue();

        var widened = request.deepCopy();
        widened.getAsJsonObject("program").getAsJsonArray("body").get(0)
                .getAsJsonObject().addProperty("expected_block", "minecraft:oak_fence_gate");
        assertThat(CatalogSchemaValidator.matches(schema, widened)).isFalse();
    }

    @Test
    void actionProgressSchemaMatchesTheRuntimeRecordingLimits() {
        var output = new McpToolCatalog().outputSchema("agent_get_action");
        var progress = output.getAsJsonObject("properties")
                .getAsJsonObject("progress")
                .getAsJsonObject("properties");

        assertThat(progress.getAsJsonObject("ticks").get("maximum").getAsInt())
                .isEqualTo(AgentActionStore.MAX_RECORDED_TICKS);
        assertThat(progress.getAsJsonObject("interactions").get("maximum").getAsInt())
                .isEqualTo(AgentActionStore.MAX_RECORDED_INTERACTIONS);
        assertThat(progress.getAsJsonObject("blocks_broken").get("maximum").getAsInt())
                .isEqualTo(AgentActionStore.MAX_RECORDED_BLOCKS_BROKEN);
        assertThat(progress.getAsJsonObject("blocks_placed").get("maximum").getAsInt())
                .isEqualTo(AgentActionStore.MAX_RECORDED_BLOCKS_PLACED);
    }

    @Test
    void registryDispatchesExactlyTheFixedFiveToolsAndValidatesTheirOutputs() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var registry = new McmcpToolRegistry((command, context) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(McpRuntimePort.RuntimeReply.success(
                    toolResult(command)));
        }, Duration.ofSeconds(1));

        var state = registry.call("agent_get_state", new com.google.gson.JsonObject());
        assertThat(state.get("isError").getAsBoolean()).isFalse();
        assertThat(state.has("structuredContent")).isTrue();
        assertThat(state.getAsJsonArray("content")).hasSize(1);

        var malformed = new com.google.gson.JsonObject();
        malformed.addProperty("raw_mouse", true);
        var rejected = registry.call("agent_get_state", malformed);
        assertThat(rejected.get("isError").getAsBoolean()).isTrue();
        assertThat(rejected.has("structuredContent")).isFalse();
        assertThat(rejected.toString()).contains("INVALID_ARGUMENT");

        var catalog = new McpToolCatalog();
        var observation = JsonParser.parseString("""
                {"schema_version":1,"frame_id":"obs-0000000000000000",
                 "kinds":["visible_surface"],"cursor":null,"limit":1}
                """).getAsJsonObject();
        var start = catalog.inputSchema("agent_start_action")
                .getAsJsonArray("examples").get(0).getAsJsonObject();
        var action = new com.google.gson.JsonObject();
        action.addProperty("action_id", "550e8400-e29b-41d4-a716-446655440000");
        assertThat(registry.call("agent_get_observation", observation).get("isError").getAsBoolean())
                .isFalse();
        assertThat(registry.call("agent_start_action", start).get("isError").getAsBoolean())
                .isFalse();
        assertThat(registry.call("agent_get_action", action).get("isError").getAsBoolean())
                .isFalse();
        assertThat(registry.call("agent_cancel_action", action).get("isError").getAsBoolean())
                .isFalse();
        assertThat(calls).hasValue(6);
    }

    private static Map<String, Object> toolResult(McpRuntimePort.RuntimeCommand command) {
        return switch (command) {
            case McpRuntimePort.GetState ignored -> McpTestFixtures.state();
            case McpRuntimePort.GetObservation ignored -> nullableMap(
                    "schema_version", 1,
                    "frame_id", "obs-0000000000000000",
                    "frame_completed_tick", 0,
                    "visible_entities_truncated", false,
                    "records", List.of(),
                    "next_cursor", null,
                    "sampling_coverage", 1);
            case McpRuntimePort.StartAction ignored -> Map.of(
                    "schema_version", 1,
                    "action_id", "550e8400-e29b-41d4-a716-446655440000",
                    "state", "queued",
                    "accepted_at", "2026-08-26T00:00:00Z");
            case McpRuntimePort.GetAction ignored -> nullableMap(
                    "schema_version", 1,
                    "action_id", "550e8400-e29b-41d4-a716-446655440000",
                    "state", "queued",
                    "progress", nullableMap(
                            "phase", "queued", "current_node_id", null,
                            "executed_nodes", 0, "total_node_upper_bound", 1,
                            "distance_travelled", 0, "camera_degrees", 0,
                            "interactions", 0, "blocks_broken", 0,
                            "blocks_placed", 0, "ticks", 0),
                    "failure", null,
                    "trace", List.of());
            case McpRuntimePort.CancelAction ignored -> Map.of(
                    "schema_version", 1,
                    "action_id", "550e8400-e29b-41d4-a716-446655440000",
                    "cancel_requested", true,
                    "state_at_request", "queued");
            case McpRuntimePort.ConfirmActionDelivery delivery -> Map.of(
                    "action_id", delivery.actionId().toString(),
                    "confirmed", true);
            case McpRuntimePort.AbandonActionDelivery delivery -> Map.of(
                    "action_id", delivery.actionId().toString(),
                    "abandoned", true);
            default -> throw new AssertionError("Legacy runtime command escaped the five-tool registry");
        };
    }

    private static Map<String, Object> nullableMap(Object... pairs) {
        var result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < pairs.length; index += 2) {
            result.put((String) pairs[index], pairs[index + 1]);
        }
        return result;
    }
}
