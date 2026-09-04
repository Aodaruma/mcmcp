package dev.aod.mcmcp.mcp;

import dev.aod.mcmcp.agent.dsl.ActionDslOperationManifest;

import java.util.List;
import java.util.LinkedHashMap;
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
                        "game_paused", false,
                        "granted_capabilities", List.of()),
                "world", nullValue(),
                "inventory", List.of(),
                "standard_potions", List.of(),
                "recipe_query", nullValue(),
                "policy", Map.ofEntries(
                        Map.entry("profile", "survival_omnidirectional"),
                        Map.entry("multiplayer_enabled", false),
                        Map.entry("max_duration_ms", 750_000),
                        Map.entry("max_ticks", 15_000),
                        Map.entry("max_distance_blocks", 32),
                        Map.entry("max_camera_degrees", 720),
                        Map.entry("max_blocks_broken", 8),
                        Map.entry("max_interactions", 16),
                        Map.entry("max_blocks_placed", 8),
                        Map.entry("omnidirectional_visual_radius_blocks", 16),
                        Map.entry("local_observation_radius_blocks", 6),
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
                                        "block_break", "block_interact", "block_place", "camera",
                                        "inventory_transfer", "movement"),
                                "available_operations",
                                ActionDslOperationManifest.operationPayload(java.util.Set.of()),
                                "reference_descriptors",
                                ActionDslOperationManifest.referenceDescriptorPayload(),
                                "missing_capability_guidance",
                                ActionDslOperationManifest.missingCapabilityGuidance()))),
                "observation", nullValue(),
                "action", nullValue());
    }

    static Map<String, Object> stateWithEmptyRecipeQuery() {
        var result = new LinkedHashMap<>(state());
        result.put("recipe_query", Map.of(
                "basis", Map.of(
                        "world_session_id", "550e8400-e29b-41d4-a716-446655440000",
                        "client_tick", 0,
                        "recipe_book_revision", 1),
                "coverage", Map.of(
                        "source", "client_known_recipe_displays",
                        "complete", false,
                        "known", 0,
                        "matched", 0,
                        "returned", 0,
                        "truncated", false),
                "recipes", List.of()));
        return result;
    }

    /** Map.of rejects null; this marker is replaced by Gson as JsonNull through a mutable map helper. */
    private static Object nullValue() {
        return com.google.gson.JsonNull.INSTANCE;
    }
}
