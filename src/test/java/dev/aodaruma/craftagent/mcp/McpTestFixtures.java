package dev.aodaruma.craftagent.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class McpTestFixtures {
    static final String TOKEN = "test-token-0123456789-abcdefghijklmnopqrstuvwxyz";

    private McpTestFixtures() {
    }

    static Map<String, Object> statusData() {
        Map<String, Object> versions = fields(
                "mcp", "2025-11-25",
                "mod", "0.1.0",
                "minecraft", "1.21.11",
                "neoforge", "21.11.38-beta",
                "adapter", "none");
        Map<String, Object> world = fields(
                "connected", true,
                "dimension", "minecraft:overworld",
                "world_session_id", "session-test");
        Map<String, Object> lock = fields(
                "locked", false,
                "unlock_expires_at_client_tick", null,
                "reason", "operator_armed");
        Map<String, Object> voiceChat = fields(
                "status", "unavailable",
                "adapter_version", "none",
                "connected", null,
                "muted", null,
                "failure", null,
                "recovery_required", false);
        Map<String, Object> policies = fields("survival", "conservative", "completion", "safe_idle");
        Map<String, Object> routine = fields(
                "routine_id", "routine-test", "kind", "observe", "state", "RUNNING");
        Map<String, Object> memory = fields(
                "count", 0,
                "retention_policy", "session_lru",
                "evicted_count", 0L,
                "oldest_retained_tick", 0L,
                "warning", "none");
        return fields(
                "versions", versions,
                "world", world,
                "lock", lock,
                "capability_profile", List.of("observe"),
                "voice_chat", voiceChat,
                "policies", policies,
                "active_routine", routine,
                "memory", memory);
    }

    static Map<String, Object> fields(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put((String) entries[index], entries[index + 1]);
        }
        return result;
    }
}
