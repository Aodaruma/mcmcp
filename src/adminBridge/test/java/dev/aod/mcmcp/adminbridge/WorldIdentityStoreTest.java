package dev.aod.mcmcp.adminbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorldIdentityStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsAWorldIdAndAlsoBindsItToTheCanonicalSavePath() throws Exception {
        Path world = temporaryDirectory.resolve("world");
        Files.createDirectory(world);
        WorldIdentityStore store = new WorldIdentityStore();

        WorldIdentityStore.Identity first = store.loadOrCreate(world);
        WorldIdentityStore.Identity second = store.loadOrCreate(world);

        assertThat(second).isEqualTo(first);
        assertThat(first.worldPathSha256()).matches("[0-9a-f]{64}");
        assertThat(first.worldId()).matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

        Path otherWorld = temporaryDirectory.resolve("other-world");
        Files.createDirectory(otherWorld);
        WorldIdentityStore.Identity other = store.loadOrCreate(otherWorld);
        assertThat(other.worldPathSha256()).isNotEqualTo(first.worldPathSha256());
        assertThat(other.worldId()).isNotEqualTo(first.worldId());
    }

    @Test
    void failsClosedForMalformedPersistentMarker() throws Exception {
        Path world = temporaryDirectory.resolve("world");
        Files.createDirectory(world);
        Files.writeString(world.resolve(".mcmcp-fixture-admin-world-id"), "not-a-uuid");

        assertThatThrownBy(() -> new WorldIdentityStore().loadOrCreate(world))
                .isInstanceOfSatisfying(WorldIdentityStore.IdentityException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("world_identity_marker_invalid"));
    }
}
