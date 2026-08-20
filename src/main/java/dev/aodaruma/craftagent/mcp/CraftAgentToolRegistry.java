package dev.aodaruma.craftagent.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/** Builds the bounded Phase 1 tool catalog and bridges calls to the client-thread runtime port. */
public final class CraftAgentToolRegistry {
    private static final McpSchema.ToolAnnotations READ_ONLY_ANNOTATIONS = McpSchema.ToolAnnotations.builder()
            .readOnlyHint(true)
            .destructiveHint(false)
            .idempotentHint(true)
            .openWorldHint(true)
            .build();
    private static final McpSchema.ToolAnnotations STOP_ANNOTATIONS = McpSchema.ToolAnnotations.builder()
            .readOnlyHint(false)
            .destructiveHint(false)
            .idempotentHint(true)
            .openWorldHint(true)
            .build();

    private final McpRuntimePort runtimePort;
    private final Duration runtimeDispatchTimeout;
    private final McpJsonMapper jsonMapper;
    private final JsonSchemaValidator schemaValidator;
    private final List<McpStatelessServerFeatures.SyncToolSpecification> specifications;

    public CraftAgentToolRegistry(McpRuntimePort runtimePort, Duration runtimeDispatchTimeout) {
        this(runtimePort, runtimeDispatchTimeout, McpJsonDefaults.getMapper());
    }

    CraftAgentToolRegistry(
            McpRuntimePort runtimePort, Duration runtimeDispatchTimeout, McpJsonMapper jsonMapper) {
        this.runtimePort = Objects.requireNonNull(runtimePort, "runtimePort");
        this.runtimeDispatchTimeout = requirePositive(runtimeDispatchTimeout);
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
        schemaValidator = McpJsonDefaults.getSchemaValidator();
        specifications = List.of(
                specification(
                        tool(
                                "get_status",
                                "Read connection, local lock, policy, compatibility, routine and memory status.",
                                McpToolSchemas.statusInput(),
                                McpToolSchemas.statusOutput(),
                                READ_ONLY_ANNOTATIONS),
                        ignored -> new McpRuntimePort.GetStatus()),
                specification(
                        tool(
                                "get_snapshot",
                                "Read a same-client-tick snapshot for explicitly requested observation scopes.",
                                McpToolSchemas.snapshotInput(),
                                McpToolSchemas.snapshotOutput(),
                                READ_ONLY_ANNOTATIONS),
                        McpRuntimePort.GetSnapshot::new),
                specification(
                        tool(
                                "compare_block_plan",
                                "Compare up to 512 expected blocks against current observations and session memory.",
                                McpToolSchemas.compareInput(),
                                McpToolSchemas.compareOutput(),
                                READ_ONLY_ANNOTATIONS),
                        McpRuntimePort.CompareBlockPlan::new),
                specification(
                        tool(
                                "emergency_stop",
                                "Release automation inputs, discard pending starts, cancel the routine and lock locally.",
                                McpToolSchemas.emergencyStopInput(),
                                McpToolSchemas.emergencyStopOutput(),
                                STOP_ANNOTATIONS),
                        this::emergencyStopCommand));
    }

    public List<McpStatelessServerFeatures.SyncToolSpecification> specifications() {
        return specifications;
    }

    private McpStatelessServerFeatures.SyncToolSpecification specification(
            McpSchema.Tool tool, Function<Map<String, Object>, McpRuntimePort.RuntimeCommand> commandFactory) {
        return McpStatelessServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((transportContext, request) -> invoke(tool, request.arguments(), commandFactory))
                .build();
    }

    private McpSchema.Tool tool(
            String name,
            String description,
            Map<String, Object> inputSchema,
            Map<String, Object> outputSchema,
            McpSchema.ToolAnnotations annotations) {
        return McpSchema.Tool.builder(name, inputSchema)
                .description(description)
                .outputSchema(outputSchema)
                .annotations(annotations)
                .build();
    }

    private McpSchema.CallToolResult invoke(
            McpSchema.Tool tool,
            Map<String, Object> arguments,
            Function<Map<String, Object>, McpRuntimePort.RuntimeCommand> commandFactory) {
        String toolName = tool.name();
        RuntimeCallContext callContext = RuntimeCallContext.withTimeout(runtimeDispatchTimeout);
        Map<String, Object> safeArguments = arguments == null ? Map.of() : new LinkedHashMap<>(arguments);
        var validation = schemaValidator.validate(tool.inputSchema(), safeArguments);
        if (!validation.valid()) {
            return failureResult(
                    toolName,
                    callContext,
                    "invalid_argument",
                    "The tool arguments do not match the advertised schema.",
                    false,
                    Map.of());
        }
        McpRuntimePort.RuntimeCommand command;
        try {
            command = commandFactory.apply(safeArguments);
        }
        catch (IllegalArgumentException exception) {
            return failureResult(toolName, callContext, "invalid_argument", exception.getMessage(), false, Map.of());
        }

        CompletionStage<McpRuntimePort.RuntimeReply> stage;
        try {
            stage = Objects.requireNonNull(
                    runtimePort.submit(command, callContext), "runtime port returned a null CompletionStage");
        }
        catch (RuntimeException exception) {
            callContext.cancel();
            return failureResult(
                    toolName, callContext, "runtime_unavailable", "The client runtime rejected the call.", true, Map.of());
        }

        CompletableFuture<McpRuntimePort.RuntimeReply> result = bridge(stage);
        try {
            long remainingNanos = callContext.remainingNanos();
            if (remainingNanos == 0) {
                throw new TimeoutException("runtime deadline elapsed before waiting");
            }
            McpRuntimePort.RuntimeReply reply = result.get(remainingNanos, TimeUnit.NANOSECONDS);
            if (reply == null) {
                return failureResult(
                        toolName, callContext, "runtime_failure", "The client runtime returned no result.", true, Map.of());
            }
            if (reply.successful()) {
                return successResult(toolName, callContext, reply.data());
            }
            McpRuntimePort.RuntimeFailure failure = reply.failure();
            return failureResult(
                    toolName,
                    callContext,
                    failure.code(),
                    failure.message(),
                    failure.retryable(),
                    failure.details());
        }
        catch (TimeoutException exception) {
            callContext.cancel();
            result.cancel(false);
            cancelStage(stage);
            return failureResult(
                    toolName,
                    callContext,
                    "runtime_timeout",
                    "The client runtime did not respond before the call deadline.",
                    true,
                    Map.of());
        }
        catch (InterruptedException exception) {
            callContext.cancel();
            result.cancel(false);
            cancelStage(stage);
            Thread.currentThread().interrupt();
            return failureResult(
                    toolName, callContext, "request_interrupted", "The MCP request was interrupted.", true, Map.of());
        }
        catch (CancellationException exception) {
            callContext.cancel();
            return failureResult(
                    toolName, callContext, "runtime_cancelled", "The client runtime cancelled the call.", true, Map.of());
        }
        catch (ExecutionException exception) {
            callContext.cancel();
            return failureResult(
                    toolName,
                    callContext,
                    "runtime_failure",
                    "The client runtime failed while handling the call.",
                    true,
                    Map.of());
        }
    }

    private McpRuntimePort.RuntimeCommand emergencyStopCommand(Map<String, Object> arguments) {
        Object rawReason = arguments.get("reason");
        if (!(rawReason instanceof String reason) || reason.isBlank() || reason.length() > 160) {
            throw new IllegalArgumentException("reason must contain 1..160 characters");
        }
        if (reason.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                || Character.getType(codePoint) == Character.FORMAT)) {
            throw new IllegalArgumentException("reason must not contain control or formatting characters");
        }
        return new McpRuntimePort.EmergencyStop(reason);
    }

    private McpSchema.CallToolResult successResult(
            String toolName, RuntimeCallContext context, Map<String, Object> data) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("ok", true);
        envelope.put("tool", toolName);
        envelope.put("request_id", context.requestId());
        envelope.put("data", data);
        return callResult(envelope, false);
    }

    private McpSchema.CallToolResult failureResult(
            String toolName,
            RuntimeCallContext context,
            String code,
            String message,
            boolean retryable,
            Map<String, Object> details) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        error.put("retryable", retryable);
        error.put("details", details);

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("ok", false);
        envelope.put("tool", toolName);
        envelope.put("request_id", context.requestId());
        envelope.put("error", error);
        return callResult(envelope, true);
    }

    private McpSchema.CallToolResult callResult(Map<String, Object> envelope, boolean error) {
        Map<String, Object> structured = envelope;
        String text;
        try {
            text = jsonMapper.writeValueAsString(structured);
        }
        catch (IOException exception) {
            structured = serializationFailureEnvelope(envelope);
            try {
                text = jsonMapper.writeValueAsString(structured);
            }
            catch (IOException impossibleForScalarEnvelope) {
                throw new IllegalStateException("MCP JSON mapper could not serialize a scalar error envelope",
                        impossibleForScalarEnvelope);
            }
            error = true;
        }
        return McpSchema.CallToolResult.builder()
                .addContent(new McpSchema.TextContent(text))
                .structuredContent(structured)
                .isError(error)
                .build();
    }

    private static Map<String, Object> serializationFailureEnvelope(Map<String, Object> original) {
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("code", "serialization_failure");
        failure.put("message", "The client runtime returned data that could not be encoded as JSON.");
        failure.put("retryable", false);
        failure.put("details", Map.of());

        Map<String, Object> replacement = new LinkedHashMap<>();
        replacement.put("ok", false);
        replacement.put("tool", original.get("tool"));
        replacement.put("request_id", original.get("request_id"));
        replacement.put("error", failure);
        return replacement;
    }

    private static <T> CompletableFuture<T> bridge(CompletionStage<T> stage) {
        CompletableFuture<T> result = new CompletableFuture<>();
        stage.whenComplete((value, failure) -> {
            if (failure == null) {
                result.complete(value);
            }
            else {
                result.completeExceptionally(unwrapCompletionException(failure));
            }
        });
        return result;
    }

    private static Throwable unwrapCompletionException(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
    }

    private static void cancelStage(CompletionStage<?> stage) {
        try {
            stage.toCompletableFuture().cancel(false);
        }
        catch (RuntimeException ignored) {
            // RuntimeCallContext cancellation remains the authoritative side-effect fence.
        }
    }

    private static Duration requirePositive(Duration timeout) {
        Objects.requireNonNull(timeout, "runtimeDispatchTimeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("runtimeDispatchTimeout must be positive");
        }
        return timeout;
    }
}
