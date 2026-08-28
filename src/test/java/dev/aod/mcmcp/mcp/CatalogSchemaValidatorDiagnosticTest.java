package dev.aod.mcmcp.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogSchemaValidatorDiagnosticTest {
    @Test
    void startActionReportsCatalogDerivedFailurePathsWithoutDispatching() throws Exception {
        assertRejected(startRequest(request -> request.getAsJsonObject("program")
                        .getAsJsonArray("body").get(0).getAsJsonObject().remove("id")),
                "program.body[0].id: required");

        assertRejected(startRequest(request -> request.getAsJsonObject("program")
                        .getAsJsonArray("body").get(0).getAsJsonObject()
                        .addProperty("op", "move_to")),
                "program.body[0].op: unknown catalog value");

        assertRejected(startRequest(request -> request.getAsJsonObject("program")
                        .getAsJsonArray("body").get(0).getAsJsonObject()
                        .getAsJsonObject("target").remove("dimension")),
                "program.body[0].target.dimension: required");

        assertRejected(startRequest(request -> request.getAsJsonObject("program")
                        .getAsJsonArray("body").get(0).getAsJsonObject()
                        .getAsJsonObject("target").addProperty("x", 100.5)),
                "program.body[0].target.x: expected integer");
    }

    @Test
    void diagnosticsGeneralizeToOtherToolsAndDoNotReflectUnknownInput() throws Exception {
        JsonObject observation = JsonParser.parseString("""
                {"schema_version":1,"frame_id":"obs-0000000000000000",
                 "kinds":["visible_surface"],"cursor":null,"limit":300}
                """).getAsJsonObject();
        assertRejected("agent_get_observation", observation, "limit: above catalog maximum");

        JsonObject untrusted = new JsonObject();
        untrusted.addProperty("secret-token-should-not-echo", "sensitive-value");
        String message = rejection("agent_get_state", untrusted);
        assertThat(message).isEqualTo("$: unknown property");
        assertThat(message).doesNotContain("secret", "sensitive");
    }

    private static JsonObject startRequest(java.util.function.Consumer<JsonObject> mutation) {
        JsonObject request = new McpToolCatalog().inputSchema("agent_start_action")
                .getAsJsonArray("examples").get(0).getAsJsonObject().deepCopy();
        mutation.accept(request);
        return request;
    }

    private static void assertRejected(JsonObject request, String expected) throws Exception {
        assertRejected("agent_start_action", request, expected);
    }

    private static void assertRejected(
            String tool, JsonObject request, String expected) throws Exception {
        assertThat(rejection(tool, request)).isEqualTo(expected);
    }

    private static String rejection(String tool, JsonObject request) throws Exception {
        var dispatches = new AtomicInteger();
        var registry = new McmcpToolRegistry((command, context) -> {
            dispatches.incrementAndGet();
            throw new AssertionError("invalid input must not reach the Minecraft runtime");
        }, Duration.ofSeconds(1));

        JsonObject response = registry.call(tool, request);
        assertThat(response.get("isError").getAsBoolean()).isTrue();
        assertThat(response.has("structuredContent")).isFalse();
        assertThat(dispatches).hasValue(0);
        JsonObject error = JsonParser.parseString(response.getAsJsonArray("content").get(0)
                .getAsJsonObject().get("text").getAsString()).getAsJsonObject();
        assertThat(error.get("code").getAsString()).isEqualTo("INVALID_ARGUMENT");
        assertThat(error.get("recoverable").getAsBoolean()).isTrue();
        return error.get("message").getAsString();
    }
}
