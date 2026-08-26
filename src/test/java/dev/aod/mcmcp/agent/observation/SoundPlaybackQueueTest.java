package dev.aod.mcmcp.agent.observation;

import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SoundPlaybackQueueTest {
    private static final ObservationValues.ResourceId OVERWORLD =
            new ObservationValues.ResourceId("minecraft:overworld");

    @Test
    void drainsOnlyActualPositionSoundsAndBuildsEntityHintOnClientThread() {
        var queue = new SoundPlaybackQueue();
        var store = new SoundClueStore();

        assertThat(queue.capturePlaybackStart(sound(
                "minecraft:entity.zombie.ambient", SoundSource.HOSTILE,
                false, SoundInstance.Attenuation.LINEAR, 1, 64, 2))).isTrue();
        assertThat(queue.capturePlaybackStart(sound(
                "minecraft:entity.zombie.ambient", SoundSource.HOSTILE,
                true, SoundInstance.Attenuation.LINEAR, 1, 64, 2))).isFalse();
        assertThat(queue.capturePlaybackStart(sound(
                "minecraft:entity.zombie.ambient", SoundSource.HOSTILE,
                false, SoundInstance.Attenuation.NONE, 1, 64, 2))).isFalse();
        assertThat(queue.capturePlaybackStart(sound(
                "minecraft:music.game", SoundSource.MUSIC,
                false, SoundInstance.Attenuation.LINEAR, 1, 64, 2))).isFalse();
        assertThat(queue.capturePlaybackStart(sound(
                "minecraft:ui.button.click", SoundSource.UI,
                false, SoundInstance.Attenuation.LINEAR, 1, 64, 2))).isFalse();
        assertThat(queue.capturePlaybackStart(sound(
                "minecraft:block.note_block.harp", SoundSource.BLOCKS,
                false, SoundInstance.Attenuation.LINEAR, Double.NaN, 64, 2))).isFalse();

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
        var first = sound("minecraft:block.note_block.harp", SoundSource.BLOCKS,
                false, SoundInstance.Attenuation.LINEAR, 0, 64, 0);
        var dropped = sound("minecraft:block.note_block.bass", SoundSource.BLOCKS,
                false, SoundInstance.Attenuation.LINEAR, 3, 64, 0);

        assertThat(queue.capturePlaybackStart(first)).isTrue();
        assertThat(queue.capturePlaybackStart(dropped)).isFalse();
        assertThat(queue.drainInto(store, OVERWORLD, 10, 0, ignored -> false))
                .isEqualTo(new SoundPlaybackQueue.DrainResult(1, true));
        assertThat(queue.drainInto(store, OVERWORLD, 610, 0, ignored -> false)
                .recentSoundCluesTruncated()).isTrue();
        assertThat(queue.drainInto(store, OVERWORLD, 611, 0, ignored -> false)
                .recentSoundCluesTruncated()).isFalse();

        queue.capturePlaybackStart(first);
        queue.clear();
        assertThat(queue.drainInto(store, OVERWORLD, 612, 0, ignored -> false))
                .isEqualTo(new SoundPlaybackQueue.DrainResult(0, false));
    }

    @Test
    void playbackCapturedAcrossClearCannotEnterTheNextWorldEpoch() throws Exception {
        var queue = new SoundPlaybackQueue();
        var store = new SoundClueStore();
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        SoundInstance blocked = new BlockingSoundInstance(entered, resume);
        var capture = new Thread(() -> queue.capturePlaybackStart(blocked));

        capture.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        queue.clear();
        resume.countDown();
        capture.join(5_000);

        assertThat(capture.isAlive()).isFalse();
        assertThat(queue.drainInto(store, OVERWORLD, 20, 1, ignored -> false))
                .isEqualTo(new SoundPlaybackQueue.DrainResult(0, false));
        assertThat(store.snapshot(20).clues()).isEmpty();
    }

    private static SoundInstance sound(
            String id,
            SoundSource source,
            boolean relative,
            SoundInstance.Attenuation attenuation,
            double x,
            double y,
            double z) {
        return new TestSoundInstance(
                Identifier.parse(id), source, relative, attenuation, x, y, z);
    }

    private record TestSoundInstance(
            Identifier getIdentifier,
            SoundSource getSource,
            boolean isRelative,
            SoundInstance.Attenuation getAttenuation,
            double getX,
            double getY,
            double getZ) implements SoundInstance {
        @Override public WeighedSoundEvents resolve(SoundManager soundManager) { return null; }
        @Override public Sound getSound() { return null; }
        @Override public boolean isLooping() { return false; }
        @Override public int getDelay() { return 0; }
        @Override public float getVolume() { return 1; }
        @Override public float getPitch() { return 1; }
    }

    private record BlockingSoundInstance(
            CountDownLatch entered,
            CountDownLatch resume) implements SoundInstance {
        @Override public Identifier getIdentifier() {
            return Identifier.parse("minecraft:entity.zombie.ambient");
        }
        @Override public SoundSource getSource() { return SoundSource.HOSTILE; }
        @Override public boolean isRelative() { return false; }
        @Override public Attenuation getAttenuation() { return Attenuation.LINEAR; }
        @Override public double getX() { return 1; }
        @Override public double getY() { return 64; }
        @Override public double getZ() {
            entered.countDown();
            try {
                resume.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return 2;
        }
        @Override public WeighedSoundEvents resolve(SoundManager soundManager) { return null; }
        @Override public Sound getSound() { return null; }
        @Override public boolean isLooping() { return false; }
        @Override public int getDelay() { return 0; }
        @Override public float getVolume() { return 1; }
        @Override public float getPitch() { return 1; }
    }
}
