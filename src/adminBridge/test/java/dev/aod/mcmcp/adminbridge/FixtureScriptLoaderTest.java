package dev.aod.mcmcp.adminbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixtureScriptLoaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsAndHashesAnExternalFixtureWithoutCompiledScenarioCode() throws Exception {
        writeFixture("wheat-test", manifest("wheat-test", ""), """
                # coordinates are external data
                clear @s
                setblock 1 64 1 minecraft:dirt replace
                item replace block 4 64 4 container.0 with minecraft:wheat_seeds 64
                tp @s 0.5 64 0.5 180 0
                """);

        FixtureScript first = new FixtureScriptLoader(temporaryDirectory).load("wheat-test");
        assertThat(first.manifest().id()).isEqualTo("wheat-test");
        assertThat(first.commands()).hasSize(4);
        assertThat(first.sha256()).matches("[0-9a-f]{64}");

        Files.writeString(first.directory().resolve("setup.mcfunction"),
                "setblock 2 64 2 minecraft:dirt replace\n", StandardCharsets.UTF_8);
        FixtureScript changed = new FixtureScriptLoader(temporaryDirectory).load("wheat-test");
        assertThat(changed.sha256()).isNotEqualTo(first.sha256());
    }

    @Test
    void rejectsUnknownManifestFieldsAndIdMismatch() throws Exception {
        writeFixture("fixture-a", manifest("fixture-b", ",\"unexpected\":true"),
                "clear @s\n");
        assertCode("fixture_manifest_fields_invalid", "fixture-a");

        writeFixture("fixture-c", manifest("fixture-b", ""), "clear @s\n");
        assertCode("fixture_id_mismatch", "fixture-c");
    }

    @Test
    void rejectsTraversalMalformedUtf8AndOversizedScripts() throws Exception {
        assertCode("fixture_id_invalid", "../escape");

        Path bad = temporaryDirectory.resolve("bad-utf8");
        Files.createDirectories(bad);
        Files.writeString(bad.resolve("fixture.json"), manifest("bad-utf8", ""));
        Files.write(bad.resolve("setup.mcfunction"), new byte[] {(byte) 0xc3, (byte) 0x28});
        assertCode("fixture_utf8_invalid", "bad-utf8");

        Path huge = temporaryDirectory.resolve("huge");
        Files.createDirectories(huge);
        Files.writeString(huge.resolve("fixture.json"), manifest("huge", ""));
        Files.writeString(huge.resolve("setup.mcfunction"), "x".repeat(65_537));
        assertCode("fixture_file_invalid", "huge");
    }

    @Test
    void validatesRandomTickLeaseBounds() throws Exception {
        writeFixture("lease", manifest("lease",
                ",\"random_tick_speed\":{\"target\":3000,\"maximum_seconds\":1200}"),
                "clear @s\n");
        FixtureScript loaded = new FixtureScriptLoader(temporaryDirectory).load("lease");
        assertThat(loaded.manifest().randomTickLease())
                .isEqualTo(new FixtureManifest.RandomTickLease(3000, 1200));

        writeFixture("bad-lease", manifest("bad-lease",
                ",\"random_tick_speed\":{\"target\":5000,\"maximum_seconds\":1200}"),
                "clear @s\n");
        assertCode("random_tick_speed_invalid", "bad-lease");
    }

    @Test
    void rejectsFractionalIntegersAndCoordinatesBeyondTheWorldEnvelope() throws Exception {
        writeFixture("fractional", manifest("fractional", "")
                        .replace("\"max_changed_blocks\": 64", "\"max_changed_blocks\": 1.5"),
                "clear @s\n");
        assertCode("fixture_manifest_invalid", "fractional");

        writeFixture("far-away", manifest("far-away", "")
                        .replace("\"x\": 10", "\"x\": 30000001"),
                "clear @s\n");
        assertCode("position_invalid", "far-away");
    }

    @Test
    void repositoryWheatFixtureSatisfiesTheExternalFixtureContract() throws Exception {
        Path projectDirectory = Path.of(System.getProperty("mcmcp.projectDir"));
        FixtureScript fixture = new FixtureScriptLoader(
                projectDirectory.resolve("fixtures")).load("wheat-original-v1");

        assertThat(fixture.manifest().dimension()).isEqualTo("minecraft:overworld");
        assertThat(fixture.manifest().randomTickLease())
                .isEqualTo(new FixtureManifest.RandomTickLease(3000, 1320));
        assertThat(fixture.sha256()).matches("[0-9a-f]{64}");

        String script = fixture.commands().stream()
                .map(RestrictedCommandPolicy.ValidatedCommand::source)
                .collect(Collectors.joining("\n"));
        assertThat(script)
                .contains("gamemode survival @s")
                .contains("minecraft:oak_fence_gate[")
                .contains("open=false")
                .contains("minecraft:netherite_hoe 1")
                .contains("minecraft:wheat_seeds 64")
                .doesNotContain("minecraft:water")
                .doesNotContain("minecraft:spruce_trapdoor");
    }

    private void writeFixture(String directory, String manifest, String script) throws IOException {
        Path target = temporaryDirectory.resolve(directory);
        Files.createDirectories(target);
        Files.writeString(target.resolve("fixture.json"), manifest, StandardCharsets.UTF_8);
        Files.writeString(target.resolve("setup.mcfunction"), script, StandardCharsets.UTF_8);
    }

    private void assertCode(String code, String fixtureId) {
        assertThatThrownBy(() -> new FixtureScriptLoader(temporaryDirectory).load(fixtureId))
                .isInstanceOfSatisfying(FixtureFormatException.class,
                        error -> assertThat(error.code()).isEqualTo(code));
    }

    private static String manifest(String id, String extension) {
        return """
                {
                  "schema_version": 1,
                  "id": "%s",
                  "dimension": "minecraft:overworld",
                  "mutation_bounds": {
                    "min": {"x": 0, "y": 60, "z": 0},
                    "max": {"x": 10, "y": 70, "z": 10}
                  },
                  "player_bounds": {
                    "min": {"x": 0, "y": 60, "z": 0},
                    "max": {"x": 10, "y": 70, "z": 10}
                  },
                  "max_changed_blocks": 64,
                  "containers": [{"x": 4, "y": 64, "z": 4}]
                  %s
                }
                """.formatted(id, extension);
    }
}
