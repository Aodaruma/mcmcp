package dev.aod.mcmcp.adminbridge;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Immutable, hash-addressed fixture loaded from the external fixture directory. */
public record FixtureScript(
        FixtureManifest manifest,
        List<RestrictedCommandPolicy.ValidatedCommand> commands,
        String sha256,
        Path directory) {
    public FixtureScript {
        Objects.requireNonNull(manifest, "manifest");
        commands = List.copyOf(commands);
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(directory, "directory");
    }
}
