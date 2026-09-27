package dev.aod.mcmcp.client;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
        assertThat(McpClientAutoConfigurator.anyClientConfigured(game, home, 8765)).isTrue();

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
    void managedCodexRegistrationCoexistsWithAnUnrelatedMultilineValue() throws Exception {
        Path game = gameWithToken();
        Path home = temporary.resolve("multiline-home");
        Path config = home.resolve(".codex/config.toml");
        Files.createDirectories(config.getParent());
        String unrelated = "notice = \"\"\"\nWelcome to this profile\n\"\"\"\n";
        Files.writeString(config, unrelated);

        assertThat(McpClientAutoConfigurator.configure(
                McpClientAutoConfigurator.Target.CODEX, game, home, 8765).success()).isTrue();
        assertThat(McpClientAutoConfigurator.diagnose(
                McpClientAutoConfigurator.Target.CODEX, game, home, 8765)).isEqualTo("configured");
        assertThat(McpClientAutoConfigurator.configure(
                McpClientAutoConfigurator.Target.CODEX, game, home, 8765).success()).isTrue();
        assertThat(Files.readString(config)).contains(unrelated.strip());
    }

    @Test
    void concurrentSamePortProfilesCannotReplaceTheWinner() throws Exception {
        for (var target : List.of(McpClientAutoConfigurator.Target.CODEX,
                McpClientAutoConfigurator.Target.CLAUDE_CODE)) {
            Path first = gameWithToken("parallel/" + target + "/first");
            Path second = gameWithToken("parallel/" + target + "/second");
            Path home = temporary.resolve("parallel-home-" + target);
            var start = new CountDownLatch(1);
            try (var workers = Executors.newFixedThreadPool(2)) {
                var a = workers.submit(() -> {
                    start.await();
                    return McpClientAutoConfigurator.configure(target, first, home, 8765);
                });
                var b = workers.submit(() -> {
                    start.await();
                    return McpClientAutoConfigurator.configure(target, second, home, 8765);
                });
                start.countDown();
                var results = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
                assertThat(results.stream().filter(McpClientAutoConfigurator.Result::success).count())
                        .isEqualTo(1L);
                assertThat(results.stream().filter(result -> "another_profile".equals(result.code())).count())
                        .isEqualTo(1L);
                assertThat(McpClientAutoConfigurator.diagnose(target, first, home, 8765))
                        .isEqualTo(results.get(0).success() ? "configured" : "another_profile");
                assertThat(McpClientAutoConfigurator.diagnose(target, second, home, 8765))
                        .isEqualTo(results.get(1).success() ? "configured" : "another_profile");
            }
        }
    }

    @Test
    void separateJvmRegistrationsWaitForTheSameConfigLock() throws Exception {
        Path first = gameWithToken("process/first");
        Path second = gameWithToken("process/second");
        Path home = temporary.resolve("process-home");
        Path config = McpClientAutoConfigurator.configPath(
                McpClientAutoConfigurator.Target.CODEX, first, home);
        Files.createDirectories(config.getParent());
        Path lockPath = config.resolveSibling(config.getFileName() + ".mcmcp.lock");
        Path gate = temporary.resolve("process-start");
        Path firstReady = temporary.resolve("process-first-ready");
        Path secondReady = temporary.resolve("process-second-ready");
        Path firstAttempt = temporary.resolve("process-first-attempt");
        Path secondAttempt = temporary.resolve("process-second-attempt");
        Path firstResult = temporary.resolve("process-first-result");
        Path secondResult = temporary.resolve("process-second-result");
        Process a = processProbe(first, home, firstReady, gate, firstAttempt, firstResult);
        Process b = processProbe(second, home, secondReady, gate, secondAttempt, secondResult);
        try {
            try (FileChannel channel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock ignored = channel.lock()) {
                waitForFile(firstReady);
                waitForFile(secondReady);
                Files.writeString(gate, "go");
                waitForFile(firstAttempt);
                waitForFile(secondAttempt);
                Thread.sleep(300);
                assertThat(firstResult).doesNotExist();
                assertThat(secondResult).doesNotExist();
            }
            assertThat(a.waitFor(20, TimeUnit.SECONDS)).isTrue();
            assertThat(b.waitFor(20, TimeUnit.SECONDS)).isTrue();
            assertThat(a.exitValue()).as(new String(a.getInputStream().readAllBytes())).isZero();
            assertThat(b.exitValue()).as(new String(b.getInputStream().readAllBytes())).isZero();
            assertThat(List.of(Files.readString(firstResult), Files.readString(secondResult)))
                    .containsExactlyInAnyOrder("configured", "another_profile");
            assertThat(lockPath).isRegularFile();
        } finally {
            if (a.isAlive()) a.destroyForcibly();
            if (b.isAlive()) b.destroyForcibly();
        }
    }

    private Process processProbe(Path game, Path home, Path ready, Path gate,
            Path attempt, Path result) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");
        return new ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"),
                RegistrationProcessProbe.class.getName(), game.toString(), home.toString(),
                ready.toString(), gate.toString(), attempt.toString(), result.toString())
                .redirectErrorStream(true).start();
    }

    private static void waitForFile(Path file) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!Files.exists(file) && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(file).exists();
    }

    public static final class RegistrationProcessProbe {
        public static void main(String[] args) throws Exception {
            Path game = Path.of(args[0]);
            Path home = Path.of(args[1]);
            Path ready = Path.of(args[2]);
            Path gate = Path.of(args[3]);
            Path attempt = Path.of(args[4]);
            Path result = Path.of(args[5]);
            Files.writeString(ready, "ready");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!Files.exists(gate) && System.nanoTime() < deadline) Thread.sleep(20);
            if (!Files.exists(gate)) throw new IllegalStateException("process gate timed out");
            Files.writeString(attempt, "attempt");
            var configured = McpClientAutoConfigurator.configure(
                    McpClientAutoConfigurator.Target.CODEX, game, home, 8765);
            Files.writeString(result, configured.code());
        }
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

    @Test
    void samePortProfilesNeverOverwriteEachOtherOrSuppressTheSetupNotice() throws Exception {
        for (var target : new McpClientAutoConfigurator.Target[] {
                McpClientAutoConfigurator.Target.CODEX, McpClientAutoConfigurator.Target.CLAUDE_CODE}) {
            Path game = gameWithToken("normal");
            Path validation = gameWithToken("validation");
            Path home = temporary.resolve(target.name());
            assertThat(McpClientAutoConfigurator.configure(target, game, home, 8765).success()).isTrue();
            Path file = McpClientAutoConfigurator.configPath(target, game, home);
            byte[] before = Files.readAllBytes(file);
            assertThat(McpClientAutoConfigurator.diagnose(target, validation, home, 8765))
                    .isEqualTo("another_profile");
            assertThat(McpClientAutoConfigurator.anyClientConfigured(validation, home, 8765)).isFalse();
            assertThat(McpClientAutoConfigurator.configure(target, validation, home, 8765).code())
                    .isEqualTo("another_profile");
            assertThat(Files.readAllBytes(file)).isEqualTo(before);
            assertThat(helper(validation)).doesNotExist();
            assertThat(file.resolveSibling(file.getFileName() + ".mcmcp.bak")).doesNotExist();
        }
    }

    @Test
    void isolatedSetupPreservesTheGlobalRegistrationAndDoesNotImplyClientSelection() throws Exception {
        Path game = gameWithToken();
        Path validation = gameWithToken("validation");
        Path home = temporary.resolve("home");
        var global = McpClientAutoConfigurator.configure(McpClientAutoConfigurator.Target.CODEX, game, home, 8765);
        byte[] before = Files.readAllBytes(global.configPath());
        var isolated = McpClientAutoConfigurator.configure(
                McpClientAutoConfigurator.Target.CODEX_ISOLATED, validation, home, 8765);
        assertThat(isolated.success()).isTrue();
        assertThat(isolated.configPath()).isEqualTo(validation.resolve("config/mcmcp/codex-home/config.toml"));
        assertThat(Files.readAllBytes(global.configPath())).isEqualTo(before);
        assertThat(McpClientAutoConfigurator.diagnose(
                McpClientAutoConfigurator.Target.CODEX_ISOLATED, validation, home, 8765)).isEqualTo("configured");
        assertThat(McpClientAutoConfigurator.anyClientConfigured(validation, home, 8765)).isFalse();
    }

    @Test
    void staleAndMissingHelpersAreDistinctAndRepairableOnlyForTheSameProfile() throws Exception {
        Path game = gameWithToken();
        Path home = temporary.resolve("home");
        var target = McpClientAutoConfigurator.Target.CODEX;
        McpClientAutoConfigurator.configure(target, game, home, 8765);
        Files.writeString(helper(game), "throw 'FAKE_PRIVATE_HELPER_CONTENT'");
        assertThat(McpClientAutoConfigurator.diagnose(target, game, home, 8765)).isEqualTo("stale_helper");
        assertThat(McpClientAutoConfigurator.anyClientConfigured(game, home, 8765)).isFalse();
        Files.delete(helper(game));
        assertThat(McpClientAutoConfigurator.diagnose(target, game, home, 8765)).isEqualTo("helper_missing");
        assertThat(McpClientAutoConfigurator.configure(target, game, home, 8765).success()).isTrue();
        assertThat(McpClientAutoConfigurator.diagnose(target, game, home, 8765)).isEqualTo("configured");
        assertThat(McpClientAutoConfigurator.diagnose(target, game, home, 8766)).isEqualTo("endpoint_mismatch");
    }

    @Test
    void unconfiguredAndManualSameUrlAreDistinctAndManualEntriesRemainUntouched() throws Exception {
        Path game = gameWithToken();
        Path home = temporary.resolve("home");
        for (var target : new McpClientAutoConfigurator.Target[] {
                McpClientAutoConfigurator.Target.CODEX, McpClientAutoConfigurator.Target.CLAUDE_CODE}) {
            assertThat(McpClientAutoConfigurator.diagnose(target, game, home, 8765)).isEqualTo("unconfigured");
            Path config = McpClientAutoConfigurator.configPath(target, game, home);
            Files.createDirectories(config.getParent());
            String manual = target == McpClientAutoConfigurator.Target.CODEX
                    ? "[mcp_servers.mcmcp]\nurl = \"http://127.0.0.1:8765/mcp\"\nbearer_token_env_var = \"FAKE_SECRET_ENV\"\n"
                    : "{\"mcpServers\":{\"mcmcp\":{\"type\":\"http\",\"url\":\"http://127.0.0.1:8765/mcp\",\"headers\":{\"Authorization\":\"FAKE_SECRET\"}}}}";
            Files.writeString(config, manual);
            assertThat(McpClientAutoConfigurator.diagnose(target, game, home, 8765)).isEqualTo("unmanaged_unknown");
            assertThat(McpClientAutoConfigurator.configure(target, game, home, 8765).code())
                    .isEqualTo("existing_unmanaged_entry");
            assertThat(Files.readString(config)).isEqualTo(manual);
        }
        assertThat(McpClientAutoConfigurator.anyClientConfigured(game, home, 8765)).isFalse();
        assertThat(helper(game)).doesNotExist();
    }

    @Test
    void virtualizedPrivateCopyIsNotAnAliasEvenWithIdenticalFakeTokens() throws Exception {
        Path game = gameWithToken("Prism/instances/通常 profile/minecraft");
        Path copied = gameWithToken("Packages/FakeApp/LocalCache/Prism/instances/通常 profile/minecraft");
        Path visibleHome = temporary.resolve("Packages/FakeApp/LocalCache/userHome");
        var target = McpClientAutoConfigurator.Target.CODEX;
        var result = McpClientAutoConfigurator.configure(target, copied, visibleHome, 8765);
        assertThat(McpClientAutoConfigurator.diagnoseConfig(target, game, result.configPath(), 8765))
                .isEqualTo("another_profile");
        assertThat(McpClientAutoConfigurator.configure(target, game, visibleHome, 8765).code())
                .isEqualTo("another_profile");
    }

    @Test
    void windowsCanonicalPathViewAndOrdinaryPathReferToTheSameProfile() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path game = gameWithToken("Prism/instances/日本語 profile/minecraft");
        Path home = temporary.resolve("home");
        var target = McpClientAutoConfigurator.Target.CODEX;
        assertThat(McpClientAutoConfigurator.configure(target, game, home, 8765).success()).isTrue();
        Path canonicalView = Path.of("\\\\?\\" + game.toAbsolutePath());
        assertThat(McpClientAutoConfigurator.diagnose(target, canonicalView, home, 8765)).isEqualTo("configured");
        assertThat(McpClientAutoConfigurator.configure(target, canonicalView, home, 8765).success()).isTrue();
        assertThat(McpClientAutoConfigurator.diagnose(target, game, home, 8765)).isEqualTo("configured");
    }

    @Test
    void ambiguousManagedBlocksAndExtraAuthenticationNeverGetReplaced() throws Exception {
        Path game = gameWithToken();
        Path home = temporary.resolve("home");
        var target = McpClientAutoConfigurator.Target.CODEX;
        Path config = McpClientAutoConfigurator.configure(target, game, home, 8765).configPath();
        String generated = Files.readString(config);
        for (String changed : new String[] { generated + generated,
                generated.replace("startup_timeout_sec = 30", "bearer_token_env_var = \"FAKE_SECRET_ENV\""),
                generated + "bearer_token_env_var = \"FAKE_SECRET_ENV\"\n",
                generated + "\n[mcp_servers.\"mcmcp\"]\nurl = \"http://127.0.0.1:8765/mcp\"\n",
                "message = \"\"\"\n" + generated + "\"\"\"\n" }) {
            Files.writeString(config, changed);
            assertThat(McpClientAutoConfigurator.configure(target, game, home, 8765).code())
                    .isEqualTo("existing_unmanaged_entry");
            assertThat(Files.readString(config)).isEqualTo(changed);
        }
    }

    @Test
    void quotedInlineAndEscapedManualTablesArePreservedWithoutReadingCredentials() throws Exception {
        Path game = gameWithToken();
        Path home = temporary.resolve("home");
        var target = McpClientAutoConfigurator.Target.CODEX;
        Path config = McpClientAutoConfigurator.configPath(target, game, home);
        Files.createDirectories(config.getParent());
        for (String manual : new String[] {
                "[mcp_servers.\"mcmcp\"]\nurl = \"http://127.0.0.1:8765/mcp\"\n",
                "[mcp_servers.\"\\u006dcmcp\"]\nurl = \"http://127.0.0.1:8765/mcp\"\n",
                "mcp_servers = {mcmcp = {url = \"http://127.0.0.1:8765/mcp\"}}\n"}) {
            Files.writeString(config, manual);
            assertThat(McpClientAutoConfigurator.diagnose(target, game, home, 8765)).isEqualTo("unmanaged_unknown");
            assertThat(McpClientAutoConfigurator.configure(target, game, home, 8765).code())
                    .isEqualTo("existing_unmanaged_entry");
            assertThat(Files.readString(config)).isEqualTo(manual);
        }
        assertThat(helper(game)).doesNotExist();
    }

    @Test
    void urlInUnrelatedConfigIsNotARegistrationAndReadOnlyDiagnosisWritesNothing() throws Exception {
        Path game = gameWithToken();
        Path home = temporary.resolve("home");
        var target = McpClientAutoConfigurator.Target.CODEX;
        Path config = McpClientAutoConfigurator.configPath(target, game, home);
        Files.createDirectories(config.getParent());
        String unrelated = "# http://127.0.0.1:8765/mcp\n[mcp_servers.other]\nurl = \"http://127.0.0.1:8765/mcp\"\n";
        Files.writeString(config, unrelated);
        assertThat(McpClientAutoConfigurator.diagnose(target, game, home, 8765)).isEqualTo("unconfigured");
        assertThat(Files.readString(config)).isEqualTo(unrelated);
        assertThat(helper(game)).doesNotExist();
        assertThat(config.resolveSibling("config.toml.mcmcp.bak")).doesNotExist();
    }

    private static Path helper(Path game) {
        return game.resolve("config/mcmcp").resolve(System.getProperty("os.name").startsWith("Windows")
                ? "mcmcp-auth-headers.ps1" : "mcmcp-auth-headers.sh");
    }

    private Path gameWithToken() throws Exception {
        return gameWithToken("game");
    }

    private Path gameWithToken(String name) throws Exception {
        Path game = temporary.resolve(name);
        Path directory = game.resolve("config/mcmcp");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("mcp-token"), token() + "\n");
        return game;
    }

    private static String token() {
        return "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    }
}
