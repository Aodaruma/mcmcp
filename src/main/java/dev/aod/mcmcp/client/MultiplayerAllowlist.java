package dev.aod.mcmcp.client;

import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Fail-closed local server allowlist; no server handshake or capability query is performed. */
public final class MultiplayerAllowlist {
    public static final int MAX_BYTES = 16_384;
    public static final int MAX_SERVERS = 64;

    private MultiplayerAllowlist() { }

    public static boolean allows(Path file, String address) {
        Objects.requireNonNull(file, "file");
        String normalized = normalize(address);
        if (normalized == null) return false;
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) return false;
            String json = Files.readString(file, StandardCharsets.UTF_8);
            var root = JsonParser.parseString(json);
            if (!root.isJsonObject()) return false;
            var object = root.getAsJsonObject();
            var schema = object.get("schema_version");
            if (!object.keySet().equals(Set.of("schema_version", "servers"))
                    || schema == null
                    || !schema.isJsonPrimitive()
                    || !schema.getAsJsonPrimitive().isNumber()
                    || !schema.getAsString().equals("1")
                    || !object.get("servers").isJsonArray()
                    || object.getAsJsonArray("servers").size() > MAX_SERVERS) {
                return false;
            }
            var servers = new LinkedHashSet<String>();
            for (var entry : object.getAsJsonArray("servers")) {
                if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) {
                    return false;
                }
                String server = normalize(entry.getAsString());
                if (server == null) return false;
                servers.add(server);
            }
            return servers.contains(normalized);
        } catch (IOException | RuntimeException invalid) {
            return false;
        }
    }

    private static String normalize(String address) {
        if (address == null) return null;
        String value = address.strip().toLowerCase(Locale.ROOT);
        return value.isEmpty() || value.length() > 255 || value.chars().anyMatch(Character::isISOControl)
                ? null : value;
    }
}
