package dev.aod.mcmcp.runtime;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class TestHarnessWorldSessionAccessTest {
    @Test
    void bothDevelopmentGuardsMustRemainEnabled() {
        var enabled = new AtomicBoolean(false);
        var loaded = new AtomicBoolean(false);
        var access = new TestHarnessWorldSessionAccess(enabled::get, loaded::get);
        UUID session = UUID.randomUUID();
        access.bindSnapshot(() -> ready(session));
        assertThatIllegalStateException().isThrownBy(access::read);
        enabled.set(true);
        assertThatIllegalStateException().isThrownBy(access::read);
        loaded.set(true);
        assertThat(access.read()).isEqualTo(session);
        enabled.set(false);
        assertThatIllegalStateException().isThrownBy(access::read);
    }

    @Test
    void unboundNotReadyAndShutdownSnapshotsFailClosed() {
        var access = new TestHarnessWorldSessionAccess(() -> true, () -> true);
        assertThatIllegalStateException().isThrownBy(access::read);
        var current = new AtomicReference<WorldSessionTracker.Snapshot>();
        access.bindSnapshot(current::get);
        assertThatIllegalStateException().isThrownBy(access::read);
        for (var readiness : WorldSessionTracker.Readiness.values()) {
            current.set(new WorldSessionTracker.Snapshot(readiness, 1, 2, null, "minecraft:overworld"));
            assertThatIllegalStateException().isThrownBy(access::read);
        }
        current.set(new WorldSessionTracker.Snapshot(WorldSessionTracker.Readiness.STOPPING, 1, 2,
                UUID.randomUUID(), "minecraft:overworld"));
        assertThatIllegalStateException().isThrownBy(access::read);
    }

    @Test
    void replacementAndWorldChangeReadTheCurrentRealSnapshotRatherThanCachingAUuid() {
        var access = new TestHarnessWorldSessionAccess(() -> true, () -> true);
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        var snapshot = new AtomicReference<>(ready(first));
        access.bindSnapshot(snapshot::get);
        assertThat(access.read()).isEqualTo(first);
        snapshot.set(ready(second));
        assertThat(access.read()).isEqualTo(second).isNotEqualTo(first);
        access.bindSnapshot(() -> ready(first));
        assertThat(access.read()).isEqualTo(first);
    }

    private static WorldSessionTracker.Snapshot ready(UUID session) {
        return new WorldSessionTracker.Snapshot(WorldSessionTracker.Readiness.WORLD_READY,
                1, 2, session, "minecraft:overworld");
    }
}
