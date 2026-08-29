package dev.aod.mcmcp.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.Duration;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.UUID;

/** Fixed five-tool catalog plus the narrow bridge to the Minecraft client runtime. */
public final class McmcpToolRegistry {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final Duration ACTION_WAIT_DISPATCH_HEADROOM = Duration.ofSeconds(2);
    private static final Duration MAX_EFFECTIVE_DISPATCH_TIMEOUT = Duration.ofSeconds(30);
    private static final Set<String> PUBLIC_DOMAIN_CODES = Set.of(
            "INVALID_ARGUMENT", "MCP_OPERATION_DISABLED", "NO_WORLD", "TASK_BUSY",
            "MULTIPLAYER_NOT_ALLOWED", "TARGET_UNKNOWN", "NO_KNOWN_PATH",
            "SAFETY_PRECONDITION", "PROGRAM_TOO_COMPLEX", "PROGRAM_BUDGET_UNPROVABLE",
            "PREDICATE_UNAVAILABLE", "CAPABILITY_DENIED", "SERVER_BUSY", "ACTION_NOT_FOUND",
            "FRAME_EXPIRED", "INVALID_CURSOR", "INTERNAL_ERROR");

    private final McpRuntimePort runtimePort;
    private final Duration runtimeDispatchTimeout;
    private final McpToolCatalog catalog = new McpToolCatalog();
    private final Semaphore terminalWaitAdmission = new Semaphore(1);

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
        PreparedCall prepared = prepareCall(name, arguments);
        confirmDelivery(prepared);
        return prepared.response();
    }

    PreparedCall prepareCall(String name, JsonObject arguments) throws UnknownToolException {
        if (!catalog.contains(name)) {
            throw new UnknownToolException();
        }
        var schemaFailures = CatalogSchemaValidator.failures(
                catalog.inputSchema(name), arguments);
        if (!schemaFailures.isEmpty()) {
            return new PreparedCall(domainFailure(
                    "INVALID_ARGUMENT",
                    schemaFailures.summary(),
                    true), null);
        }

        return dispatch(name, command(name, arguments));
    }

    private PreparedCall dispatch(String toolName, McpRuntimePort.RuntimeCommand command) {
        boolean terminalWait = isTerminalWait(command);
        if (terminalWait && !terminalWaitAdmission.tryAcquire()) {
            return serverBusyCall("Another action terminal wait is already active.");
        }
        try {
            return dispatchAdmitted(toolName, command);
        } finally {
            if (terminalWait) {
                terminalWaitAdmission.release();
            }
        }
    }

    private PreparedCall dispatchAdmitted(
            String toolName, McpRuntimePort.RuntimeCommand command) {
        RuntimeCallContext context = RuntimeCallContext.withTimeout(
                effectiveDispatchTimeout(command));
        var future = runtimePort.submit(command, context).toCompletableFuture();
        McpRuntimePort.RuntimeReply reply;
        try {
            reply = future.get(context.remainingNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException failure) {
            if (future.cancel(true)) {
                context.cancel();
                return serverBusyCall("Minecraft client dispatch timed out.");
            }
            try {
                reply = future.join();
            } catch (RuntimeException completionFailure) {
                context.cancel();
                return serverBusyCall("Minecraft client dispatch timed out.");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            if (future.cancel(true)) {
                context.cancel();
                return failedCall("Minecraft client dispatch was interrupted.");
            }
            try {
                reply = future.join();
            } catch (RuntimeException completionFailure) {
                context.cancel();
                return failedCall("Minecraft client dispatch was interrupted.");
            }
        } catch (CancellationException failure) {
            context.cancel();
            return failedCall("Minecraft client dispatch was cancelled.");
        } catch (ExecutionException | RuntimeException failure) {
            context.cancel();
            return failedCall("Minecraft client dispatch failed.");
        }

        if (!reply.successful()) {
            McpRuntimePort.RuntimeFailure failure = reply.failure();
            return new PreparedCall(domainFailure(
                    publicCode(failure.code()), failure.message(), failure.retryable()), null);
        }
        McpRuntimePort.DeliveryReceipt deliveryReceipt = reply.deliveryReceipt();
        if (deliveryReceipt == null) {
            UUID deliveryActionId = deliveryActionId(toolName, reply.data());
            if (deliveryActionId != null) {
                deliveryReceipt = new McpRuntimePort.ActionDeliveryReceipt(deliveryActionId);
            }
        }
        JsonElement output = GSON.toJsonTree(reply.data());
        JsonObject schema = catalog.outputSchema(toolName);
        if (!output.isJsonObject() || !CatalogSchemaValidator.matches(schema, output)) {
            abandonDelivery(deliveryReceipt);
            return new PreparedCall(domainFailure(
                    "INTERNAL_ERROR",
                    "The Minecraft client returned an invalid tool result.",
                    true), null);
        }
        return new PreparedCall(success(output.getAsJsonObject()), deliveryReceipt);
    }

    private static boolean isTerminalWait(McpRuntimePort.RuntimeCommand command) {
        if (!(command instanceof McpRuntimePort.GetAction getAction)) {
            return false;
        }
        Object value = getAction.arguments().get("wait_timeout_ms");
        return value instanceof Number number && number.longValue() > 0L;
    }

    Duration effectiveDispatchTimeout(McpRuntimePort.RuntimeCommand command) {
        if (!(command instanceof McpRuntimePort.GetAction getAction)) {
            return runtimeDispatchTimeout;
        }
        Object value = getAction.arguments().get("wait_timeout_ms");
        if (!(value instanceof Number number) || number.longValue() <= 0L) {
            return runtimeDispatchTimeout;
        }
        Duration required = Duration.ofMillis(number.longValue())
                .plus(ACTION_WAIT_DISPATCH_HEADROOM);
        if (required.compareTo(MAX_EFFECTIVE_DISPATCH_TIMEOUT) > 0) {
            required = MAX_EFFECTIVE_DISPATCH_TIMEOUT;
        }
        return required.compareTo(runtimeDispatchTimeout) > 0
                ? required : runtimeDispatchTimeout;
    }

    private PreparedCall failedCall(String message) {
        return new PreparedCall(domainFailure("INTERNAL_ERROR", message, true), null);
    }

    private PreparedCall serverBusyCall(String message) {
        return new PreparedCall(domainFailure("SERVER_BUSY", message, true), null);
    }

    void confirmDelivery(PreparedCall prepared) {
        Objects.requireNonNull(prepared, "prepared");
        switch (prepared.deliveryReceipt()) {
            case null -> { }
            case McpRuntimePort.ActionDeliveryReceipt action ->
                    submitDelivery(new McpRuntimePort.ConfirmActionDelivery(action.actionId()));
            case McpRuntimePort.ObservationDeliveryReceipt observation ->
                    submitDelivery(new McpRuntimePort.ConfirmObservationDelivery(
                            observation.receiptId()));
        }
    }

    void abandonDelivery(PreparedCall prepared) {
        Objects.requireNonNull(prepared, "prepared");
        abandonDelivery(prepared.deliveryReceipt());
    }

    private void abandonDelivery(McpRuntimePort.DeliveryReceipt receipt) {
        switch (receipt) {
            case null -> { }
            case McpRuntimePort.ActionDeliveryReceipt action ->
                    submitDelivery(new McpRuntimePort.AbandonActionDelivery(action.actionId()));
            case McpRuntimePort.ObservationDeliveryReceipt observation ->
                    submitDelivery(new McpRuntimePort.AbandonObservationDelivery(
                            observation.receiptId()));
        }
    }

    private void submitDelivery(McpRuntimePort.RuntimeCommand command) {
        RuntimeCallContext context = RuntimeCallContext.withTimeout(runtimeDispatchTimeout);
        try {
            runtimePort.submit(command, context);
        } catch (RuntimeException failure) {
            context.cancel();
        }
    }

    private static UUID deliveryActionId(String toolName, Map<String, Object> data) {
        if (!"agent_start_action".equals(toolName)) return null;
        Object value = data.get("action_id");
        if (!(value instanceof String actionId)) return null;
        try {
            return UUID.fromString(actionId);
        } catch (IllegalArgumentException failure) {
            return null;
        }
    }

    private static McpRuntimePort.RuntimeCommand command(String name, JsonObject arguments) {
        Map<String, Object> values = jsonMap(arguments);
        return switch (name) {
            case "agent_get_state" -> new McpRuntimePort.GetState();
            case "agent_get_observation" -> new McpRuntimePort.GetObservation(values);
            case "agent_start_action" -> new McpRuntimePort.StartAction(values);
            case "agent_get_action" -> new McpRuntimePort.GetAction(values);
            case "agent_cancel_action" -> new McpRuntimePort.CancelAction(values);
            default -> throw new AssertionError("Catalog and dispatch table diverged");
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jsonMap(JsonObject arguments) {
        Map<String, Object> decoded = GSON.fromJson(arguments, LinkedHashMap.class);
        return decoded == null ? Map.of() : decoded;
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
            case "BUSY", "TIMEOUT" -> "SERVER_BUSY";
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

    record PreparedCall(
            JsonObject response, McpRuntimePort.DeliveryReceipt deliveryReceipt) {
        PreparedCall {
            Objects.requireNonNull(response, "response");
        }
    }
}
