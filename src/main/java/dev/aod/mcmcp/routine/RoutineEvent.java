package dev.aod.mcmcp.routine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** A cursor-addressable routine event. */
public record RoutineEvent(
        long seq,
        RoutineEventType type,
        long clientTick,
        long observationRevision,
        Map<String, Object> details) {
    public RoutineEvent {
        if (seq < 1) {
            throw new IllegalArgumentException("event seq must be positive");
        }
        Objects.requireNonNull(type, "type");
        if (clientTick < 0 || observationRevision < 0) {
            throw new IllegalArgumentException("event clocks must be non-negative");
        }
        Objects.requireNonNull(details, "details");
        details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }
}
