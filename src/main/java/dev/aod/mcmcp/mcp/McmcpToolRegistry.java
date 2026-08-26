package dev.aod.mcmcp.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Fixed five-tool catalog plus the narrow bridge to the Minecraft client runtime. */
public final class McmcpToolRegistry {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final Set<String> PUBLIC_DOMAIN_CODES = Set.of(
            "INVALID_ARGUMENT", "MCP_OPERATION_DISABLED", "NO_WORLD", "TASK_BUSY",
            "MULTIPLAYER_NOT_ALLOWED", "TARGET_UNKNOWN", "NO_KNOWN_PATH",
            "SAFETY_PRECONDITION", "PROGRAM_TOO_COMPLEX", "PROGRAM_BUDGET_UNPROVABLE",
            "PREDICATE_UNAVAILABLE", "CAPABILITY_DENIED", "SERVER_BUSY", "ACTION_NOT_FOUND",
            "FRAME_EXPIRED", "INVALID_CURSOR", "INTERNAL_ERROR");

    private final McpRuntimePort runtimePort;
    private final Duration runtimeDispatchTimeout;
    private final McpToolCatalog catalog = new McpToolCatalog();

    public McmcpToolRegistry(McpRuntimePort runtimePort, Duration runtimeDispatchTimeout) {
        this.runtimePort = Objects.requireNonNull(runtimePort, "runtimePort");
        this.runtimeDispatchTimeout = requirePositive(runtimeDispatchTimeout);
    }

    JsonObject listResult() {
        return catalog.listResult();
    }

    JsonObject serverMeta() {
        return catalog.serverMeta();
    }

    JsonObject call(String name, JsonObject arguments) throws UnknownToolException {
        if (!catalog.contains(name)) {
            throw new UnknownToolException();
        }
        if (!CatalogSchemaValidator.matches(catalog.inputSchema(name), arguments)) {
            return domainFailure(
                    "INVALID_ARGUMENT",
                    "Tool arguments do not match the catalog schema.",
                    true);
        }

        if (!"agent_get_state".equals(name)) {
            return domainFailure(
                    "CAPABILITY_DENIED",
                    "This domain capability is not active in the current implementation phase.",
                    false);
        }
        return getState();
    }

    private JsonObject getState() {
        RuntimeCallContext context = RuntimeCallContext.withTimeout(runtimeDispatchTimeout);
        var future = runtimePort.submit(new McpRuntimePort.GetState(), context).toCompletableFuture();
        McpRuntimePort.RuntimeReply reply;
        try {
            reply = future.get(context.remainingNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException failure) {
            context.cancel();
            future.cancel(true);
            return domainFailure("INTERNAL_ERROR", "Minecraft client dispatch timed out.", true);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            context.cancel();
            future.cancel(true);
            return domainFailure("INTERNAL_ERROR", "Minecraft client dispatch was interrupted.", true);
        } catch (CancellationException failure) {
            context.cancel();
            return domainFailure("INTERNAL_ERROR", "Minecraft client dispatch was cancelled.", true);
        } catch (ExecutionException | RuntimeException failure) {
            context.cancel();
            return domainFailure("INTERNAL_ERROR", "Minecraft client dispatch failed.", true);
        }

        if (!reply.successful()) {
            McpRuntimePort.RuntimeFailure failure = reply.failure();
            return domainFailure(publicCode(failure.code()), failure.message(), failure.retryable());
        }
        JsonElement output = GSON.toJsonTree(reply.data());
        JsonObject schema = catalog.outputSchema("agent_get_state");
        if (!output.isJsonObject() || !CatalogSchemaValidator.matches(schema, output)) {
            return domainFailure(
                    "CAPABILITY_DENIED",
                    "The MCMCP state adapter is not active in the current implementation phase.",
                    false);
        }
        return success(output.getAsJsonObject());
    }

    private JsonObject success(JsonObject structuredContent) {
        JsonObject result = baseResult(false);
        result.add("structuredContent", structuredContent.deepCopy());
        result.add("content", textContent(structuredContent));
        return result;
    }

    private JsonObject domainFailure(String code, String message, boolean recoverable) {
        JsonObject error = new JsonObject();
        error.addProperty("code", PUBLIC_DOMAIN_CODES.contains(code) ? code : "INTERNAL_ERROR");
        error.addProperty("message", boundedMessage(message));
        error.addProperty("recoverable", recoverable);

        JsonObject result = baseResult(true);
        result.add("content", textContent(error));
        return result;
    }

    private JsonObject baseResult(boolean error) {
        JsonObject result = new JsonObject();
        result.addProperty("resultType", "complete");
        result.addProperty("isError", error);
        result.add("_meta", catalog.serverMeta());
        return result;
    }

    private static JsonArray textContent(JsonObject value) {
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", GSON.toJson(value));
        JsonArray content = new JsonArray();
        content.add(text);
        return content;
    }

    private static String publicCode(String runtimeCode) {
        if (runtimeCode == null) {
            return "INTERNAL_ERROR";
        }
        String normalized = runtimeCode.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "LOCKED" -> "MCP_OPERATION_DISABLED";
            case "BUSY" -> "TASK_BUSY";
            case "UNSAFE_STATE" -> "SAFETY_PRECONDITION";
            default -> PUBLIC_DOMAIN_CODES.contains(normalized) ? normalized : "INTERNAL_ERROR";
        };
    }

    private static String boundedMessage(String message) {
        if (message == null || message.isBlank()) {
            return "The request could not be completed.";
        }
        return message.length() <= 512 ? message : message.substring(0, 512);
    }

    private static Duration requirePositive(Duration timeout) {
        Objects.requireNonNull(timeout, "runtimeDispatchTimeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("runtimeDispatchTimeout must be positive");
        }
        return timeout;
    }

    static final class UnknownToolException extends Exception {
    }
}
