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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpPublicV2ToolsTest {
    @Test
    void flightMovementUsesExistingCoordinateAndVerticalDirectionSchemas() {
        var catalog = new McpToolCatalog();
        var schema = catalog.inputSchema("agent_move");
        for (String arguments : List.of("{\"direction\":\"up\",\"distance\":12}",
                "{\"direction\":\"down\",\"distance\":12}", "{\"x\":1,\"y\":200,\"z\":2}")) {
            assertThat(CatalogSchemaValidator.matches(schema, JsonParser.parseString(arguments))).isTrue();
        }
    }
    private static final UUID ACTION_ID =
            UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    @Test
    void scriptReplacesThePublicJsonDslAndOldRequestsCannotDispatch() {
        var calls = new ArrayList<McpRuntimePort.RuntimeCommand>();
        var registry = registry(calls);
        var names = registry.listResult().getAsJsonArray("tools").asList().stream()
                .map(tool -> tool.getAsJsonObject().get("name").getAsString()).toList();
        assertThat(names).contains("agent_run_script").doesNotContain("agent_start_action");
        assertThat(names).hasSize(14);
        assertThatThrownBy(() -> registry.prepareCall("agent_start_action",
                LegacyActionSchema.inputSchema().getAsJsonArray("examples").get(0).getAsJsonObject()))
                .isInstanceOf(McmcpToolRegistry.UnknownToolException.class);
        assertThat(calls).isEmpty();
    }

    @Test
    void allNineBasicToolsAreDiscoverableAndDeliveryGated() throws Exception {
        var calls = new ArrayList<McpRuntimePort.RuntimeCommand>();
        var registry = registry(calls);
        var examples = Map.of(
                "agent_move", "{\"x\":4,\"y\":65,\"z\":8}",
                "agent_look", "{\"x\":4.5,\"y\":65.5,\"z\":8.5}",
                "agent_break_block", "{\"x\":4,\"y\":65,\"z\":8}",
                "agent_place_block", "{\"x\":4,\"y\":65,\"z\":8,\"block\":\"minecraft:stone\"}",
                "agent_interact", "{\"target\":\"block\",\"x\":4,\"y\":65,\"z\":8}",
                "agent_inventory", "{\"operation\":\"inspect\"}",
                "agent_click", "{\"button\":\"right\"}",
                "agent_input_sequence", "{\"steps\":[{\"inputs\":[\"sneak\",\"forward\"],\"hold_ticks\":2}]}",
                "agent_run_script", "{\"source\":\"move(x=4, y=65, z=8);\"}");
        var expectedTypes = Map.<String, Class<? extends McpRuntimePort.RuntimeCommand>>of(
                "agent_move", McpRuntimePort.StartMove.class,
                "agent_look", McpRuntimePort.StartLook.class,
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
    void itemUseIsPublicAndUsesTheSameInteractionReceipt() throws Exception {
        var calls = new ArrayList<McpRuntimePort.RuntimeCommand>();
        var registry = registry(calls);
        var input = JsonParser.parseString("""
                {"target":"item","item":"example:flask","result_item":"example:empty_flask",
                 "hold_ticks":40,"max_ticks":200}
                """).getAsJsonObject();
        var prepared = registry.prepareCall("agent_interact", input);
        assertThat(prepared.response().get("isError").getAsBoolean()).isFalse();
        assertThat(calls.getFirst()).isInstanceOf(McpRuntimePort.StartInteract.class);
        assertThat(prepared.deliveryReceipt()).isInstanceOf(McpRuntimePort.ActionDeliveryReceipt.class);
    }

    @Test
    void entityInteractionUsesTheCommonJobAndRejectsMixedTargets() throws Exception {
        var calls = new ArrayList<McpRuntimePort.RuntimeCommand>();
        var registry = registry(calls);
        var input = JsonParser.parseString("""
                {"target":"entity","entity_ref":"abcdefghijklmnopqrstuvwx",
                 "entity_type":"example:animal","item":"example:flask",
                 "result_item":"example:milk","max_ticks":200}
                """).getAsJsonObject();
        var prepared = registry.prepareCall("agent_interact", input);
        assertThat(prepared.response().get("isError").getAsBoolean()).isFalse();
        assertThat(calls.getFirst()).isInstanceOf(McpRuntimePort.StartInteract.class);
        assertThat(prepared.deliveryReceipt()).isInstanceOf(McpRuntimePort.ActionDeliveryReceipt.class);
        calls.clear();
        input.addProperty("x", 1);
        assertThat(registry.prepareCall("agent_interact", input).response().get("isError").getAsBoolean())
                .isTrue();
        assertThat(calls).isEmpty();
    }

    @Test
    void inventorySwapIsPublicAndUsesTheSharedActionReceipt() throws Exception {
        var calls = new ArrayList<McpRuntimePort.RuntimeCommand>();
        var registry = registry(calls);
        var input = JsonParser.parseString("{\"operation\":\"swap\",\"source_slot\":12,"
                + "\"hotbar_slot\":2,\"item\":\"minecraft:torch\"}").getAsJsonObject();
        var prepared = registry.prepareCall("agent_inventory", input);
        assertThat(prepared.response().get("isError").getAsBoolean()).isFalse();
        assertThat(calls.getFirst()).isInstanceOf(McpRuntimePort.StartInventory.class);
        assertThat(prepared.deliveryReceipt())
                .isInstanceOf(McpRuntimePort.ActionDeliveryReceipt.class);
    }

    @Test
    void coordinateContainerInspectionAndExactTransferUseTheSharedJob() throws Exception {
        var calls = new ArrayList<McpRuntimePort.RuntimeCommand>();
        var registry = registry(calls);
        for (String operation : List.of("\"operation\":\"inspect\"",
                "\"operation\":\"transfer\",\"direction\":\"take\",\"item\":\"example:material\",\"count\":13",
                "\"operation\":\"transfer\",\"direction\":\"store\",\"item\":\"example:material\",\"count\":13")) {
            var input = JsonParser.parseString("{" + operation
                    + ",\"target\":\"container\",\"x\":4,\"y\":65,\"z\":8}").getAsJsonObject();
            var prepared = registry.prepareCall("agent_inventory", input);
            assertThat(prepared.response().get("isError").getAsBoolean()).isFalse();
            assertThat(calls.getLast()).isInstanceOf(McpRuntimePort.StartInventory.class);
            assertThat(prepared.deliveryReceipt()).isInstanceOf(McpRuntimePort.ActionDeliveryReceipt.class);
            registry.confirmDelivery(prepared);
            assertThat(calls.getLast()).isInstanceOf(McpRuntimePort.ConfirmActionDelivery.class);
        }
    }

    @Test
    void portableStorageHasNoPublicProviderSpecificArguments() throws Exception {
        var calls = new ArrayList<McpRuntimePort.RuntimeCommand>();
        var registry = registry(calls);
        for (String payload : List.of(
                "{\"operation\":\"inspect\",\"target\":\"storage\",\"storage_slot\":38}",
                "{\"operation\":\"transfer\",\"target\":\"storage\",\"storage_slot\":0,\"direction\":\"take\",\"item\":\"example:ore\",\"count\":3}",
                "{\"operation\":\"transfer\",\"target\":\"storage\",\"storage_slot\":40,\"direction\":\"store\",\"item\":\"example:ore\",\"count\":3}")) {
            var prepared = registry.prepareCall("agent_inventory", JsonParser.parseString(payload).getAsJsonObject());
            assertThat(prepared.response().get("isError").getAsBoolean()).isFalse();
            assertThat(calls.getLast()).isInstanceOf(McpRuntimePort.StartInventory.class);
            assertThat(prepared.deliveryReceipt()).isInstanceOf(McpRuntimePort.ActionDeliveryReceipt.class);
        }
    }

    @Test
    void priorityExtensionsAreDiscoveredWithStrictArguments() throws Exception {
        var calls = new ArrayList<McpRuntimePort.RuntimeCommand>();
        var registry = registry(calls);
        for (var entry : Map.of(
                "agent_move", "{\"x\":1,\"y\":65,\"z\":2,\"clear_path\":true,\"bridge_block\":\"minecraft:stone\",\"stop_when\":{\"type\":\"item\",\"item\":\"minecraft:stone\",\"count\":3}}",
                "agent_input_sequence", "{\"steps\":[{\"inputs\":[\"sneak\"],\"hold_ticks\":4}],\"stop_when\":{\"type\":\"block\",\"x\":1,\"y\":65,\"z\":2,\"block\":\"minecraft:stone\"}}",
                "agent_interact", "{\"target\":\"menu\",\"x\":1,\"y\":65,\"z\":2,\"block\":\"minecraft:chest\",\"menu_type\":\"minecraft:generic_9x3\",\"clicks\":[{\"type\":\"quick_move\",\"slot\":0,\"item\":\"minecraft:stone\",\"count\":16}]}"
        ).entrySet()) {
            assertThat(registry.prepareCall(entry.getKey(), JsonParser.parseString(entry.getValue()).getAsJsonObject())
                    .response().get("isError").getAsBoolean()).as(entry.getKey()).isFalse();
        }
        calls.clear();
        assertThat(registry.prepareCall("agent_move", JsonParser.parseString(
                "{\"x\":1,\"y\":65,\"z\":2,\"bridge_block\":\"minecraft:stone\"}").getAsJsonObject())
                .response().get("isError").getAsBoolean()).isTrue();
        assertThat(registry.prepareCall("agent_input_sequence", JsonParser.parseString(
                "{\"steps\":[{\"inputs\":[\"sneak\"],\"hold_ticks\":4}],\"stop_when\":{\"type\":\"screen\",\"screen\":\"arbitrary\"}}").getAsJsonObject())
                .response().get("isError").getAsBoolean()).isTrue();
        assertThat(calls).isEmpty();
    }

    @Test
    void incompleteOrUnsupportedRequestsAreRejectedBeforeRuntimeDispatch()
            throws Exception {
        var calls = new ArrayList<McpRuntimePort.RuntimeCommand>();
        var registry = registry(calls);
        var invalid = Map.of(
                "agent_move", List.of("{}", "{\"x\":1,\"y\":2,\"z\":3,\"direction\":\"north\",\"distance\":1}"),
                "agent_inventory", List.of("{\"operation\":\"transfer\"}",
                        "{\"operation\":\"drop\",\"item\":\"minecraft:stone\"}",
                        "{\"operation\":\"inspect\",\"target\":\"container\",\"x\":1}",
                        "{\"operation\":\"transfer\",\"target\":\"container\",\"x\":1,\"y\":2,\"z\":3,"
                                + "\"direction\":\"take\",\"item\":\"minecraft:stone\",\"count\":0}"),
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
