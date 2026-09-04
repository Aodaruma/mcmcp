package dev.aod.mcmcp.agent.observation;

import dev.aod.mcmcp.agent.observation.ObservationRecord.SoundCategory;
import dev.aod.mcmcp.agent.observation.ObservationRecord.SoundClue;
import dev.aod.mcmcp.agent.observation.ObservationValues.ResourceId;
import dev.aod.mcmcp.agent.observation.ObservationValues.WorldPosition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/** Bounded session-local aggregation of accepted client-level position-sound events. */
public final class SoundClueStore {
    public static final int LIMIT = 32;
    public static final long TTL_TICKS = 600;
    private static final long MERGE_TICKS = 10;
    private static final double MERGE_DISTANCE_SQUARED = 4.0;

    private final List<Entry> entries = new ArrayList<>(LIMIT);
    private long nextSequence;
    private long truncatedThroughTick = -1;

    public synchronized void record(
            ResourceId soundEvent,
            SoundCategory category,
            WorldPosition position,
            long tick,
            long worldRevision,
            ResourceId entityHint) {
        Objects.requireNonNull(soundEvent, "soundEvent");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(position, "position");
        ObservationValues.requireTick(tick, "tick");
        ObservationValues.requireTick(worldRevision, "worldRevision");
        purge(tick);

        Entry match = entries.stream()
                .filter(entry -> entry.matches(soundEvent, category, position, tick))
                .min(Comparator
                        .comparingDouble((Entry entry) -> entry.distanceSquared(position))
                        .thenComparing(Comparator.comparingLong((Entry entry) -> entry.lastTick).reversed())
                        .thenComparingLong(entry -> entry.sequence))
                .orElse(null);
        if (match != null) {
            match.position = position;
            match.lastTick = tick;
            match.worldRevision = worldRevision;
            match.occurrences = Math.min(Integer.MAX_VALUE, match.occurrences + 1);
            if (match.entityHint == null) {
                match.entityHint = entityHint;
            }
            return;
        }

        if (entries.size() == LIMIT) {
            Entry discarded = entries.stream()
                    .min(Comparator.comparingLong((Entry entry) -> entry.lastTick)
                            .thenComparingLong(entry -> entry.sequence))
                    .orElseThrow();
            entries.remove(discarded);
            truncatedThroughTick = Math.max(truncatedThroughTick, discarded.lastTick + TTL_TICKS);
        }
        entries.add(new Entry(
                soundEvent, category, position, tick, worldRevision, entityHint, nextSequence++));
    }

    public synchronized Snapshot snapshot(long frameCompletedTick) {
        ObservationValues.requireTick(frameCompletedTick, "frameCompletedTick");
        purge(frameCompletedTick);
        List<SoundClue> clues = entries.stream()
                .sorted(Comparator.comparingLong(entry -> entry.sequence))
                .map(entry -> entry.toRecord(frameCompletedTick))
                .toList();
        return new Snapshot(clues, frameCompletedTick <= truncatedThroughTick);
    }

    public synchronized void clear() {
        entries.clear();
        nextSequence = 0;
        truncatedThroughTick = -1;
    }

    /** Maps {@code namespace:entity.<candidate>.*} only when candidate is a real entity type. */
    public static ResourceId entityHint(
            ResourceId soundEvent, Predicate<String> registeredEntityType) {
        Objects.requireNonNull(soundEvent, "soundEvent");
        Objects.requireNonNull(registeredEntityType, "registeredEntityType");
        String value = soundEvent.value();
        int colon = value.indexOf(':');
        String namespace = value.substring(0, colon);
        String path = value.substring(colon + 1);
        if (!path.startsWith("entity.")) {
            return null;
        }
        int candidateEnd = path.indexOf('.', "entity.".length());
        if (candidateEnd < 0) {
            return null;
        }
        String candidate = namespace + ':' + path.substring("entity.".length(), candidateEnd);
        return registeredEntityType.test(candidate) ? new ResourceId(candidate) : null;
    }

    private void purge(long tick) {
        entries.removeIf(entry -> tick - entry.lastTick > TTL_TICKS);
    }

    public record Snapshot(List<SoundClue> clues, boolean recentSoundCluesTruncated) {
        public Snapshot {
            clues = List.copyOf(clues);
        }
    }

    private static final class Entry {
        private final ResourceId soundEvent;
        private final SoundCategory category;
        private WorldPosition position;
        private final long firstTick;
        private long lastTick;
        private long worldRevision;
        private ResourceId entityHint;
        private int occurrences = 1;
        private final long sequence;

        private Entry(
                ResourceId soundEvent,
                SoundCategory category,
                WorldPosition position,
                long tick,
                long worldRevision,
                ResourceId entityHint,
                long sequence) {
            this.soundEvent = soundEvent;
            this.category = category;
            this.position = position;
            firstTick = tick;
            lastTick = tick;
            this.worldRevision = worldRevision;
            this.entityHint = entityHint;
            this.sequence = sequence;
        }

        private boolean matches(
                ResourceId requestedEvent,
                SoundCategory requestedCategory,
                WorldPosition requestedPosition,
                long tick) {
            return soundEvent.equals(requestedEvent)
                    && category == requestedCategory
                    && position.dimension().equals(requestedPosition.dimension())
                    && tick >= lastTick
                    && tick - lastTick <= MERGE_TICKS
                    && distanceSquared(requestedPosition) <= MERGE_DISTANCE_SQUARED;
        }

        private double distanceSquared(WorldPosition other) {
            double x = position.x() - other.x();
            double y = position.y() - other.y();
            double z = position.z() - other.z();
            return x * x + y * y + z * z;
        }

        private SoundClue toRecord(long completedTick) {
            return new SoundClue(
                    soundEvent,
                    category,
                    position,
                    firstTick,
                    lastTick,
                    Math.toIntExact(completedTick - lastTick),
                    occurrences,
                    entityHint,
                    worldRevision);
        }
    }
}
