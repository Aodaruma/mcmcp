package dev.aod.mcmcp.agent.observation;

import java.util.List;
import java.util.Objects;

/** Stable page returned by {@code agent_get_observation}. */
public record ObservationPage(
        String frameId,
        long frameCompletedTick,
        boolean visibleEntitiesTruncated,
        List<ObservationRecord> records,
        String nextCursor) {

    public ObservationPage {
        ObservationFrame.requireFrameId(frameId);
        ObservationValues.requireTick(frameCompletedTick, "frameCompletedTick");
        records = List.copyOf(Objects.requireNonNull(records, "records"));
        if (records.size() > 256) {
            throw new IllegalArgumentException("Observation page exceeds 256 records");
        }
        if (nextCursor != null && (nextCursor.isEmpty() || nextCursor.length() > 256)) {
            throw new IllegalArgumentException("Invalid next cursor");
        }
    }

    public ObservationPage(
            String frameId,
            long frameCompletedTick,
            List<ObservationRecord> records,
            String nextCursor) {
        this(frameId, frameCompletedTick, false, records, nextCursor);
    }

    public int schemaVersion() { return 1; }
    public int samplingCoverage() { return 1; }
}
