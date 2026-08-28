package dev.aod.mcmcp.mcp;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class McpHttpServerTest {
    @TempDir
    Path temporaryDirectory;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private McpHttpServer server;

    @AfterEach
    void closeServer() throws Exception {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void servesModernDiscoverListAndFixedToolCallWithoutSessions() throws Exception {
        start(defaultRuntime(), config("wire").rateLimit(100, 100).build());

        HttpResponse<String> discover = send(request(
                "server/discover", null, metaParams(), McpTestFixtures.TOKEN));
        assertThat(discover.statusCode()).isEqualTo(200);
        JsonObject discoverResult = json(discover).getAsJsonObject("result");
        assertThat(discoverResult.get("resultType").getAsString()).isEqualTo("complete");
        assertThat(discoverResult.getAsJsonArray("supportedVersions").get(0).getAsString())
                .isEqualTo(McpHttpServer.PROTOCOL_VERSION);
        assertThat(discoverResult.get("ttlMs").getAsInt()).isZero();
        assertThat(discoverResult.get("cacheScope").getAsString()).isEqualTo("private");

        HttpResponse<String> list = send(request(
                "tools/list", null, metaParams(), McpTestFixtures.TOKEN));
        JsonObject listResult = json(list).getAsJsonObject("result");
        assertThat(listResult.getAsJsonArray("tools").asList().stream()
                .map(tool -> tool.getAsJsonObject().get("name").getAsString()))
                .containsExactlyElementsOf(McpToolCatalog.REQUIRED_NAMES);
        assertThat(listResult.getAsJsonObject("_meta").toString()).contains("mcmcp", "0.1.0");

        JsonObject callParams = metaParams();
        callParams.addProperty("name", "agent_get_state");
        callParams.add("arguments", new JsonObject());
        String encodedName = "=?base64?" + Base64.getEncoder().encodeToString(
                "agent_get_state".getBytes(StandardCharsets.UTF_8)) + "?=";
        HttpResponse<String> call = send(request(
                "tools/call", encodedName, callParams, McpTestFixtures.TOKEN));
        JsonObject callResult = json(call).getAsJsonObject("result");
        assertThat(call.statusCode()).isEqualTo(200);
        assertThat(callResult.get("resultType").getAsString()).isEqualTo("complete");
        assertThat(callResult.get("isError").getAsBoolean()).isFalse();
        assertThat(callResult.has("structuredContent")).isTrue();
        assertRequiredStateNullsOnWire(call, callResult);
        assertThat(callResult.getAsJsonArray("content")).hasSize(1);
        assertThat(call.headers().firstValue("Content-Type").orElseThrow())
                .startsWith("application/json");
        assertThat(call.headers().firstValue("Mcp-Session-Id")).isEmpty();
        assertThat(call.body()).doesNotContain(McpTestFixtures.TOKEN);
    }

    @Test
    void servesCodex01461LegacyLifecycleAndFixedToolsWithoutSessions() throws Exception {
        start(defaultRuntime(), config("codex-legacy").rateLimit(100, 100).build());

        JsonObject initializeParams = new JsonObject();
        initializeParams.addProperty("protocolVersion", McpHttpServer.CODEX_LEGACY_PROTOCOL_VERSION);
        JsonObject clientCapabilities = new JsonObject();
        clientCapabilities.add("elicitation", new JsonObject());
        initializeParams.add("capabilities", clientCapabilities);
        JsonObject clientInfo = new JsonObject();
        clientInfo.addProperty("name", "codex-mcp-client");
        clientInfo.addProperty("title", "Codex");
        clientInfo.addProperty("version", "0.146.1");
        initializeParams.add("clientInfo", clientInfo);

        HttpResponse<String> initialize = send(codexLegacyPost(
                requestBody("initialize", initializeParams), null, null, null, null));
        assertThat(initialize.statusCode()).isEqualTo(200);
        JsonObject initializeResult = json(initialize).getAsJsonObject("result");
        assertThat(initializeResult.get("protocolVersion").getAsString())
                .isEqualTo(McpHttpServer.CODEX_LEGACY_PROTOCOL_VERSION);
        assertThat(initializeResult.getAsJsonObject("capabilities").keySet())
                .containsExactly("tools");
        assertThat(initializeResult.getAsJsonObject("capabilities")
                .getAsJsonObject("tools").get("listChanged").getAsBoolean()).isFalse();
        assertThat(initializeResult.getAsJsonObject("serverInfo").get("name").getAsString())
                .isEqualTo("mcmcp");
        assertThat(initialize.headers().firstValue("Mcp-Session-Id")).isEmpty();

        HttpResponse<String> initialized = send(codexLegacyPost(
                notificationBody("notifications/initialized"),
                McpHttpServer.CODEX_LEGACY_PROTOCOL_VERSION, null, null, null));
        assertThat(initialized.statusCode()).isEqualTo(202);
        assertThat(initialized.body()).isEmpty();
        assertThat(initialized.headers().firstValue("Mcp-Session-Id")).isEmpty();

        JsonObject listMeta = new JsonObject();
        listMeta.addProperty("progressToken", 1);
        JsonObject listParams = new JsonObject();
        listParams.add("_meta", listMeta);
        HttpResponse<String> list = send(codexLegacyPost(
                requestBody("tools/list", listParams),
                McpHttpServer.CODEX_LEGACY_PROTOCOL_VERSION, null, null, null));
        JsonObject listResult = json(list).getAsJsonObject("result");
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(listResult.keySet()).containsExactly("tools");
        assertThat(listResult.getAsJsonArray("tools").asList().stream()
                .map(tool -> tool.getAsJsonObject().get("name").getAsString()))
                .containsExactlyElementsOf(McpToolCatalog.REQUIRED_NAMES);

        JsonObject callMeta = new JsonObject();
        callMeta.addProperty("progressToken", "call-1");
        JsonObject callParams = new JsonObject();
        callParams.addProperty("name", "agent_get_state");
        callParams.add("arguments", new JsonObject());
        callParams.add("_meta", callMeta);
        HttpResponse<String> call = send(codexLegacyPost(
                requestBody("tools/call", callParams),
                McpHttpServer.CODEX_LEGACY_PROTOCOL_VERSION, null, null, null));
        JsonObject callResult = json(call).getAsJsonObject("result");
        assertThat(call.statusCode()).isEqualTo(200);
        assertThat(callResult.get("isError").getAsBoolean()).isFalse();
        assertThat(callResult.has("structuredContent")).isTrue();
        assertRequiredStateNullsOnWire(call, callResult);
        assertThat(callResult.has("resultType")).isFalse();
        assertThat(callResult.has("_meta")).isFalse();
        assertThat(call.headers().firstValue("Mcp-Session-Id")).isEmpty();
        assertThat(call.body()).doesNotContain(McpTestFixtures.TOKEN);

        JsonObject argumentlessCall = new JsonObject();
        argumentlessCall.addProperty("name", "agent_get_state");
        HttpResponse<String> argumentless = send(codexLegacyPost(
                requestBody("tools/call", argumentlessCall),
                McpHttpServer.CODEX_LEGACY_PROTOCOL_VERSION, null, null, null));
        assertThat(argumentless.statusCode()).isEqualTo(200);
        assertThat(json(argumentless).getAsJsonObject("result").get("isError").getAsBoolean())
                .isFalse();

        JsonObject initializedWithEmptyParams = new JsonObject();
        initializedWithEmptyParams.addProperty("jsonrpc", "2.0");
        initializedWithEmptyParams.addProperty("method", "notifications/initialized");
        initializedWithEmptyParams.add("params", new JsonObject());
        HttpResponse<String> initializedEmpty = send(codexLegacyPost(
                initializedWithEmptyParams.toString(),
                McpHttpServer.CODEX_LEGACY_PROTOCOL_VERSION, null, null, null));
        assertThat(initializedEmpty.statusCode()).isEqualTo(202);
        assertThat(initializedEmpty.body()).isEmpty();
    }

    @Test
    void codexLegacyPathRejectsNonCapturedHeadersMethodsAndParameterShapes() throws Exception {
        start(defaultRuntime(), config("codex-legacy-closed").rateLimit(100, 100).build());

        JsonObject initializeParams = new JsonObject();
        initializeParams.addProperty("protocolVersion", McpHttpServer.CODEX_LEGACY_PROTOCOL_VERSION);
        initializeParams.add("capabilities", new JsonObject());
        JsonObject clientInfo = new JsonObject();
        clientInfo.addProperty("name", "codex-mcp-client");
        clientInfo.addProperty("version", "0.146.1");
        initializeParams.add("clientInfo", clientInfo);

        HttpResponse<String> initializeWithCustomHeader = send(codexLegacyPost(
                requestBody("initialize", initializeParams), null, "initialize", null, null));
        assertThat(initializeWithCustomHeader.statusCode()).isEqualTo(400);
        assertThat(json(initializeWithCustomHeader).getAsJsonObject("error").get("code").getAsInt())
                .isEqualTo(McpHttpServer.HEADER_MISMATCH);

        JsonObject unexpectedInitializeParams = initializeParams.deepCopy();
        unexpectedInitializeParams.addProperty("unexpected", true);
        assertThat(send(codexLegacyPost(requestBody("initialize", unexpectedInitializeParams),
                null, null, null, null)).statusCode()).isEqualTo(400);

        JsonObject initializedWithParams = new JsonObject();
        initializedWithParams.addProperty("jsonrpc", "2.0");
        initializedWithParams.addProperty("method", "notifications/initialized");
        JsonObject nonEmptyNotificationParams = new JsonObject();
        nonEmptyNotificationParams.addProperty("unexpected", true);
        initializedWithParams.add("params", nonEmptyNotificationParams);
        assertThat(send(codexLegacyPost(initializedWithParams.toString(),
                McpHttpServer.CODEX_LEGACY_PROTOCOL_VERSION, null, null, null)).statusCode())
                .isEqualTo(400);

        JsonObject invalidListParams = new JsonObject();
        invalidListParams.addProperty("unexpected", true);
        assertThat(send(codexLegacyPost(requestBody("tools/list", invalidListParams),
                McpHttpServer.CODEX_LEGACY_PROTOCOL_VERSION, null, null, null)).statusCode())
                .isEqualTo(400);
        assertThat(send(codexLegacyPost(requestBody("tools/list", new JsonObject()),
                McpHttpServer.CODEX_LEGACY_PROTOCOL_VERSION, "tools/list", null, null)).statusCode())
                .isEqualTo(400);

        JsonObject callParams = new JsonObject();
        callParams.addProperty("name", "agent_get_state");
        callParams.add("arguments", new JsonObject());
        assertThat(send(codexLegacyPost(requestBody("tools/call", callParams),
                McpHttpServer.CODEX_LEGACY_PROTOCOL_VERSION, null, "agent_get_state", null)).statusCode())
                .isEqualTo(400);
        assertThat(send(codexLegacyPost(requestBody("tools/call", callParams),
                McpHttpServer.CODEX_LEGACY_PROTOCOL_VERSION, null, null, "forbidden-session")).statusCode())
                .isEqualTo(400);

        assertThat(send(codexLegacyPost(requestBody("resources/list", new JsonObject()),
                McpHttpServer.CODEX_LEGACY_PROTOCOL_VERSION, null, null, null)).statusCode())
                .isEqualTo(404);
    }

    @Test
    void enforcesLoopbackAuthenticationHeadersAndBoundedJson() throws Exception {
        start(defaultRuntime(), config("guards")
                .maxRequestBodyBytes(1_024)
                .maxJsonDepth(4)
                .rateLimit(100, 100)
                .build());

        HttpResponse<String> wrongToken = send(request(
                "server/discover", null, metaParams(), "wrong-token-012345678901234567890123456"));
        assertThat(wrongToken.statusCode()).isEqualTo(401);
        assertThat(wrongToken.body()).doesNotContain(McpTestFixtures.TOKEN);

        HttpRequest origin = HttpRequest.newBuilder(endpoint())
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + McpTestFixtures.TOKEN)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header(McpHttpServer.PROTOCOL_HEADER, McpHttpServer.PROTOCOL_VERSION)
                .header(McpHttpServer.METHOD_HEADER, "server/discover")
                .header("Origin", "http://localhost")
                .POST(HttpRequest.BodyPublishers.ofString(
                        requestBody("server/discover", metaParams()), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> originRejected = send(origin);
        assertThat(originRejected.statusCode()).isEqualTo(403);
        assertThat(originRejected.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();

        HttpRequest get = HttpRequest.newBuilder(endpoint()).GET().build();
        assertThat(send(get).statusCode()).isEqualTo(405);

        String oversized = "{\"padding\":\"" + "x".repeat(2_000) + "\"}";
        assertThat(send(rawPost(oversized, "server/discover", null,
                McpTestFixtures.TOKEN, "application/json", "application/json, text/event-stream",
                McpHttpServer.PROTOCOL_VERSION)).statusCode()).isEqualTo(413);

        assertThat(send(rawPost("[]", "tools/list", null,
                McpTestFixtures.TOKEN, "application/json", "application/json, text/event-stream",
                McpHttpServer.PROTOCOL_VERSION)).statusCode()).isEqualTo(400);

        HttpResponse<String> mediaRejected = send(rawPost("{}", "server/discover", null,
                McpTestFixtures.TOKEN, "text/plain", "application/json, text/event-stream",
                McpHttpServer.PROTOCOL_VERSION));
        assertThat(mediaRejected.statusCode()).isEqualTo(415);

        HttpResponse<String> mismatchedVersion = send(rawPost(
                requestBody("server/discover", metaParams()), "server/discover", null,
                McpTestFixtures.TOKEN, "application/json", "application/json, text/event-stream",
                "2025-11-25"));
        assertThat(mismatchedVersion.statusCode()).isEqualTo(400);
        assertThat(json(mismatchedVersion).getAsJsonObject("error").get("code").getAsInt())
                .isEqualTo(McpHttpServer.HEADER_MISMATCH);

        JsonObject unsupportedParams = metaParams("2099-01-01");
        HttpResponse<String> unsupportedVersion = send(rawPost(
                requestBody("server/discover", unsupportedParams), "server/discover", null,
                McpTestFixtures.TOKEN, "application/json", "application/json, text/event-stream",
                "2099-01-01"));
        JsonObject unsupportedError = json(unsupportedVersion).getAsJsonObject("error");
        assertThat(unsupportedVersion.statusCode()).isEqualTo(400);
        assertThat(unsupportedError.get("code").getAsInt())
                .isEqualTo(McpHttpServer.UNSUPPORTED_PROTOCOL_VERSION);
        assertThat(unsupportedError.getAsJsonObject("data").getAsJsonArray("supported")
                .get(0).getAsString()).isEqualTo(McpHttpServer.PROTOCOL_VERSION);
        assertThat(unsupportedError.getAsJsonObject("data").get("requested").getAsString())
                .isEqualTo("2099-01-01");

        assertThat(rawRequestWithHost("evil.example")).startsWith("HTTP/1.1 421");
        assertThat(rawRequestWithHost("localhost")).startsWith("HTTP/1.1 200");
    }

    @Test
    void conformanceAuthenticationBypassCannotActivateInProduction() {
        assertThat(McpHttpServer.allowsConformanceAuthenticationBypass(true, true)).isFalse();
        assertThat(McpHttpServer.allowsConformanceAuthenticationBypass(false, false)).isFalse();
        assertThat(McpHttpServer.allowsConformanceAuthenticationBypass(false, true)).isTrue();
    }

    @Test
    void developmentConformanceEndpointStillEnforcesEveryGuardExceptBearer() throws Exception {
        server = new McpHttpServer(
                config("conformance").rateLimit(100, 100).build(), defaultRuntime(), true);
        server.start();

        HttpResponse<String> unauthenticated = send(rawPost(
                requestBody("server/discover", metaParams()), "server/discover", null,
                null, "application/json", "application/json, text/event-stream",
                McpHttpServer.PROTOCOL_VERSION));
        assertThat(unauthenticated.statusCode()).isEqualTo(200);

        HttpRequest withOrigin = HttpRequest.newBuilder(endpoint())
                .timeout(Duration.ofSeconds(5))
                .header("Origin", "http://localhost")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header(McpHttpServer.PROTOCOL_HEADER, McpHttpServer.PROTOCOL_VERSION)
                .header(McpHttpServer.METHOD_HEADER, "server/discover")
                .POST(HttpRequest.BodyPublishers.ofString(
                        requestBody("server/discover", metaParams()), StandardCharsets.UTF_8))
                .build();
        assertThat(send(withOrigin).statusCode()).isEqualTo(403);

        server.close();
        server = new McpHttpServer(
                config("production-conformance").rateLimit(100, 100).build(),
                defaultRuntime(),
                McpHttpServer.allowsConformanceAuthenticationBypass(true, true));
        server.start();
        assertThat(send(rawPost(
                requestBody("server/discover", metaParams()), "server/discover", null,
                null, "application/json", "application/json, text/event-stream",
                McpHttpServer.PROTOCOL_VERSION)).statusCode()).isEqualTo(401);
    }

    @Test
    void limitsEveryAuthenticatedMcpRequestAndConcurrentDispatch() throws Exception {
        start(defaultRuntime(), config("rate")
                .rateLimit(1, 0.001)
                .build());
        assertThat(send(request("server/discover", null, metaParams(), McpTestFixtures.TOKEN))
                .statusCode()).isEqualTo(200);
        HttpResponse<String> limitedList = send(request(
                "tools/list", null, metaParams(), McpTestFixtures.TOKEN));
        assertThat(limitedList.statusCode()).isEqualTo(429);
        assertThat(limitedList.headers().firstValue("Retry-After")).contains("1");
        server.close();

        CountDownLatch enteredRuntime = new CountDownLatch(1);
        CompletableFuture<McpRuntimePort.RuntimeReply> pending = new CompletableFuture<>();
        start((command, context) -> {
            enteredRuntime.countDown();
            return pending;
        }, config("concurrency")
                .maxConcurrentRequests(1)
                .runtimeDispatchTimeout(Duration.ofSeconds(3))
                .rateLimit(100, 100)
                .build());

        JsonObject callParams = metaParams();
        callParams.addProperty("name", "agent_get_state");
        callParams.add("arguments", new JsonObject());
        HttpRequest call = request("tools/call", "agent_get_state", callParams, McpTestFixtures.TOKEN);
        CompletableFuture<HttpResponse<String>> first = client.sendAsync(
                call, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(enteredRuntime.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(send(call).statusCode()).isEqualTo(429);

        pending.complete(McpRuntimePort.RuntimeReply.success(McpTestFixtures.state()));
        assertThat(first.get(2, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
    }

    private void start(McpRuntimePort runtime, McpHttpServerConfig config) throws Exception {
        if (server != null && server.state() != McpHttpServer.State.STOPPED) {
            server.close();
        }
        server = new McpHttpServer(config, runtime);
        server.start();
    }

    private McpHttpServerConfig.Builder config(String name) {
        return McpHttpServerConfig.builder(temporaryDirectory.resolve(name), McpTestFixtures.TOKEN)
                .port(0);
    }

    private static McpRuntimePort defaultRuntime() {
        return (command, context) -> CompletableFuture.completedFuture(
                McpRuntimePort.RuntimeReply.success(McpTestFixtures.state()));
    }

    private URI endpoint() {
        return URI.create("http://127.0.0.1:" + server.localPort() + "/mcp");
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpRequest request(String method, String name, JsonObject params, String token) {
        return rawPost(requestBody(method, params), method, name, token,
                "application/json", "application/json, text/event-stream", McpHttpServer.PROTOCOL_VERSION);
    }

    private HttpRequest rawPost(
            String body, String method, String name, String token, String contentType,
            String accept, String protocolVersion) {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint())
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", contentType)
                .header("Accept", accept)
                .header(McpHttpServer.PROTOCOL_HEADER, protocolVersion)
                .header(McpHttpServer.METHOD_HEADER, method)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (name != null) {
            request.header(McpHttpServer.NAME_HEADER, name);
        }
        return request.build();
    }

    private HttpRequest codexLegacyPost(
            String body, String protocolVersion, String method, String name, String sessionId) {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint())
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + McpTestFixtures.TOKEN)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (protocolVersion != null) {
            request.header(McpHttpServer.PROTOCOL_HEADER, protocolVersion);
        }
        if (method != null) {
            request.header(McpHttpServer.METHOD_HEADER, method);
        }
        if (name != null) {
            request.header(McpHttpServer.NAME_HEADER, name);
        }
        if (sessionId != null) {
            request.header("Mcp-Session-Id", sessionId);
        }
        return request.build();
    }

    private static String requestBody(String method, JsonObject params) {
        JsonObject body = new JsonObject();
        body.addProperty("jsonrpc", "2.0");
        body.addProperty("id", 1);
        body.addProperty("method", method);
        body.add("params", params);
        return body.toString();
    }

    private static String notificationBody(String method) {
        JsonObject body = new JsonObject();
        body.addProperty("jsonrpc", "2.0");
        body.addProperty("method", method);
        return body.toString();
    }

    private static JsonObject metaParams() {
        return metaParams(McpHttpServer.PROTOCOL_VERSION);
    }

    private static JsonObject metaParams(String protocolVersion) {
        JsonObject meta = new JsonObject();
        meta.addProperty("io.modelcontextprotocol/protocolVersion", protocolVersion);
        meta.add("io.modelcontextprotocol/clientCapabilities", new JsonObject());
        JsonObject params = new JsonObject();
        params.add("_meta", meta);
        return params;
    }

    private static JsonObject json(HttpResponse<String> response) {
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static void assertRequiredStateNullsOnWire(
            HttpResponse<String> response, JsonObject callResult) {
        JsonObject state = callResult.getAsJsonObject("structuredContent");
        assertThat(state.getAsJsonObject("control").get("ready_expires_at"))
                .isEqualTo(JsonNull.INSTANCE);
        assertThat(state.get("action")).isEqualTo(JsonNull.INSTANCE);
        assertThat(response.body())
                .contains("\"ready_expires_at\":null")
                .contains("\"action\":null");
    }

    private String rawRequestWithHost(String host) throws Exception {
        byte[] body = requestBody("server/discover", metaParams()).getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket("127.0.0.1", server.localPort())) {
            socket.setSoTimeout(2_000);
            String headers = "POST /mcp HTTP/1.1\r\n"
                    + "Host: " + host + "\r\n"
                    + "Authorization: Bearer " + McpTestFixtures.TOKEN + "\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Accept: application/json, text/event-stream\r\n"
                    + McpHttpServer.PROTOCOL_HEADER + ": " + McpHttpServer.PROTOCOL_VERSION + "\r\n"
                    + McpHttpServer.METHOD_HEADER + ": server/discover\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(body);
            socket.getOutputStream().flush();
            return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
                    .readLine();
        }
    }
}
