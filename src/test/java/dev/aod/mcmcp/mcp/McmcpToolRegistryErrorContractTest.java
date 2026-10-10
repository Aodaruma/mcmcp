package dev.aod.mcmcp.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class McmcpToolRegistryErrorContractTest {
    @Test
    void safetyDiagnosisSurvivesMappingWithoutAddingFieldsToThePublicErrorEnvelope() throws Exception {
        String message = "Move requires a safe local world state. Reason: local_safety_unconfirmed. "
                + "Local hazard: collision; observed_tick: 3859; world_revision: 42.";
        var registry = new McmcpToolRegistry(
                (command, context) -> CompletableFuture.completedFuture(McpRuntimePort.RuntimeReply.failure(
                        "unsafe_state", message, true, java.util.Map.of("private_detail", "not public"))),
                Duration.ofSeconds(1));
        var result = registry.call("agent_move", JsonParser.parseString(
                "{\"x\":101,\"y\":49,\"z\":1}").getAsJsonObject());
        assertError(result, "SAFETY_PRECONDITION");
        var error = JsonParser.parseString(result.getAsJsonArray("content").get(0)
                .getAsJsonObject().get("text").getAsString()).getAsJsonObject();
        assertThat(error.get("message").getAsString()).isEqualTo(message);
        assertThat(error.keySet()).containsExactlyInAnyOrder("code", "message", "recoverable");
    }

    @Test
    void dispatchPressureIsServerBusyAndTaskBusyMustBeExplicit() throws Exception {
        var timedOut = new McmcpToolRegistry(
                (command, context) -> new CompletableFuture<>(), Duration.ofNanos(1));
        assertError(timedOut.call("agent_get_state", new JsonObject()), "SERVER_BUSY");

        var genericBusy = registryFailure("busy");
        assertError(genericBusy.call("agent_get_state", new JsonObject()), "SERVER_BUSY");

        var legacyTimeout = registryFailure("timeout");
        assertError(legacyTimeout.call("agent_get_state", new JsonObject()), "SERVER_BUSY");

        var activeTask = registryFailure("task_busy");
        assertError(activeTask.call("agent_get_state", new JsonObject()), "TASK_BUSY");
    }

    private static McmcpToolRegistry registryFailure(String code) {
        return new McmcpToolRegistry(
                (command, context) -> CompletableFuture.completedFuture(
                        McpRuntimePort.RuntimeReply.failure(code, "try later", true)),
                Duration.ofSeconds(1));
    }

    private static void assertError(JsonObject result, String code) {
        var error = JsonParser.parseString(result.getAsJsonArray("content").get(0)
                .getAsJsonObject().get("text").getAsString()).getAsJsonObject();
        assertThat(result.get("isError").getAsBoolean()).isTrue();
        assertThat(error.get("code").getAsString()).isEqualTo(code);
        assertThat(error.get("recoverable").getAsBoolean()).isTrue();
    }
}
