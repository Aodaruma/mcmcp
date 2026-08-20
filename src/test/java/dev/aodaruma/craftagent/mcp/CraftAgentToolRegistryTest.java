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
    void advertisesOnlyTheBoundedPhaseOneCatalogWithClosedSchemas() {
        CraftAgentToolRegistry registry = new CraftAgentToolRegistry(
                (command, context) -> CompletableFuture.completedFuture(
                        McpRuntimePort.RuntimeReply.success(McpTestFixtures.statusData())),
                Duration.ofSeconds(1));

        List<McpSchema.Tool> tools = registry.specifications().stream()
                .map(McpStatelessServerFeatures.SyncToolSpecification::tool)
                .toList();

        assertThat(tools).extracting(McpSchema.Tool::name)
                .containsExactly("get_status", "get_snapshot", "compare_block_plan", "emergency_stop");
        for (McpSchema.Tool tool : tools) {
            assertThat(tool.inputSchema()).containsEntry("additionalProperties", false);
            assertThat(tool.outputSchema()).containsEntry("additionalProperties", false);
            assertEveryObjectSchemaControlsAdditionalProperties(tool.inputSchema());
            assertEveryObjectSchemaControlsAdditionalProperties(tool.outputSchema());
            assertThat(tool.annotations().idempotentHint()).isTrue();
            assertThat(tool.annotations().openWorldHint()).isTrue();
            assertThat(tool.annotations().destructiveHint()).isFalse();
        }
        assertThat(tools.subList(0, 3)).allSatisfy(tool ->
                assertThat(tool.annotations().readOnlyHint()).isTrue());
        assertThat(tools.get(3).annotations().readOnlyHint()).isFalse();
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
