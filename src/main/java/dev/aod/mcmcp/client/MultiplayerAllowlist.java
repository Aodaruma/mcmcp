package dev.aod.mcmcp.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
            var servers = read(file, false);
            return servers != null && servers.contains(normalized);
        } catch (IOException | RuntimeException invalid) {
            return false;
        }
    }

    public static boolean sameAddress(String first, String second) {
        String normalizedFirst = normalize(first);
        String normalizedSecond = normalize(second);
        return normalizedFirst != null && normalizedFirst.equals(normalizedSecond);
    }

    /** Remembers one exact server after a physical in-game confirmation. */
    public static boolean remember(Path file, String address) {
        Objects.requireNonNull(file, "file");
        String normalized = normalize(address);
        if (normalized == null) return false;
        try {
            var servers = read(file, true);
            if (servers == null || servers.size() >= MAX_SERVERS && !servers.contains(normalized)) {
                return false;
            }
            servers.add(normalized);
            var root = new JsonObject();
            root.addProperty("schema_version", 1);
            var array = new JsonArray();
            servers.forEach(array::add);
            root.add("servers", array);
            Files.createDirectories(file.toAbsolutePath().normalize().getParent());
            Path temporary = Files.createTempFile(
                    file.toAbsolutePath().normalize().getParent(), ".allowed-servers-", ".tmp");
            try {
                Files.writeString(temporary, new Gson().toJson(root), StandardCharsets.UTF_8);
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            return true;
        } catch (IOException | RuntimeException invalid) {
            return false;
        }
    }

    private static LinkedHashSet<String> read(Path file, boolean allowMissing) throws IOException {
        if (Files.notExists(file)) return allowMissing ? new LinkedHashSet<>() : null;
        if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)
                || Files.size(file) > MAX_BYTES) return null;
        var root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
        if (!root.isJsonObject()) return null;
        var object = root.getAsJsonObject();
        var schema = object.get("schema_version");
        if (!object.keySet().equals(Set.of("schema_version", "servers"))
                || schema == null
                || !schema.isJsonPrimitive()
                || !schema.getAsJsonPrimitive().isNumber()
                || !schema.getAsString().equals("1")
                || !object.get("servers").isJsonArray()
                || object.getAsJsonArray("servers").size() > MAX_SERVERS) {
            return null;
        }
        var servers = new LinkedHashSet<String>();
        for (var entry : object.getAsJsonArray("servers")) {
            if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) return null;
            String server = normalize(entry.getAsString());
            if (server == null) return null;
            servers.add(server);
        }
        return servers;
    }

    private static String normalize(String address) {
        if (address == null) return null;
        String value = address.strip().toLowerCase(Locale.ROOT);
        return value.isEmpty() || value.length() > 255 || value.chars().anyMatch(Character::isISOControl)
                ? null : value;
    }
}
