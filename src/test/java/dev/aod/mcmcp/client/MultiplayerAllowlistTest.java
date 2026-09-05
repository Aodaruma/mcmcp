package dev.aod.mcmcp.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MultiplayerAllowlistTest {
    @TempDir Path temporary;

    @Test
    void acceptsOnlyAnExactEntryFromTheBoundedLocalSchema() throws Exception {
        Path file = temporary.resolve("allowed-servers.json");
        Files.writeString(file, """
                {"schema_version":1,"servers":["Example.org:25565"]}
                """);

        assertThat(MultiplayerAllowlist.allows(file, "example.ORG:25565")).isTrue();
        assertThat(MultiplayerAllowlist.allows(file, "example.org")).isFalse();
        Files.writeString(file, "{\"schema_version\":1,\"servers\":[],\"extra\":true}");
        assertThat(MultiplayerAllowlist.allows(file, "example.org:25565")).isFalse();
        Files.writeString(file, "{\"schema_version\":\"1\",\"servers\":[\"example.org:25565\"]}");
        assertThat(MultiplayerAllowlist.allows(file, "example.org:25565")).isFalse();
    }

    @Test
    void physicalConfirmationCanRememberAnExactServerWithoutManualEditing() throws Exception {
        Path file = temporary.resolve("config/mcmcp/allowed-servers.json");

        assertThat(MultiplayerAllowlist.remember(file, "Example.org:25565")).isTrue();
        assertThat(MultiplayerAllowlist.allows(file, "example.org:25565")).isTrue();
        assertThat(MultiplayerAllowlist.allows(file, "example.org")).isFalse();
        assertThat(MultiplayerAllowlist.remember(file, "second.example:25565")).isTrue();
        assertThat(MultiplayerAllowlist.allows(file, "second.example:25565")).isTrue();
    }

    @Test
    void malformedAllowlistIsNeverOverwrittenByTheRememberPath() throws Exception {
        Path file = temporary.resolve("allowed-servers.json");
        Files.writeString(file, "{\"unexpected\":true}");

        assertThat(MultiplayerAllowlist.remember(file, "example.org:25565")).isFalse();
        assertThat(Files.readString(file)).isEqualTo("{\"unexpected\":true}");
    }
}
