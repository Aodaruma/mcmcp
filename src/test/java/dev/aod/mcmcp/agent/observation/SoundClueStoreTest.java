package dev.aod.mcmcp.agent.observation;

import org.junit.jupiter.api.Test;

import static dev.aod.mcmcp.agent.observation.ObservationRecord.SoundCategory.HOSTILE;
import static org.assertj.core.api.Assertions.assertThat;

class SoundClueStoreTest {
    private static final ObservationValues.ResourceId OVERWORLD = id("minecraft:overworld");

    @Test
    void mergesNearbySoundsAndExpiresThemWithoutInventingEntityEvidence() {
        var store = new SoundClueStore();
        var event = id("minecraft:entity.zombie.ambient");
        var hint = SoundClueStore.entityHint(event, "minecraft:zombie"::equals);
        store.record(event, HOSTILE, at(0, 64, 0), 5, 2, hint);
        store.record(event, HOSTILE, at(1, 64, 0), 15, 3, hint);

        var current = store.snapshot(20);
        assertThat(current.clues()).singleElement().satisfies(clue -> {
            assertThat(clue.occurrences()).isEqualTo(2);
            assertThat(clue.position()).isEqualTo(at(1, 64, 0));
            assertThat(clue.ageTicks()).isEqualTo(5);
            assertThat(clue.entityHint()).isEqualTo(id("minecraft:zombie"));
        });
        assertThat(SoundClueStore.entityHint(
                id("minecraft:entity.generic.explode"), ignored -> false)).isNull();
        assertThat(store.snapshot(616).clues()).isEmpty();
    }

    @Test
    void overflowIsReportedOnlyUntilTheDiscardedClueWouldExpire() {
        var store = new SoundClueStore();
        for (int index = 0; index <= SoundClueStore.LIMIT; index++) {
            store.record(id("minecraft:block.note_block." + index), HOSTILE,
                    at(index * 3, 64, 0), index, index, null);
        }

        assertThat(store.snapshot(100).recentSoundCluesTruncated()).isTrue();
        assertThat(store.snapshot(601).recentSoundCluesTruncated()).isFalse();
    }

    private static ObservationValues.ResourceId id(String value) {
        return new ObservationValues.ResourceId(value);
    }

    private static ObservationValues.WorldPosition at(double x, double y, double z) {
        return new ObservationValues.WorldPosition(OVERWORLD, x, y, z);
    }
}
