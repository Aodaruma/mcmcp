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
}
