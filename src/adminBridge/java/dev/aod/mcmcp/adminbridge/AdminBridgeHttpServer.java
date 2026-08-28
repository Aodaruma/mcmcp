package dev.aod.mcmcp.adminbridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Minimal authenticated loopback JSON API kept intentionally separate from MCP. */
final class AdminBridgeHttpServer implements AutoCloseable {
    private static final int MAX_BODY_BYTES = 8_192;
    private static final int MAX_HEADER_COUNT = 32;
    private static final int MAX_HEADER_CHARS = 8_192;
    private static final Set<String> VALIDATE_FIELDS = Set.of("fixture_id");
    private static final Set<String> APPLY_FIELDS = Set.of(
            "fixture_id", "fixture_sha256", "world_session_id");

    private final AdminBridgeApi service;
    private final byte[] bearerToken;
    private final int port;
    private final Semaphore admission = new Semaphore(1);
    private final AdminRateLimiter rateLimiter = new AdminRateLimiter(4, 2.0D);
    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;
    private java.util.concurrent.ScheduledExecutorService bodyTimeoutExecutor;

    AdminBridgeHttpServer(AdminBridgeApi service, String bearerToken, int port) {
        this.service = service;
        this.bearerToken = bearerToken.getBytes(StandardCharsets.UTF_8);
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("admin bridge port must be in 0..65535");
        }
        this.port = port;
    }

    synchronized void start() throws IOException {
        if (server != null) {
            throw new IllegalStateException("admin bridge already started");
        }
        InetAddress loopback = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        server = HttpServer.create(new InetSocketAddress(loopback, port), 8);
        executor = Executors.newFixedThreadPool(2, task ->
                Thread.ofPlatform().daemon(true).name("mcmcp-fixture-admin-http").unstarted(task));
        bodyTimeoutExecutor = Executors.newSingleThreadScheduledExecutor(task ->
                Thread.ofPlatform().daemon(true).name("mcmcp-fixture-admin-timeout").unstarted(task));
        server.createContext("/v1/status", this::handleStatus);
        server.createContext("/v1/fixtures/validate", this::handleValidate);
        server.createContext("/v1/fixtures/apply", this::handleApply);
        server.setExecutor(executor);
        server.start();
    }

    int localPort() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    private void handleStatus(HttpExchange exchange) {
        handle(exchange, "/v1/status", "GET", false, () -> statusJson(service.status()));
    }

    private void handleValidate(HttpExchange exchange) {
        handle(exchange, "/v1/fixtures/validate", "POST", true, () -> {
            JsonObject request = requestObject(exchange, VALIDATE_FIELDS);
            FixtureScript script = service.validate(string(request, "fixture_id"));
            JsonObject result = new JsonObject();
            result.addProperty("fixture_id", script.manifest().id());
            result.addProperty("fixture_sha256", script.sha256());
            result.addProperty("command_count", script.commands().size());
            result.addProperty("declared_changed_blocks", script.commands().stream()
                    .mapToLong(RestrictedCommandPolicy.ValidatedCommand::changedBlocks).sum());
            result.addProperty("random_tick_lease_requested",
                    script.manifest().randomTickLease() != null);
            return result;
        });
    }

    private void handleApply(HttpExchange exchange) {
        handle(exchange, "/v1/fixtures/apply", "POST", true, () -> {
            JsonObject request = requestObject(exchange, APPLY_FIELDS);
            var applied = service.apply(
                    string(request, "fixture_id"),
                    string(request, "fixture_sha256"),
                    string(request, "world_session_id"));
            JsonObject result = new JsonObject();
            result.addProperty("fixture_id", applied.fixtureId());
            result.addProperty("fixture_sha256", applied.fixtureSha256());
            result.addProperty("world_session_id", applied.worldSessionId());
            result.addProperty("commands_dispatched", applied.commandsDispatched());
            result.addProperty("declared_changed_blocks", applied.declaredChangedBlocks());
            result.add("random_tick_lease", leaseJson(applied.randomTickLease()));
            return result;
        });
    }

    private void handle(
            HttpExchange exchange,
            String requiredPath,
            String requiredMethod,
            boolean requiresJson,
            Operation operation) {
        try {
            if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()) {
                sendError(exchange, 403, "loopback_required");
                return;
            }
            if (!requiredPath.equals(exchange.getRequestURI().getRawPath())
                    || exchange.getRequestURI().getRawQuery() != null) {
                sendError(exchange, 404, "not_found");
                return;
            }
            if (!requiredMethod.equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", requiredMethod);
                sendError(exchange, 405, "method_not_allowed");
                return;
            }
            Headers headers = exchange.getRequestHeaders();
            if (!headersWithinLimits(headers)) {
                sendError(exchange, 431, "headers_too_large");
                return;
            }
            if (!validHost(singleHeader(headers, "Host"))) {
                sendError(exchange, 421, "host_invalid");
                return;
            }
            if (headers.get("Origin") != null) {
                sendError(exchange, 403, "origin_forbidden");
                return;
            }
            if (!validAuthorization(singleHeader(headers, "Authorization"))) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                sendError(exchange, 401, "unauthorized");
                return;
            }
            if (requiresJson && !validJsonContentType(singleHeader(headers, "Content-Type"))) {
                sendError(exchange, 415, "content_type_invalid");
                return;
            }
            if (!rateLimiter.tryAcquire()) {
                sendError(exchange, 429, "rate_limited");
                return;
            }
            if (!admission.tryAcquire()) {
                sendError(exchange, 429, "busy");
                return;
            }
            try {
                sendJson(exchange, 200, success(operation.run()));
            } finally {
                admission.release();
            }
        } catch (AdminBridgeException failure) {
            try {
                sendError(exchange, 409, failure.code());
            } catch (IOException ignored) {
                // Peer already closed.
            }
        } catch (RequestFailure failure) {
            try {
                sendError(exchange, failure.status, failure.code);
            } catch (IOException ignored) {
                // Peer already closed.
            }
        } catch (IOException ignored) {
            // Peer already closed.
        } catch (RuntimeException failure) {
            try {
                sendError(exchange, 500, "internal_error");
            } catch (IOException ignored) {
                // Peer already closed.
            }
        } finally {
            exchange.close();
        }
    }

    private JsonObject requestObject(HttpExchange exchange, Set<String> fields)
            throws IOException, RequestFailure {
        byte[] body = readBody(exchange, MAX_BODY_BYTES);
        String source;
        try {
            source = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(body)).toString();
        } catch (java.nio.charset.CharacterCodingException invalid) {
            throw new RequestFailure(400, "utf8_invalid");
        }
        if (!jsonNestingWithinLimit(source, 8)) {
            throw new RequestFailure(400, "json_depth_exceeded");
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(source);
        } catch (RuntimeException invalid) {
            throw new RequestFailure(400, "json_invalid");
        }
        if (!parsed.isJsonObject() || !parsed.getAsJsonObject().keySet().equals(fields)) {
            throw new RequestFailure(400, "request_fields_invalid");
        }
        return parsed.getAsJsonObject();
    }

    private static boolean jsonNestingWithinLimit(String source, int maximumDepth) {
        int depth = 0;
        boolean string = false;
        boolean escaped = false;
        for (int index = 0; index < source.length(); index++) {
            char value = source.charAt(index);
            if (string) {
                if (escaped) {
                    escaped = false;
                } else if (value == '\\') {
                    escaped = true;
                } else if (value == '"') {
                    string = false;
                }
                continue;
            }
            if (value == '"') {
                string = true;
            } else if (value == '{' || value == '[') {
                if (++depth > maximumDepth) {
                    return false;
                }
            } else if ((value == '}' || value == ']') && --depth < 0) {
                return false;
            }
        }
        return !string && depth == 0;
    }

    private static String string(JsonObject object, String field) throws RequestFailure {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new RequestFailure(400, "request_value_invalid");
        }
        String text = value.getAsString();
        if (text.isBlank() || text.length() > 128
                || text.codePoints().anyMatch(Character::isISOControl)) {
            throw new RequestFailure(400, "request_value_invalid");
        }
        return text;
    }

    private static JsonObject statusJson(AdminBridgeApi.Status status) {
        JsonObject result = new JsonObject();
        result.addProperty("state", status.state());
        result.addProperty("world_session_id", status.worldSessionId());
        JsonObject player = new JsonObject();
        player.addProperty("x", status.playerX());
        player.addProperty("y", status.playerY());
        player.addProperty("z", status.playerZ());
        result.add("player", player);
        JsonObject lease = new JsonObject();
        lease.addProperty("active", status.randomTickLease().active());
        if (status.randomTickLease().target() == null) {
            lease.add("target", JsonNull.INSTANCE);
        } else {
            lease.addProperty("target", status.randomTickLease().target());
        }
        lease.addProperty("remaining_seconds", status.randomTickLease().remainingSeconds());
        result.add("random_tick_lease", lease);
        return result;
    }

    private static JsonObject leaseJson(AdminBridgeApi.LeaseStatus snapshot) {
        JsonObject result = new JsonObject();
        result.addProperty("active", snapshot.active());
        if (snapshot.target() == null) {
            result.add("target", JsonNull.INSTANCE);
        } else {
            result.addProperty("target", snapshot.target());
        }
        result.addProperty("remaining_seconds", snapshot.remainingSeconds());
        return result;
    }

    private boolean validAuthorization(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return false;
        }
        byte[] supplied = authorization.substring(7).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(bearerToken, supplied);
    }

    private static boolean validHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String hostname = host;
        int colon = host.lastIndexOf(':');
        if (colon >= 0) {
            String port = host.substring(colon + 1);
            if (port.isBlank() || !port.chars().allMatch(Character::isDigit)) {
                return false;
            }
            try {
                int parsed = Integer.parseInt(port);
                if (parsed < 1 || parsed > 65_535) {
                    return false;
                }
            } catch (NumberFormatException invalid) {
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
            String parameter = parts[index].strip().toLowerCase(Locale.ROOT);
            if (parameter.startsWith("charset=")
                    && !Set.of("charset=utf-8", "charset=\"utf-8\"").contains(parameter)) {
                return false;
            }
        }
        return true;
    }

    private byte[] readBody(HttpExchange exchange, int maximum)
            throws IOException, RequestFailure {
        String length = singleHeader(exchange.getRequestHeaders(), "Content-Length");
        if (length != null) {
            try {
                long parsed = Long.parseLong(length);
                if (parsed < 0L || parsed > maximum) {
                    throw new RequestFailure(413, "body_too_large");
                }
            } catch (NumberFormatException invalid) {
                throw new RequestFailure(400, "content_length_invalid");
            }
        }
        var timeout = bodyTimeoutExecutor.schedule(exchange::close, 5L, TimeUnit.SECONDS);
        try (var input = exchange.getRequestBody(); var output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2_048];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > maximum) {
                    throw new RequestFailure(413, "body_too_large");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } finally {
            timeout.cancel(false);
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
                count++;
                characters += entry.getKey().length() + value.length();
                if (count > MAX_HEADER_COUNT || characters > MAX_HEADER_CHARS) {
                    return false;
                }
            }
        }
        return true;
    }

    private static JsonObject success(JsonObject result) {
        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        response.add("result", result);
        return response;
    }

    private static void sendError(HttpExchange exchange, int status, String code)
            throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("ok", false);
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        body.add("error", error);
        sendJson(exchange, status, body);
    }

    private static void sendJson(HttpExchange exchange, int status, JsonObject body)
            throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    @Override
    public synchronized void close() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        if (bodyTimeoutExecutor != null) {
            bodyTimeoutExecutor.shutdownNow();
            bodyTimeoutExecutor = null;
        }
    }

    @FunctionalInterface
    private interface Operation {
        JsonObject run() throws AdminBridgeException, IOException, RequestFailure;
    }

    private static final class RequestFailure extends Exception {
        private final int status;
        private final String code;

        private RequestFailure(int status, String code) {
            this.status = status;
            this.code = code;
        }
    }
}
