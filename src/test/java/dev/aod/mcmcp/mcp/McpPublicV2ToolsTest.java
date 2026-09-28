package dev.aod.mcmcp.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class McpPublicV2ToolsTest {
    private static final UUID ACTION_ID =
            UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    @Test
    void allEightBasicToolsAreDiscoverableAndDeliveryGated() throws Exception {
        var calls = new ArrayList<McpRuntimePort.RuntimeCommand>();
        var registry = registry(calls);
        var examples = Map.of(
                "agent_move", "{\"x\":4,\"y\":65,\"z\":8}",
                "agent_break_block", "{\"x\":4,\"y\":65,\"z\":8}",
                "agent_place_block", "{\"x\":4,\"y\":65,\"z\":8,\"block\":\"minecraft:stone\"}",
                "agent_interact", "{\"target\":\"block\",\"x\":4,\"y\":65,\"z\":8}",
                "agent_inventory", "{\"operation\":\"inspect\"}",
                "agent_click", "{\"button\":\"right\"}",
                "agent_input_sequence", "{\"steps\":[{\"inputs\":[\"sneak\",\"forward\"],\"hold_ticks\":2}]}",
                "agent_run_script", "{\"source\":\"move(x=4, y=65, z=8);\"}");
        var expectedTypes = Map.<String, Class<? extends McpRuntimePort.RuntimeCommand>>of(
                "agent_move", McpRuntimePort.StartMove.class,
                "agent_break_block", McpRuntimePort.StartBreakBlock.class,
                "agent_place_block", McpRuntimePort.StartPlaceBlock.class,
                "agent_interact", McpRuntimePort.StartInteract.class,
                "agent_inventory", McpRuntimePort.StartInventory.class,
                "agent_click", McpRuntimePort.StartClick.class,
                "agent_input_sequence", McpRuntimePort.StartInputSequence.class,
                "agent_run_script", McpRuntimePort.StartScript.class);
        var listed = registry.listResult().getAsJsonArray("tools").asList().stream()
                .map(tool -> tool.getAsJsonObject().get("name").getAsString()).toList();
        assertThat(listed).containsAll(examples.keySet());
        for (var entry : examples.entrySet()) {
            calls.clear();
            JsonObject input = JsonParser.parseString(entry.getValue()).getAsJsonObject();
            var prepared = registry.prepareCall(entry.getKey(), input);
            assertThat(prepared.response().get("isError").getAsBoolean())
                    .as(entry.getKey()).isFalse();
            assertThat(calls.getFirst()).isInstanceOf(expectedTypes.get(entry.getKey()));
            assertThat(prepared.deliveryReceipt())
                    .isInstanceOf(McpRuntimePort.ActionDeliveryReceipt.class);
            registry.confirmDelivery(prepared);
            assertThat(calls.getLast())
                    .isInstanceOf(McpRuntimePort.ConfirmActionDelivery.class);
        }
    }

    @Test
    void v2ProgressAndCancellationPassThePublicSchemas() throws Exception {
        var calls = new ArrayList<McpRuntimePort.RuntimeCommand>();
        var registry = registry(calls);
        var input = JsonParser.parseString("{\"action_id\":\"" + ACTION_ID + "\"}")
                .getAsJsonObject();
        var progress = registry.prepareCall("agent_get_action", input);
        assertThat(progress.response().get("isError").getAsBoolean()).isFalse();
        assertThat(calls.getLast()).isInstanceOf(McpRuntimePort.GetAction.class);
        var cancel = registry.prepareCall("agent_cancel_action", input);
        assertThat(cancel.response().get("isError").getAsBoolean()).isFalse();
        assertThat(calls.getLast()).isInstanceOf(McpRuntimePort.CancelAction.class);
    }

    @Test
    void incompleteOrUnsupportedRequestsAreRejectedBeforeRuntimeDispatch()
            throws Exception {
        var calls = new ArrayList<McpRuntimePort.RuntimeCommand>();
        var registry = registry(calls);
        var invalid = Map.of(
                "agent_move", List.of("{}", "{\"x\":1,\"y\":2,\"z\":3,\"direction\":\"north\",\"distance\":1}"),
                "agent_inventory", List.of("{\"operation\":\"transfer\"}",
                        "{\"operation\":\"drop\",\"item\":\"minecraft:stone\"}"),
                "agent_click", List.of("{\"button\":\"right\",\"x\":1}",
                        "{\"button\":\"left\",\"entity_ref\":\"bad\"}"),
                "agent_interact", List.of("{\"target\":\"entity\",\"entity_ref\":\"bad\"}"));
        for (var entry : invalid.entrySet()) {
            for (String payload : entry.getValue()) {
                var response = registry.prepareCall(entry.getKey(),
                        JsonParser.parseString(payload).getAsJsonObject()).response();
                assertThat(response.get("isError").getAsBoolean())
                        .as(entry.getKey() + " " + payload).isTrue();
            }
        }
        assertThat(calls).isEmpty();
    }

    private static McmcpToolRegistry registry(
            List<McpRuntimePort.RuntimeCommand> calls) {
        return new McmcpToolRegistry((command, context) -> {
            calls.add(command);
            Map<String, Object> reply;
            if (command instanceof McpRuntimePort.GetAction) {
                reply = new LinkedHashMap<>();
                reply.put("schema_version", 2);
                reply.put("action_id", ACTION_ID.toString());
                reply.put("kind", "move");
                reply.put("state", "running");
                reply.put("progress", Map.of("completed_operations", 3,
                        "max_operations", 1200));
                reply.put("cancel_requested", false);
                reply.put("failure", null);
            } else if (command instanceof McpRuntimePort.CancelAction) {
                reply = Map.of("schema_version", 2, "action_id", ACTION_ID.toString(),
                        "cancel_requested", true, "state_at_request", "running");
            } else {
                reply = Map.of("schema_version", 2, "action_id", ACTION_ID.toString(),
                        "state", "queued");
            }
            return CompletableFuture.completedFuture(McpRuntimePort.RuntimeReply.success(reply));
        }, Duration.ofSeconds(1));
    }
}
