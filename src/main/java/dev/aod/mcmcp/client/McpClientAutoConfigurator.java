package dev.aod.mcmcp.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Explicitly user-triggered, local-only setup for supported MCP clients. */
public final class McpClientAutoConfigurator {
    static final String ENDPOINT = "http://127.0.0.1:%d/mcp";
    private static final int MAX_CONFIG_BYTES = 2 * 1024 * 1024;
    private static final String BEGIN = "# BEGIN MCMCP AUTO-CONFIG";
    private static final String END = "# END MCMCP AUTO-CONFIG";
    private static final Pattern MANAGED_CODEX_BLOCK = Pattern.compile(
            "(?ms)(?:\\R)?^" + Pattern.quote(BEGIN) + "\\R.*?^" + Pattern.quote(END) + "(?:\\R|$)");

    private McpClientAutoConfigurator() { }

    public static Result configure(
            Target target, Path gameDirectory, Path userHome, int port) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(gameDirectory, "gameDirectory");
        Objects.requireNonNull(userHome, "userHome");
        if (port < 1 || port > 65_535) {
            return new Result(false, "invalid_port", null);
        }
        try {
            Path token = gameDirectory.toAbsolutePath().normalize()
                    .resolve("config").resolve("mcmcp").resolve("mcp-token");
            if (!Files.isRegularFile(token)
                    || Files.isSymbolicLink(token)
                    || Files.size(token) < 43
                    || Files.size(token) > 256) {
                return new Result(false, "token_unavailable", token);
            }
            Path file = configPath(target, gameDirectory, userHome);
            var registration = readRegistration(target, file);
            Path helperPath = helperPath(token.getParent());
            String conflict = registrationConflict(registration, helperPath);
            if (conflict != null) return new Result(false, conflict, file);
            String helper = installHeaderHelper(token.getParent());
            String endpoint = ENDPOINT.formatted(port);
            return switch (target) {
                case CODEX, CODEX_ISOLATED -> configureCodex(file, endpoint, helper);
                case CLAUDE_CODE -> configureClaudeCode(file, endpoint, helper);
            };
        } catch (IOException | RuntimeException failure) {
            return new Result(false, "write_failed", null);
        }
    }

    public static boolean anyClientConfigured(Path gameDirectory, Path userHome, int port) {
        // An isolated file is not evidence that the user's client was launched with it.
        return diagnose(Target.CODEX, gameDirectory, userHome, port).equals("configured")
                || diagnose(Target.CLAUDE_CODE, gameDirectory, userHome, port).equals("configured");
    }

    /** Static reference diagnosis only: never executes helpers, reads tokens or contacts a server. */
    public static String diagnose(Target target, Path gameDirectory, Path userHome, int port) {
        return diagnoseConfig(target, gameDirectory, configPath(target, gameDirectory, userHome), port);
    }

    /** Allows checking the exact config file visible to a client, including a virtualized view. */
    public static String diagnoseConfig(Target target, Path gameDirectory, Path file, int port) {
        try {
            if (port < 1 || port > 65_535) return "invalid_port";
            var registration = readRegistration(target, file);
            if (!registration.present()) return "unconfigured";
            if (!registration.managed()) return "unmanaged_unknown";
            Path expected = helperPath(gameDirectory.toAbsolutePath().normalize().resolve("config/mcmcp"));
            if (!sameProfile(registration.helper(), expected)) return "another_profile";
            if (!ENDPOINT.formatted(port).equals(registration.endpoint())) return "endpoint_mismatch";
            Path actual = commandPath(registration.helper());
            if (!Files.isRegularFile(actual)) return "helper_missing";
            if (!readOptionalConfig(actual).equals(helperBody())) return "stale_helper";
            Path token = expected.resolveSibling("mcp-token");
            if (!Files.isRegularFile(token) || Files.isSymbolicLink(token)
                    || Files.size(token) < 43 || Files.size(token) > 256) return "token_unavailable";
            return "configured";
        } catch (IOException | RuntimeException ignored) {
            return "config_unreadable";
        }
    }

    public static Path configPath(Target target, Path gameDirectory, Path userHome) {
        return (switch (target) {
            case CODEX -> userHome.resolve(".codex/config.toml");
            case CODEX_ISOLATED -> gameDirectory.resolve("config/mcmcp/codex-home/config.toml");
            case CLAUDE_CODE -> userHome.resolve(".claude.json");
        }).toAbsolutePath().normalize();
    }

    private static Result configureCodex(Path file, String endpoint, String helper)
            throws IOException {
        String existing = readOptionalConfig(file);
        String conflict = registrationConflict(parseRegistration(Target.CODEX, existing), commandPath(helper));
        if (conflict != null) return new Result(false, conflict, file);
        Matcher managed = MANAGED_CODEX_BLOCK.matcher(existing);
        String block = BEGIN + System.lineSeparator()
                + "[mcp_servers.mcmcp]" + System.lineSeparator()
                + "url = " + tomlString(endpoint) + System.lineSeparator()
                + "http_headers_helper = " + tomlString(helper) + System.lineSeparator()
                + "startup_timeout_sec = 30" + System.lineSeparator()
                + "tool_timeout_sec = 900" + System.lineSeparator()
                + END + System.lineSeparator();
        String updated;
        if (managed.find(0)) {
            updated = managed.replaceFirst(Matcher.quoteReplacement(
                    System.lineSeparator() + block));
        } else {
            String prefix = existing.isEmpty() ? "" : existing.stripTrailing()
                    + System.lineSeparator() + System.lineSeparator();
            updated = prefix + block;
        }
        backupOnce(file);
        atomicWrite(file, updated);
        return new Result(true, "configured", file);
    }

    private static Result configureClaudeCode(Path file, String endpoint, String helper)
            throws IOException {
        String existing = readOptionalConfig(file);
        String conflict = registrationConflict(parseRegistration(Target.CLAUDE_CODE, existing), commandPath(helper));
        if (conflict != null) return new Result(false, conflict, file);
        JsonObject root = existing.isBlank()
                ? new JsonObject()
                : requireObject(JsonParser.parseString(existing), "invalid_claude_config");
        JsonObject servers;
        if (!root.has("mcpServers")) {
            servers = new JsonObject();
            root.add("mcpServers", servers);
        } else if (root.get("mcpServers").isJsonObject()) {
            servers = root.getAsJsonObject("mcpServers");
        } else {
            return new Result(false, "invalid_claude_config", file);
        }
        var mcmcp = new JsonObject();
        mcmcp.addProperty("type", "http");
        mcmcp.addProperty("url", endpoint);
        mcmcp.addProperty("headersHelper", helper);
        servers.add("mcmcp", mcmcp);
        backupOnce(file);
        atomicWrite(file, new GsonBuilder().setPrettyPrinting().create().toJson(root)
                + System.lineSeparator());
        return new Result(true, "configured", file);
    }

    private static JsonObject requireObject(com.google.gson.JsonElement element, String code)
            throws IOException {
        if (!element.isJsonObject()) {
            throw new IOException(code);
        }
        return element.getAsJsonObject();
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private static Path helperPath(Path configDirectory) {
        return configDirectory.resolve(windows()
                ? "mcmcp-auth-headers.ps1" : "mcmcp-auth-headers.sh");
    }

    private static String helperBody() {
        if (windows()) {
            return "$ErrorActionPreference = 'Stop'\r\n"
                    + "$mcmcpToken = (Get-Content -LiteralPath "
                    + "(Join-Path $PSScriptRoot 'mcp-token') -Raw -Encoding UTF8).Trim()\r\n"
                    + "if ($mcmcpToken -notmatch '^[A-Za-z0-9_-]{43,256}$') "
                    + "{ throw 'MCMCP token file is invalid' }\r\n"
                    + "[Console]::Out.Write("
                    + "\"{`\"Authorization`\":`\"Bearer $mcmcpToken`\"}\")\r\n";
        } else {
            return "#!/bin/sh\nset -eu\n"
                    + "mcmcp_token=$(tr -d '\\r\\n' < \"$(dirname \"$0\")/mcp-token\")\n"
                    + "printf '{\"Authorization\":\"Bearer %s\"}' \"$mcmcp_token\"\n";
        }
    }

    private static String helperCommand(Path helper) {
        return windows() ? "powershell.exe -NoLogo -NoProfile -NonInteractive "
                + "-ExecutionPolicy Bypass -File \"" + helper + "\""
                : "sh \"" + helper.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String installHeaderHelper(Path configDirectory) throws IOException {
        Path helper = helperPath(configDirectory);
        atomicWrite(helper, helperBody());
        return helperCommand(helper);
    }

    // Recognize only the shape generated by this class. Other TOML/JSON remains user-owned.
    // Never interpret arbitrary helper commands or infer ownership from a matching URL.
    private static Registration readRegistration(Target target, Path file) throws IOException {
        return parseRegistration(target, readOptionalConfig(file));
    }

    private static Registration parseRegistration(Target target, String text) throws IOException {
        if (target == Target.CLAUDE_CODE) {
            if (text.isBlank()) return Registration.absent();
            var root = requireObject(JsonParser.parseString(text), "invalid_claude_config");
            if (!root.has("mcpServers")) return Registration.absent();
            var servers = requireObject(root.get("mcpServers"), "invalid_claude_config");
            if (!servers.has("mcmcp")) return Registration.absent();
            if (!servers.get("mcmcp").isJsonObject()) return Registration.unknown();
            var entry = servers.getAsJsonObject("mcmcp");
            if (!entry.keySet().equals(java.util.Set.of("type", "url", "headersHelper"))) {
                return Registration.unknown();
            }
            if (!"http".equals(string(entry, "type"))) return Registration.unknown();
            String helper = string(entry, "headersHelper");
            return new Registration(true, commandPath(helper) != null, string(entry, "url"), helper);
        }
        // Parse actual TOML keys as well as the marker: quoted/escaped keys, inline tables,
        // duplicate declarations and fields after END must never bypass the ownership check.
        UnmodifiableConfig parsed;
        try {
            parsed = new TomlParser().parse(text);
        } catch (RuntimeException invalid) {
            return Registration.unknown();
        }
        Object rawEntry = parsed.get("mcp_servers.mcmcp");
        Matcher blocks = MANAGED_CODEX_BLOCK.matcher(text);
        if (!blocks.find()) {
            return rawEntry != null || text.contains(BEGIN) || text.contains(END)
                    ? Registration.unknown() : Registration.absent();
        }
        if (!(rawEntry instanceof UnmodifiableConfig entry)
                || !entry.valueMap().keySet().equals(java.util.Set.of(
                        "url", "http_headers_helper", "startup_timeout_sec", "tool_timeout_sec"))) {
            return Registration.unknown();
        }
        String block = blocks.group().strip();
        String outside = text.substring(0, blocks.start()) + text.substring(blocks.end());
        if (blocks.find() || outside.contains(BEGIN) || outside.contains(END)
                || text.contains("\"\"\"") || text.contains("'''")) {
            return Registration.unknown();
        }
        String[] lines = block.split("\\R");
        if (lines.length != 7 || !lines[0].equals(BEGIN)
                || !lines[1].equals("[mcp_servers.mcmcp]")
                || !lines[2].startsWith("url = ") || !lines[3].startsWith("http_headers_helper = ")
                || !lines[4].equals("startup_timeout_sec = 30")
                || !lines[5].equals("tool_timeout_sec = 900") || !lines[6].equals(END)) {
            return Registration.unknown();
        }
        String endpoint = generatedTomlString(lines[2].substring("url = ".length()));
        String helper = generatedTomlString(lines[3].substring("http_headers_helper = ".length()));
        if (!endpoint.equals(entry.get("url")) || !helper.equals(entry.get("http_headers_helper"))) {
            return Registration.unknown();
        }
        return new Registration(true, commandPath(helper) != null, endpoint, helper);
    }

    private static String string(JsonObject object, String key) throws IOException {
        var value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IOException("invalid_registration");
        }
        return value.getAsString();
    }

    private static String generatedTomlString(String encoded) throws IOException {
        var value = JsonParser.parseString(encoded);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || !tomlString(value.getAsString()).equals(encoded)) {
            throw new IOException("invalid_registration");
        }
        return value.getAsString();
    }

    private static Path commandPath(String command) {
        String prefix = windows() ? "powershell.exe -NoLogo -NoProfile -NonInteractive "
                + "-ExecutionPolicy Bypass -File \"" : "sh \"";
        if (command == null || !command.startsWith(prefix) || !command.endsWith("\"")) return null;
        String raw = command.substring(prefix.length(), command.length() - 1);
        if (!windows()) raw = raw.replace("\\\"", "\"").replace("\\\\", "\\");
        if (raw.indexOf('"') >= 0 || raw.chars().anyMatch(Character::isISOControl)) return null;
        Path path = Path.of(raw);
        return path.isAbsolute() && path.getFileName().equals(helperPath(Path.of(".")).getFileName())
                && helperCommand(path).equals(command) ? path : null;
    }

    private static boolean sameProfile(String command, Path expected) throws IOException {
        Path path = commandPath(command);
        if (path == null) return false;
        // Compare directories even if the helper has not been generated or has been removed.
        // isSameFile accepts real aliases (including Windows canonical/UNC views), not copies.
        if (path.getParent().toAbsolutePath().normalize().equals(expected.getParent())) return true;
        return Files.exists(path.getParent()) && Files.exists(expected.getParent())
                && Files.isSameFile(path.getParent(), expected.getParent());
    }

    private static String registrationConflict(Registration registration, Path expected) throws IOException {
        if (!registration.present()) return null;
        if (!registration.managed()) return "existing_unmanaged_entry";
        return sameProfile(registration.helper(), expected) ? null : "another_profile";
    }

    private record Registration(boolean present, boolean managed, String endpoint, String helper) {
        static Registration absent() { return new Registration(false, false, null, null); }
        static Registration unknown() { return new Registration(true, false, null, null); }
    }

    private static String readOptionalConfig(Path file) throws IOException {
        if (Files.notExists(file)) return "";
        if (!Files.isRegularFile(file)
                || Files.isSymbolicLink(file)
                || Files.size(file) > MAX_CONFIG_BYTES) {
            throw new IOException("unsafe or oversized MCP client config");
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static void backupOnce(Path file) throws IOException {
        if (Files.notExists(file)) return;
        Path backup = file.resolveSibling(file.getFileName() + ".mcmcp.bak");
        if (Files.notExists(backup)) {
            Files.copy(file, backup);
        }
    }

    private static void atomicWrite(Path file, String content) throws IOException {
        Files.createDirectories(file.toAbsolutePath().normalize().getParent());
        Path temporary = Files.createTempFile(
                file.toAbsolutePath().normalize().getParent(), ".mcmcp-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String tomlString(String value) {
        return "\"" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n") + "\"";
    }

    public enum Target {
        CODEX,
        CODEX_ISOLATED,
        CLAUDE_CODE
    }

    public record Result(boolean success, String code, Path configPath) {
        public Result {
            Objects.requireNonNull(code, "code");
        }
    }
}
