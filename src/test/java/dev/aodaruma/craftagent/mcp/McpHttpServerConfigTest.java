package dev.aodaruma.craftagent.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class McpHttpServerConfigTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void neverIncludesBearerSecretInDiagnosticString() {
        McpHttpServerConfig config = McpHttpServerConfig.builder(
                temporaryDirectory, McpTestFixtures.TOKEN).build();

        assertThat(config.toString())
                .contains("bearerToken=<redacted>")
                .doesNotContain(McpTestFixtures.TOKEN);
    }

    @Test
    void rejectsTokensThatCannotBeSafelyPlacedInAnAuthorizationHeader() {
        assertThatIllegalArgumentException().isThrownBy(() -> McpHttpServerConfig.builder(
                temporaryDirectory, "01234567890123456789012345678901\nforged").build());
    }

    @Test
    void rejectsUnboundedJsonDepthConfiguration() {
        assertThatIllegalArgumentException().isThrownBy(() -> McpHttpServerConfig.builder(
                temporaryDirectory, McpTestFixtures.TOKEN).maxJsonDepth(129).build());
    }
}
