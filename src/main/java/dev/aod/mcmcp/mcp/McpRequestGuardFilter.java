package dev.aod.mcmcp.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.ReadListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;

/**
 * Transport-level guards missing from the SDK's stateless Servlet implementation.
 */
public final class McpRequestGuardFilter implements Filter {
    static final String PROTOCOL_HEADER = "MCP-Protocol-Version";
    static final String PROTOCOL_VERSION = "2025-11-25";
    private static final String MULTIPLE_HEADER_VALUES = "\u0000";

    private final byte[] expectedBearerToken;
    private final List<String> allowedHosts;
    private final List<String> allowedOrigins;
    private final int maxRequestBodyBytes;
    private final int maxJsonDepth;
    private final Semaphore concurrentRequests;
    private final TokenBucketRateLimiter rateLimiter;
    private final McpJsonMapper jsonMapper;

    public McpRequestGuardFilter(McpHttpServerConfig config) {
        Objects.requireNonNull(config, "config");
        expectedBearerToken = config.bearerToken().getBytes(StandardCharsets.UTF_8);
        allowedHosts = lowercase(config.allowedHosts());
        allowedOrigins = lowercase(config.allowedOrigins());
        maxRequestBodyBytes = config.maxRequestBodyBytes();
        maxJsonDepth = config.maxJsonDepth();
        concurrentRequests = new Semaphore(config.maxConcurrentRequests(), true);
        rateLimiter = new TokenBucketRateLimiter(config.rateLimitBurst(), config.rateLimitPerSecond());
        jsonMapper = McpJsonDefaults.getMapper();
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException {
        if (!(servletRequest instanceof HttpServletRequest request)
                || !(servletResponse instanceof HttpServletResponse response)) {
            throw new ServletException("MCP endpoint only accepts HTTP requests");
        }

        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");

        String method = request.getMethod();
        if (!"GET".equals(method) && !"POST".equals(method)) {
            response.setHeader("Allow", "GET, POST");
            reject(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "method_not_allowed");
            return;
        }
        if (!validHost(singleHeader(request, "Host"))) {
            reject(response, 421, "invalid_host");
            return;
        }
        if (!validOrigin(singleHeader(request, "Origin"))) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "invalid_origin");
            return;
        }
        if (!validAuthorization(singleHeader(request, "Authorization"))) {
            response.setHeader("WWW-Authenticate", "Bearer realm=\"mcmcp\"");
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized");
            return;
        }
        if ("GET".equals(method)) {
            if (!acquireNormalRequest(response)) {
                return;
            }
            try {
                chain.doFilter(request, response);
            }
            finally {
                concurrentRequests.release();
            }
            return;
        }

        if (!validJsonContentType(request.getContentType())) {
            reject(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, "content_type_must_be_application_json");
            return;
        }
        if (!acceptsRequiredMediaTypes(singleHeader(request, "Accept"))) {
            reject(response, HttpServletResponse.SC_NOT_ACCEPTABLE, "accept_must_include_json_and_event_stream");
            return;
        }
        long contentLength = request.getContentLengthLong();
        if (contentLength > maxRequestBodyBytes) {
            reject(response, 413, "request_body_too_large");
            return;
        }

        CachedBodyRequest wrapped;
        try {
            wrapped = new CachedBodyRequest(request, maxRequestBodyBytes);
        }
        catch (RequestBodyTooLargeException ignored) {
            reject(response, 413, "request_body_too_large");
            return;
        }
        byte[] body = wrapped.body();
        if (!withinJsonDepth(body, maxJsonDepth)) {
            reject(response, HttpServletResponse.SC_BAD_REQUEST, "json_too_deep");
            return;
        }
        if (!validProtocolVersion(request, body)) {
            reject(response, HttpServletResponse.SC_BAD_REQUEST, "unsupported_or_missing_protocol_version");
            return;
        }

        boolean emergencyStop = isValidEmergencyStopCall(body);
        if (!emergencyStop && !acquireNormalRequest(response)) {
            return;
        }

        try {
            chain.doFilter(wrapped, response);
        }
        finally {
            if (!emergencyStop) {
                concurrentRequests.release();
            }
        }
    }

    private boolean acquireNormalRequest(HttpServletResponse response) throws IOException {
        if (!rateLimiter.tryAcquire()) {
            response.setHeader("Retry-After", "1");
            reject(response, 429, "rate_limited");
            return false;
        }
        if (!concurrentRequests.tryAcquire()) {
            response.setHeader("Retry-After", "1");
            reject(response, 429, "too_many_concurrent_requests");
            return false;
        }
        return true;
    }

    private boolean isValidEmergencyStopCall(byte[] body) {
        try {
            Map<String, Object> message = jsonMapper.readValue(body, new TypeRef<>() { });
            if (!"2.0".equals(message.get("jsonrpc"))
                    || !message.containsKey("id")
                    || message.get("id") == null
                    || !"tools/call".equals(message.get("method"))
                    || !(message.get("params") instanceof Map<?, ?> params)
                    || !"emergency_stop".equals(params.get("name"))
                    || !(params.get("arguments") instanceof Map<?, ?> arguments)
                    || arguments.size() != 1
                    || !(arguments.get("reason") instanceof String reason)) {
                return false;
            }
            return !reason.isBlank()
                    && reason.length() <= 160
                    && reason.codePoints().noneMatch(codePoint -> Character.isISOControl(codePoint)
                            || Character.getType(codePoint) == Character.FORMAT);
        }
        catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private boolean validProtocolVersion(HttpServletRequest request, byte[] body) {
        String version = singleHeader(request, PROTOCOL_HEADER);
        if (MULTIPLE_HEADER_VALUES.equals(version)) {
            return false;
        }
        if (version != null) {
            return PROTOCOL_VERSION.equals(version);
        }
        try {
            Map<String, Object> message = jsonMapper.readValue(body, new TypeRef<>() { });
            return "initialize".equals(message.get("method"));
        }
        catch (IOException | RuntimeException ignored) {
            // Leave malformed payload reporting to the MCP transport.
            return true;
        }
    }

    private boolean validAuthorization(String authorization) {
        if (authorization == null || authorization.length() < 8
                || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return false;
        }
        String supplied = authorization.substring(7);
        if (supplied.isEmpty() || !supplied.equals(supplied.strip())) {
            return false;
        }
        return MessageDigest.isEqual(expectedBearerToken, supplied.getBytes(StandardCharsets.UTF_8));
    }

    private boolean validHost(String host) {
        return host != null && matchesPattern(host.toLowerCase(Locale.ROOT), allowedHosts);
    }

    private boolean validOrigin(String origin) {
        if (MULTIPLE_HEADER_VALUES.equals(origin)) {
            return false;
        }
        if (origin == null) {
            return true;
        }
        if (origin.isBlank()) {
            return false;
        }
        return matchesPattern(origin.toLowerCase(Locale.ROOT), allowedOrigins);
    }

    private static boolean matchesPattern(String value, List<String> patterns) {
        for (String pattern : patterns) {
            if (pattern.equals(value)) {
                return true;
            }
            if (pattern.endsWith(":*")) {
                String base = pattern.substring(0, pattern.length() - 2);
                if (value.equals(base)) {
                    return true;
                }
                if (value.startsWith(base + ":")) {
                    String port = value.substring(base.length() + 1);
                    if (!port.isEmpty() && port.chars().allMatch(Character::isDigit)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean validJsonContentType(String contentType) {
        if (contentType == null) {
            return false;
        }
        String[] parts = contentType.split(";");
        if (!"application/json".equalsIgnoreCase(parts[0].strip())) {
            return false;
        }
        for (int index = 1; index < parts.length; index++) {
            String parameter = parts[index].strip();
            if (parameter.regionMatches(true, 0, "charset=", 0, 8)) {
                String charset = parameter.substring(8).replace("\"", "").strip();
                if (!"utf-8".equalsIgnoreCase(charset)) {
                    return false;
                }
            }
        }
        return true;
    }

    static boolean withinJsonDepth(byte[] body, int maximumDepth) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (byte current : body) {
            char value = (char) (current & 0xff);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (value == '\\') {
                    escaped = true;
                } else if (value == '"') {
                    inString = false;
                }
                continue;
            }
            if (value == '"') {
                inString = true;
            } else if (value == '{' || value == '[') {
                depth++;
                if (depth > maximumDepth) {
                    return false;
                }
            } else if ((value == '}' || value == ']') && depth > 0) {
                depth--;
            }
        }
        return true;
    }

    private static boolean acceptsRequiredMediaTypes(String accept) {
        if (accept == null) {
            return false;
        }
        boolean json = false;
        boolean eventStream = false;
        for (String entry : accept.split(",")) {
            String mediaType = entry.split(";", 2)[0].strip();
            json |= "application/json".equalsIgnoreCase(mediaType);
            eventStream |= "text/event-stream".equalsIgnoreCase(mediaType);
        }
        return json && eventStream;
    }

    private static String singleHeader(HttpServletRequest request, String name) {
        Enumeration<String> values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) {
            return null;
        }
        String value = values.nextElement();
        return values.hasMoreElements() ? MULTIPLE_HEADER_VALUES : value;
    }

    private static List<String> lowercase(List<String> values) {
        List<String> result = new ArrayList<>(values.size());
        for (String value : values) {
            result.add(Objects.requireNonNull(value, "allowlist entry").toLowerCase(Locale.ROOT));
        }
        return List.copyOf(result);
    }

    private static void reject(HttpServletResponse response, int status, String code) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        try (PrintWriter writer = response.getWriter()) {
            writer.write("{\"error\":\"");
            writer.write(code);
            writer.write("\"}");
        }
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, int maxBytes) throws IOException {
            super(request);
            try (var input = request.getInputStream(); var output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8_192];
                int total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > maxBytes) {
                        throw new RequestBodyTooLargeException();
                    }
                    output.write(buffer, 0, read);
                }
                body = output.toByteArray();
            }
        }

        byte[] body() {
            return body.clone();
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    Objects.requireNonNull(readListener, "readListener");
                    try {
                        if (!isFinished()) {
                            readListener.onDataAvailable();
                        }
                        if (isFinished()) {
                            readListener.onAllDataRead();
                        }
                    }
                    catch (IOException exception) {
                        readListener.onError(exception);
                    }
                }

                @Override
                public int read() {
                    return input.read();
                }

                @Override
                public int read(byte[] target, int offset, int length) {
                    return input.read(target, offset, length);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }

    private static final class RequestBodyTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;
    }
}
