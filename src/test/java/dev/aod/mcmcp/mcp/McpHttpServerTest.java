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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
    void modernFormElicitationCarriesKillZoneApprovalWithoutMinecraftPrompt() throws Exception {
        var seen = new AtomicReference<RuntimeCallContext.ElicitationInput>();
        String binding = "sha256:" + "a".repeat(64);
        McpRuntimePort runtime = (command, context) -> {
            if (!command.toolName().equals("agent_start_action")) {
                return CompletableFuture.completedFuture(
                        McpRuntimePort.RuntimeReply.success(Map.of("schema_version", 1)));
            }
            seen.set(context.elicitationInput());
            if (context.elicitationInput().acceptedAndConfirmed()) {
                return CompletableFuture.completedFuture(McpRuntimePort.RuntimeReply.success(Map.of(
                        "schema_version", 1,
                        "action_id", "00000000-0000-4000-8000-000000000001",
                        "state", "queued",
                        "accepted_at", "2026-09-05T00:00:00Z")));
            }
            return CompletableFuture.completedFuture(McpRuntimePort.RuntimeReply.success(Map.of(
                    "schema_version", 1,
                    "state", "AWAITING_CONSENT",
                    "policy_binding_hash", binding,
                    "approval_request_state", "r_" + "b".repeat(22),
                    "approval_scope_summary",
                            "ディメンション minecraft:overworld、対象区域 X=2.000〜3.000 / "
                                    + "Y=60.000〜62.000 / Z=0.000〜1.000、待機位置 "
                                    + "X=0.100〜0.900 / Y=60.000〜61.900 / Z=0.100〜0.900",
                    "action_reserved", false,
                    "input_acquired", false)));
        };
        start(runtime, config("mrtr-kill-zone").rateLimit(100, 100).build());

        JsonObject arguments = JsonParser.parseString("""
                {"schema_version":1,"program":{"dsl_version":1,
                 "capabilities":["entity_attack"],"body":[{
                   "id":"operate","op":"operate_kill_zone",
                   "target_kill_zone_bounds":{"dimension":"minecraft:overworld",
                     "min":{"x":2,"y":60,"z":0},
                     "max":{"x":3,"y":62,"z":1}},
                   "entity_type_allowlist":["minecraft:armor_stand"],
                   "main_hand_item":"minecraft:iron_sword","consent_ref":null,
                   "max_attacks":12,"minimum_interval_ticks":10,
                   "max_operation_duration_ticks":3600}]},
                 "budget":{"max_duration_ms":180500,"max_ticks":3610,
                   "max_distance_blocks":0,"max_camera_degrees":0,"max_interactions":12,
                   "max_blocks_broken":0,"max_blocks_placed":0}}
                """).getAsJsonObject();
        JsonObject first = metaParams();
        JsonObject capabilities = first.getAsJsonObject("_meta")
                .getAsJsonObject("io.modelcontextprotocol/clientCapabilities");
        JsonObject elicitation = new JsonObject();
        elicitation.add("form", new JsonObject());
        capabilities.add("elicitation", elicitation);
        first.addProperty("name", "agent_start_action");
        first.add("arguments", arguments);

        HttpResponse<String> initial = send(request(
                "tools/call", "agent_start_action", first, McpTestFixtures.TOKEN));
        JsonObject required = json(initial).getAsJsonObject("result");
        assertThat(required.get("resultType").getAsString())
                .withFailMessage(initial.body()).isEqualTo("input_required");
        assertThat(required.getAsJsonObject("_meta").toString()).contains("mcmcp");
        assertThat(required.has("structuredContent")).isFalse();
        String requestState = required.get("requestState").getAsString();
        assertThat(requestState).startsWith("kz1.").doesNotContain(binding);
        JsonObject inputRequest = required.getAsJsonObject("inputRequests")
                .getAsJsonObject("kill_zone_operation_approval");
        assertThat(inputRequest.get("method").getAsString()).isEqualTo("elicitation/create");
        assertThat(inputRequest.getAsJsonObject("params").get("message").getAsString())
                .contains("特定個体の指定ではありません", "防具立て", "鉄の剣",
                        "上限12回", "最短間隔10tick", "最長180秒",
                        "ディメンション minecraft:overworld", "対象区域 X=2.000〜3.000",
                        "待機位置 X=0.100〜0.900")
                .doesNotContain("armor_stand");
        assertThat(seen.get().formSupported()).isTrue();
        assertThat(seen.get().responded()).isFalse();

        JsonObject retry = first.deepCopy();
        retry.addProperty("requestState", requestState);
        JsonObject content = new JsonObject();
        content.addProperty("approve", true);
        JsonObject approval = new JsonObject();
        approval.addProperty("action", "accept");
        approval.add("content", content);
        JsonObject responses = new JsonObject();
        responses.add("kill_zone_operation_approval", approval);
        retry.add("inputResponses", responses);

        JsonObject tampered = retry.deepCopy();
        tampered.addProperty(
                "requestState",
                requestState.substring(0, requestState.length() - 1)
                        + (requestState.endsWith("A") ? "B" : "A"));
        assertThat(send(requestWithId(
                2, "tools/call", "agent_start_action", tampered,
                McpTestFixtures.TOKEN)).statusCode()).isEqualTo(400);

        // SHA-256 leaves two unused bits in the last Base64 character. Java's decoder
        // accepts aliases, so construct one deterministically rather than relying on luck.
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        int lastIndex = alphabet.indexOf(requestState.charAt(requestState.length() - 1));
        String alias = requestState.substring(0, requestState.length() - 1)
                + alphabet.charAt(lastIndex | 1);
        assertThat(Base64.getUrlDecoder().decode(alias.substring(alias.lastIndexOf('.') + 1)))
                .isEqualTo(Base64.getUrlDecoder().decode(
                        requestState.substring(requestState.lastIndexOf('.') + 1)));
        for (String noncanonical : List.of(alias, requestState + "=")) {
            JsonObject invalidEncoding = retry.deepCopy();
            invalidEncoding.addProperty("requestState", noncanonical);
            assertThat(send(requestWithId(
                    20, "tools/call", "agent_start_action", invalidEncoding,
                    McpTestFixtures.TOKEN)).statusCode()).isEqualTo(400);
        }

        JsonObject capabilityRemoved = retry.deepCopy();
        capabilityRemoved.getAsJsonObject("_meta")
                .getAsJsonObject("io.modelcontextprotocol/clientCapabilities")
                .remove("elicitation");
        assertThat(send(requestWithId(
                3, "tools/call", "agent_start_action", capabilityRemoved,
                McpTestFixtures.TOKEN)).statusCode()).isEqualTo(400);

        HttpResponse<String> accepted = send(requestWithId(
                4, "tools/call", "agent_start_action", retry, McpTestFixtures.TOKEN));
        JsonObject completed = json(accepted).getAsJsonObject("result");
        assertThat(completed.get("resultType").getAsString()).isEqualTo("complete");
        assertThat(completed.getAsJsonObject("structuredContent").get("state").getAsString())
                .isEqualTo("queued");
        assertThat(seen.get().acceptedAndConfirmed()).isTrue();
        assertThat(seen.get().requestState()).isEqualTo("r_" + "b".repeat(22));
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
    void codexLegacyCallsAcceptCorrelationMetadataWithoutReflectingIt() throws Exception {
        start(defaultRuntime(), config("codex-correlation").rateLimit(100, 100).build());
        JsonObject meta = new JsonObject();
        meta.addProperty("progressToken", 7);
        meta.addProperty("callId", "private-call-marker");
        meta.addProperty("itemId", "private-item-marker");
        meta.addProperty("threadId", "private-thread-marker");
        JsonObject turn = new JsonObject();
        turn.addProperty("session_id", "private-session-marker");
        turn.addProperty("sandbox_mode", "danger-full-access");
        turn.addProperty("auto_review_enabled", false);
        meta.add("x-codex-turn-metadata", turn);
        JsonObject params = new JsonObject();
        params.addProperty("name", "agent_get_state");
        params.add("arguments", new JsonObject());
        params.add("_meta", meta);

        HttpResponse<String> response = send(codexLegacyPost(requestBody("tools/call", params),
                McpHttpServer.CODEX_LEGACY_PROTOCOL_VERSION, null, null, null));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).getAsJsonObject("result").get("isError").getAsBoolean()).isFalse();
        assertThat(response.body()).doesNotContain("private-", "danger-full-access", "auto_review_enabled");

        for (String field : List.of("callId", "itemId", "threadId", "x-codex-turn-metadata")) {
            JsonObject malformed = params.deepCopy();
            malformed.getAsJsonObject("_meta").addProperty(field, true);
            HttpResponse<String> rejected = send(codexLegacyPost(requestBody("tools/call", malformed),
                    McpHttpServer.CODEX_LEGACY_PROTOCOL_VERSION, null, null, null));
            assertThat(rejected.statusCode()).isEqualTo(400);
            assertThat(rejected.body()).contains("InvalidToolCall").doesNotContain(field);
        }
        JsonObject unknown = params.deepCopy();
        unknown.getAsJsonObject("_meta").addProperty("unknown-private-marker", "private-value");
        HttpResponse<String> rejected = send(codexLegacyPost(requestBody("tools/call", unknown),
                McpHttpServer.CODEX_LEGACY_PROTOCOL_VERSION, null, null, null));
        assertThat(rejected.statusCode()).isEqualTo(400);
        assertThat(rejected.body()).doesNotContain("private-");
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

    @Test
    void evaluationTurnControlIsAuthenticatedUnadvertisedAndStreamsUntilRelease() throws Exception {
        var runtime = new EvaluationRuntime();
        start(runtime, config("evaluation-control").rateLimit(100, 100).build());

        UUID leaseId = UUID.randomUUID();
        JsonObject acquireBody = new JsonObject();
        acquireBody.addProperty("lease_id", leaseId.toString());
        acquireBody.addProperty("runner_pid", ProcessHandle.current().pid());
        acquireBody.addProperty("max_duration_ms", 5_445_000);
        HttpRequest acquire = evaluationRequest("POST", acquireBody, McpTestFixtures.TOKEN, null);
        HttpResponse<java.io.InputStream> stream = client.send(
                acquire, HttpResponse.BodyHandlers.ofInputStream());

        assertThat(stream.statusCode()).isEqualTo(200);
        assertThat(stream.headers().firstValue("Mcmcp-Evaluation-Lease")).contains("active");
        var reader = new BufferedReader(new InputStreamReader(
                stream.body(), StandardCharsets.UTF_8));
        assertThat(reader.readLine()).isEqualTo(
                "{\"state\":\"active\",\"reason\":null,\"inputs_released\":false,"
                        + "\"input_owner_none\":true,\"all_actions_terminal\":true,"
                        + "\"process_identity_bound\":true}");

        assertThat(send(request(
                "server/discover", null, metaParams(), McpTestFixtures.TOKEN)).statusCode())
                .isEqualTo(409);
        assertThat(send(evaluationBoundRequest(
                "server/discover", null, metaParams(), UUID.randomUUID())).statusCode())
                .isEqualTo(409);
        assertThat(send(evaluationBoundRequest(
                "server/discover", null, metaParams(), leaseId)).statusCode())
                .isEqualTo(200);
        HttpResponse<String> activeToolCall = send(toolStateRequest(leaseId));
        assertThat(activeToolCall.statusCode()).isEqualTo(200);
        assertThat(json(activeToolCall).getAsJsonObject("result")
                .get("isError").getAsBoolean()).isFalse();

        JsonObject releaseBody = new JsonObject();
        releaseBody.addProperty("lease_id", leaseId.toString());
        releaseBody.addProperty("reason", "turn_completed");
        HttpResponse<String> released = send(evaluationRequest(
                "DELETE", releaseBody, McpTestFixtures.TOKEN, null));
        assertThat(released.statusCode()).isEqualTo(200);
        assertThat(released.body())
                .contains("\"state\":\"released\"")
                .contains("\"reason\":\"turn_completed\"")
                .contains("\"inputs_released\":true");

        String terminalLine;
        do {
            terminalLine = reader.readLine();
        } while (terminalLine != null && !terminalLine.contains("\"state\":\"released\""));
        assertThat(terminalLine).contains("turn_completed", "inputs_released");
        reader.close();

        HttpResponse<String> preflightToolCall = send(toolStateRequest(null));
        assertThat(preflightToolCall.statusCode()).isEqualTo(200);
        assertThat(json(preflightToolCall).getAsJsonObject("result")
                .get("isError").getAsBoolean()).isFalse();

        assertThat(send(evaluationRequest("DELETE", releaseBody, "wrong-token", null)).statusCode())
                .isEqualTo(401);
        assertThat(send(evaluationRequest(
                "DELETE", releaseBody, McpTestFixtures.TOKEN, "http://localhost")).statusCode())
                .isEqualTo(403);

        HttpResponse<String> list = send(request(
                "tools/list", null, metaParams(), McpTestFixtures.TOKEN));
        assertThat(json(list).getAsJsonObject("result").getAsJsonArray("tools")).hasSize(5);
        assertThat(list.body()).doesNotContain(
                "evaluation_turn", "evaluation_lease", McpHttpServer.EVALUATION_TURN_PATH);
    }

    @Test
    void evaluationTurnDurationAllowsTheFiniteTunnelProfileAndRejectsLargerValues() throws Exception {
        var runtime = new EvaluationRuntime();
        start(runtime, config("evaluation-duration-boundary").rateLimit(100, 100).build());

        for (long acceptedDuration : List.of(7_245_000L, 7_260_000L)) {
            UUID leaseId = UUID.randomUUID();
            JsonObject acquireBody = new JsonObject();
            acquireBody.addProperty("lease_id", leaseId.toString());
            acquireBody.addProperty("runner_pid", ProcessHandle.current().pid());
            acquireBody.addProperty("max_duration_ms", acceptedDuration);
            HttpResponse<java.io.InputStream> acquired = client.send(
                    evaluationRequest("POST", acquireBody, McpTestFixtures.TOKEN, null),
                    HttpResponse.BodyHandlers.ofInputStream());
            assertThat(acquired.statusCode()).isEqualTo(200);

            JsonObject releaseBody = new JsonObject();
            releaseBody.addProperty("lease_id", leaseId.toString());
            releaseBody.addProperty("reason", "turn_completed");
            assertThat(send(evaluationRequest(
                    "DELETE", releaseBody, McpTestFixtures.TOKEN, null)).statusCode()).isEqualTo(200);
            acquired.body().close();
        }

        JsonObject oversizedBody = new JsonObject();
        oversizedBody.addProperty("lease_id", UUID.randomUUID().toString());
        oversizedBody.addProperty("runner_pid", ProcessHandle.current().pid());
        oversizedBody.addProperty("max_duration_ms", 7_260_001L);
        assertThat(send(evaluationRequest(
                "POST", oversizedBody, McpTestFixtures.TOKEN, null)).statusCode()).isEqualTo(400);
    }

    @Test
    void evaluationAdmissionRaceIsRecheckedBeforeRuntimeSideEffects() throws Exception {
        var absenceRuntime = new EvaluationRuntime();
        start(absenceRuntime, config("evaluation-absence-race").rateLimit(100, 100).build());
        UUID acquiredDuringRequest = UUID.randomUUID();
        absenceRuntime.afterFenceSnapshot.set(() ->
                absenceRuntime.forceLease(acquiredDuringRequest));

        HttpResponse<String> absentRace = send(toolStateRequest(null));
        assertThat(absentRace.statusCode()).isEqualTo(200);
        assertThat(json(absentRace).getAsJsonObject("result")
                .get("isError").getAsBoolean()).isTrue();
        assertThat(absenceRuntime.sideEffects).hasValue(0);

        server.close();
        var activeRuntime = new EvaluationRuntime();
        UUID admittedLease = UUID.randomUUID();
        UUID replacementLease = UUID.randomUUID();
        activeRuntime.forceLease(admittedLease);
        activeRuntime.afterFenceSnapshot.set(() ->
                activeRuntime.forceLease(replacementLease));
        start(activeRuntime, config("evaluation-active-race").rateLimit(100, 100).build());

        HttpResponse<String> activeRace = send(toolStateRequest(admittedLease));
        assertThat(activeRace.statusCode()).isEqualTo(200);
        assertThat(json(activeRace).getAsJsonObject("result")
                .get("isError").getAsBoolean()).isTrue();
        assertThat(activeRuntime.sideEffects).hasValue(0);
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

    private URI evaluationEndpoint() {
        return URI.create("http://127.0.0.1:" + server.localPort()
                + McpHttpServer.EVALUATION_TURN_PATH);
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpRequest request(String method, String name, JsonObject params, String token) {
        return rawPost(requestBody(method, params), method, name, token,
                "application/json", "application/json, text/event-stream", McpHttpServer.PROTOCOL_VERSION);
    }

    private HttpRequest requestWithId(
            int id, String method, String name, JsonObject params, String token) {
        JsonObject body = new JsonObject();
        body.addProperty("jsonrpc", "2.0");
        body.addProperty("id", id);
        body.addProperty("method", method);
        body.add("params", params);
        return rawPost(body.toString(), method, name, token,
                "application/json", "application/json, text/event-stream",
                McpHttpServer.PROTOCOL_VERSION);
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

    private HttpRequest evaluationBoundRequest(
            String method, String name, JsonObject params, UUID leaseId) {
        String body = requestBody(method, params);
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint())
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + McpTestFixtures.TOKEN)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header(McpHttpServer.PROTOCOL_HEADER, McpHttpServer.PROTOCOL_VERSION)
                .header(McpHttpServer.METHOD_HEADER, method)
                .header(McpHttpServer.EVALUATION_LEASE_HEADER, leaseId.toString())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (name != null) {
            request.header(McpHttpServer.NAME_HEADER, name);
        }
        return request.build();
    }

    private HttpRequest toolStateRequest(UUID leaseId) {
        JsonObject params = metaParams();
        params.addProperty("name", "agent_get_state");
        params.add("arguments", new JsonObject());
        return leaseId == null
                ? request(
                        "tools/call",
                        "agent_get_state",
                        params,
                        McpTestFixtures.TOKEN)
                : evaluationBoundRequest(
                        "tools/call", "agent_get_state", params, leaseId);
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

    private HttpRequest evaluationRequest(
            String method, JsonObject body, String token, String origin) {
        HttpRequest.Builder request = HttpRequest.newBuilder(evaluationEndpoint())
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "POST".equals(method)
                        ? "application/x-ndjson" : "application/json");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (origin != null) {
            request.header("Origin", origin);
        }
        var publisher = HttpRequest.BodyPublishers.ofString(
                body.toString(), StandardCharsets.UTF_8);
        return switch (method) {
            case "POST" -> request.POST(publisher).build();
            case "DELETE" -> request.method("DELETE", publisher).build();
            default -> throw new IllegalArgumentException("unsupported test method");
        };
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

    private static final class EvaluationRuntime
            implements McpRuntimePort, EvaluationTurnControl {
        private final AtomicReference<UUID> active = new AtomicReference<>();
        private final AtomicReference<CompletableFuture<LeaseReceipt>> terminal =
                new AtomicReference<>();
        private final AtomicReference<Runnable> afterFenceSnapshot = new AtomicReference<>();
        private final AtomicInteger fenceRevision = new AtomicInteger();
        private final AtomicInteger sideEffects = new AtomicInteger();

        @Override
        public CompletableFuture<RuntimeReply> submit(
                RuntimeCommand command, RuntimeCallContext context) {
            if (!context.evaluationLeaseCurrent(this)) {
                return CompletableFuture.completedFuture(RuntimeReply.failure(
                        "unsafe_state", "evaluation lease changed", true));
            }
            sideEffects.incrementAndGet();
            return CompletableFuture.completedFuture(
                    RuntimeReply.success(McpTestFixtures.state()));
        }

        @Override
        public synchronized CompletableFuture<LeaseReceipt> acquire(AcquireRequest request) {
            if (!active.compareAndSet(null, request.leaseId())) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("evaluation lease busy"));
            }
            fenceRevision.incrementAndGet();
            terminal.set(new CompletableFuture<>());
            return CompletableFuture.completedFuture(new LeaseReceipt(
                    request.leaseId(), LeaseState.ACTIVE, null,
                    false, true, true, true));
        }

        @Override
        public CompletableFuture<LeaseReceipt> await(UUID leaseId) {
            if (!leaseId.equals(active.get()) || terminal.get() == null) {
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException("unknown evaluation lease"));
            }
            return terminal.get();
        }

        @Override
        public synchronized CompletableFuture<LeaseReceipt> release(UUID leaseId, ReleaseReason reason) {
            UUID retained = active.get();
            if (!leaseId.equals(retained) || !active.compareAndSet(retained, null)) {
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException("unknown evaluation lease"));
            }
            fenceRevision.incrementAndGet();
            var receipt = new LeaseReceipt(
                    leaseId, LeaseState.RELEASED, reason.wireName(),
                    true, true, true, true);
            terminal.getAndSet(null).complete(receipt);
            return CompletableFuture.completedFuture(receipt);
        }

        @Override
        public synchronized FenceSnapshot fenceSnapshot() {
            UUID accepted = active.get();
            var snapshot = new FenceSnapshot(
                    fenceRevision.get(), accepted != null, accepted);
            var hook = afterFenceSnapshot.getAndSet(null);
            if (hook != null) {
                hook.run();
            }
            return snapshot;
        }

        @Override
        public synchronized boolean active(UUID leaseId) {
            return leaseId != null && leaseId.equals(active.get());
        }

        @Override
        public synchronized boolean anyActive() {
            return active.get() != null;
        }

        private synchronized void forceLease(UUID leaseId) {
            active.set(leaseId);
            fenceRevision.incrementAndGet();
            terminal.set(new CompletableFuture<>());
        }
    }
}
