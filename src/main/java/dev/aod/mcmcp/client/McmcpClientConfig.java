package dev.aod.mcmcp.client;

import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Client-local endpoint and control settings stored in mcmcp-client.toml. */
public final class McmcpClientConfig {
    public static final int DEFAULT_HUD_OFFSET = 8;
    public static final int DEFAULT_PORT = 8_765;
    public static final int DEFAULT_MAX_REQUEST_BYTES = 65_536;
    public static final int DEFAULT_VISUAL_RADIUS_BLOCKS = 16;
    public static final int DEFAULT_RAYS_PER_TICK = 256;
    private static final String TEST_FIXTURE_MOD_ID = "mcmcp_test_fixture";
    public static final int DEFAULT_MAX_CAMERA_DEGREES_PER_SECOND = 90;
    public static final int DEFAULT_RECOVERY_MAX_TICKS = 200;
    public static final int DEFAULT_RECOVERY_MAX_DISTANCE = 16;
    public static final int DEFAULT_RECOVERY_MAX_CAMERA_DEGREES = 360;
    public static final int DEFAULT_RECOVERY_MAX_INTERACTIONS = 8;
    public static final int DEFAULT_RECOVERY_MAX_PLACEMENTS = 8;
    public static final int DEFAULT_RECOVERY_MAX_BREAKS = 4;

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
    private static final ModConfigSpec.IntValue HUD_OFFSET_X = BUILDER
            .comment("Horizontal pixel offset from the right edge for the MCMCP status UI")
            .defineInRange("hud_offset_x", DEFAULT_HUD_OFFSET, 0, 2048);
    private static final ModConfigSpec.IntValue HUD_OFFSET_Y = BUILDER
            .comment("Vertical pixel offset from the bottom edge for the MCMCP status UI")
            .defineInRange("hud_offset_y", DEFAULT_HUD_OFFSET, 0, 2048);
    private static final ModConfigSpec.IntValue VISUAL_RADIUS_BLOCKS = BUILDER
            .comment("Camera-independent omnidirectional visual radius in blocks")
            .defineInRange(
                    "omnidirectional_visual_radius_blocks",
                    DEFAULT_VISUAL_RADIUS_BLOCKS,
                    1,
                    32);
    private static final ModConfigSpec.IntValue RAYS_PER_TICK = BUILDER
            .comment("Omnidirectional visual rays sampled per active client tick")
            .defineInRange(
                    "omnidirectional_rays_per_tick",
                    DEFAULT_RAYS_PER_TICK,
                    64,
                    512);
    private static volatile Integer testHarnessRaysPerTickOverride;
    private static final ModConfigSpec.IntValue MAX_CAMERA_DEGREES_PER_SECOND = BUILDER
            .comment("Maximum synthetic camera rotation speed")
            .defineInRange(
                    "max_camera_degrees_per_second",
                    DEFAULT_MAX_CAMERA_DEGREES_PER_SECOND,
                    15,
                    360);
    private static final ModConfigSpec.BooleanValue EMERGENCY_ITEM_USE = BUILDER
            .comment("Allow phase-gated emergency item use")
            .define("emergency_item_use", true);
    private static final ModConfigSpec.BooleanValue EMERGENCY_BLOCK_PLACEMENT = BUILDER
            .comment("Allow phase-gated, verified emergency block placement")
            .define("emergency_block_placement", true);
    private static final ModConfigSpec.BooleanValue EMERGENCY_BLOCK_BREAK = BUILDER
            .comment("Allow phase-gated emergency block breaking")
            .define("emergency_block_break", false);
    private static final ModConfigSpec.IntValue RECOVERY_MAX_TICKS = BUILDER
            .defineInRange(
                    "recovery_max_ticks",
                    DEFAULT_RECOVERY_MAX_TICKS,
                    20,
                    DEFAULT_RECOVERY_MAX_TICKS);
    private static final ModConfigSpec.IntValue RECOVERY_MAX_DISTANCE = BUILDER
            .defineInRange(
                    "recovery_max_distance",
                    DEFAULT_RECOVERY_MAX_DISTANCE,
                    1,
                    DEFAULT_RECOVERY_MAX_DISTANCE);
    private static final ModConfigSpec.IntValue RECOVERY_MAX_CAMERA_DEGREES = BUILDER
            .defineInRange(
                    "recovery_max_camera_degrees",
                    DEFAULT_RECOVERY_MAX_CAMERA_DEGREES,
                    0,
                    DEFAULT_RECOVERY_MAX_CAMERA_DEGREES);
    private static final ModConfigSpec.IntValue RECOVERY_MAX_INTERACTIONS = BUILDER
            .defineInRange(
                    "recovery_max_interactions",
                    DEFAULT_RECOVERY_MAX_INTERACTIONS,
                    0,
                    DEFAULT_RECOVERY_MAX_INTERACTIONS);
    private static final ModConfigSpec.IntValue RECOVERY_MAX_PLACEMENTS = BUILDER
            .defineInRange(
                    "recovery_max_placements",
                    DEFAULT_RECOVERY_MAX_PLACEMENTS,
                    0,
                    DEFAULT_RECOVERY_MAX_PLACEMENTS);
    private static final ModConfigSpec.IntValue RECOVERY_MAX_BREAKS = BUILDER
            .defineInRange(
                    "recovery_max_breaks",
                    DEFAULT_RECOVERY_MAX_BREAKS,
                    0,
                    DEFAULT_RECOVERY_MAX_BREAKS);
    private static final ModConfigSpec.BooleanValue MULTIPLAYER_DEFAULT = BUILDER
            .comment("Default local policy for multiplayer automation; no server handshake is used")
            .define("multiplayer_default", false);

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

    public static int hudOffsetX() {
        return HUD_OFFSET_X.get();
    }

    public static int hudOffsetY() {
        return HUD_OFFSET_Y.get();
    }

    public static int visualRadiusBlocks() {
        return VISUAL_RADIUS_BLOCKS.get();
    }

    public static int raysPerTick() {
        Integer fixtureOverride = testHarnessRaysPerTickOverride;
        if (fixtureOverride != null) {
            return fixtureOverride;
        }
        return RAYS_PER_TICK.get();
    }

    /** Acquires cleanup-capable access for the separately packaged validation fixture. */
    public static TestHarnessRaysPerTickAccess acquireTestHarnessRaysPerTickAccess() {
        requireTestHarnessRuntime();
        return new TestHarnessRaysPerTickAccess();
    }

    static Integer rawTestHarnessRaysPerTickOverride() {
        return testHarnessRaysPerTickOverride;
    }

    static void setRawTestHarnessRaysPerTickOverride(Integer value) {
        if (value != null && (value < 64 || value > 512)) {
            throw new IllegalArgumentException("test harness rays per tick must be in 64..512");
        }
        testHarnessRaysPerTickOverride = value;
    }

    private static void requireTestHarnessRuntime() {
        boolean fixtureLoaded;
        try {
            fixtureLoaded = ModList.get().getModContainerById(TEST_FIXTURE_MOD_ID).isPresent();
        } catch (RuntimeException | LinkageError failure) {
            fixtureLoaded = false;
        }
        if (!testHarnessRuntimeAvailable(
                Boolean.getBoolean("mcmcp.testHarness"), fixtureLoaded)) {
            throw new IllegalStateException(
                    "test harness observation override requires the enabled fixture mod");
        }
    }

    static boolean testHarnessRuntimeAvailable(boolean enabled, boolean fixtureLoaded) {
        return enabled && fixtureLoaded;
    }

    /** A lease-scoped handle whose cleanup remains available after its admission guard changes. */
    public static final class TestHarnessRaysPerTickAccess {
        private TestHarnessRaysPerTickAccess() {
        }

        public Integer currentOverride() {
            return rawTestHarnessRaysPerTickOverride();
        }

        public void setOverride(Integer value) {
            setRawTestHarnessRaysPerTickOverride(value);
        }
    }

    public static int maxCameraDegreesPerSecond() {
        return MAX_CAMERA_DEGREES_PER_SECOND.get();
    }

    public static boolean emergencyItemUse() { return EMERGENCY_ITEM_USE.get(); }
    public static boolean emergencyBlockPlacement() { return EMERGENCY_BLOCK_PLACEMENT.get(); }
    public static boolean emergencyBlockBreak() { return EMERGENCY_BLOCK_BREAK.get(); }
    public static int recoveryMaxTicks() { return RECOVERY_MAX_TICKS.get(); }
    public static int recoveryMaxDistance() { return RECOVERY_MAX_DISTANCE.get(); }
    public static int recoveryMaxCameraDegrees() { return RECOVERY_MAX_CAMERA_DEGREES.get(); }
    public static int recoveryMaxInteractions() { return RECOVERY_MAX_INTERACTIONS.get(); }
    public static int recoveryMaxPlacements() { return RECOVERY_MAX_PLACEMENTS.get(); }
    public static int recoveryMaxBreaks() { return RECOVERY_MAX_BREAKS.get(); }
    public static boolean multiplayerDefault() { return MULTIPLAYER_DEFAULT.get(); }
}
