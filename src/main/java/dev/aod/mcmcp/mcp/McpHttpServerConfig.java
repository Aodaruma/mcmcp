package dev.aod.mcmcp.mcp;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/** Immutable limits for the loopback-only MCP endpoint. */
public final class McpHttpServerConfig {
    private final int port;
    private final Path baseDirectory;
    private final byte[] bearerToken;
    private final String serverName;
    private final String serverVersion;
    private final Duration runtimeDispatchTimeout;
    private final Duration ioTimeout;
    private final int maxRequestBodyBytes;
    private final int maxJsonDepth;
    private final int maxJsonStringChars;
    private final int maxJsonCollectionItems;
    private final int maxConcurrentRequests;
    private final int rateLimitBurst;
    private final double rateLimitPerSecond;

    private McpHttpServerConfig(Builder builder) {
        port = builder.port;
        baseDirectory = builder.baseDirectory.toAbsolutePath().normalize();
        bearerToken = builder.bearerToken.getBytes(StandardCharsets.UTF_8);
        serverName = builder.serverName;
        serverVersion = builder.serverVersion;
        runtimeDispatchTimeout = builder.runtimeDispatchTimeout;
        ioTimeout = builder.ioTimeout;
        maxRequestBodyBytes = builder.maxRequestBodyBytes;
        maxJsonDepth = builder.maxJsonDepth;
        maxJsonStringChars = builder.maxJsonStringChars;
        maxJsonCollectionItems = builder.maxJsonCollectionItems;
        maxConcurrentRequests = builder.maxConcurrentRequests;
        rateLimitBurst = builder.rateLimitBurst;
        rateLimitPerSecond = builder.rateLimitPerSecond;
        validate(builder.bearerToken);
    }

    public static Builder builder(Path baseDirectory, String bearerToken) {
        return new Builder(baseDirectory, bearerToken);
    }

    private void validate(String token) {
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("port must be in 0..65535");
        }
        if (bearerToken.length < 32) {
            throw new IllegalArgumentException("bearerToken must contain at least 32 UTF-8 bytes");
        }
        if (token.codePoints().anyMatch(codePoint -> Character.isWhitespace(codePoint)
                || Character.isISOControl(codePoint)
                || Character.getType(codePoint) == Character.FORMAT)) {
            throw new IllegalArgumentException("bearerToken must not contain whitespace or control characters");
        }
        if (serverName.isBlank() || serverVersion.isBlank()) {
            throw new IllegalArgumentException("serverName and serverVersion must not be blank");
        }
        requirePositive(runtimeDispatchTimeout, "runtimeDispatchTimeout");
        requirePositive(ioTimeout, "ioTimeout");
        if (runtimeDispatchTimeout.compareTo(Duration.ofSeconds(30)) > 0
                || ioTimeout.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new IllegalArgumentException("endpoint timeouts exceed their hard limits");
        }
        if (maxRequestBodyBytes < 1_024 || maxRequestBodyBytes > 65_536) {
            throw new IllegalArgumentException("maxRequestBodyBytes must be in 1024..65536");
        }
        if (maxJsonDepth < 4 || maxJsonDepth > 64) {
            throw new IllegalArgumentException("maxJsonDepth must be in 4..64");
        }
        if (maxJsonStringChars < 128 || maxJsonStringChars > 65_536) {
            throw new IllegalArgumentException("maxJsonStringChars must be in 128..65536");
        }
        if (maxJsonCollectionItems < 16 || maxJsonCollectionItems > 4_096) {
            throw new IllegalArgumentException("maxJsonCollectionItems must be in 16..4096");
        }
        if (maxConcurrentRequests < 1 || maxConcurrentRequests > 2) {
            throw new IllegalArgumentException("maxConcurrentRequests must be in 1..2");
        }
        if (rateLimitBurst < 1 || rateLimitBurst > 10_000
                || !Double.isFinite(rateLimitPerSecond)
                || rateLimitPerSecond <= 0 || rateLimitPerSecond > 10_000) {
            throw new IllegalArgumentException("invalid rate limit");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    public int port() { return port; }

    public Path baseDirectory() { return baseDirectory; }

    byte[] bearerToken() { return bearerToken.clone(); }

    public String serverName() { return serverName; }

    public String serverVersion() { return serverVersion; }

    public Duration runtimeDispatchTimeout() { return runtimeDispatchTimeout; }

    public Duration ioTimeout() { return ioTimeout; }

    public int maxRequestBodyBytes() { return maxRequestBodyBytes; }

    public int maxJsonDepth() { return maxJsonDepth; }

    public int maxJsonStringChars() { return maxJsonStringChars; }

    public int maxJsonCollectionItems() { return maxJsonCollectionItems; }

    public int maxConcurrentRequests() { return maxConcurrentRequests; }

    public int rateLimitBurst() { return rateLimitBurst; }

    public double rateLimitPerSecond() { return rateLimitPerSecond; }

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
        private String serverName = "mcmcp";
        private String serverVersion = "0.1.0";
        private Duration runtimeDispatchTimeout = Duration.ofSeconds(2);
        private Duration ioTimeout = Duration.ofSeconds(5);
        private int maxRequestBodyBytes = 65_536;
        private int maxJsonDepth = 32;
        private int maxJsonStringChars = 16_384;
        private int maxJsonCollectionItems = 1_024;
        private int maxConcurrentRequests = 2;
        private int rateLimitBurst = 40;
        private double rateLimitPerSecond = 20.0;

        private Builder(Path baseDirectory, String bearerToken) {
            this.baseDirectory = Objects.requireNonNull(baseDirectory, "baseDirectory");
            this.bearerToken = Objects.requireNonNull(bearerToken, "bearerToken");
        }

        public Builder port(int value) { port = value; return this; }

        public Builder serverInfo(String name, String version) {
            serverName = Objects.requireNonNull(name, "name");
            serverVersion = Objects.requireNonNull(version, "version");
            return this;
        }

        public Builder runtimeDispatchTimeout(Duration value) {
            runtimeDispatchTimeout = Objects.requireNonNull(value, "value");
            return this;
        }

        public Builder ioTimeout(Duration value) {
            ioTimeout = Objects.requireNonNull(value, "value");
            return this;
        }

        public Builder maxRequestBodyBytes(int value) { maxRequestBodyBytes = value; return this; }

        public Builder maxJsonDepth(int value) { maxJsonDepth = value; return this; }

        public Builder maxJsonStringChars(int value) { maxJsonStringChars = value; return this; }

        public Builder maxJsonCollectionItems(int value) {
            maxJsonCollectionItems = value;
            return this;
        }

        public Builder maxConcurrentRequests(int value) { maxConcurrentRequests = value; return this; }

        public Builder rateLimit(int burst, double requestsPerSecond) {
            rateLimitBurst = burst;
            rateLimitPerSecond = requestsPerSecond;
            return this;
        }

        public McpHttpServerConfig build() { return new McpHttpServerConfig(this); }
    }
}
