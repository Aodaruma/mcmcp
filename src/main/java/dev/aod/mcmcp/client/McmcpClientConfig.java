package dev.aod.mcmcp.client;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Client-local endpoint and control settings stored in mcmcp-client.toml. */
public final class McmcpClientConfig {
    public static final int DEFAULT_HUD_OFFSET = 8;
    public static final int DEFAULT_PORT = 8_765;
    public static final int DEFAULT_MAX_REQUEST_BYTES = 65_536;
    public static final int DEFAULT_READY_TIMEOUT_SECONDS = 30;

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    private static final ModConfigSpec.BooleanValue ENDPOINT_ENABLED = BUILDER
            .comment("Start the loopback-only embedded MCP endpoint")
            .define("endpoint_enabled", true);
    private static final ModConfigSpec.IntValue PORT = BUILDER
            .comment("Loopback MCP endpoint port; a conflict disables the endpoint")
            .defineInRange("port", DEFAULT_PORT, 1, 65_535);
    private static final ModConfigSpec.IntValue MAX_REQUEST_BYTES = BUILDER
            .comment("Maximum MCP HTTP request body size in bytes")
            .defineInRange(
                    "max_request_bytes", DEFAULT_MAX_REQUEST_BYTES, 1_024, 65_536);
    private static final ModConfigSpec.IntValue READY_TIMEOUT_SECONDS = BUILDER
            .comment("Seconds that one local ON click accepts a single Action")
            .defineInRange(
                    "ready_timeout_seconds", DEFAULT_READY_TIMEOUT_SECONDS, 5, 300);
    private static final ModConfigSpec.IntValue HUD_OFFSET_X = BUILDER
            .comment("Horizontal pixel offset from the right edge for the MCMCP status UI")
            .defineInRange("hud_offset_x", DEFAULT_HUD_OFFSET, 0, 2048);
    private static final ModConfigSpec.IntValue HUD_OFFSET_Y = BUILDER
            .comment("Vertical pixel offset from the bottom edge for the MCMCP status UI")
            .defineInRange("hud_offset_y", DEFAULT_HUD_OFFSET, 0, 2048);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private McmcpClientConfig() { }

    public static boolean endpointEnabled() {
        return ENDPOINT_ENABLED.get();
    }

    public static int port() {
        return PORT.get();
    }

    public static int maxRequestBytes() {
        return MAX_REQUEST_BYTES.get();
    }

    public static int readyTimeoutSeconds() {
        return READY_TIMEOUT_SECONDS.get();
    }

    public static int hudOffsetX() {
        return HUD_OFFSET_X.get();
    }

    public static int hudOffsetY() {
        return HUD_OFFSET_Y.get();
    }
}
