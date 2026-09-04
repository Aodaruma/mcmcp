package dev.aod.mcmcp.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Bounded opaque capabilities for bobbers confirmed to belong to the local player. */
final class FishingSessionRefs {
    static final long TTL_TICKS = 1_200L;
    static final int LIMIT = 8;

    private final LinkedHashMap<String, Session> sessions = new LinkedHashMap<>();

    synchronized String issue(
            UUID worldSessionId,
            String dimension,
            UUID bobberId,
            String hand,
            String rodItem,
            long issuedTick) {
        Objects.requireNonNull(worldSessionId, "worldSessionId");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(bobberId, "bobberId");
        Objects.requireNonNull(hand, "hand");
        Objects.requireNonNull(rodItem, "rodItem");
        if (issuedTick < 0L) throw new IllegalArgumentException("issuedTick must be non-negative");
        purge(issuedTick);
        while (sessions.size() >= LIMIT) {
            sessions.remove(sessions.keySet().iterator().next());
        }
        String reference;
        do {
            reference = "f_" + UUID.randomUUID().toString().replace("-", "").substring(0, 22);
        } while (sessions.containsKey(reference));
        sessions.put(reference, new Session(
                worldSessionId, dimension, bobberId, hand, rodItem,
                issuedTick, Math.addExact(issuedTick, TTL_TICKS)));
        return reference;
    }

    synchronized Optional<Session> resolve(
            String reference, UUID worldSessionId, String dimension, long currentTick) {
        Objects.requireNonNull(reference, "reference");
        purge(currentTick);
        Session session = sessions.get(reference);
        if (session == null
                || !session.worldSessionId().equals(worldSessionId)
                || !session.dimension().equals(dimension)) {
            return Optional.empty();
        }
        return Optional.of(session);
    }

    synchronized Optional<Session> consume(
            String reference, UUID worldSessionId, String dimension, long currentTick) {
        Optional<Session> resolved = resolve(reference, worldSessionId, dimension, currentTick);
        resolved.ifPresent(ignored -> sessions.remove(reference));
        return resolved;
    }

    synchronized void clear() {
        sessions.clear();
    }

    private void purge(long currentTick) {
        if (currentTick < 0L) throw new IllegalArgumentException("currentTick must be non-negative");
        sessions.entrySet().removeIf(entry -> currentTick > entry.getValue().expiresTick());
    }

    record Session(
            UUID worldSessionId,
            String dimension,
            UUID bobberId,
            String hand,
            String rodItem,
            long issuedTick,
            long expiresTick) {
        Session {
            Objects.requireNonNull(worldSessionId, "worldSessionId");
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(bobberId, "bobberId");
            Objects.requireNonNull(hand, "hand");
            Objects.requireNonNull(rodItem, "rodItem");
        }
    }
}
