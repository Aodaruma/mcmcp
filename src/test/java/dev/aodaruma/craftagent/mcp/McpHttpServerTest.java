package dev.aodaruma.craftagent.mcp;

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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class McpHttpServerTest {
    private static final String INITIALIZE = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
              "protocolVersion":"2025-11-25","capabilities":{},
              "clientInfo":{"name":"black-box-test","version":"1"}}}
            """;
    private static final String INITIALIZED =
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";
    private static final String TOOLS_LIST =
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}";
    private static final String GET_STATUS = """
            {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
              "name":"get_status","arguments":{}}}
            """;

    @TempDir
    Path temporaryDirectory;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private McpHttpServer server;

    @AfterEach
    void stopServer() throws Exception {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void negotiatesStatelessProtocolAndServesToolsListAndCall() throws Exception {
        start("basic", defaultRuntime(), builder("basic").build());

        HttpResponse<String> initialize = send(INITIALIZE, McpTestFixtures.TOKEN,
                "application/json", "application/json, text/event-stream", null, false);
        assertThat(initialize.statusCode()).isEqualTo(200);
        assertThat(initialize.body())
                .contains("\"protocolVersion\":\"2025-11-25\"")
                .contains("\"tools\"");

        HttpResponse<String> initialized = send(INITIALIZED, McpTestFixtures.TOKEN,
                "application/json", "application/json, text/event-stream", null, true);
        assertThat(initialized.statusCode()).isEqualTo(202);

        HttpResponse<String> tools = send(TOOLS_LIST, McpTestFixtures.TOKEN,
                "application/json", "application/json, text/event-stream", null, true);
        assertThat(tools.statusCode()).isEqualTo(200);
        assertThat(tools.body())
                .contains("\"get_status\"")
                .contains("\"get_snapshot\"")
                .contains("\"compare_block_plan\"")
                .contains("\"emergency_stop\"")
                .contains("\"get_recipes\"")
                .contains("\"capture_creative_region\"")
                .contains("\"edit_creative_world\"")
                .contains("\"outputSchema\"");

        HttpResponse<String> call = send(GET_STATUS, McpTestFixtures.TOKEN,
                "application/json", "application/json, text/event-stream", null, true);
        assertThat(call.statusCode()).isEqualTo(200);
        assertThat(call.body())
                .contains("\"structuredContent\"")
                .contains("\"ok\":true")
                .contains("\"tool\":\"get_status\"")
                .contains("\"world_session_id\":\"session-test\"");

        HttpResponse<String> invalidCall = send(
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{"
                        + "\"name\":\"get_status\",\"arguments\":{\"unexpected\\nfield\":true}}}",
                McpTestFixtures.TOKEN,
                "application/json", "application/json, text/event-stream", null, true);
        assertThat(invalidCall.statusCode()).isEqualTo(200);
        assertThat(invalidCall.body())
                .contains("\"structuredContent\"")
                .contains("\"ok\":false")
                .contains("\"code\":\"invalid_argument\"")
                .doesNotContain("unexpected");

        assertThat(server.state()).isEqualTo(McpHttpServer.State.RUNNING);
        assertThat(server.localPort()).isPositive();
    }

    @Test
    void rejectsInvalidSecurityAndTransportHeadersBeforeSdkDispatch() throws Exception {
        start("guards", defaultRuntime(), builder("guards")
                .maxRequestBodyBytes(1_024)
                .maxJsonDepth(4)
                .build());

        assertThat(send(INITIALIZE, McpTestFixtures.TOKEN,
                "application/json", "application/json, text/event-stream", "http://localhost", false)
                .statusCode()).isEqualTo(200);
        assertThat(send(INITIALIZE, "wrong-token-that-is-long-enough-000000000000",
                "application/json", "application/json, text/event-stream", null, false).statusCode())
                .isEqualTo(401);
        assertThat(send(INITIALIZE, McpTestFixtures.TOKEN,
                "application/json", "application/json, text/event-stream", "https://evil.example", false)
                .statusCode()).isEqualTo(403);
        assertThat(send(INITIALIZE, McpTestFixtures.TOKEN,
                "text/plain", "application/json, text/event-stream", null, false).statusCode())
                .isEqualTo(415);
        assertThat(send(INITIALIZE, McpTestFixtures.TOKEN,
                "application/json", "application/json", null, false).statusCode())
                .isEqualTo(406);
        assertThat(send(TOOLS_LIST, McpTestFixtures.TOKEN,
                "application/json", "application/json, text/event-stream", null, false).statusCode())
                .isEqualTo(400);

        String oversized = "{\"padding\":\"" + "x".repeat(2_000) + "\"}";
        assertThat(send(oversized, McpTestFixtures.TOKEN,
                "application/json", "application/json, text/event-stream", null, true).statusCode())
                .isEqualTo(413);

        String tooDeep = "{\"a\":{\"b\":{\"c\":{\"d\":{\"e\":1}}}}}";
        HttpResponse<String> depthRejected = send(tooDeep, McpTestFixtures.TOKEN,
                "application/json", "application/json, text/event-stream", null, true);
        assertThat(depthRejected.statusCode()).isEqualTo(400);
        assertThat(depthRejected.body()).contains("json_too_deep");

        HttpRequest get = HttpRequest.newBuilder(endpoint())
                .header("Authorization", "Bearer " + McpTestFixtures.TOKEN)
                .GET()
                .build();
        assertThat(client.send(get, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(405);
        assertThat(rawRequestWithHost("localhost")).startsWith("HTTP/1.1 200");
        assertThat(rawRequestWithHost("evil.example")).startsWith("HTTP/1.1 421");
    }

    @Test
    void appliesTokenBucketRateLimit() throws Exception {
        start("rate", defaultRuntime(), builder("rate").rateLimit(1, 0.001).build());

        assertThat(send(INITIALIZE, McpTestFixtures.TOKEN,
                "application/json", "application/json, text/event-stream", null, false).statusCode())
                .isEqualTo(200);
        HttpResponse<String> limited = send(INITIALIZE, McpTestFixtures.TOKEN,
                "application/json", "application/json, text/event-stream", null, false);
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).contains("1");
    }

    @Test
    void rejectsConcurrentRequestWhileFirstRuntimeCallIsInFlight() throws Exception {
        CountDownLatch enteredRuntime = new CountDownLatch(1);
        CompletableFuture<McpRuntimePort.RuntimeReply> pending = new CompletableFuture<>();
        McpRuntimePort runtime = (command, context) -> {
            enteredRuntime.countDown();
            return pending;
        };
        McpHttpServerConfig config = builder("concurrency")
                .maxConcurrentRequests(1)
                .runtimeDispatchTimeout(Duration.ofSeconds(3))
                .rateLimit(100, 100)
                .build();
        start("concurrency", runtime, config);

        CompletableFuture<HttpResponse<String>> first = client.sendAsync(
                request(GET_STATUS, McpTestFixtures.TOKEN, "application/json",
                        "application/json, text/event-stream", null, true),
                HttpResponse.BodyHandlers.ofString());
        assertThat(enteredRuntime.await(2, TimeUnit.SECONDS)).isTrue();

        HttpResponse<String> second = send(GET_STATUS, McpTestFixtures.TOKEN,
                "application/json", "application/json, text/event-stream", null, true);
        assertThat(second.statusCode()).isEqualTo(429);
        assertThat(second.body()).contains("too_many_concurrent_requests");

        pending.complete(McpRuntimePort.RuntimeReply.success(McpTestFixtures.statusData()));
        assertThat(first.get(2, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
    }

    @Test
    void validEmergencyStopsBypassSaturatedAdmissionAndCoalesceWithout429() throws Exception {
        CountDownLatch enteredRuntime = new CountDownLatch(1);
        CountDownLatch stopsEnteredRuntime = new CountDownLatch(2);
        CompletableFuture<McpRuntimePort.RuntimeReply> pendingRead = new CompletableFuture<>();
        CompletableFuture<McpRuntimePort.RuntimeReply> pendingStop = new CompletableFuture<>();
        AtomicInteger stopCalls = new AtomicInteger();
        McpRuntimePort runtime = (command, context) -> {
            if (command instanceof McpRuntimePort.EmergencyStop) {
                stopCalls.incrementAndGet();
                stopsEnteredRuntime.countDown();
                return pendingStop;
            }
            enteredRuntime.countDown();
            return pendingRead;
        };
        McpHttpServerConfig config = builder("emergency-reserve")
                .maxConcurrentRequests(1)
                .runtimeDispatchTimeout(Duration.ofSeconds(3))
                .rateLimit(1, 0.001)
                .build();
        start("emergency-reserve", runtime, config);

        CompletableFuture<HttpResponse<String>> first = client.sendAsync(
                request(GET_STATUS, McpTestFixtures.TOKEN, "application/json",
                        "application/json, text/event-stream", null, true),
                HttpResponse.BodyHandlers.ofString());
        assertThat(enteredRuntime.await(2, TimeUnit.SECONDS)).isTrue();

        String invalidStop = """
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{
                  "name":"emergency_stop","arguments":{}}}
                """;
        HttpResponse<String> invalid = send(invalidStop, McpTestFixtures.TOKEN,
                "application/json", "application/json, text/event-stream", null, true);
        assertThat(invalid.statusCode()).isEqualTo(429);

        String firstEmergencyStop = """
                {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{
                  "name":"emergency_stop","arguments":{"reason":"first saturated stop"}}}
                """;
        String secondEmergencyStop = """
                {"jsonrpc":"2.0","id":6,"method":"tools/call","params":{
                  "name":"emergency_stop","arguments":{"reason":"second saturated stop"}}}
                """;
        CompletableFuture<HttpResponse<String>> firstStop = client.sendAsync(
                request(firstEmergencyStop, McpTestFixtures.TOKEN, "application/json",
                        "application/json, text/event-stream", null, true),
                HttpResponse.BodyHandlers.ofString());
        CompletableFuture<HttpResponse<String>> secondStop = client.sendAsync(
                request(secondEmergencyStop, McpTestFixtures.TOKEN, "application/json",
                        "application/json, text/event-stream", null, true),
                HttpResponse.BodyHandlers.ofString());
        assertThat(stopsEnteredRuntime.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(stopCalls).hasValue(2);

        pendingStop.complete(McpRuntimePort.RuntimeReply.success(Map.of(
                "stop_requested", true,
                "locked", true,
                "released_inputs", true,
                "discarded_pending_starts", 1)));
        assertThat(firstStop.get(2, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        assertThat(secondStop.get(2, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        assertThat(firstStop.get().body())
                .contains("\"ok\":true")
                .contains("\"tool\":\"emergency_stop\"")
                .contains("\"released_inputs\":true");
        assertThat(secondStop.get().body())
                .contains("\"ok\":true")
                .contains("\"tool\":\"emergency_stop\"")
                .contains("\"released_inputs\":true");

        pendingRead.complete(McpRuntimePort.RuntimeReply.success(McpTestFixtures.statusData()));
        assertThat(first.get(2, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
    }

    private McpHttpServerConfig.Builder builder(String name) {
        return McpHttpServerConfig.builder(temporaryDirectory.resolve(name), McpTestFixtures.TOKEN)
                .port(0)
                .rateLimit(100, 100);
    }

    private void start(String name, McpRuntimePort runtime, McpHttpServerConfig config) throws Exception {
        server = new McpHttpServer(config, runtime);
        server.start();
    }

    private static McpRuntimePort defaultRuntime() {
        return (command, context) -> CompletableFuture.completedFuture(
                McpRuntimePort.RuntimeReply.success(McpTestFixtures.statusData()));
    }

    private URI endpoint() {
        return URI.create("http://127.0.0.1:" + server.localPort() + "/mcp");
    }

    private HttpResponse<String> send(
            String body,
            String token,
            String contentType,
            String accept,
            String origin,
            boolean protocolHeader) throws Exception {
        return client.send(
                request(body, token, contentType, accept, origin, protocolHeader),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpRequest request(
            String body,
            String token,
            String contentType,
            String accept,
            String origin,
            boolean protocolHeader) {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint())
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", contentType)
                .header("Accept", accept)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (origin != null) {
            request.header("Origin", origin);
        }
        if (protocolHeader) {
            request.header(McpRequestGuardFilter.PROTOCOL_HEADER, McpRequestGuardFilter.PROTOCOL_VERSION);
        }
        return request.build();
    }

    private String rawRequestWithHost(String host) throws Exception {
        byte[] body = INITIALIZE.getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket("127.0.0.1", server.localPort())) {
            socket.setSoTimeout(2_000);
            String headers = "POST /mcp HTTP/1.1\r\n"
                    + "Host: " + host + "\r\n"
                    + "Authorization: Bearer " + McpTestFixtures.TOKEN + "\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Accept: application/json, text/event-stream\r\n"
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
