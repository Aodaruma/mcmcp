package dev.aod.mcmcp.client;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class McpClientAutoConfiguratorTest {
    @TempDir Path temporary;

    @Test
    void codexSetupUsesADynamicHelperWithoutCopyingTheToken() throws Exception {
        Path game = gameWithToken();
        Path home = temporary.resolve("home");
        Path config = home.resolve(".codex/config.toml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "model = \"example\"\n");

        var result = McpClientAutoConfigurator.configure(
                McpClientAutoConfigurator.Target.CODEX, game, home, 8765);

        assertThat(result.success()).isTrue();
        String written = Files.readString(config);
        assertThat(written)
                .contains("model = \"example\"")
                .contains("[mcp_servers.mcmcp]")
                .contains("http_headers_helper")
                .contains("http://127.0.0.1:8765/mcp")
                .doesNotContain(token());
        assertThat(config.resolveSibling("config.toml.mcmcp.bak")).exists();
        assertThat(McpClientAutoConfigurator.anyClientConfigured(home, 8765)).isTrue();

        Path helper = game.resolve("config/mcmcp").resolve(
                System.getProperty("os.name", "").toLowerCase().contains("win")
                        ? "mcmcp-auth-headers.ps1" : "mcmcp-auth-headers.sh");
        Process process = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? new ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile",
                        "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File",
                        helper.toString()).start()
                : new ProcessBuilder("sh", helper.toString()).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).isZero();
        assertThat(JsonParser.parseString(output).getAsJsonObject()
                .get("Authorization").getAsString()).isEqualTo("Bearer " + token());

        assertThat(McpClientAutoConfigurator.configure(
                McpClientAutoConfigurator.Target.CODEX, game, home, 8765).success()).isTrue();
        assertThat(Files.readString(config).split("BEGIN MCMCP AUTO-CONFIG", -1))
                .hasSize(2);
    }

    @Test
    void claudeCodeSetupPreservesOtherGlobalProperties() throws Exception {
        Path game = gameWithToken();
        Path home = temporary.resolve("home");
        Files.createDirectories(home);
        Path config = home.resolve(".claude.json");
        Files.writeString(config, "{\"theme\":\"dark\"}");

        var result = McpClientAutoConfigurator.configure(
                McpClientAutoConfigurator.Target.CLAUDE_CODE, game, home, 8765);

        assertThat(result.success()).isTrue();
        var root = JsonParser.parseString(Files.readString(config)).getAsJsonObject();
        assertThat(root.get("theme").getAsString()).isEqualTo("dark");
        var mcmcp = root.getAsJsonObject("mcpServers").getAsJsonObject("mcmcp");
        assertThat(mcmcp.get("url").getAsString())
                .isEqualTo("http://127.0.0.1:8765/mcp");
        assertThat(mcmcp.get("headersHelper").getAsString()).isNotBlank();
        assertThat(Files.readString(config)).doesNotContain(token());
        assertThat(config.resolveSibling(".claude.json.mcmcp.bak")).exists();
    }

    @Test
    void refusesToOverwriteAnUnmanagedCodexEntry() throws Exception {
        Path game = gameWithToken();
        Path home = temporary.resolve("home");
        Path config = home.resolve(".codex/config.toml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "[mcp_servers.mcmcp]\nurl = \"https://example.invalid\"\n");

        var result = McpClientAutoConfigurator.configure(
                McpClientAutoConfigurator.Target.CODEX, game, home, 8765);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("existing_unmanaged_entry");
        assertThat(Files.readString(config)).contains("https://example.invalid");
    }

    private Path gameWithToken() throws Exception {
        Path game = temporary.resolve("game");
        Path directory = game.resolve("config/mcmcp");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("mcp-token"), token() + "\n");
        return game;
    }

    private static String token() {
        return "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    }
}
