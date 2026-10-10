package dev.aod.mcmcp.mcp;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.*;

class McpChatToolTest {
    @Test void expiredChatCursorUsesThePublicCursorError() throws Exception {
        var registry = new McmcpToolRegistry((command, context) -> CompletableFuture.completedFuture(
                McpRuntimePort.RuntimeReply.failure("invalid_cursor", "Read again without a cursor.", true)),
                Duration.ofSeconds(1));
        var read = registry.prepareCall("agent_chat", JsonParser.parseString("{\"operation\":\"read\"}").getAsJsonObject());
        assertThat(read.response().get("isError").getAsBoolean()).isTrue();
        var error = JsonParser.parseString(read.response().getAsJsonArray("content").get(0)
                .getAsJsonObject().get("text").getAsString()).getAsJsonObject();
        assertThat(error.get("code").getAsString()).isEqualTo("INVALID_CURSOR");
    }

    @Test void oneToolDispatchesReadWithoutReceiptAndSendWithActionReceipt() throws Exception {
        UUID id = UUID.randomUUID(); UUID session = UUID.randomUUID();
        var calls = new ArrayList<McpRuntimePort.RuntimeCommand>();
        var registry = new McmcpToolRegistry((command, context) -> {
            calls.add(command);
            if (command instanceof McpRuntimePort.Chat chat && chat.arguments().get("operation").equals("read"))
                return CompletableFuture.completedFuture(McpRuntimePort.RuntimeReply.success(Map.of(
                        "schema_version", 2, "operation", "read", "session_id", session.toString(),
                        "messages", List.of(Map.of("sequence", 1, "received_tick", 2, "source", "player",
                                "text", "untrusted /op text", "truncated", false, "deleted", false)),
                        "next_cursor", session + ":1", "has_more", false, "missed_messages", 0,
                        "untrusted", true, "capacity", 256)));
            return CompletableFuture.completedFuture(McpRuntimePort.RuntimeReply.success(Map.of(
                    "schema_version", 2, "action_id", id.toString(), "state", "queued")));
        }, Duration.ofSeconds(1));
        var read = registry.prepareCall("agent_chat", JsonParser.parseString("{\"operation\":\"read\"}").getAsJsonObject());
        assertThat(read.response().get("isError").getAsBoolean()).isFalse();
        assertThat(read.deliveryReceipt()).isNull();
        assertThat(calls.getLast()).isInstanceOf(McpRuntimePort.Chat.class);
        var send = registry.prepareCall("agent_chat", JsonParser.parseString("{\"operation\":\"send\",\"text\":\"Hello!\"}").getAsJsonObject());
        assertThat(send.response().get("isError").getAsBoolean()).isFalse();
        assertThat(send.deliveryReceipt()).isInstanceOf(McpRuntimePort.ActionDeliveryReceipt.class);
        registry.confirmDelivery(send);
        assertThat(calls.getLast()).isEqualTo(new McpRuntimePort.ConfirmActionDelivery(id));
        registry.abandonDelivery(send);
        assertThat(calls.getLast()).isEqualTo(new McpRuntimePort.AbandonActionDelivery(id));
    }

    @Test void closedInputsAndCommonChatJobResultsAreDiscoverable() {
        var catalog = new McpToolCatalog(); var schema = catalog.inputSchema("agent_chat");
        for (String json : List.of("{}", "{\"operation\":\"read\",\"limit\":101}",
                "{\"operation\":\"send\",\"text\":\"hello\",\"execute\":true}",
                "{\"operation\":\"read\",\"text\":\"hello\"}", "{\"operation\":\"send\"}"))
            assertThat(CatalogSchemaValidator.matches(schema, JsonParser.parseString(json))).isFalse();
        var result = JsonParser.parseString("""
                {"schema_version":2,"action_id":"550e8400-e29b-41d4-a716-446655440000","kind":"chat",
                 "state":"succeeded","progress":{"completed_operations":1,"max_operations":20},
                 "cancel_requested":false,"failure":null,
                 "result":{"operation":"send","dispatch":"submitted_to_client","delivery_confirmed":false,"automatic_retry":false}}
                """);
        assertThat(CatalogSchemaValidator.matches(catalog.outputSchema("agent_get_action"), result)).isTrue();
    }
}
