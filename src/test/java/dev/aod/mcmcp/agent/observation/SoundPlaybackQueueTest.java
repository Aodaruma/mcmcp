package dev.aod.mcmcp.agent.observation;

import net.minecraft.sounds.SoundSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SoundPlaybackQueueTest {
    private static final ObservationValues.ResourceId OVERWORLD =
            new ObservationValues.ResourceId("minecraft:overworld");

    @Test
    void drainsOnlyAcceptedPositionSoundsAndBuildsEntityHintOnClientThread() {
        var queue = new SoundPlaybackQueue();
        var store = new SoundClueStore();

        assertThat(queue.capturePositionSound(
                "minecraft:entity.zombie.ambient", SoundSource.HOSTILE, 1, 64, 2)).isTrue();
        assertThat(queue.capturePositionSound(
                "minecraft:music.game", SoundSource.MUSIC, 1, 64, 2)).isFalse();
        assertThat(queue.capturePositionSound(
                "minecraft:ui.button.click", SoundSource.UI, 1, 64, 2)).isFalse();
        assertThat(queue.capturePositionSound(
                "not a resource id", SoundSource.BLOCKS, 1, 64, 2)).isFalse();
        assertThat(queue.capturePositionSound(
                "minecraft:block.note_block.harp", SoundSource.BLOCKS,
                Double.NaN, 64, 2)).isFalse();

        var drained = queue.drainInto(
                store, OVERWORLD, 20, 3, "minecraft:zombie"::equals);

        assertThat(drained.recordedPlaybacks()).isOne();
        assertThat(drained.recentSoundCluesTruncated()).isFalse();
        assertThat(store.snapshot(20).clues()).singleElement().satisfies(clue -> {
            assertThat(clue.soundEvent().value())
                    .isEqualTo("minecraft:entity.zombie.ambient");
            assertThat(clue.category()).isEqualTo(ObservationRecord.SoundCategory.HOSTILE);
            assertThat(clue.position())
                    .isEqualTo(new ObservationValues.WorldPosition(OVERWORLD, 1, 64, 2));
            assertThat(clue.entityHint().value()).isEqualTo("minecraft:zombie");
        });
    }

    @Test
    void reportsQueueOverflowForTheSameTtlAsSoundClues() {
        var queue = new SoundPlaybackQueue(1);
        var store = new SoundClueStore();
        assertThat(queue.capturePositionSound(
                "minecraft:block.note_block.harp", SoundSource.BLOCKS, 0, 64, 0)).isTrue();
        assertThat(queue.capturePositionSound(
                "minecraft:block.note_block.bass", SoundSource.BLOCKS, 3, 64, 0)).isFalse();
        assertThat(queue.drainInto(store, OVERWORLD, 10, 0, ignored -> false))
                .isEqualTo(new SoundPlaybackQueue.DrainResult(1, true));
        assertThat(queue.drainInto(store, OVERWORLD, 610, 0, ignored -> false)
                .recentSoundCluesTruncated()).isTrue();
        assertThat(queue.drainInto(store, OVERWORLD, 611, 0, ignored -> false)
                .recentSoundCluesTruncated()).isFalse();

        queue.capturePositionSound(
                "minecraft:block.note_block.harp", SoundSource.BLOCKS, 0, 64, 0);
        queue.clear();
        assertThat(queue.drainInto(store, OVERWORLD, 612, 0, ignored -> false))
                .isEqualTo(new SoundPlaybackQueue.DrainResult(0, false));
    }

    @Test
    void preservesRepeatedLevelEventsAsOccurrences() {
        var queue = new SoundPlaybackQueue();
        var store = new SoundClueStore();

        queue.capturePositionSound(
                "minecraft:entity.fishing_bobber.splash", SoundSource.NEUTRAL,
                199, 202, 200);
        queue.capturePositionSound(
                "minecraft:entity.fishing_bobber.splash", SoundSource.NEUTRAL,
                199, 202, 200);

        assertThat(queue.drainInto(store, OVERWORLD, 40, 1, ignored -> false)
                .recordedPlaybacks()).isEqualTo(2);
        assertThat(store.snapshot(40).clues()).singleElement().satisfies(clue -> {
            assertThat(clue.soundEvent().value())
                    .isEqualTo("minecraft:entity.fishing_bobber.splash");
            assertThat(clue.occurrences()).isEqualTo(2);
        });
    }

}
