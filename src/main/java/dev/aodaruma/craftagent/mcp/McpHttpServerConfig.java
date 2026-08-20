package dev.aodaruma.craftagent.mcp;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable configuration for the loopback-only MCP endpoint. */
public final class McpHttpServerConfig {
    private final int port;
    private final Path baseDirectory;
    private final String bearerToken;
    private final String serverName;
    private final String serverVersion;
    private final Duration runtimeDispatchTimeout;
    private final int maxRequestBodyBytes;
    private final int maxJsonDepth;
    private final int maxConcurrentRequests;
    private final int rateLimitBurst;
    private final double rateLimitPerSecond;
    private final List<String> allowedHosts;
    private final List<String> allowedOrigins;

    private McpHttpServerConfig(Builder builder) {
        port = builder.port;
        baseDirectory = builder.baseDirectory.toAbsolutePath().normalize();
        bearerToken = builder.bearerToken;
        serverName = builder.serverName;
        serverVersion = builder.serverVersion;
        runtimeDispatchTimeout = builder.runtimeDispatchTimeout;
        maxRequestBodyBytes = builder.maxRequestBodyBytes;
        maxJsonDepth = builder.maxJsonDepth;
        maxConcurrentRequests = builder.maxConcurrentRequests;
        rateLimitBurst = builder.rateLimitBurst;
        rateLimitPerSecond = builder.rateLimitPerSecond;
        allowedHosts = List.copyOf(builder.allowedHosts);
        allowedOrigins = List.copyOf(builder.allowedOrigins);
        validate();
    }

    public static Builder builder(Path baseDirectory, String bearerToken) {
        return new Builder(baseDirectory, bearerToken);
    }

    private void validate() {
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("port must be in 0..65535");
        }
        if (bearerToken.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("bearerToken must contain at least 32 UTF-8 bytes");
        }
        if (bearerToken.codePoints().anyMatch(codePoint -> Character.isWhitespace(codePoint)
                || Character.isISOControl(codePoint)
                || Character.getType(codePoint) == Character.FORMAT)) {
            throw new IllegalArgumentException("bearerToken must not contain whitespace or control characters");
        }
        if (serverName.isBlank() || serverVersion.isBlank()) {
            throw new IllegalArgumentException("serverName and serverVersion must not be blank");
        }
        if (runtimeDispatchTimeout.isZero() || runtimeDispatchTimeout.isNegative()) {
            throw new IllegalArgumentException("runtimeDispatchTimeout must be positive");
        }
        if (maxRequestBodyBytes < 1_024 || maxRequestBodyBytes > 8 * 1_024 * 1_024) {
            throw new IllegalArgumentException("maxRequestBodyBytes must be in 1024..8388608");
        }
        if (maxJsonDepth < 4 || maxJsonDepth > 128) {
            throw new IllegalArgumentException("maxJsonDepth must be in 4..128");
        }
        if (maxConcurrentRequests < 1 || maxConcurrentRequests > 64) {
            throw new IllegalArgumentException("maxConcurrentRequests must be in 1..64");
        }
        if (rateLimitBurst < 1 || rateLimitBurst > 10_000) {
            throw new IllegalArgumentException("rateLimitBurst must be in 1..10000");
        }
        if (!Double.isFinite(rateLimitPerSecond) || rateLimitPerSecond <= 0 || rateLimitPerSecond > 10_000) {
            throw new IllegalArgumentException("rateLimitPerSecond must be in (0,10000]");
        }
        if (allowedHosts.isEmpty()) {
            throw new IllegalArgumentException("At least one allowed Host is required");
        }
    }

    public int port() {
        return port;
    }

    public Path baseDirectory() {
        return baseDirectory;
    }

    String bearerToken() {
        return bearerToken;
    }

    public String serverName() {
        return serverName;
    }

    public String serverVersion() {
        return serverVersion;
    }

    public Duration runtimeDispatchTimeout() {
        return runtimeDispatchTimeout;
    }

    public int maxRequestBodyBytes() {
        return maxRequestBodyBytes;
    }

    public int maxJsonDepth() {
        return maxJsonDepth;
    }

    public int maxConcurrentRequests() {
        return maxConcurrentRequests;
    }

    public int rateLimitBurst() {
        return rateLimitBurst;
    }

    public double rateLimitPerSecond() {
        return rateLimitPerSecond;
    }

    public List<String> allowedHosts() {
        return allowedHosts;
    }

    public List<String> allowedOrigins() {
        return allowedOrigins;
    }

    @Override
    public String toString() {
        return "McpHttpServerConfig[port=" + port + ", baseDirectory=" + baseDirectory
                + ", bearerToken=<redacted>, serverName=" + serverName + ", serverVersion="
                + serverVersion + "]";
    }

    public static final class Builder {
        private int port = 8_765;
        private final Path baseDirectory;
        private final String bearerToken;
        private String serverName = "craftagent";
        private String serverVersion = "0.1.0";
        private Duration runtimeDispatchTimeout = Duration.ofSeconds(2);
        private int maxRequestBodyBytes = 1_048_576;
        private int maxJsonDepth = 32;
        private int maxConcurrentRequests = 4;
        private int rateLimitBurst = 30;
        private double rateLimitPerSecond = 15.0;
        private List<String> allowedHosts = new ArrayList<>(List.of("127.0.0.1:*", "localhost:*"));
        private List<String> allowedOrigins = new ArrayList<>(List.of(
                "http://127.0.0.1:*", "https://127.0.0.1:*",
                "http://localhost:*", "https://localhost:*"));

        private Builder(Path baseDirectory, String bearerToken) {
            this.baseDirectory = Objects.requireNonNull(baseDirectory, "baseDirectory");
            this.bearerToken = Objects.requireNonNull(bearerToken, "bearerToken");
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder serverInfo(String name, String version) {
            serverName = Objects.requireNonNull(name, "name");
            serverVersion = Objects.requireNonNull(version, "version");
            return this;
        }

        public Builder runtimeDispatchTimeout(Duration timeout) {
            runtimeDispatchTimeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        public Builder maxRequestBodyBytes(int bytes) {
            maxRequestBodyBytes = bytes;
            return this;
        }

        public Builder maxJsonDepth(int depth) {
            maxJsonDepth = depth;
            return this;
        }

        public Builder maxConcurrentRequests(int count) {
            maxConcurrentRequests = count;
            return this;
        }

        public Builder rateLimit(int burst, double requestsPerSecond) {
            rateLimitBurst = burst;
            rateLimitPerSecond = requestsPerSecond;
            return this;
        }

        public Builder allowedHosts(List<String> hosts) {
            allowedHosts = new ArrayList<>(Objects.requireNonNull(hosts, "hosts"));
            return this;
        }

        public Builder allowedOrigins(List<String> origins) {
            allowedOrigins = new ArrayList<>(Objects.requireNonNull(origins, "origins"));
            return this;
        }

        public McpHttpServerConfig build() {
            return new McpHttpServerConfig(this);
        }
    }
}
