package dev.aod.mcmcp.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Frozen v1 examples for shared executor regressions; never shipped or registered as a tool. */
public final class LegacyActionSchema {
    private LegacyActionSchema() { }

    static JsonObject tool() {
        try (var stream = LegacyActionSchema.class.getResourceAsStream("/mcmcp/legacy-action-tool-v1.json");
                var reader = new InputStreamReader(java.util.Objects.requireNonNull(stream), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("legacy executor fixture unavailable", failure);
        }
    }

    public static JsonObject inputSchema() { return tool().getAsJsonObject("inputSchema"); }
}
