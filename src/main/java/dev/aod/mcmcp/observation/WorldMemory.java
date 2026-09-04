package dev.aod.mcmcp.observation;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Session-scoped observation memory. Only explicit visual observations and
 * server-confirmed automation effects may enter this store.
 */
public final class WorldMemory {
    public static final int DEFAULT_BLOCK_LIMIT = 8_192;
    public static final int DEFAULT_ENTITY_LIMIT = 512;
    public static final long ENTITY_REF_TTL_TICKS = 100;

    private final int blockLimit;
    private final int entityLimit;
    private final SecureRandom random = new SecureRandom();
    private final LinkedHashMap<BlockPosition, ObservedBlock> blocks = new LinkedHashMap<>(128, 0.75f, true);
    private final LinkedHashMap<UUID, EntityObservation> entities = new LinkedHashMap<>(64, 0.75f, true);
    private final TreeMap<Long, Integer> retainedTickCounts = new TreeMap<>();

    private UUID sessionId;
    private String dimension;
    private long revision;
    private long evictedBlocks;
    private long evictedEntities;
    private long oldestRetainedTick;

    public WorldMemory() {
        this(DEFAULT_BLOCK_LIMIT, DEFAULT_ENTITY_LIMIT);
    }

    public WorldMemory(int blockLimit, int entityLimit) {
        if (blockLimit < 1 || entityLimit < 1) {
            throw new IllegalArgumentException("memory limits must be positive");
        }
        this.blockLimit = blockLimit;
        this.entityLimit = entityLimit;
    }

    public synchronized void startSession(UUID newSessionId, String newDimension) {
        Objects.requireNonNull(newSessionId, "newSessionId");
        Objects.requireNonNull(newDimension, "newDimension");
        if (newSessionId.equals(sessionId)) {
            dimension = newDimension;
            return;
        }
        sessionId = newSessionId;
        dimension = Objects.requireNonNull(newDimension, "newDimension");
        blocks.clear();
        entities.clear();
        retainedTickCounts.clear();
        revision = 0;
        evictedBlocks = 0;
        evictedEntities = 0;
        oldestRetainedTick = 0;
    }

    public synchronized void detachSession() {
        sessionId = null;
        dimension = null;
        blocks.clear();
        entities.clear();
        retainedTickCounts.clear();
        revision = 0;
        evictedBlocks = 0;
        evictedEntities = 0;
        oldestRetainedTick = 0;
    }

    public synchronized long rememberBlock(ObservedBlock observation) {
        requireCurrentSession(observation.worldSessionId(), observation.position().dimension());
        var replaced = blocks.put(observation.position(), observation);
        if (replaced != null) {
            removeRetainedTick(replaced.observedAtClientTick());
        }
        addRetainedTick(observation.observedAtClientTick());
        while (blocks.size() > blockLimit) {
            var iterator = blocks.entrySet().iterator();
            var evicted = iterator.next().getValue();
            iterator.remove();
            removeRetainedTick(evicted.observedAtClientTick());
            evictedBlocks++;
        }
        revision++;
        updateOldestTick();
        return revision;
    }

    public synchronized Optional<ObservedBlock> findBlock(BlockPosition position) {
        if (sessionId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(blocks.get(position));
    }

    public synchronized List<ObservedBlock> retainedBlocks(int maxResults) {
        if (maxResults < 0) {
            throw new IllegalArgumentException("maxResults must be non-negative");
        }
        var values = new ArrayList<>(blocks.values());
        Collections.reverse(values);
        return List.copyOf(values.subList(0, Math.min(maxResults, values.size())));
    }

    synchronized EntityObservation rememberEntity(
            UUID internalUuid,
            String type,
            double x,
            double y,
            double z,
            double dx,
            double dy,
            double dz,
            boolean player,
            boolean vehicle,
            boolean passenger,
            String observedDimension,
            long observedTick) {
        if (sessionId == null) {
            throw new IllegalStateException("no active world session");
        }
        if (!Objects.equals(dimension, observedDimension)) {
            throw new IllegalArgumentException("entity observation belongs to another dimension");
        }
        var previous = entities.get(internalUuid);
        boolean sameReferenceScope = previous != null
                && previous.worldSessionId().equals(sessionId)
                && previous.dimension().equals(observedDimension)
                && previous.type().equals(type)
                && observedTick >= previous.observedAtClientTick()
                && observedTick - previous.observedAtClientTick() <= ENTITY_REF_TTL_TICKS;
        var opaqueRef = player ? null
                : sameReferenceScope ? previous.opaqueRef() : newEntityRef();
        var result = new EntityObservation(internalUuid, opaqueRef, type, x, y, z, dx, dy, dz, player, vehicle,
                passenger, Objects.requireNonNull(observedDimension, "observedDimension"), observedTick, sessionId);
        entities.put(internalUuid, result);
        if (previous != null) {
            removeRetainedTick(previous.observedAtClientTick());
        }
        addRetainedTick(observedTick);
        while (entities.size() > entityLimit) {
            var iterator = entities.entrySet().iterator();
            var evicted = iterator.next().getValue();
            iterator.remove();
            removeRetainedTick(evicted.observedAtClientTick());
            evictedEntities++;
        }
        revision++;
        updateOldestTick();
        return result;
    }

    /**
     * Records one non-player identity after the caller has proved it visible in the current
     * omnidirectional sample. The opaque value remains bound to this memory's exact world session,
     * dimension, registry type, and internal UUID and is resolvable for at most
     * {@link #ENTITY_REF_TTL_TICKS} after its latest visible observation.
     *
     * <p>This method does not perform entity discovery. Callers must invoke it only from the
     * successful branch of a policy-visible sampler; hidden or merely client-loaded entities must
     * never reach this boundary.</p>
     */
    public synchronized String rememberVisibleEntityReference(
            UUID expectedSessionId,
            String expectedDimension,
            UUID internalUuid,
            String type,
            double x,
            double y,
            double z,
            double dx,
            double dy,
            double dz,
            boolean vehicle,
            boolean passenger,
            long observedTick) {
        Objects.requireNonNull(expectedSessionId, "expectedSessionId");
        Objects.requireNonNull(expectedDimension, "expectedDimension");
        Objects.requireNonNull(internalUuid, "internalUuid");
        Objects.requireNonNull(type, "type");
        requireCurrentSession(expectedSessionId, expectedDimension);
        if ("minecraft:player".equals(type)) {
            return null;
        }
        return rememberEntity(
                internalUuid,
                type,
                x,
                y,
                z,
                dx,
                dy,
                dz,
                false,
                vehicle,
                passenger,
                expectedDimension,
                observedTick).opaqueRef();
    }

    synchronized List<EntityObservation> retainedEntities(int maxResults) {
        if (maxResults < 0) {
            throw new IllegalArgumentException("maxResults must be non-negative");
        }
        var values = new ArrayList<>(entities.values());
        Collections.reverse(values);
        return List.copyOf(values.subList(0, Math.min(maxResults, values.size())));
    }

    private synchronized Optional<EntityObservation> resolveEntityRef(String opaqueRef, long currentTick) {
        if (opaqueRef == null || opaqueRef.isBlank()) {
            return Optional.empty();
        }
        return entities.values().stream()
                .filter(entity -> opaqueRef.equals(entity.opaqueRef()))
                .filter(entity -> Objects.equals(sessionId, entity.worldSessionId()))
                .filter(entity -> Objects.equals(dimension, entity.dimension()))
                .filter(entity -> currentTick >= entity.observedAtClientTick())
                .filter(entity -> currentTick - entity.observedAtClientTick() <= ENTITY_REF_TTL_TICKS)
                .findFirst();
    }

    /**
     * Resolves an opaque non-player reference only inside the caller's exact current session and
     * dimension. The returned handle is for in-process action validation and is never serialized.
     */
    public synchronized Optional<ResolvedEntityRef> resolveEntityRef(
            String opaqueRef,
            long currentTick,
            UUID expectedSessionId,
            String expectedDimension) {
        Objects.requireNonNull(expectedSessionId, "expectedSessionId");
        Objects.requireNonNull(expectedDimension, "expectedDimension");
        if (!expectedSessionId.equals(sessionId) || !expectedDimension.equals(dimension)) {
            return Optional.empty();
        }
        return resolveEntityRef(opaqueRef, currentTick)
                .filter(entity -> !entity.player() && entity.opaqueRef() != null)
                .map(entity -> new ResolvedEntityRef(
                        entity.internalUuid(), entity.type(), entity.dimension(),
                        entity.observedAtClientTick(), entity.worldSessionId()));
    }

    public synchronized Stats stats() {
        return new Stats(blocks.size(), entities.size(), revision, evictedBlocks, evictedEntities,
                oldestRetainedTick, blockLimit, entityLimit);
    }

    public synchronized UUID sessionId() {
        return sessionId;
    }

    public synchronized long revision() {
        return revision;
    }

    private void requireCurrentSession(UUID candidateSession, String candidateDimension) {
        if (sessionId == null || !sessionId.equals(candidateSession) || !Objects.equals(dimension, candidateDimension)) {
            throw new IllegalArgumentException("observation belongs to another world session");
        }
    }

    private String newEntityRef() {
        var bytes = new byte[18];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void addRetainedTick(long tick) {
        retainedTickCounts.merge(tick, 1, Integer::sum);
    }

    private void removeRetainedTick(long tick) {
        retainedTickCounts.computeIfPresent(tick, (ignored, count) -> count == 1 ? null : count - 1);
    }

    private void updateOldestTick() {
        oldestRetainedTick = retainedTickCounts.isEmpty() ? 0 : retainedTickCounts.firstKey();
    }

    record EntityObservation(
            UUID internalUuid,
            String opaqueRef,
            String type,
            double x,
            double y,
            double z,
            double dx,
            double dy,
            double dz,
            boolean player,
            boolean vehicle,
            boolean passenger,
            String dimension,
            long observedAtClientTick,
            UUID worldSessionId) {
        public Map<String, Object> toMap(long currentTick, boolean current) {
            var result = new LinkedHashMap<String, Object>();
            result.put("type", type);
            result.put("dimension", dimension);
            result.put("position", Map.of("x", x, "y", y, "z", z));
            result.put("motion", Map.of("x", dx, "y", dy, "z", dz));
            result.put("vehicle", vehicle);
            result.put("passenger", passenger);
            if (!player && currentTick - observedAtClientTick <= ENTITY_REF_TTL_TICKS) {
                result.put("entity_ref", opaqueRef);
            }
            result.put("knowledge", Map.of(
                    "currentness", current ? "current" : "last_known",
                    "observed_at_client_tick", observedAtClientTick,
                    "age_ticks", Math.max(0, currentTick - observedAtClientTick),
                    "visible_now", current));
            result.put("world_session_id", worldSessionId.toString());
            return result;
        }
    }

    public record Stats(
            int retainedBlocks,
            int retainedEntities,
            long revision,
            long evictedBlocks,
            long evictedEntities,
            long oldestRetainedTick,
            int blockLimit,
            int entityLimit) {
        public Map<String, Object> toMap() {
            return Map.of(
                    "retained_blocks", retainedBlocks,
                    "retained_entities", retainedEntities,
                    "revision", revision,
                    "evicted_blocks", evictedBlocks,
                    "evicted_entities", evictedEntities,
                    "oldest_retained_tick", oldestRetainedTick,
                    "block_limit", blockLimit,
                    "entity_limit", entityLimit);
        }
    }

    public record ResolvedEntityRef(
            UUID internalUuid,
            String type,
            String dimension,
            long observedAtClientTick,
            UUID worldSessionId) {
        public ResolvedEntityRef {
            Objects.requireNonNull(internalUuid, "internalUuid");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(worldSessionId, "worldSessionId");
        }
    }
}
