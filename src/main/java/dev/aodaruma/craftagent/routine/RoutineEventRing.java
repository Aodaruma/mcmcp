package dev.aodaruma.craftagent.routine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Fixed-size event ring with monotonic cursors. Client-thread ownership is expected. */
public final class RoutineEventRing {
    private final int capacity;
    private final ArrayDeque<RoutineEvent> events;
    private long nextSequence = 1;

    public RoutineEventRing(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("event capacity must be positive");
        }
        this.capacity = capacity;
        events = new ArrayDeque<>(capacity);
    }

    public RoutineEvent append(
            RoutineEventType type,
            long clientTick,
            long observationRevision,
            Map<String, Object> details) {
        Objects.requireNonNull(type, "type");
        var event = new RoutineEvent(nextSequence++, type, clientTick, observationRevision, details);
        if (events.size() == capacity) {
            events.removeFirst();
        }
        events.addLast(event);
        return event;
    }

    public EventPage page(long afterEventSeq, int maxEvents) {
        if (afterEventSeq < 0) {
            throw new IllegalArgumentException("after_event_seq must be non-negative");
        }
        if (maxEvents < 1) {
            throw new IllegalArgumentException("max_events must be positive");
        }

        long oldest = events.isEmpty() ? nextSequence : events.getFirst().seq();
        boolean truncated = afterEventSeq < oldest - 1;
        var result = new ArrayList<RoutineEvent>(Math.min(maxEvents, events.size()));
        for (var event : events) {
            if (event.seq() > afterEventSeq && result.size() < maxEvents) {
                result.add(event);
            }
        }
        long latest = nextSequence - 1;
        boolean hasMore = !result.isEmpty() && result.getLast().seq() < latest;
        return new EventPage(List.copyOf(result), truncated, hasMore, oldest, latest);
    }

    public long latestSequence() {
        return nextSequence - 1;
    }

    public record EventPage(
            List<RoutineEvent> events,
            boolean eventsTruncated,
            boolean hasMore,
            long oldestRetainedSeq,
            long latestSeq) {
        public EventPage {
            events = List.copyOf(events);
        }
    }
}
