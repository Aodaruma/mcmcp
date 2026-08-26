package dev.aod.mcmcp.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads the normative tool list and schemas directly from the shipped catalog. */
final class McpToolCatalog {
    static final String RESOURCE = "/mcmcp/MCMCP_MCP_Tool_Catalog.json";
    static final List<String> REQUIRED_NAMES = List.of(
            "agent_get_state",
            "agent_get_observation",
            "agent_start_action",
            "agent_get_action",
            "agent_cancel_action");

    private final JsonObject listResult;
    private final Map<String, JsonObject> tools;

    McpToolCatalog() {
        try (var stream = McpToolCatalog.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing MCP tool catalog resource");
            }
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                listResult = JsonParser.parseReader(reader).getAsJsonObject();
            }
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("Cannot load MCP tool catalog", failure);
        }

        var loaded = new LinkedHashMap<String, JsonObject>();
        var toolArray = requiredArray(listResult, "tools");
        for (var element : toolArray) {
            JsonObject tool = element.getAsJsonObject();
            String name = requiredString(tool, "name");
            if (loaded.put(name, tool) != null) {
                throw new IllegalStateException("Duplicate MCP tool: " + name);
            }
            requiredObject(tool, "inputSchema");
            requiredObject(tool, "outputSchema");
        }
        if (!List.copyOf(loaded.keySet()).equals(REQUIRED_NAMES)) {
            throw new IllegalStateException("MCP tool catalog must contain the fixed five tools in order");
        }
        if (!"complete".equals(requiredString(listResult, "resultType"))
                || listResult.get("ttlMs").getAsLong() != 0
                || !"private".equals(requiredString(listResult, "cacheScope"))) {
            throw new IllegalStateException("Invalid MCP catalog result metadata");
        }
        JsonObject serverInfo = requiredObject(
                requiredObject(listResult, "_meta"), "io.modelcontextprotocol/serverInfo");
        if (!"mcmcp".equals(requiredString(serverInfo, "name"))
                || !"0.1.0".equals(requiredString(serverInfo, "version"))) {
            throw new IllegalStateException("Invalid MCP catalog server identity");
        }
        tools = Map.copyOf(loaded);
    }

    JsonObject listResult() {
        return listResult.deepCopy();
    }

    JsonObject serverMeta() {
        return listResult.getAsJsonObject("_meta").deepCopy();
    }

    boolean contains(String name) {
        return tools.containsKey(name);
    }

    JsonObject inputSchema(String name) {
        JsonObject tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("Unknown MCP tool");
        }
        return tool.getAsJsonObject("inputSchema").deepCopy();
    }

    JsonObject outputSchema(String name) {
        JsonObject tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("Unknown MCP tool");
        }
        return tool.getAsJsonObject("outputSchema").deepCopy();
    }

    private static com.google.gson.JsonArray requiredArray(JsonObject object, String name) {
        if (!object.has(name) || !object.get(name).isJsonArray()) {
            throw new IllegalStateException("Catalog field must be an array: " + name);
        }
        return object.getAsJsonArray(name);
    }

    private static JsonObject requiredObject(JsonObject object, String name) {
        if (!object.has(name) || !object.get(name).isJsonObject()) {
            throw new IllegalStateException("Catalog field must be an object: " + name);
        }
        return object.getAsJsonObject(name);
    }

    private static String requiredString(JsonObject object, String name) {
        if (!object.has(name) || !object.get(name).isJsonPrimitive()
                || !object.getAsJsonPrimitive(name).isString()) {
            throw new IllegalStateException("Catalog field must be a string: " + name);
        }
        return object.get(name).getAsString();
    }
}
