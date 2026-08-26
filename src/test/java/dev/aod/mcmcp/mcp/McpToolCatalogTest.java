package dev.aod.mcmcp.mcp;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolCatalogTest {
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
    void registryReturnsSchemaValidStateAndStructuredErrorsWithoutDispatchingRawTools() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var registry = new McmcpToolRegistry((command, context) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(McpRuntimePort.RuntimeReply.success(McpTestFixtures.state()));
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

        var getAction = new com.google.gson.JsonObject();
        getAction.addProperty("action_id", "550e8400-e29b-41d4-a716-446655440000");
        var unavailable = registry.call("agent_get_action", getAction);
        assertThat(unavailable.toString()).contains("CAPABILITY_DENIED");
        assertThat(calls).hasValue(1);
    }
}
