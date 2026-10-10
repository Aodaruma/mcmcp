package dev.aod.mcmcp.runtime;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Single-use references to received menu rows, bound to the opening item, origin and session. */
final class V2WaystoneReferences {
    static final long TTL_TICKS = 2400;
    static final int MAX_REFERENCES = 256;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    record Scope(UUID session, String dimension, int x, int y, int z,
                 ContainerSyncSignals.StackFingerprint held) {
        Scope { Objects.requireNonNull(session); Objects.requireNonNull(dimension); Objects.requireNonNull(held); }
    }

    record Destination(UUID id, String name, String dimension, int x, int y, int z,
                       int levels, int points, Long availableLevels, Long availablePoints,
                       boolean selectable, List<String> requirements) {
        Destination {
            Objects.requireNonNull(id); Objects.requireNonNull(name); Objects.requireNonNull(dimension);
            requirements = List.copyOf(requirements);
            if (levels < 0 || points < 0 || name.length() > 256 || requirements.size() > 32)
                throw new IllegalArgumentException("unbounded destination");
        }
        boolean sameSelection(Destination current) {
            return current != null && id.equals(current.id) && name.equals(current.name)
                    && dimension.equals(current.dimension) && x == current.x && y == current.y && z == current.z
                    && levels == current.levels && points == current.points && selectable && current.selectable;
        }
    }
    record Entry(Scope scope, long expires, Destination destination) { }

    String register(Scope scope, long tick, Destination destination) {
        entries.entrySet().removeIf(value -> !value.getValue().scope().session().equals(scope.session())
                || tick > value.getValue().expires());
        while (entries.size() >= MAX_REFERENCES) entries.remove(entries.keySet().iterator().next());
        byte[] bytes = new byte[18];
        random.nextBytes(bytes);
        String ref = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        entries.put(ref, new Entry(scope, Math.addExact(tick, TTL_TICKS), destination));
        return ref;
    }

    Entry resolve(String ref, Scope scope, long tick) {
        var entry = entries.get(ref);
        if (entry == null || !entry.scope().equals(scope) || tick > entry.expires())
            throw new IllegalArgumentException("destination_ref expired or context changed; inspect again");
        return entry;
    }

    Destination consume(String ref, Scope scope, long tick, Destination current, int maxLevels, int maxPoints) {
        var entry = resolve(ref, scope, tick);
        if (!entry.destination().sameSelection(current))
            throw new IllegalArgumentException("destination or requirements changed; inspect again");
        if (current.levels() > maxLevels || current.points() > maxPoints)
            throw new IllegalArgumentException("destination experience cost exceeds budget");
        entries.remove(ref); // Before native dispatch: even an uncertain send cannot reuse authority.
        return current;
    }
}
