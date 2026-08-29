package dev.aod.mcmcp.agent.observation;

import dev.aod.mcmcp.agent.safety.LocalObservationVolume;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Compact latest-frame view embedded in {@code agent_get_state}. */
public record ObservationFrameSummary(
        String latestFrameId,
        double configuredVisualRadiusBlocks,
        long oldestTick,
        long newestTick,
        Map<ObservationKind, Integer> recordCounts,
        boolean visibleEntitiesTruncated,
        boolean recentSoundCluesTruncated) {

    public static final String MODE = "omnidirectional_local";
    public static final int NEAR_VOLUME_RADIUS_BLOCKS =
            (int) LocalObservationVolume.RADIUS_BLOCKS;

    public ObservationFrameSummary {
        ObservationFrame.requireFrameId(latestFrameId);
        ObservationValues.requireFiniteRange(
                configuredVisualRadiusBlocks, 1.0, 32.0, "configuredVisualRadiusBlocks");
        ObservationValues.requireTick(oldestTick, "oldestTick");
        ObservationValues.requireTick(newestTick, "newestTick");
        if (newestTick < oldestTick) {
            throw new IllegalArgumentException("newestTick precedes oldestTick");
        }
        Objects.requireNonNull(recordCounts, "recordCounts");
        var copied = new EnumMap<ObservationKind, Integer>(ObservationKind.class);
        copied.putAll(recordCounts);
        if (copied.size() != ObservationKind.values().length) {
            throw new IllegalArgumentException("recordCounts must contain every observation kind");
        }
        for (ObservationKind kind : ObservationKind.values()) {
            Integer count = copied.get(kind);
            if (count == null || count < 0) {
                throw new IllegalArgumentException("record count must be non-negative");
            }
        }
        if (copied.get(ObservationKind.SOUND_CLUE) > 32) {
            throw new IllegalArgumentException("sound clue count exceeds catalog limit");
        }
        recordCounts = Collections.unmodifiableMap(copied);
    }

    public ObservationFrameSummary(
            String latestFrameId,
            double configuredVisualRadiusBlocks,
            long oldestTick,
            long newestTick,
            Map<ObservationKind, Integer> recordCounts,
            boolean recentSoundCluesTruncated) {
        this(latestFrameId, configuredVisualRadiusBlocks, oldestTick, newestTick,
                recordCounts, false, recentSoundCluesTruncated);
    }

    public String mode() { return MODE; }
    public int nearVolumeRadiusBlocks() { return NEAR_VOLUME_RADIUS_BLOCKS; }
    public boolean fullAzimuth() { return true; }
    public boolean fullElevation() { return true; }
    public int samplingCoverage() { return 1; }
    public boolean cameraMotionGenerated() { return false; }
}
