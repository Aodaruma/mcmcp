package dev.aod.mcmcp.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
            "(?s)(?:\\R)?" + Pattern.quote(BEGIN) + ".*?" + Pattern.quote(END) + "(?:\\R)?");
    private static final Pattern ANY_MCMCP_CODEX_TABLE = Pattern.compile(
            "(?m)^\\s*\\[mcp_servers\\.mcmcp]\\s*$");

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
            String helper = installHeaderHelper(token.getParent());
            String endpoint = ENDPOINT.formatted(port);
            return switch (target) {
                case CODEX -> configureCodex(userHome, endpoint, helper);
                case CLAUDE_CODE -> configureClaudeCode(userHome, endpoint, helper);
            };
        } catch (IOException | RuntimeException failure) {
            return new Result(false, "write_failed", null);
        }
    }

    public static boolean anyClientConfigured(Path userHome, int port) {
        Objects.requireNonNull(userHome, "userHome");
        String endpoint = ENDPOINT.formatted(port);
        return containsBounded(userHome.resolve(".codex").resolve("config.toml"), endpoint)
                || containsBounded(userHome.resolve(".claude.json"), endpoint);
    }

    private static boolean containsBounded(Path file, String text) {
        try {
            return Files.isRegularFile(file)
                    && !Files.isSymbolicLink(file)
                    && Files.size(file) <= MAX_CONFIG_BYTES
                    && Files.readString(file, StandardCharsets.UTF_8).contains(text);
        } catch (IOException ignored) {
            return false;
        }
    }

    private static Result configureCodex(Path userHome, String endpoint, String helper)
            throws IOException {
        Path file = userHome.toAbsolutePath().normalize()
                .resolve(".codex").resolve("config.toml");
        String existing = readOptionalConfig(file);
        Matcher managed = MANAGED_CODEX_BLOCK.matcher(existing);
        if (!managed.find() && ANY_MCMCP_CODEX_TABLE.matcher(existing).find()) {
            return new Result(false, "existing_unmanaged_entry", file);
        }
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

    private static Result configureClaudeCode(Path userHome, String endpoint, String helper)
            throws IOException {
        Path file = userHome.toAbsolutePath().normalize().resolve(".claude.json");
        String existing = readOptionalConfig(file);
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
        if (servers.has("mcmcp")) {
            if (!servers.get("mcmcp").isJsonObject()) {
                return new Result(false, "existing_unmanaged_entry", file);
            }
            var current = servers.getAsJsonObject("mcmcp").get("url");
            if (current == null || !current.isJsonPrimitive()
                    || !endpoint.equals(current.getAsString())) {
                return new Result(false, "existing_unmanaged_entry", file);
            }
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

    private static String installHeaderHelper(Path configDirectory) throws IOException {
        Files.createDirectories(configDirectory);
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT).contains("win");
        Path helper = configDirectory.resolve(windows
                ? "mcmcp-auth-headers.ps1" : "mcmcp-auth-headers.sh");
        String body;
        String command;
        if (windows) {
            body = "$ErrorActionPreference = 'Stop'\r\n"
                    + "$mcmcpToken = (Get-Content -LiteralPath "
                    + "(Join-Path $PSScriptRoot 'mcp-token') -Raw -Encoding UTF8).Trim()\r\n"
                    + "if ($mcmcpToken -notmatch '^[A-Za-z0-9_-]{43,256}$') "
                    + "{ throw 'MCMCP token file is invalid' }\r\n"
                    + "[Console]::Out.Write("
                    + "\"{`\"Authorization`\":`\"Bearer $mcmcpToken`\"}\")\r\n";
            command = "powershell.exe -NoLogo -NoProfile -NonInteractive "
                    + "-ExecutionPolicy Bypass -File \"" + helper + "\"";
        } else {
            body = "#!/bin/sh\nset -eu\n"
                    + "mcmcp_token=$(tr -d '\\r\\n' < \"$(dirname \"$0\")/mcp-token\")\n"
                    + "printf '{\"Authorization\":\"Bearer %s\"}' \"$mcmcp_token\"\n";
            command = "sh \"" + helper.toString().replace("\\", "\\\\")
                    .replace("\"", "\\\"") + "\"";
        }
        atomicWrite(helper, body);
        return command;
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
        CLAUDE_CODE
    }

    public record Result(boolean success, String code, Path configPath) {
        public Result {
            Objects.requireNonNull(code, "code");
        }
    }
}
