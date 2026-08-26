package dev.aod.mcmcp.agent.observation;

import dev.aod.mcmcp.agent.observation.ObservationRecord.SoundCategory;
import dev.aod.mcmcp.agent.observation.ObservationValues.ResourceId;
import dev.aod.mcmcp.agent.observation.ObservationValues.WorldPosition;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;

import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/** Moves actual position-sound playback starts onto the client tick thread. */
public final class SoundPlaybackQueue {
    public static final int CAPACITY = 256;

    private final ArrayBlockingQueue<Playback> pending;
    private final AtomicBoolean overflowed = new AtomicBoolean();
    private final AtomicLong epoch = new AtomicLong();
    private long truncatedThroughTick = -1;

    public SoundPlaybackQueue() {
        this(CAPACITY);
    }

    SoundPlaybackQueue(int capacity) {
        pending = new ArrayBlockingQueue<>(capacity);
    }

    /**
     * Captures values from a {@code PlaySoundSourceEvent} or
     * {@code PlayStreamingSourceEvent}; no game objects are retained.
     */
    public boolean capturePlaybackStart(SoundInstance sound) {
        Objects.requireNonNull(sound, "sound");
        long captureEpoch = epoch.get();
        Identifier identifier = sound.getIdentifier();
        SoundCategory category = category(sound.getSource());
        double x = sound.getX();
        double y = sound.getY();
        double z = sound.getZ();
        if (sound.isRelative()
                || sound.getAttenuation() != SoundInstance.Attenuation.LINEAR
                || category == null
                || identifier == null
                || !validPosition(x, y, z)) {
            return false;
        }

        final ResourceId soundEvent;
        try {
            soundEvent = new ResourceId(identifier.toString());
        } catch (IllegalArgumentException exception) {
            return false;
        }
        if (pending.offer(new Playback(captureEpoch, soundEvent, category, x, y, z))) {
            return true;
        }
        overflowed.set(true);
        return false;
    }

    /** Drains queued values and performs registry-backed entity hinting on the client tick thread. */
    public DrainResult drainInto(
            SoundClueStore store,
            ResourceId dimension,
            long tick,
            long worldRevision,
            Predicate<String> registeredEntityType) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(registeredEntityType, "registeredEntityType");
        ObservationValues.requireTick(tick, "tick");
        ObservationValues.requireTick(worldRevision, "worldRevision");

        if (overflowed.getAndSet(false)) {
            truncatedThroughTick = Math.max(
                    truncatedThroughTick,
                    tick > Long.MAX_VALUE - SoundClueStore.TTL_TICKS
                            ? Long.MAX_VALUE
                            : tick + SoundClueStore.TTL_TICKS);
        }

        var batch = new ArrayList<Playback>(pending.size());
        pending.drainTo(batch);
        long currentEpoch = epoch.get();
        int recorded = 0;
        for (Playback playback : batch) {
            if (playback.epoch() != currentEpoch) {
                continue;
            }
            store.record(
                    playback.soundEvent(),
                    playback.category(),
                    new WorldPosition(
                            dimension, playback.x(), playback.y(), playback.z()),
                    tick,
                    worldRevision,
                    SoundClueStore.entityHint(playback.soundEvent(), registeredEntityType));
            recorded++;
        }
        return new DrainResult(recorded, tick <= truncatedThroughTick);
    }

    public void clear() {
        epoch.incrementAndGet();
        pending.clear();
        overflowed.set(false);
        truncatedThroughTick = -1;
    }

    private static SoundCategory category(SoundSource source) {
        if (source == null) {
            return null;
        }
        return switch (source) {
            case MASTER -> SoundCategory.MASTER;
            case RECORDS -> SoundCategory.RECORDS;
            case WEATHER -> SoundCategory.WEATHER;
            case BLOCKS -> SoundCategory.BLOCKS;
            case HOSTILE -> SoundCategory.HOSTILE;
            case NEUTRAL -> SoundCategory.NEUTRAL;
            case PLAYERS -> SoundCategory.PLAYERS;
            case AMBIENT -> SoundCategory.AMBIENT;
            case VOICE -> SoundCategory.VOICE;
            case MUSIC, UI -> null;
        };
    }

    private static boolean validPosition(double x, double y, double z) {
        return Double.isFinite(x) && x >= -30_000_000.0 && x <= 30_000_000.0
                && Double.isFinite(y) && y >= -2_048.0 && y <= 2_048.0
                && Double.isFinite(z) && z >= -30_000_000.0 && z <= 30_000_000.0;
    }

    public record DrainResult(int recordedPlaybacks, boolean recentSoundCluesTruncated) {
    }

    private record Playback(
            long epoch,
            ResourceId soundEvent,
            SoundCategory category,
            double x,
            double y,
            double z) {
    }
}
