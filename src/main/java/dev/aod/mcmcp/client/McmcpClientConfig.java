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
    public static final int DEFAULT_RECOVERY_MAX_TICKS = 600;
    public static final int DEFAULT_RECOVERY_MAX_DISTANCE = 128;
    public static final int DEFAULT_RECOVERY_MAX_CAMERA_DEGREES = 360;
    public static final int DEFAULT_RECOVERY_MAX_INTERACTIONS = 8;
    public static final int DEFAULT_RECOVERY_MAX_PLACEMENTS = 8;
    public static final int DEFAULT_RECOVERY_MAX_BREAKS = 4;

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    private static final ModConfigSpec.BooleanValue AGENT_CHAT_PREFIX_ENABLED = BUILDER
            .comment("Prepend the configured prefix to Agent chat sends only")
            .define("agent_chat_prefix_enabled", false);
    private static final ModConfigSpec.ConfigValue<String> AGENT_CHAT_PREFIX = BUILDER
            .define("agent_chat_prefix", "", AgentChatSettings::validFragment);
    private static final ModConfigSpec.BooleanValue AGENT_CHAT_SUFFIX_ENABLED = BUILDER
            .comment("Append the configured suffix to Agent chat sends only")
            .define("agent_chat_suffix_enabled", true);
    private static final ModConfigSpec.ConfigValue<String> AGENT_CHAT_SUFFIX = BUILDER
            .comment("Spaces are literal; the entire formatted message must fit 256 UTF-16 units")
            .define("agent_chat_suffix", AgentChatSettings.DEFAULT_SUFFIX, AgentChatSettings::validFragment);
    private static final ModConfigSpec.EnumValue<dev.aod.mcmcp.observation.RecipeScope> RECIPE_SCOPE = BUILDER
            .comment("UNLOCKED: 解放済みのレシピのみ（既定）",
                    "ALL_CRAFTABLE: すべての作成可能なレシピ。既存MODが同期した公開client情報の範囲に限定。",
                    "全件取得・材料充足・自動craft対応を保証せず、解放状態を変更しません。")
            .defineEnum("recipe_scope", dev.aod.mcmcp.observation.RecipeScope.UNLOCKED);
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
    private static final ModConfigSpec.ConfigValue<String> SCRIPT_RESOURCE_ROOT = BUILDER
            .comment("Trusted local script/data directory, relative to the game directory or an absolute local path")
            .define("script_resource_root", "mcmcp/scripts");
    private static final ModConfigSpec.IntValue SCRIPT_RESOURCE_MIB = BUILDER
            .comment("Total source and JSON/CSV input per script, independent of HTTP and execution budgets")
            .defineInRange("script_resource_max_mib", 32, 1, 256);
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
            .comment("Normal omnidirectional rays per active tick; repeated invalidations trigger"
                    + " one bounded 2048-ray catch-up within two scan periods")
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
    private static final ModConfigSpec.BooleanValue AUTOMATIC_RECOVERY = BUILDER
            .comment("Recover from observed danger while an Agent action owns control; Esc and OFF always stop")
            .define("automatic_recovery", true);
    private static final ModConfigSpec.BooleanValue RECOVERY_RESUME = BUILDER
            .comment("Re-observe and resume interrupted movement/block work after a stable safe interval")
            .define("recovery_resume", true);
    private static final ModConfigSpec.BooleanValue RECOVERY_RETALIATE = BUILDER
            .comment("Defend against an observed attacking mob only; never attack players or merely nearby mobs")
            .define("recovery_retaliate", true);
    private static final ModConfigSpec.BooleanValue RECOVERY_ELYTRA = BUILDER
            .define("recovery_elytra", true);
    private static final ModConfigSpec.IntValue FLIGHT_OBSERVATION_BLOCKS = BUILDER
            .comment("Bounded forward ray observations for flight, refreshed every tick and capped by render distance")
            .defineInRange("flight_observation_blocks", 96, 16, 128);
    private static final ModConfigSpec.BooleanValue RECOVERY_DISCONNECT = BUILDER
            .comment("Last resort for player attacks or fatal danger; never reconnect automatically")
            .define("recovery_disconnect", false);
    private static final ModConfigSpec.IntValue RECOVERY_SAFE_TICKS = BUILDER
            .defineInRange("recovery_safe_ticks", 100, 20, 1200);
    private static final ModConfigSpec.ConfigValue<String> RECOVERY_PLAYER_MESSAGE = BUILDER
            .comment("Optional literal chat message on a player attack/interference; empty disables, no commands")
            .define("recovery_player_message", "", text -> text instanceof String value
                    && AgentChatSettings.validFragment(value) && value.length() <= 200
                    && !value.stripLeading().startsWith("/"));
    private static final ModConfigSpec.ConfigValue<String> RECOVERY_WAYSTONE_DESTINATION = BUILDER
            .comment("Optional exact registered Waystones destination UUID used for emergency retreat; empty disables")
            .define("recovery_waystone_destination", "", text -> text instanceof String value
                    && (value.isEmpty() || value.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")));
    private static final ModConfigSpec.IntValue RECOVERY_WAYSTONE_MAX_LEVELS = BUILDER
            .defineInRange("recovery_waystone_max_levels", 0, 0, 100);
    private static final ModConfigSpec.IntValue RECOVERY_WAYSTONE_MAX_POINTS = BUILDER
            .defineInRange("recovery_waystone_max_points", 0, 0, 100000);
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
                    2400);
    private static final ModConfigSpec.IntValue RECOVERY_MAX_DISTANCE = BUILDER
            .defineInRange(
                    "recovery_max_distance",
                    DEFAULT_RECOVERY_MAX_DISTANCE,
                    1,
                    256);
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
    public static final ModConfigSpec SPEC = BUILDER.build();

    private McmcpClientConfig() { }

    public static AgentChatSettings agentChatSettings() {
        return new AgentChatSettings(AGENT_CHAT_PREFIX_ENABLED.get(), AGENT_CHAT_PREFIX.get(),
                AGENT_CHAT_SUFFIX_ENABLED.get(), AGENT_CHAT_SUFFIX.get());
    }

    public static void setAgentChatPrefixEnabled(boolean enabled) { AGENT_CHAT_PREFIX_ENABLED.set(enabled); SPEC.save(); }
    public static void setAgentChatSuffixEnabled(boolean enabled) { AGENT_CHAT_SUFFIX_ENABLED.set(enabled); SPEC.save(); }
    public static void setAgentChatPrefix(String text) { AGENT_CHAT_PREFIX.set(text); SPEC.save(); }
    public static void setAgentChatSuffix(String text) { AGENT_CHAT_SUFFIX.set(text); SPEC.save(); }

    public static dev.aod.mcmcp.observation.RecipeScope recipeScope() {
        return RECIPE_SCOPE.get();
    }

    public static boolean endpointEnabled() {
        return ENDPOINT_ENABLED.get();
    }

    public static int port() {
        return PORT.get();
    }

    public static int maxRequestBytes() {
        return MAX_REQUEST_BYTES.get();
    }
    public static String scriptResourceRoot() { return SCRIPT_RESOURCE_ROOT.get(); }
    public static long scriptResourceMaxBytes() { return SCRIPT_RESOURCE_MIB.get() * 1_048_576L; }

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
    public static boolean automaticRecovery() { return AUTOMATIC_RECOVERY.get(); }
    public static boolean recoveryResume() { return RECOVERY_RESUME.get(); }
    public static boolean recoveryRetaliate() { return RECOVERY_RETALIATE.get(); }
    public static boolean recoveryElytra() { return RECOVERY_ELYTRA.get(); }
    public static int flightObservationBlocks() { return FLIGHT_OBSERVATION_BLOCKS.get(); }
    public static boolean recoveryDisconnect() { return RECOVERY_DISCONNECT.get(); }
    public static int recoverySafeTicks() { return RECOVERY_SAFE_TICKS.get(); }
    public static String recoveryPlayerMessage() { return RECOVERY_PLAYER_MESSAGE.get(); }
    public static String recoveryWaystoneDestination() { return RECOVERY_WAYSTONE_DESTINATION.get(); }
    public static int recoveryWaystoneMaxLevels() { return RECOVERY_WAYSTONE_MAX_LEVELS.get(); }
    public static int recoveryWaystoneMaxPoints() { return RECOVERY_WAYSTONE_MAX_POINTS.get(); }
    public static boolean emergencyBlockPlacement() { return EMERGENCY_BLOCK_PLACEMENT.get(); }
    public static boolean emergencyBlockBreak() { return EMERGENCY_BLOCK_BREAK.get(); }
    public static int recoveryMaxTicks() { return RECOVERY_MAX_TICKS.get(); }
    public static int recoveryMaxDistance() { return RECOVERY_MAX_DISTANCE.get(); }
    public static int recoveryMaxCameraDegrees() { return RECOVERY_MAX_CAMERA_DEGREES.get(); }
    public static int recoveryMaxInteractions() { return RECOVERY_MAX_INTERACTIONS.get(); }
    public static int recoveryMaxPlacements() { return RECOVERY_MAX_PLACEMENTS.get(); }
    public static int recoveryMaxBreaks() { return RECOVERY_MAX_BREAKS.get(); }
}
