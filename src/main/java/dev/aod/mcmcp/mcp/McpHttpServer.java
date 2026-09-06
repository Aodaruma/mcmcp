package dev.aod.mcmcp.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.neoforged.fml.loading.FMLEnvironment;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * JDK-only MCP Streamable HTTP endpoint. The normative stateless 2026-07-28
 * dialect remains primary; a deliberately narrow 2025-06-18 projection supports
 * the wire shape used by Codex CLI 0.146.1.
 */
public final class McpHttpServer implements AutoCloseable {
    static final String PROTOCOL_VERSION = "2026-07-28";
    static final String CODEX_LEGACY_PROTOCOL_VERSION = "2025-06-18";
    static final String PROTOCOL_HEADER = "MCP-Protocol-Version";
    static final String METHOD_HEADER = "Mcp-Method";
    static final String NAME_HEADER = "Mcp-Name";
    static final String EVALUATION_LEASE_HEADER = "Mcmcp-Evaluation-Lease";
    static final String EVALUATION_TURN_PATH = "/mcp/internal/evaluation-turn";
    private static final String SESSION_HEADER = "Mcp-Session-Id";
    static final int HEADER_MISMATCH = -32_020;
    static final int UNSUPPORTED_PROTOCOL_VERSION = -32_022;
    private static final String PROTOCOL_META = "io.modelcontextprotocol/protocolVersion";
    private static final String CLIENT_CAPABILITIES_META = "io.modelcontextprotocol/clientCapabilities";
    private static final String CLIENT_INFO_META = "io.modelcontextprotocol/clientInfo";
    private static final String KILL_ZONE_APPROVAL_INPUT = "kill_zone_operation_approval";
    private static final String KILL_ZONE_STATE_PREFIX = "kz1";
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .create();
    private static final int MAX_HEADER_COUNT = 64;
    private static final int MAX_HEADER_CHARS = 16_384;
    private static final int MAX_EVALUATION_CONTROL_BODY_BYTES = 512;
    private static final long MIN_EVALUATION_DURATION_MILLIS = 1_000L;
    private static final long MAX_EVALUATION_DURATION_MILLIS = 121L * 60L * 1_000L;
    private static final long EVALUATION_STREAM_HEARTBEAT_SECONDS = 2L;
    private static final Set<String> REQUEST_KEYS = Set.of("jsonrpc", "id", "method", "params");

    private final McpHttpServerConfig config;
    private final McmcpToolRegistry tools;
    private final EvaluationTurnControl evaluationTurns;
    private final TokenBucketRateLimiter rateLimiter;
    private final TokenBucketRateLimiter evaluationControlRateLimiter;
    private final Semaphore admission;
    private final byte[] elicitationStateKey = new byte[32];
    private final boolean conformanceAuthenticationBypass;
    private volatile State state = State.NEW;
    private HttpServer server;
    private ExecutorService requestExecutor;
    private ScheduledExecutorService timeoutExecutor;

    public McpHttpServer(McpHttpServerConfig config, McpRuntimePort runtimePort) {
        this(config, runtimePort, allowsConformanceAuthenticationBypass(
                FMLEnvironment.isProduction(), Boolean.getBoolean("mcmcp.conformanceTest")));
    }

    McpHttpServer(
            McpHttpServerConfig config,
            McpRuntimePort runtimePort,
            boolean conformanceAuthenticationBypass) {
        this.config = java.util.Objects.requireNonNull(config, "config");
        tools = new McmcpToolRegistry(runtimePort, config.runtimeDispatchTimeout());
        evaluationTurns = runtimePort instanceof EvaluationTurnControl control ? control : null;
        rateLimiter = new TokenBucketRateLimiter(config.rateLimitBurst(), config.rateLimitPerSecond());
        evaluationControlRateLimiter = new TokenBucketRateLimiter(8, 4.0D);
        admission = new Semaphore(config.maxConcurrentRequests());
        new SecureRandom().nextBytes(elicitationStateKey);
        this.conformanceAuthenticationBypass = conformanceAuthenticationBypass;
    }

    public synchronized void start() throws McpHttpServerException {
        if (state != State.NEW) {
            throw new McpHttpServerException("MCP endpoint has already been started");
        }
        state = State.STARTING;
        try {
            InetAddress ipv4Loopback = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
            server = HttpServer.create(new InetSocketAddress(ipv4Loopback, config.port()), 16);
            requestExecutor = Executors.newFixedThreadPool(2, command ->
                    Thread.ofPlatform().daemon(true).name("mcmcp-mcp-worker").unstarted(command));
            timeoutExecutor = Executors.newSingleThreadScheduledExecutor(command ->
                    Thread.ofPlatform().daemon(true).name("mcmcp-mcp-timeout").unstarted(command));
            server.createContext("/mcp", this::handle);
            if (evaluationTurns != null) {
                server.createContext(EVALUATION_TURN_PATH, this::handleEvaluationTurn);
            }
            server.setExecutor(requestExecutor);
            server.start();
            state = State.RUNNING;
        } catch (IOException | RuntimeException failure) {
            state = State.FAILED;
            cleanup();
            throw new McpHttpServerException("Failed to start loopback MCP endpoint", failure);
        }
    }

    private void handle(HttpExchange exchange) {
        var timeout = timeoutExecutor.schedule(
                exchange::close, config.ioTimeout().toNanos(), TimeUnit.NANOSECONDS);
        try {
            handleRequest(exchange);
        } catch (IOException ignored) {
            // A peer or the request deadline may close the exchange. Never escape into the game runtime.
        } catch (RuntimeException failure) {
            try {
                sendJson(exchange, 500, jsonRpcError(JsonNull.INSTANCE, -32603, "Internal error", "InternalError"));
            } catch (IOException ignored) {
                // The response may already be committed.
            }
        } finally {
            timeout.cancel(false);
            exchange.close();
        }
    }

    /**
     * Trusted evaluator lifecycle channel. It is not an MCP method and is never advertised to
     * the model. Bearer authentication and loopback/Origin guards are always enforced, including
     * development conformance runs.
     */
    private void handleEvaluationTurn(HttpExchange exchange) {
        try {
            handleEvaluationTurnRequest(exchange);
        } catch (IOException ignored) {
            // A closed runner console/process is itself a release signal for a streaming lease.
        } catch (RuntimeException failure) {
            try {
                sendHttpError(exchange, 500, "EvaluationControlInternalError");
            } catch (IOException ignored) {
                // The response may already be committed.
            }
        } finally {
            exchange.close();
        }
    }

    private void handleEvaluationTurnRequest(HttpExchange exchange) throws IOException {
        if (!EVALUATION_TURN_PATH.equals(exchange.getRequestURI().getRawPath())) {
            sendHttpError(exchange, 404, "NotFound");
            return;
        }
        String method = exchange.getRequestMethod();
        if (!Set.of("POST", "DELETE").contains(method)) {
            exchange.getResponseHeaders().set("Allow", "POST, DELETE");
            sendHttpError(exchange, 405, "MethodNotAllowed");
            return;
        }
        Headers headers = exchange.getRequestHeaders();
        if (!headersWithinLimits(headers)) {
            sendHttpError(exchange, 431, "HeadersTooLarge");
            return;
        }
        if (!validHost(singleHeader(headers, "Host"))) {
            sendHttpError(exchange, 421, "InvalidHost");
            return;
        }
        if (headers.get("Origin") != null) {
            sendHttpError(exchange, 403, "OriginForbidden");
            return;
        }
        if (!validAuthorization(singleHeader(headers, "Authorization"))) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            sendHttpError(exchange, 401, "Unauthorized");
            return;
        }
        if (!validJsonContentType(singleHeader(headers, "Content-Type"))) {
            sendHttpError(exchange, 415, "UnsupportedMediaType");
            return;
        }
        if (("POST".equals(method)
                && !acceptsMediaType(singleHeader(headers, "Accept"), "application/x-ndjson"))
                || ("DELETE".equals(method)
                && !acceptsMediaType(singleHeader(headers, "Accept"), "application/json"))) {
            sendHttpError(exchange, 406, "NotAcceptable");
            return;
        }
        if (headers.get(PROTOCOL_HEADER) != null
                || headers.get(METHOD_HEADER) != null
                || headers.get(NAME_HEADER) != null
                || headers.get(SESSION_HEADER) != null) {
            sendHttpError(exchange, 400, "EvaluationControlHeaderMismatch");
            return;
        }
        if (!evaluationControlRateLimiter.tryAcquire()) {
            rejectBusy(exchange, "EvaluationControlRateLimitExceeded");
            return;
        }

        final JsonObject body;
        try {
            body = McpJson.parse(
                    readBody(exchange, MAX_EVALUATION_CONTROL_BODY_BYTES), config)
                    .getAsJsonObject();
        } catch (BodyTooLargeException failure) {
            sendHttpError(exchange, 413, "RequestBodyTooLarge");
            return;
        } catch (McpJson.LimitException failure) {
            sendHttpError(exchange, 400, "MalformedEvaluationControlRequest");
            return;
        } catch (IOException | IllegalStateException failure) {
            sendHttpError(exchange, 400, "MalformedEvaluationControlRequest");
            return;
        }

        if ("POST".equals(method)) {
            final EvaluationTurnControl.AcquireRequest request;
            try {
                request = parseEvaluationAcquire(body);
            } catch (IOException failure) {
                sendHttpError(exchange, 400, "MalformedEvaluationControlRequest");
                return;
            }
            streamEvaluationTurn(exchange, request);
        } else {
            releaseEvaluationTurn(exchange, body);
        }
    }

    private EvaluationTurnControl.AcquireRequest parseEvaluationAcquire(JsonObject body)
            throws IOException {
        if (!body.keySet().equals(Set.of("lease_id", "runner_pid", "max_duration_ms"))) {
            throw new IOException("invalid evaluation acquire shape");
        }
        UUID leaseId = boundedUuid(body.get("lease_id"));
        Long runnerPid = exactPositiveLong(body.get("runner_pid"));
        Long maximumMillis = exactPositiveLong(body.get("max_duration_ms"));
        if (leaseId == null || runnerPid == null || maximumMillis == null
                || maximumMillis < MIN_EVALUATION_DURATION_MILLIS
                || maximumMillis > MAX_EVALUATION_DURATION_MILLIS) {
            throw new IOException("invalid evaluation acquire value");
        }
        return new EvaluationTurnControl.AcquireRequest(
                leaseId, runnerPid, Duration.ofMillis(maximumMillis));
    }

    private void streamEvaluationTurn(
            HttpExchange exchange,
            EvaluationTurnControl.AcquireRequest request) throws IOException {
        EvaluationTurnControl.LeaseReceipt acquired;
        try {
            acquired = awaitEvaluationControl(evaluationTurns.acquire(request));
        } catch (EvaluationControlException failure) {
            sendHttpError(exchange, failure.status(), failure.code());
            return;
        }
        if (acquired.state() != EvaluationTurnControl.LeaseState.ACTIVE) {
            sendHttpError(exchange, 409, "EvaluationLeaseRejected");
            return;
        }

        boolean terminalObserved = false;
        try {
            exchange.getResponseHeaders().set(
                    "Content-Type", "application/x-ndjson; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set(EVALUATION_LEASE_HEADER, "active");
            exchange.sendResponseHeaders(200, 0);

            try (var output = exchange.getResponseBody()) {
                writeEvaluationStreamRecord(output, acquired);
                var terminal = evaluationTurns.await(request.leaseId()).toCompletableFuture();
                while (true) {
                    try {
                        var receipt = terminal.get(
                                EVALUATION_STREAM_HEARTBEAT_SECONDS, TimeUnit.SECONDS);
                        terminalObserved = true;
                        writeEvaluationStreamRecord(output, receipt);
                        return;
                    } catch (TimeoutException waiting) {
                        writeEvaluationHeartbeat(output);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (java.util.concurrent.ExecutionException failure) {
                        return;
                    }
                }
            }
        } finally {
            if (!terminalObserved) {
                try {
                    awaitEvaluationControl(evaluationTurns.release(
                            request.leaseId(),
                            EvaluationTurnControl.ReleaseReason.RUNNER_CONNECTION_CLOSED));
                } catch (EvaluationControlException ignored) {
                    // ProcessHandle/deadline release remains the final independent fence.
                }
            }
        }
    }

    private void releaseEvaluationTurn(HttpExchange exchange, JsonObject body) throws IOException {
        if (!body.keySet().equals(Set.of("lease_id", "reason"))) {
            sendHttpError(exchange, 400, "MalformedEvaluationControlRequest");
            return;
        }
        UUID leaseId = boundedUuid(body.get("lease_id"));
        String reasonValue = stringValue(body.get("reason"));
        if (leaseId == null || reasonValue == null) {
            sendHttpError(exchange, 400, "MalformedEvaluationControlRequest");
            return;
        }
        final EvaluationTurnControl.ReleaseReason reason;
        try {
            reason = EvaluationTurnControl.ReleaseReason.runnerValue(reasonValue);
        } catch (IllegalArgumentException failure) {
            sendHttpError(exchange, 400, "MalformedEvaluationControlRequest");
            return;
        }
        try {
            var receipt = awaitEvaluationControl(evaluationTurns.release(leaseId, reason));
            sendJson(exchange, 200, evaluationReceiptJson(receipt));
        } catch (EvaluationControlException failure) {
            sendHttpError(exchange, failure.status(), failure.code());
        }
    }

    private EvaluationTurnControl.LeaseReceipt awaitEvaluationControl(
            java.util.concurrent.CompletionStage<EvaluationTurnControl.LeaseReceipt> stage)
            throws EvaluationControlException {
        var future = stage.toCompletableFuture();
        try {
            return future.get(
                    config.runtimeDispatchTimeout().toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            future.cancel(false);
            throw new EvaluationControlException(503, "EvaluationControlInterrupted");
        } catch (TimeoutException failure) {
            future.cancel(false);
            throw new EvaluationControlException(503, "EvaluationControlTimeout");
        } catch (java.util.concurrent.ExecutionException | CompletionException failure) {
            throw new EvaluationControlException(409, "EvaluationLeaseRejected");
        }
    }

    private static void writeEvaluationHeartbeat(java.io.OutputStream output) throws IOException {
        output.write("{\"state\":\"active\"}\n".getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static void writeEvaluationStreamRecord(
            java.io.OutputStream output,
            EvaluationTurnControl.LeaseReceipt receipt) throws IOException {
        output.write((GSON.toJson(evaluationReceiptJson(receipt)) + "\n")
                .getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static JsonObject evaluationReceiptJson(EvaluationTurnControl.LeaseReceipt receipt) {
        JsonObject body = new JsonObject();
        body.addProperty("state", receipt.state().name().toLowerCase(Locale.ROOT));
        if (receipt.reason() == null) {
            body.add("reason", JsonNull.INSTANCE);
        } else {
            body.addProperty("reason", receipt.reason());
        }
        body.addProperty("inputs_released", receipt.inputsReleased());
        body.addProperty("input_owner_none", receipt.inputOwnerNone());
        body.addProperty("all_actions_terminal", receipt.allActionsTerminal());
        body.addProperty("process_identity_bound", receipt.processIdentityBound());
        return body;
    }

    private static UUID boundedUuid(JsonElement element) {
        String value = stringValue(element);
        return boundedUuidString(value);
    }

    private static UUID boundedUuidString(String value) {
        if (value == null || value.length() != 36 || !value.equals(value.toLowerCase(Locale.ROOT))) {
            return null;
        }
        try {
            UUID parsed = UUID.fromString(value);
            return parsed.toString().equals(value) ? parsed : null;
        } catch (IllegalArgumentException failure) {
            return null;
        }
    }

    private static Long exactPositiveLong(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        try {
            var decimal = element.getAsBigDecimal();
            long value = decimal.longValueExact();
            return value > 0L ? value : null;
        } catch (ArithmeticException | NumberFormatException failure) {
            return null;
        }
    }

    private static final class EvaluationControlException extends Exception {
        private final int status;
        private final String code;

        private EvaluationControlException(int status, String code) {
            this.status = status;
            this.code = code;
        }

        private int status() {
            return status;
        }

        private String code() {
            return code;
        }
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        if (!"/mcp".equals(exchange.getRequestURI().getRawPath())) {
            sendHttpError(exchange, 404, "NotFound");
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            sendHttpError(exchange, 405, "MethodNotAllowed");
            return;
        }
        Headers headers = exchange.getRequestHeaders();
        if (!headersWithinLimits(headers)) {
            sendHttpError(exchange, 431, "HeadersTooLarge");
            return;
        }
        if (!validHost(singleHeader(headers, "Host"))) {
            sendHttpError(exchange, 421, "InvalidHost");
            return;
        }
        if (headers.get("Origin") != null) {
            sendHttpError(exchange, 403, "OriginForbidden");
            return;
        }
        if (!conformanceAuthenticationBypass
                && !validAuthorization(singleHeader(headers, "Authorization"))) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            sendHttpError(exchange, 401, "Unauthorized");
            return;
        }
        List<String> evaluationLeaseHeaders = headers.get(EVALUATION_LEASE_HEADER);
        EvaluationTurnControl.FenceSnapshot evaluationFence = evaluationTurns == null
                ? null : evaluationTurns.fenceSnapshot();
        boolean evaluationLeaseRequired = evaluationFence != null
                && evaluationFence.isolationActive();
        RuntimeCallContext.EvaluationLeaseExpectation evaluationLeaseExpectation =
                evaluationFence == null
                        ? RuntimeCallContext.EvaluationLeaseExpectation.unmanaged()
                        : RuntimeCallContext.EvaluationLeaseExpectation.absent(
                                evaluationFence.revision());
        if (evaluationLeaseRequired || evaluationLeaseHeaders != null) {
            UUID evaluationLeaseId = evaluationLeaseHeaders != null
                    && evaluationLeaseHeaders.size() == 1
                    ? boundedUuidString(evaluationLeaseHeaders.getFirst()) : null;
            if (!evaluationLeaseRequired || evaluationLeaseId == null
                    || !evaluationFence.accepts(evaluationLeaseId)) {
                sendHttpError(exchange, 409, "EvaluationLeaseInactive");
                return;
            }
            evaluationLeaseExpectation =
                    RuntimeCallContext.EvaluationLeaseExpectation.active(
                            evaluationLeaseId, evaluationFence.revision());
        }
        if (!validJsonContentType(singleHeader(headers, "Content-Type"))) {
            sendHttpError(exchange, 415, "UnsupportedMediaType");
            return;
        }
        if (!acceptsMcpMediaTypes(singleHeader(headers, "Accept"))) {
            sendHttpError(exchange, 406, "NotAcceptable");
            return;
        }
        if (!rateLimiter.tryAcquire()) {
            rejectBusy(exchange, "RateLimitExceeded");
            return;
        }
        if (!admission.tryAcquire()) {
            rejectBusy(exchange, "TooManyConcurrentRequests");
            return;
        }

        try {
            byte[] body;
            try {
                body = readBody(exchange, config.maxRequestBodyBytes());
            } catch (BodyTooLargeException failure) {
                sendHttpError(exchange, 413, "RequestBodyTooLarge");
                return;
            }
            JsonElement parsed;
            try {
                parsed = McpJson.parse(body, config);
            } catch (McpJson.LimitException failure) {
                sendJson(exchange, 400, jsonRpcError(JsonNull.INSTANCE, -32600,
                        "Invalid Request", failure.code()));
                return;
            } catch (IOException failure) {
                sendJson(exchange, 400, jsonRpcError(JsonNull.INSTANCE, -32700,
                        "Parse error", "MalformedJson"));
                return;
            }
            dispatch(exchange, headers, parsed, evaluationLeaseExpectation);
        } finally {
            admission.release();
        }
    }

    private void dispatch(
            HttpExchange exchange,
            Headers headers,
            JsonElement parsed,
            RuntimeCallContext.EvaluationLeaseExpectation evaluationLeaseExpectation)
            throws IOException {
        if (!parsed.isJsonObject()) {
            sendJson(exchange, 400, jsonRpcError(JsonNull.INSTANCE, -32600,
                    "Invalid Request", parsed.isJsonArray() ? "BatchNotSupported" : "InvalidRequest"));
            return;
        }
        JsonObject request = parsed.getAsJsonObject();
        String candidateMethod = stringValue(request.get("method"));
        if (isCodexLegacyCandidate(headers, candidateMethod)) {
            dispatchCodexLegacy(exchange, headers, request, evaluationLeaseExpectation);
            return;
        }
        JsonElement id = validRequestId(request.get("id")) ? request.get("id").deepCopy() : JsonNull.INSTANCE;
        if (!validRequestObject(request)) {
            sendJson(exchange, 400, jsonRpcError(id, -32600, "Invalid Request", "InvalidRequest"));
            return;
        }
        String method = request.get("method").getAsString();
        JsonObject params = request.getAsJsonObject("params");
        JsonObject meta = params.has("_meta") && params.get("_meta").isJsonObject()
                ? params.getAsJsonObject("_meta") : null;
        if (meta == null || !validClientMetadata(meta)) {
            sendJson(exchange, 400, jsonRpcError(id, -32602, "Invalid params", "InvalidClientMetadata"));
            return;
        }

        String headerVersion = singleHeader(headers, PROTOCOL_HEADER);
        String bodyVersion = stringValue(meta.get(PROTOCOL_META));
        if (bodyVersion == null) {
            sendJson(exchange, 400, jsonRpcError(id, -32602,
                    "Invalid params", "MissingProtocolVersion"));
            return;
        }
        if (headerVersion == null || !headerVersion.equals(bodyVersion)) {
            sendJson(exchange, 400, headerMismatch(id));
            return;
        }
        if (!PROTOCOL_VERSION.equals(headerVersion)) {
            sendJson(exchange, 400, unsupportedProtocolVersion(id, bodyVersion));
            return;
        }
        if (!method.equals(singleHeader(headers, METHOD_HEADER))) {
            sendJson(exchange, 400, headerMismatch(id));
            return;
        }

        switch (method) {
            case "server/discover" -> discover(exchange, id, params, headers);
            case "tools/list" -> listTools(exchange, id, params, headers);
            case "tools/call" -> callTool(
                    exchange, id, params, headers, evaluationLeaseExpectation);
            default -> sendJson(exchange, 404, jsonRpcError(id, -32601,
                    "Method not found", "MethodNotFound"));
        }
    }

    private void dispatchCodexLegacy(
            HttpExchange exchange,
            Headers headers,
            JsonObject request,
            RuntimeCallContext.EvaluationLeaseExpectation evaluationLeaseExpectation)
            throws IOException {
        JsonElement id = validRequestId(request.get("id")) ? request.get("id").deepCopy() : JsonNull.INSTANCE;
        String method = stringValue(request.get("method"));
        if (!validJsonRpcMethod(request, method)) {
            sendJson(exchange, 400, jsonRpcError(id, -32600, "Invalid Request", "InvalidRequest"));
            return;
        }

        switch (method) {
            case "initialize" -> initializeCodexLegacy(exchange, id, request, headers);
            case "notifications/initialized" -> initializedCodexLegacy(exchange, request, headers);
            case "tools/list" -> listToolsCodexLegacy(exchange, id, request, headers);
            case "tools/call" -> callToolCodexLegacy(
                    exchange, id, request, headers, evaluationLeaseExpectation);
            default -> sendJson(exchange, 404, jsonRpcError(id, -32601,
                    "Method not found", "MethodNotFound"));
        }
    }

    private void initializeCodexLegacy(
            HttpExchange exchange, JsonElement id, JsonObject request, Headers headers) throws IOException {
        if (!REQUEST_KEYS.equals(request.keySet()) || !validRequestId(request.get("id"))
                || !request.get("params").isJsonObject()) {
            sendJson(exchange, 400, jsonRpcError(id, -32600, "Invalid Request", "InvalidRequest"));
            return;
        }
        if (!legacyHeadersMatch(headers, false)) {
            sendJson(exchange, 400, headerMismatch(id));
            return;
        }
        JsonObject params = request.getAsJsonObject("params");
        if (!validLegacyInitializeParams(params)) {
            sendJson(exchange, 400, jsonRpcError(id, -32602, "Invalid params", "InvalidParams"));
            return;
        }
        String requestedVersion = params.get("protocolVersion").getAsString();
        if (!CODEX_LEGACY_PROTOCOL_VERSION.equals(requestedVersion)) {
            sendJson(exchange, 400, unsupportedLegacyProtocolVersion(id, requestedVersion));
            return;
        }

        JsonObject toolsCapability = new JsonObject();
        toolsCapability.addProperty("listChanged", false);
        JsonObject capabilities = new JsonObject();
        capabilities.add("tools", toolsCapability);
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", CODEX_LEGACY_PROTOCOL_VERSION);
        result.add("capabilities", capabilities);
        result.add("serverInfo", tools.serverMeta()
                .getAsJsonObject("io.modelcontextprotocol/serverInfo").deepCopy());
        sendJson(exchange, 200, jsonRpcResult(id, result));
    }

    private void initializedCodexLegacy(HttpExchange exchange, JsonObject request, Headers headers)
            throws IOException {
        if (!validLegacyInitializedRequest(request)) {
            sendJson(exchange, 400, jsonRpcError(JsonNull.INSTANCE, -32600,
                    "Invalid Request", "InvalidRequest"));
            return;
        }
        if (!legacyHeadersMatch(headers, true)) {
            sendJson(exchange, 400, headerMismatch(JsonNull.INSTANCE));
            return;
        }
        sendEmpty(exchange, 202);
    }

    private void listToolsCodexLegacy(
            HttpExchange exchange, JsonElement id, JsonObject request, Headers headers) throws IOException {
        if (!REQUEST_KEYS.equals(request.keySet()) || !validRequestId(request.get("id"))
                || !request.get("params").isJsonObject()) {
            sendJson(exchange, 400, jsonRpcError(id, -32600, "Invalid Request", "InvalidRequest"));
            return;
        }
        if (!legacyHeadersMatch(headers, true)) {
            sendJson(exchange, 400, headerMismatch(id));
            return;
        }
        JsonObject params = request.getAsJsonObject("params");
        if (!validLegacyListParams(params)) {
            sendJson(exchange, 400, jsonRpcError(id, -32602, "Invalid params", "InvalidParams"));
            return;
        }
        JsonObject result = new JsonObject();
        result.add("tools", tools.listResult().getAsJsonArray("tools").deepCopy());
        sendJson(exchange, 200, jsonRpcResult(id, result));
    }

    private void callToolCodexLegacy(
            HttpExchange exchange,
            JsonElement id,
            JsonObject request,
            Headers headers,
            RuntimeCallContext.EvaluationLeaseExpectation evaluationLeaseExpectation)
            throws IOException {
        if (!REQUEST_KEYS.equals(request.keySet()) || !validRequestId(request.get("id"))
                || !request.get("params").isJsonObject()) {
            sendJson(exchange, 400, jsonRpcError(id, -32600, "Invalid Request", "InvalidRequest"));
            return;
        }
        if (!legacyHeadersMatch(headers, true)) {
            sendJson(exchange, 400, headerMismatch(id));
            return;
        }
        JsonObject params = request.getAsJsonObject("params");
        if (!validLegacyCallParams(params)) {
            sendJson(exchange, 400, jsonRpcError(id, -32602, "Invalid params", "InvalidToolCall"));
            return;
        }
        String name = params.get("name").getAsString();
        try {
            JsonObject arguments = params.has("arguments")
                    ? params.getAsJsonObject("arguments") : new JsonObject();
            var prepared = tools.prepareCall(
                    name, arguments, evaluationLeaseExpectation);
            JsonObject result = legacyToolResult(prepared.response());
            try {
                sendJson(exchange, 200, jsonRpcResult(id, result));
            } catch (IOException failure) {
                tools.abandonDelivery(prepared);
                throw failure;
            }
            tools.confirmDelivery(prepared);
        } catch (McmcpToolRegistry.UnknownToolException failure) {
            sendJson(exchange, 200, jsonRpcError(id, -32602, "Unknown tool", "UnknownTool"));
        }
    }

    private void discover(HttpExchange exchange, JsonElement id, JsonObject params, Headers headers)
            throws IOException {
        if (!params.keySet().equals(Set.of("_meta"))) {
            sendJson(exchange, 400, jsonRpcError(id, -32602, "Invalid params", "InvalidParams"));
            return;
        }
        if (headers.get(NAME_HEADER) != null) {
            sendJson(exchange, 400, headerMismatch(id));
            return;
        }
        JsonObject result = new JsonObject();
        result.addProperty("resultType", "complete");
        var versions = new com.google.gson.JsonArray();
        versions.add(PROTOCOL_VERSION);
        result.add("supportedVersions", versions);
        JsonObject toolsCapability = new JsonObject();
        toolsCapability.addProperty("listChanged", false);
        JsonObject capabilities = new JsonObject();
        capabilities.add("tools", toolsCapability);
        result.add("capabilities", capabilities);
        result.add("_meta", tools.serverMeta());
        result.addProperty("ttlMs", 0);
        result.addProperty("cacheScope", "private");
        sendJson(exchange, 200, jsonRpcResult(id, result));
    }

    private void listTools(HttpExchange exchange, JsonElement id, JsonObject params, Headers headers)
            throws IOException {
        boolean validCursor = !params.has("cursor") || params.get("cursor").isJsonNull();
        if (!Set.of("_meta", "cursor").containsAll(params.keySet())
                || !params.has("_meta") || !validCursor) {
            sendJson(exchange, 400, jsonRpcError(id, -32602, "Invalid params", "InvalidParams"));
            return;
        }
        if (headers.get(NAME_HEADER) != null) {
            sendJson(exchange, 400, headerMismatch(id));
            return;
        }
        sendJson(exchange, 200, jsonRpcResult(id, tools.listResult()));
    }

    private void callTool(
            HttpExchange exchange,
            JsonElement id,
            JsonObject params,
            Headers headers,
            RuntimeCallContext.EvaluationLeaseExpectation evaluationLeaseExpectation)
            throws IOException {
        if (!Set.of("_meta", "name", "arguments", "inputResponses", "requestState")
                    .containsAll(params.keySet())
                || !params.has("name") || !params.get("name").isJsonPrimitive()
                || !params.getAsJsonPrimitive("name").isString()
                || !params.has("arguments") || !params.get("arguments").isJsonObject()) {
            sendJson(exchange, 400, jsonRpcError(id, -32602, "Invalid params", "InvalidToolCall"));
            return;
        }
        String name = params.get("name").getAsString();
        String routedName;
        try {
            routedName = decodeMcpName(singleHeader(headers, NAME_HEADER));
        } catch (IllegalArgumentException failure) {
            routedName = null;
        }
        if (!name.equals(routedName)) {
            sendJson(exchange, 400, headerMismatch(id));
            return;
        }
        RuntimeCallContext.ElicitationInput elicitationInput;
        try {
            elicitationInput = elicitationInput(params, name);
        } catch (IllegalArgumentException failure) {
            sendJson(exchange, 400, jsonRpcError(
                    id, -32602, "Invalid params", "InvalidInputResponses"));
            return;
        }
        try {
            var prepared = tools.prepareCall(
                    name,
                    params.getAsJsonObject("arguments"),
                    evaluationLeaseExpectation,
                    elicitationInput);
            JsonObject response = prepared.response();
            if (elicitationInput.formSupported() && awaitingKillZoneConsent(response)) {
                response = killZoneInputRequired(
                        response.getAsJsonObject("structuredContent")
                                .get("policy_binding_hash").getAsString(),
                        response.getAsJsonObject("structuredContent")
                                .get("approval_request_state").getAsString(),
                        response.getAsJsonObject("structuredContent")
                                .get("approval_scope_summary").getAsString(),
                        params.getAsJsonObject("arguments"));
            }
            try {
                sendJson(exchange, 200, jsonRpcResult(id, response));
            } catch (IOException failure) {
                tools.abandonDelivery(prepared);
                throw failure;
            }
            tools.confirmDelivery(prepared);
        } catch (McmcpToolRegistry.UnknownToolException failure) {
            sendJson(exchange, 200, jsonRpcError(id, -32602, "Unknown tool", "UnknownTool"));
        }
    }

    private RuntimeCallContext.ElicitationInput elicitationInput(
            JsonObject params, String toolName) {
        boolean formSupported = supportsFormElicitation(params.getAsJsonObject("_meta"));
        boolean hasResponses = params.has("inputResponses");
        boolean hasState = params.has("requestState");
        if (hasResponses != hasState || (hasResponses && !formSupported)
                || (hasResponses && !"agent_start_action".equals(toolName))) {
            throw new IllegalArgumentException("invalid elicitation retry");
        }
        if (!hasResponses) {
            return formSupported
                    ? RuntimeCallContext.ElicitationInput.awaitingResponse()
                    : RuntimeCallContext.ElicitationInput.unsupported();
        }
        if (!params.get("inputResponses").isJsonObject()
                || !params.get("requestState").isJsonPrimitive()
                || !params.getAsJsonPrimitive("requestState").isString()) {
            throw new IllegalArgumentException("invalid elicitation response shape");
        }
        String requestState = verifyAndExtractKillZoneRequestState(
                params.get("requestState").getAsString());
        JsonObject responses = params.getAsJsonObject("inputResponses");
        if (!responses.keySet().equals(Set.of(KILL_ZONE_APPROVAL_INPUT))
                || !responses.get(KILL_ZONE_APPROVAL_INPUT).isJsonObject()) {
            throw new IllegalArgumentException("invalid elicitation response key");
        }
        JsonObject response = responses.getAsJsonObject(KILL_ZONE_APPROVAL_INPUT);
        String action = stringValue(response.get("action"));
        RuntimeCallContext.ResponseAction responseAction = switch (action) {
            case "accept" -> RuntimeCallContext.ResponseAction.ACCEPT;
            case "decline" -> RuntimeCallContext.ResponseAction.DECLINE;
            case "cancel" -> RuntimeCallContext.ResponseAction.CANCEL;
            default -> throw new IllegalArgumentException("invalid response action");
        };
        boolean confirmed = false;
        if (responseAction == RuntimeCallContext.ResponseAction.ACCEPT) {
            if (!response.keySet().equals(Set.of("action", "content"))
                    || !response.get("content").isJsonObject()) {
                throw new IllegalArgumentException("accepted response needs content");
            }
            JsonObject content = response.getAsJsonObject("content");
            if (!content.keySet().equals(Set.of("approve"))
                    || !content.get("approve").isJsonPrimitive()
                    || !content.getAsJsonPrimitive("approve").isBoolean()) {
                throw new IllegalArgumentException("invalid approval content");
            }
            confirmed = content.get("approve").getAsBoolean();
        } else if (!response.keySet().equals(Set.of("action"))) {
            throw new IllegalArgumentException("declined response cannot carry content");
        }
        return new RuntimeCallContext.ElicitationInput(
                true, requestState, responseAction, confirmed);
    }

    private static boolean supportsFormElicitation(JsonObject meta) {
        JsonElement capabilities = meta.get(CLIENT_CAPABILITIES_META);
        if (capabilities == null || !capabilities.isJsonObject()) {
            return false;
        }
        JsonElement elicitation = capabilities.getAsJsonObject().get("elicitation");
        if (elicitation == null || !elicitation.isJsonObject()) {
            return false;
        }
        JsonObject modes = elicitation.getAsJsonObject();
        return modes.size() == 0 || modes.has("form") && modes.get("form").isJsonObject();
    }

    private static boolean awaitingKillZoneConsent(JsonObject response) {
        if (!response.has("structuredContent")
                || !response.get("structuredContent").isJsonObject()) {
            return false;
        }
        JsonObject structured = response.getAsJsonObject("structuredContent");
        return "AWAITING_CONSENT".equals(stringValue(structured.get("state")))
                && stringValue(structured.get("policy_binding_hash")) != null;
    }

    private JsonObject killZoneInputRequired(
            String policyBindingHash,
            String approvalRequestState,
            String approvalScopeSummary,
            JsonObject arguments) {
        JsonObject result = new JsonObject();
        result.addProperty("resultType", "input_required");
        result.add("_meta", tools.serverMeta());
        JsonObject request = new JsonObject();
        request.addProperty("method", "elicitation/create");
        JsonObject requestParams = new JsonObject();
        requestParams.addProperty("mode", "form");
        requestParams.addProperty(
                "message", killZoneApprovalMessage(arguments, approvalScopeSummary));
        JsonObject approve = new JsonObject();
        approve.addProperty("type", "boolean");
        approve.addProperty("title", "この反復攻撃を許可する");
        approve.addProperty(
                "description",
                "特定の一体ではなく、同じ安全区域へ現在または後から入る対象を処理します。");
        approve.addProperty("default", false);
        JsonObject properties = new JsonObject();
        properties.add("approve", approve);
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        var required = new com.google.gson.JsonArray();
        required.add("approve");
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        requestParams.add("requestedSchema", schema);
        request.add("params", requestParams);
        JsonObject requests = new JsonObject();
        requests.add(KILL_ZONE_APPROVAL_INPUT, request);
        result.add("inputRequests", requests);
        result.addProperty(
                "requestState",
                signKillZoneRequestState(policyBindingHash, approvalRequestState));
        return result;
    }

    private String signKillZoneRequestState(
            String policyBindingHash, String approvalRequestState) {
        if (policyBindingHash == null
                || !policyBindingHash.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid policy binding hash");
        }
        if (approvalRequestState == null
                || !approvalRequestState.matches("[A-Za-z0-9_-]{24}")) {
            throw new IllegalArgumentException("invalid approval request state");
        }
        String hex = policyBindingHash.substring("sha256:".length());
        String payload = KILL_ZONE_STATE_PREFIX + "." + approvalRequestState + "." + hex;
        return payload + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(
                hmac(payload));
    }

    private String verifyAndExtractKillZoneRequestState(String requestState) {
        String[] parts = requestState == null ? new String[0] : requestState.split("\\.", -1);
        if (parts.length != 4 || !KILL_ZONE_STATE_PREFIX.equals(parts[0])
                || !parts[1].matches("[A-Za-z0-9_-]{24}")
                || !parts[2].matches("[0-9a-f]{64}")
                || !parts[3].matches("[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException("invalid request state");
        }
        byte[] supplied;
        try {
            supplied = Base64.getUrlDecoder().decode(parts[3]);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("invalid request state", failure);
        }
        // Reject alternate spellings whose unused Base64 bits decode to the same signature.
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(supplied).equals(parts[3])) {
            throw new IllegalArgumentException("invalid request state signature encoding");
        }
        byte[] expected = hmac(parts[0] + "." + parts[1] + "." + parts[2]);
        if (!MessageDigest.isEqual(expected, supplied)) {
            throw new IllegalArgumentException("invalid request state signature");
        }
        return parts[1];
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(elicitationStateKey, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException failure) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", failure);
        }
    }

    private static String killZoneApprovalMessage(
            JsonObject arguments, String approvalScopeSummary) {
        try {
            JsonObject program = arguments.getAsJsonObject("program");
            JsonObject node = program.getAsJsonArray("body").get(0).getAsJsonObject();
            int attacks = node.get("max_attacks").getAsInt();
            long intervalTicks = node.get("minimum_interval_ticks").getAsLong();
            long ticks = node.get("max_operation_duration_ticks").getAsLong();
            long seconds = Math.max(1L, (ticks + 19L) / 20L);
            String weapon = friendlyMinecraftId(node.get("main_hand_item").getAsString());
            var types = new java.util.ArrayList<String>();
            for (JsonElement type : node.getAsJsonArray("entity_type_allowlist")) {
                types.add(friendlyMinecraftId(type.getAsString()));
            }
            return "Minecraftの安全なキルゾーンで反復攻撃を行います。\n許可区域: "
                    + approvalScopeSummary + "\n"
                    + "対象種別は" + String.join("・", types) + "（特定個体の指定ではありません）、"
                    + "使用武器は" + weapon + "です。区域へ現在または後から入る対象へ、"
                    + "上限" + attacks + "回・最短間隔" + intervalTicks + "tick・最長"
                    + seconds + "秒で動作します。範囲、武器、体力、対象種別を攻撃ごとに再検査します。";
        } catch (RuntimeException failure) {
            return "Minecraftの安全なキルゾーンで、有限回の反復攻撃を許可します。\n許可区域: "
                    + approvalScopeSummary + "\n"
                    + "範囲、武器、体力、対象種別は攻撃ごとに再検査されます。";
        }
    }

    private static String friendlyMinecraftId(String id) {
        return switch (id) {
            case "minecraft:armor_stand" -> "防具立て";
            case "minecraft:zombie" -> "ゾンビ";
            case "minecraft:skeleton" -> "スケルトン";
            case "minecraft:wooden_sword" -> "木の剣";
            case "minecraft:stone_sword" -> "石の剣";
            case "minecraft:iron_sword" -> "鉄の剣";
            case "minecraft:golden_sword" -> "金の剣";
            case "minecraft:diamond_sword" -> "ダイヤモンドの剣";
            case "minecraft:netherite_sword" -> "ネザライトの剣";
            case "minecraft:wooden_axe" -> "木の斧";
            case "minecraft:stone_axe" -> "石の斧";
            case "minecraft:iron_axe" -> "鉄の斧";
            case "minecraft:golden_axe" -> "金の斧";
            case "minecraft:diamond_axe" -> "ダイヤモンドの斧";
            case "minecraft:netherite_axe" -> "ネザライトの斧";
            default -> id;
        };
    }

    private static boolean isCodexLegacyCandidate(Headers headers, String method) {
        return "initialize".equals(method)
                || "notifications/initialized".equals(method)
                || CODEX_LEGACY_PROTOCOL_VERSION.equals(singleHeader(headers, PROTOCOL_HEADER));
    }

    private static boolean legacyHeadersMatch(Headers headers, boolean requireProtocolHeader) {
        if (headers.get(METHOD_HEADER) != null
                || headers.get(NAME_HEADER) != null
                || headers.get(SESSION_HEADER) != null) {
            return false;
        }
        if (!requireProtocolHeader) {
            return headers.get(PROTOCOL_HEADER) == null;
        }
        return CODEX_LEGACY_PROTOCOL_VERSION.equals(singleHeader(headers, PROTOCOL_HEADER));
    }

    private static boolean validJsonRpcMethod(JsonObject request, String method) {
        return request.has("jsonrpc") && request.get("jsonrpc").isJsonPrimitive()
                && request.getAsJsonPrimitive("jsonrpc").isString()
                && "2.0".equals(request.get("jsonrpc").getAsString())
                && method != null && !method.isBlank() && method.length() <= 128;
    }

    private static boolean validLegacyInitializeParams(JsonObject params) {
        if (!params.keySet().equals(Set.of("protocolVersion", "capabilities", "clientInfo"))
                || stringValue(params.get("protocolVersion")) == null
                || !params.get("capabilities").isJsonObject()
                || !params.get("clientInfo").isJsonObject()) {
            return false;
        }
        JsonObject clientInfo = params.getAsJsonObject("clientInfo");
        if (!Set.of("name", "title", "version").containsAll(clientInfo.keySet())
                || !clientInfo.has("name") || !clientInfo.has("version")
                || !validBoundedString(clientInfo.get("name"))
                || !validBoundedString(clientInfo.get("version"))) {
            return false;
        }
        return !clientInfo.has("title") || validBoundedString(clientInfo.get("title"));
    }

    private static boolean validLegacyListParams(JsonObject params) {
        return Set.of("_meta", "cursor").containsAll(params.keySet())
                && (!params.has("cursor") || params.get("cursor").isJsonNull())
                && validLegacyRequestMeta(params);
    }

    private static boolean validLegacyCallParams(JsonObject params) {
        return Set.of("_meta", "name", "arguments").containsAll(params.keySet())
                && params.has("name") && params.get("name").isJsonPrimitive()
                && params.getAsJsonPrimitive("name").isString()
                && (!params.has("arguments") || params.get("arguments").isJsonObject())
                && validLegacyRequestMeta(params);
    }

    private static boolean validLegacyInitializedRequest(JsonObject request) {
        if (!Set.of("jsonrpc", "method", "params").containsAll(request.keySet())
                || !request.has("jsonrpc") || !request.has("method") || request.has("id")) {
            return false;
        }
        return !request.has("params")
                || request.get("params").isJsonObject()
                        && request.getAsJsonObject("params").size() == 0;
    }

    private static JsonObject legacyToolResult(JsonObject source) {
        JsonObject result = new JsonObject();
        result.add("content", source.getAsJsonArray("content").deepCopy());
        if (source.has("structuredContent")) {
            result.add("structuredContent", source.getAsJsonObject("structuredContent").deepCopy());
        }
        result.addProperty("isError", source.get("isError").getAsBoolean());
        return result;
    }

    private static boolean validLegacyRequestMeta(JsonObject params) {
        if (!params.has("_meta")) {
            return true;
        }
        if (!params.get("_meta").isJsonObject()) {
            return false;
        }
        JsonObject meta = params.getAsJsonObject("_meta");
        // Codex attaches correlation data to calls as well as progress tokens.
        // Accept the known envelope, but never pass it to the runtime or treat
        // client-reported sandbox/approval fields as execution authority.
        return Set.of("progressToken", "callId", "itemId", "threadId", "x-codex-turn-metadata")
                        .containsAll(meta.keySet())
                && (!meta.has("progressToken") || validRequestId(meta.get("progressToken")))
                && (!meta.has("callId") || validBoundedString(meta.get("callId")))
                && (!meta.has("itemId") || validBoundedString(meta.get("itemId")))
                && (!meta.has("threadId") || validBoundedString(meta.get("threadId")))
                && (!meta.has("x-codex-turn-metadata")
                        || meta.get("x-codex-turn-metadata").isJsonObject());
    }

    private static boolean validBoundedString(JsonElement value) {
        String string = stringValue(value);
        return string != null && !string.isBlank() && string.length() <= 128;
    }

    private boolean validAuthorization(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return false;
        }
        byte[] supplied = authorization.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(config.bearerToken(), supplied);
    }

    static boolean allowsConformanceAuthenticationBypass(
            boolean productionEnvironment, boolean propertyRequested) {
        return !productionEnvironment && propertyRequested;
    }

    private static boolean validHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String hostname = host;
        int colon = host.lastIndexOf(':');
        if (colon >= 0) {
            if (host.indexOf(':') != colon || colon == host.length() - 1) {
                return false;
            }
            String port = host.substring(colon + 1);
            if (!port.chars().allMatch(Character::isDigit)) {
                return false;
            }
            try {
                int value = Integer.parseInt(port);
                if (value < 1 || value > 65_535) {
                    return false;
                }
            } catch (NumberFormatException failure) {
                return false;
            }
            hostname = host.substring(0, colon);
        }
        return "127.0.0.1".equals(hostname) || "localhost".equalsIgnoreCase(hostname);
    }

    private static boolean validJsonContentType(String contentType) {
        if (contentType == null) {
            return false;
        }
        String[] parts = contentType.split(";", -1);
        if (!"application/json".equalsIgnoreCase(parts[0].strip())) {
            return false;
        }
        for (int index = 1; index < parts.length; index++) {
            String parameter = parts[index].strip();
            if (parameter.regionMatches(true, 0, "charset=", 0, 8)
                    && !Set.of("utf-8", "\"utf-8\"").contains(
                    parameter.substring(8).strip().toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    private static boolean acceptsMcpMediaTypes(String accept) {
        if (accept == null) {
            return false;
        }
        boolean json = false;
        boolean eventStream = false;
        for (String entry : accept.split(",")) {
            String[] parts = entry.split(";");
            boolean accepted = true;
            for (int index = 1; index < parts.length; index++) {
                String parameter = parts[index].strip();
                if (parameter.regionMatches(true, 0, "q=", 0, 2)) {
                    try {
                        double quality = Double.parseDouble(parameter.substring(2).strip());
                        accepted = Double.isFinite(quality) && quality > 0 && quality <= 1;
                    } catch (NumberFormatException failure) {
                        accepted = false;
                    }
                }
            }
            if (!accepted) {
                continue;
            }
            String type = parts[0].strip().toLowerCase(Locale.ROOT);
            json |= "application/json".equals(type) || "*/*".equals(type);
            eventStream |= "text/event-stream".equals(type) || "*/*".equals(type);
        }
        return json && eventStream;
    }

    private static boolean acceptsMediaType(String accept, String requiredType) {
        if (accept == null) {
            return false;
        }
        for (String entry : accept.split(",")) {
            String[] parts = entry.split(";");
            boolean accepted = true;
            for (int index = 1; index < parts.length; index++) {
                String parameter = parts[index].strip();
                if (parameter.regionMatches(true, 0, "q=", 0, 2)) {
                    try {
                        double quality = Double.parseDouble(parameter.substring(2).strip());
                        accepted = Double.isFinite(quality) && quality > 0 && quality <= 1;
                    } catch (NumberFormatException failure) {
                        accepted = false;
                    }
                }
            }
            String type = parts[0].strip().toLowerCase(Locale.ROOT);
            if (accepted && (requiredType.equals(type) || "*/*".equals(type))) {
                return true;
            }
        }
        return false;
    }

    private static boolean validClientMetadata(JsonObject meta) {
        JsonElement capabilities = meta.get(CLIENT_CAPABILITIES_META);
        if (capabilities == null || !capabilities.isJsonObject()) {
            return false;
        }
        JsonElement info = meta.get(CLIENT_INFO_META);
        if (info == null) {
            return true;
        }
        if (!info.isJsonObject()) {
            return false;
        }
        JsonObject object = info.getAsJsonObject();
        String name = stringValue(object.get("name"));
        String version = stringValue(object.get("version"));
        return name != null && !name.isBlank() && name.length() <= 128
                && version != null && !version.isBlank() && version.length() <= 128;
    }

    private static boolean validRequestObject(JsonObject request) {
        if (!REQUEST_KEYS.equals(request.keySet())
                || !request.has("jsonrpc") || !request.get("jsonrpc").isJsonPrimitive()
                || !request.getAsJsonPrimitive("jsonrpc").isString()
                || !"2.0".equals(request.get("jsonrpc").getAsString())
                || !validRequestId(request.get("id"))
                || !request.has("method") || !request.get("method").isJsonPrimitive()
                || !request.getAsJsonPrimitive("method").isString()
                || !request.has("params") || !request.get("params").isJsonObject()) {
            return false;
        }
        String method = request.get("method").getAsString();
        return !method.isBlank() && method.length() <= 128;
    }

    private static boolean validRequestId(JsonElement id) {
        if (id == null || id.isJsonNull() || !id.isJsonPrimitive()) {
            return false;
        }
        return id.getAsJsonPrimitive().isString() || id.getAsJsonPrimitive().isNumber();
    }

    private static String stringValue(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    private static String decodeMcpName(String value) {
        if (value == null) {
            return null;
        }
        if (!value.startsWith("=?base64?") || !value.endsWith("?=")) {
            return value;
        }
        byte[] decoded = Base64.getDecoder().decode(value.substring(9, value.length() - 2));
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(decoded))
                    .toString();
        } catch (java.nio.charset.CharacterCodingException failure) {
            throw new IllegalArgumentException("Invalid encoded Mcp-Name", failure);
        }
    }

    private static byte[] readBody(HttpExchange exchange, int maximum) throws IOException, BodyTooLargeException {
        String contentLength = singleHeader(exchange.getRequestHeaders(), "Content-Length");
        if (contentLength != null) {
            try {
                if (Long.parseLong(contentLength) > maximum) {
                    throw new BodyTooLargeException();
                }
            } catch (NumberFormatException failure) {
                throw new IOException("Invalid Content-Length", failure);
            }
        }
        try (var input = exchange.getRequestBody(); var output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8_192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > maximum) {
                    throw new BodyTooLargeException();
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String singleHeader(Headers headers, String name) {
        List<String> values = headers.get(name);
        return values != null && values.size() == 1 ? values.getFirst() : null;
    }

    private static boolean headersWithinLimits(Headers headers) {
        int count = 0;
        int characters = 0;
        for (var entry : headers.entrySet()) {
            for (String value : entry.getValue()) {
                if (++count > MAX_HEADER_COUNT) {
                    return false;
                }
                characters += entry.getKey().length() + value.length();
                if (characters > MAX_HEADER_CHARS) {
                    return false;
                }
            }
        }
        return true;
    }

    private static JsonObject jsonRpcResult(JsonElement id, JsonObject result) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id.deepCopy());
        response.add("result", result);
        return response;
    }

    private static JsonObject jsonRpcError(
            JsonElement id, int numericCode, String message, String dataCode) {
        JsonObject data = new JsonObject();
        data.addProperty("code", dataCode);
        return jsonRpcError(id, numericCode, message, data);
    }

    private static JsonObject headerMismatch(JsonElement id) {
        return jsonRpcError(id, HEADER_MISMATCH, "Header mismatch", "HeaderMismatch");
    }

    private static JsonObject unsupportedProtocolVersion(JsonElement id, String requested) {
        JsonObject data = new JsonObject();
        var supported = new com.google.gson.JsonArray();
        supported.add(PROTOCOL_VERSION);
        data.add("supported", supported);
        data.addProperty("requested", requested);
        return jsonRpcError(
                id, UNSUPPORTED_PROTOCOL_VERSION, "Unsupported protocol version", data);
    }

    private static JsonObject unsupportedLegacyProtocolVersion(JsonElement id, String requested) {
        JsonObject data = new JsonObject();
        var supported = new com.google.gson.JsonArray();
        supported.add(CODEX_LEGACY_PROTOCOL_VERSION);
        data.add("supported", supported);
        data.addProperty("requested", requested);
        return jsonRpcError(
                id, UNSUPPORTED_PROTOCOL_VERSION, "Unsupported protocol version", data);
    }

    private static JsonObject jsonRpcError(
            JsonElement id, int numericCode, String message, JsonObject data) {
        JsonObject error = new JsonObject();
        error.addProperty("code", numericCode);
        error.addProperty("message", message);
        error.add("data", data);
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id == null ? JsonNull.INSTANCE : id.deepCopy());
        response.add("error", error);
        return response;
    }

    private static void rejectBusy(HttpExchange exchange, String code) throws IOException {
        exchange.getResponseHeaders().set("Retry-After", "1");
        sendHttpError(exchange, 429, code);
    }

    private static void sendHttpError(HttpExchange exchange, int status, String code) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("code", code);
        sendJson(exchange, status, body);
    }

    private static void sendJson(HttpExchange exchange, int status, JsonObject body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void sendEmpty(HttpExchange exchange, int status) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, -1);
    }

    public State state() {
        return state;
    }

    public int localPort() {
        HttpServer current = server;
        return state == State.RUNNING && current != null ? current.getAddress().getPort() : -1;
    }

    @Override
    public synchronized void close() throws McpHttpServerException {
        if (state == State.STOPPED) {
            return;
        }
        state = State.STOPPING;
        try {
            cleanup();
            state = State.STOPPED;
        } catch (RuntimeException failure) {
            state = State.FAILED;
            throw new McpHttpServerException("Failed to stop MCP endpoint", failure);
        }
    }

    private void cleanup() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (requestExecutor != null) {
            requestExecutor.shutdownNow();
            requestExecutor = null;
        }
        if (timeoutExecutor != null) {
            timeoutExecutor.shutdownNow();
            timeoutExecutor = null;
        }
    }

    public enum State {
        NEW,
        STARTING,
        RUNNING,
        STOPPING,
        STOPPED,
        FAILED
    }

    public static final class McpHttpServerException extends Exception {
        McpHttpServerException(String message) {
            super(message);
        }

        McpHttpServerException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class BodyTooLargeException extends Exception {
    }
}
