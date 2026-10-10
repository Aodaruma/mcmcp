package dev.aod.mcmcp.agent.script;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Independent of report/work budgets so a stopped child remains diagnosable. */
final class ScriptChildDiagnostic {
    private static final int MAX_NODES = 256;
    private static final int MAX_DEPTH = 8;
    private static final int MAX_BYTES = 8_192;
    private static final Object OMIT = new Object();
    private static final com.google.gson.Gson JSON = new com.google.gson.GsonBuilder().serializeNulls().create();
    private static final List<String> PRIORITY = List.of(
            "action_id", "kind", "state", "stop_reason", "failure", "cancel_requested", "result",
            "release_pending", "unconfirmed", "requested_count", "confirmed_count", "verification", "effects");
    private int nodes;
    // Reserve the wrapper and truncation flag before copying the existing public child payload.
    private int bytes = 256;
    private boolean truncated;

    static Map<String, Object> capture(String command, ScriptJobExecution.Outcome outcome,
            Map<String, Object> child) {
        var budget = new ScriptChildDiagnostic();
        var result = new LinkedHashMap<String, Object>();
        result.put("command", command);
        result.put("outcome", outcome.name().toLowerCase(java.util.Locale.ROOT));
        result.put("action", budget.copy(child, 0));
        result.put("truncated", budget.truncated);
        return Collections.unmodifiableMap(result);
    }

    private Object copy(Object value, int depth) {
        if (++nodes > MAX_NODES || depth > MAX_DEPTH || bytes >= MAX_BYTES) return omit();
        if (value instanceof Map<?, ?> map) {
            bytes += 2;
            var result = new LinkedHashMap<String, Object>();
            for (String key : PRIORITY) {
                if (map.containsKey(key)) entry(result, key, map.get(key), depth);
            }
            for (var entry : map.entrySet()) {
                if (nodes >= MAX_NODES || bytes >= MAX_BYTES) { truncated = true; break; }
                if (!(entry.getKey() instanceof String key)) { nodes++; truncated = true; continue; }
                if (!PRIORITY.contains(key)) entry(result, key, entry.getValue(), depth);
            }
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof List<?> list) {
            bytes += 2;
            var result = new ArrayList<>();
            for (Object element : list) {
                bytes++;
                Object item = copy(element, depth + 1);
                if (item == OMIT) break;
                result.add(item);
            }
            return Collections.unmodifiableList(result);
        }
        boolean immutableNumber = value instanceof Integer || value instanceof Long || value instanceof Short
                || value instanceof Byte || value instanceof Double || value instanceof Float;
        if (value != null && !(value instanceof String) && !(value instanceof Boolean)
                && !(immutableNumber && Double.isFinite(((Number) value).doubleValue()))) return omit();
        // Bound allocation before serializing even a single unusually large server string.
        if (value instanceof String text && text.length() > MAX_BYTES - bytes) return omit();
        int size = JSON.toJson(value).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (bytes + size > MAX_BYTES) return omit();
        bytes += size;
        return value;
    }

    private void entry(Map<String, Object> result, String key, Object value, int depth) {
        Object copiedKey = copy(key, depth + 1);
        if (copiedKey == OMIT) return;
        bytes += 2; // colon and comma
        Object copiedValue = copy(value, depth + 1);
        if (copiedValue != OMIT) result.put(key, copiedValue);
    }

    private Object omit() { truncated = true; return OMIT; }
}
