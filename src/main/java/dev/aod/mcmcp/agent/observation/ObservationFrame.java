package dev.aod.mcmcp.agent.observation;

import dev.aod.mcmcp.agent.observation.ObservationRecord.SoundClue;
import dev.aod.mcmcp.agent.observation.ObservationValues.ResourceId;

import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** A completed, immutable temporal-composite observation frame. */
public record ObservationFrame(
        String frameId,
        ResourceId dimension,
        long frameCompletedTick,
        double configuredVisualRadiusBlocks,
        boolean visibleEntitiesTruncated,
        boolean recentSoundCluesTruncated,
        List<ObservationRecord> records) {

    private static final Pattern FRAME_ID = Pattern.compile("^obs-[0-9a-f]{16}$");

    public ObservationFrame {
        requireFrameId(frameId);
        Objects.requireNonNull(dimension, "dimension");
        ObservationValues.requireTick(frameCompletedTick, "frameCompletedTick");
        ObservationValues.requireFiniteRange(
                configuredVisualRadiusBlocks, 1.0, 32.0, "configuredVisualRadiusBlocks");
        records = List.copyOf(Objects.requireNonNull(records, "records"));

        int soundClues = 0;
        for (ObservationRecord record : records) {
            Objects.requireNonNull(record, "record");
            ObservationValues.requireSameDimension(dimension, record.dimension());
            if (record.newestObservedTick() > frameCompletedTick) {
                throw new IllegalArgumentException("Record was observed after frame completion");
            }
            if (record instanceof SoundClue sound) {
                soundClues++;
                long expectedAge = frameCompletedTick - sound.lastObservedTick();
                if (expectedAge != sound.ageTicks()) {
                    throw new IllegalArgumentException("Sound age must be fixed at frame completion");
                }
            }
        }
        if (soundClues > 32) {
            throw new IllegalArgumentException("Frame contains more than 32 sound clues");
        }
    }

    public ObservationFrame(
            String frameId,
            ResourceId dimension,
            long frameCompletedTick,
            double configuredVisualRadiusBlocks,
            boolean recentSoundCluesTruncated,
            List<ObservationRecord> records) {
        this(frameId, dimension, frameCompletedTick, configuredVisualRadiusBlocks,
                false, recentSoundCluesTruncated, records);
    }

    public ObservationFrameSummary summary() {
        var counts = new EnumMap<ObservationKind, Integer>(ObservationKind.class);
        for (ObservationKind kind : ObservationKind.values()) {
            counts.put(kind, 0);
        }
        long oldest = frameCompletedTick;
        long newest = frameCompletedTick;
        if (!records.isEmpty()) {
            oldest = Long.MAX_VALUE;
            newest = 0L;
            for (ObservationRecord record : records) {
                counts.merge(record.kind(), 1, Integer::sum);
                oldest = Math.min(oldest, record.oldestObservedTick());
                newest = Math.max(newest, record.newestObservedTick());
            }
        }
        return new ObservationFrameSummary(
                frameId,
                configuredVisualRadiusBlocks,
                oldest,
                newest,
                counts,
                visibleEntitiesTruncated,
                recentSoundCluesTruncated);
    }

    static String requireFrameId(String frameId) {
        Objects.requireNonNull(frameId, "frameId");
        if (!FRAME_ID.matcher(frameId).matches()) {
            throw new IllegalArgumentException("Invalid observation frame ID");
        }
        return frameId;
    }
}
