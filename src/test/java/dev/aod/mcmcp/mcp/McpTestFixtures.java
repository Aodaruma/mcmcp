package dev.aod.mcmcp.mcp;

import java.util.List;
import java.util.Map;

final class McpTestFixtures {
    static final String TOKEN = "test-token-0123456789-abcdefghijklmnopqrstuvwxyz";

    private McpTestFixtures() {
    }

    static Map<String, Object> state() {
        return Map.of(
                "schema_version", 1,
                "control", Map.of(
                        "mode", "off",
                        "ready_expires_at", nullValue(),
                        "game_paused", false),
                "world", nullValue(),
                "inventory", List.of(),
                "policy", Map.ofEntries(
                        Map.entry("profile", "survival_omnidirectional"),
                        Map.entry("multiplayer_enabled", false),
                        Map.entry("max_duration_ms", 600_000),
                        Map.entry("max_ticks", 12_000),
                        Map.entry("max_distance_blocks", 32),
                        Map.entry("max_camera_degrees", 360),
                        Map.entry("max_blocks_broken", 8),
                        Map.entry("max_interactions", 8),
                        Map.entry("max_blocks_placed", 8),
                        Map.entry("omnidirectional_visual_radius_blocks", 16),
                        Map.entry("local_observation_radius_blocks", 4),
                        Map.entry("omnidirectional_direction_count", 2_048),
                        Map.entry("omnidirectional_rays_per_tick", 256),
                        Map.entry("max_recent_sound_clues", 32),
                        Map.entry("sound_clue_ttl_ticks", 600),
                        Map.entry("action_dsl", Map.of(
                                "version", 1,
                                "max_ast_depth", 4,
                                "max_source_nodes", 64,
                                "max_executed_nodes", 256,
                                "max_repeat_count", 16,
                                "allowed_capabilities", List.of(
                                        "block_break", "block_interact", "block_place", "camera", "movement")))),
                "observation", nullValue(),
                "action", nullValue());
    }

    /** Map.of rejects null; this marker is replaced by Gson as JsonNull through a mutable map helper. */
    private static Object nullValue() {
        return com.google.gson.JsonNull.INSTANCE;
    }
}
