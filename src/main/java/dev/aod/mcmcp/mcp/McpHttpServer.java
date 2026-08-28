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
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

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
    private static final String SESSION_HEADER = "Mcp-Session-Id";
    static final int HEADER_MISMATCH = -32_020;
    static final int UNSUPPORTED_PROTOCOL_VERSION = -32_022;
    private static final String PROTOCOL_META = "io.modelcontextprotocol/protocolVersion";
    private static final String CLIENT_CAPABILITIES_META = "io.modelcontextprotocol/clientCapabilities";
    private static final String CLIENT_INFO_META = "io.modelcontextprotocol/clientInfo";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int MAX_HEADER_COUNT = 64;
    private static final int MAX_HEADER_CHARS = 16_384;
    private static final Set<String> REQUEST_KEYS = Set.of("jsonrpc", "id", "method", "params");

    private final McpHttpServerConfig config;
    private final McmcpToolRegistry tools;
    private final TokenBucketRateLimiter rateLimiter;
    private final Semaphore admission;
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
        rateLimiter = new TokenBucketRateLimiter(config.rateLimitBurst(), config.rateLimitPerSecond());
        admission = new Semaphore(config.maxConcurrentRequests());
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
            dispatch(exchange, headers, parsed);
        } finally {
            admission.release();
        }
    }

    private void dispatch(HttpExchange exchange, Headers headers, JsonElement parsed) throws IOException {
        if (!parsed.isJsonObject()) {
            sendJson(exchange, 400, jsonRpcError(JsonNull.INSTANCE, -32600,
                    "Invalid Request", parsed.isJsonArray() ? "BatchNotSupported" : "InvalidRequest"));
            return;
        }
        JsonObject request = parsed.getAsJsonObject();
        String candidateMethod = stringValue(request.get("method"));
        if (isCodexLegacyCandidate(headers, candidateMethod)) {
            dispatchCodexLegacy(exchange, headers, request);
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
            case "tools/call" -> callTool(exchange, id, params, headers);
            default -> sendJson(exchange, 404, jsonRpcError(id, -32601,
                    "Method not found", "MethodNotFound"));
        }
    }

    private void dispatchCodexLegacy(HttpExchange exchange, Headers headers, JsonObject request)
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
            case "tools/call" -> callToolCodexLegacy(exchange, id, request, headers);
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
        if (!validLegacyCallParams(params)) {
            sendJson(exchange, 400, jsonRpcError(id, -32602, "Invalid params", "InvalidToolCall"));
            return;
        }
        String name = params.get("name").getAsString();
        try {
            JsonObject arguments = params.has("arguments")
                    ? params.getAsJsonObject("arguments") : new JsonObject();
            var prepared = tools.prepareCall(name, arguments);
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

    private void callTool(HttpExchange exchange, JsonElement id, JsonObject params, Headers headers)
            throws IOException {
        if (!params.keySet().equals(Set.of("_meta", "name", "arguments"))
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
        try {
            var prepared = tools.prepareCall(name, params.getAsJsonObject("arguments"));
            try {
                sendJson(exchange, 200, jsonRpcResult(id, prepared.response()));
            } catch (IOException failure) {
                tools.abandonDelivery(prepared);
                throw failure;
            }
            tools.confirmDelivery(prepared);
        } catch (McmcpToolRegistry.UnknownToolException failure) {
            sendJson(exchange, 200, jsonRpcError(id, -32602, "Unknown tool", "UnknownTool"));
        }
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
        return Set.of("progressToken").containsAll(meta.keySet())
                && (!meta.has("progressToken") || validRequestId(meta.get("progressToken")));
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
