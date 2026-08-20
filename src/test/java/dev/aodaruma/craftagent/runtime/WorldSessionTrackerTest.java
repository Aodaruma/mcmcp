package dev.aodaruma.craftagent.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorldSessionTrackerTest {
    @Test
    void keepsJoinSessionAcrossDimensionAndFencesGeneration() {
        var tracker = new WorldSessionTracker();
        tracker.resourcesReady();
        var session = tracker.beginConnection();
        tracker.latchReady("minecraft:overworld");
        var overworld = tracker.snapshot();
        tracker.latchReady("minecraft:the_nether");
        var nether = tracker.snapshot();

        assertThat(overworld.worldSessionId()).isEqualTo(session);
        assertThat(nether.worldSessionId()).isEqualTo(session);
        assertThat(nether.generation()).isGreaterThan(overworld.generation());
        assertThat(nether.dimension()).isEqualTo("minecraft:the_nether");
    }

    @Test
    void logoutDetachesTheSession() {
        var tracker = new WorldSessionTracker();
        tracker.beginConnection();
        tracker.latchReady("minecraft:overworld");
        tracker.invalidate();

        assertThat(tracker.snapshot().worldReady()).isFalse();
        assertThat(tracker.snapshot().worldSessionId()).isNull();
    }
}
