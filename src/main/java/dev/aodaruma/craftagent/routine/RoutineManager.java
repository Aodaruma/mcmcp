package dev.aodaruma.craftagent.routine;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Atomic admission, idempotency, retention and lifecycle owner for typed routines.
 * Start/cancel/tick are expected to be dispatched onto the Minecraft client thread.
 */
public final class RoutineManager {
    public static final int DEFAULT_EVENT_CAPACITY = 256;
    public static final int DEFAULT_RETAINED_ROUTINES = 128;
    public static final long DEFAULT_TERMINAL_TTL_TICKS = 12_000;

    private final StationaryBreakPort stationaryBreakPort;
    private final int eventCapacity;
    private final int maxRetainedRoutines;
    private final long terminalTtlTicks;
    private final Supplier<UUID> routineIds;
    private final LinkedHashMap<UUID, StationaryBreakRoutine> routines = new LinkedHashMap<>();
    private final Map<String, IdempotencyEntry> idempotency = new LinkedHashMap<>();
    private UUID activeRoutineId;

    public RoutineManager(StationaryBreakPort stationaryBreakPort) {
        this(
                stationaryBreakPort,
                DEFAULT_EVENT_CAPACITY,
                DEFAULT_RETAINED_ROUTINES,
                DEFAULT_TERMINAL_TTL_TICKS,
                UUID::randomUUID);
    }

    RoutineManager(
            StationaryBreakPort stationaryBreakPort,
            int eventCapacity,
            int maxRetainedRoutines,
            long terminalTtlTicks,
            Supplier<UUID> routineIds) {
        this.stationaryBreakPort = Objects.requireNonNull(stationaryBreakPort, "stationaryBreakPort");
        if (eventCapacity < 1 || maxRetainedRoutines < 1 || terminalTtlTicks < 1) {
            throw new IllegalArgumentException("routine manager limits must be positive");
        }
        this.eventCapacity = eventCapacity;
        this.maxRetainedRoutines = maxRetainedRoutines;
        this.terminalTtlTicks = terminalTtlTicks;
        this.routineIds = Objects.requireNonNull(routineIds, "routineIds");
    }

    /** Atomically checks/reserves the key and admits at most one active routine. */
    public synchronized StartReceipt startStationaryBreak(
            String idempotencyKey,
            StationaryBreakRequest request,
            long admittedClientTick) {
        return admitStationaryBreak(idempotencyKey, request, request, admittedClientTick);
    }

    /**
     * Production admission separates the stable external request identity from derived live
     * preconditions and absolute deadlines. This makes a timed-out retry replayable even after
     * the target has changed or the client tick has advanced.
     */
    public synchronized StartReceipt startStationaryBreak(
            String idempotencyKey,
            String requestIdentity,
            StationaryBreakRequest request,
            long admittedClientTick) {
        return admitStationaryBreak(
                idempotencyKey, requireRequestIdentity(requestIdentity), request, admittedClientTick);
    }

    private StartReceipt admitStationaryBreak(
            String idempotencyKey,
            Object requestIdentity,
            StationaryBreakRequest request,
            long admittedClientTick) {
        Objects.requireNonNull(request, "request");
        request.validateAdmissionTick(admittedClientTick);
        var normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        purgeExpired(admittedClientTick);

        var existing = idempotency.get(normalizedKey);
        if (existing != null) {
            if (!existing.requestIdentity.equals(requestIdentity)) {
                throw new IdempotencyConflictException(normalizedKey);
            }
            return new StartReceipt(existing.routineId, true);
        }

        var active = activeRoutine();
        if (active.isPresent() && !active.orElseThrow().state().terminal()) {
            throw new RoutineBusyException(activeRoutineId);
        }

        evictOldestTerminalUntilRoom();
        var routineId = nextUniqueRoutineId();
        var routine = new StationaryBreakRoutine(
                routineId,
                request,
                stationaryBreakPort,
                eventCapacity,
                admittedClientTick);
        routines.put(routineId, routine);
        idempotency.put(normalizedKey, new IdempotencyEntry(
                normalizedKey, routineId, requestIdentity));
        activeRoutineId = routineId;
        return new StartReceipt(routineId, false);
    }

    /** Returns an existing same-request receipt without requiring mutable world validation. */
    public synchronized Optional<StartReceipt> replayStationaryBreak(
            String idempotencyKey,
            String requestIdentity,
            long currentClientTick) {
        var normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        var normalizedIdentity = requireRequestIdentity(requestIdentity);
        purgeExpired(currentClientTick);
        var existing = idempotency.get(normalizedKey);
        if (existing == null) {
            return Optional.empty();
        }
        if (!existing.requestIdentity.equals(normalizedIdentity)) {
            throw new IdempotencyConflictException(normalizedKey);
        }
        return Optional.of(new StartReceipt(existing.routineId, true));
    }

    /** Advances the active routine by one client tick. */
    public synchronized void tick() {
        var active = activeRoutine();
        if (active.isEmpty()) {
            return;
        }
        var routine = active.orElseThrow();
        routine.tick();
        if (routine.state().terminal()) {
            markTerminal(routine);
        }
    }

    /** Idempotently cancels and immediately releases the routine-owned attack lease. */
    public synchronized RoutineSnapshot cancelRoutine(
            UUID routineId,
            String reason,
            long afterEventSeq,
            int maxEvents) {
        var routine = requireRoutine(routineId);
        routine.cancel(reason);
        if (routine.state().terminal()) {
            markTerminal(routine);
        }
        return routine.snapshot(afterEventSeq, validateMaxEvents(maxEvents));
    }

    public synchronized RoutineSnapshot getRoutine(
            UUID routineId,
            long afterEventSeq,
            int maxEvents) {
        return requireRoutine(routineId).snapshot(afterEventSeq, validateMaxEvents(maxEvents));
    }

    /** Completes the externally-owned input/Voice Chat finalization boundary. */
    public synchronized RoutineSnapshot completeFinalization(
            UUID routineId,
            RoutineFailure failure,
            long afterEventSeq,
            int maxEvents) {
        var routine = requireRoutine(routineId);
        routine.completeFinalization(failure);
        markTerminal(routine);
        return routine.snapshot(afterEventSeq, validateMaxEvents(maxEvents));
    }

    public synchronized RoutineSnapshot recordTerminalFinalization(
            UUID routineId,
            RoutineFailure failure,
            long afterEventSeq,
            int maxEvents) {
        var routine = requireRoutine(routineId);
        routine.recordTerminalFinalization(failure);
        return routine.snapshot(afterEventSeq, validateMaxEvents(maxEvents));
    }

    public synchronized Optional<UUID> activeRoutineId() {
        var active = activeRoutine();
        if (active.isEmpty() || active.orElseThrow().state().terminal()) {
            return Optional.empty();
        }
        return Optional.of(activeRoutineId);
    }

    /** Clears all routine/idempotency state at a world-session boundary. */
    public synchronized void clearSession(String reason) {
        var active = activeRoutine();
        if (active.isPresent() && !active.orElseThrow().state().terminal()) {
            active.orElseThrow().cancel(reason);
        }
        for (var routine : routines.values()) {
            try {
                stationaryBreakPort.retire(routine.request());
            } catch (RuntimeException | LinkageError ignored) {
                // The Minecraft adapter also clears its whole session immediately afterwards.
            }
        }
        routines.clear();
        idempotency.clear();
        activeRoutineId = null;
    }

    synchronized int retainedRoutineCount() {
        return routines.size();
    }

    synchronized int retainedIdempotencyCount() {
        return idempotency.size();
    }

    private Optional<StationaryBreakRoutine> activeRoutine() {
        if (activeRoutineId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(routines.get(activeRoutineId));
    }

    private StationaryBreakRoutine requireRoutine(UUID routineId) {
        Objects.requireNonNull(routineId, "routineId");
        var routine = routines.get(routineId);
        if (routine == null) {
            throw new RoutineNotFoundException(routineId);
        }
        return routine;
    }

    private void markTerminal(StationaryBreakRoutine routine) {
        try {
            stationaryBreakPort.retire(routine.request());
        } catch (RuntimeException | LinkageError ignored) {
            // Terminal bookkeeping must remain deterministic; session clear is the final fence.
        }
        if (routine.routineId().equals(activeRoutineId)) {
            activeRoutineId = null;
        }
        for (var entry : idempotency.values()) {
            if (entry.routineId.equals(routine.routineId()) && entry.expiresAtTick == Long.MAX_VALUE) {
                entry.expiresAtTick = saturatingAdd(routine.lastClientTick(), terminalTtlTicks);
                break;
            }
        }
    }

    private void purgeExpired(long currentClientTick) {
        var entries = idempotency.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            if (entry.getValue().expiresAtTick <= currentClientTick) {
                routines.remove(entry.getValue().routineId);
                entries.remove();
            }
        }
    }

    private void evictOldestTerminalUntilRoom() {
        while (routines.size() >= maxRetainedRoutines) {
            UUID evicted = null;
            Iterator<Map.Entry<UUID, StationaryBreakRoutine>> iterator = routines.entrySet().iterator();
            while (iterator.hasNext()) {
                var candidate = iterator.next();
                if (candidate.getValue().state().terminal()) {
                    evicted = candidate.getKey();
                    iterator.remove();
                    break;
                }
            }
            if (evicted == null) {
                throw new RoutineBusyException(activeRoutineId);
            }
            var evictedId = evicted;
            idempotency.entrySet().removeIf(entry -> entry.getValue().routineId.equals(evictedId));
        }
    }

    private UUID nextUniqueRoutineId() {
        for (int attempts = 0; attempts < 16; attempts++) {
            var candidate = Objects.requireNonNull(routineIds.get(), "routine id supplier returned null");
            if (!routines.containsKey(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("routine id supplier produced too many collisions");
    }

    private static String normalizeIdempotencyKey(String key) {
        Objects.requireNonNull(key, "idempotencyKey");
        try {
            return UUID.fromString(key).toString();
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("idempotency key must be a UUID", invalid);
        }
    }

    private static String requireRequestIdentity(String identity) {
        Objects.requireNonNull(identity, "requestIdentity");
        if (identity.isBlank() || identity.length() > 4_096) {
            throw new IllegalArgumentException("request identity must be 1..4096 characters");
        }
        return identity;
    }

    private static int validateMaxEvents(int maxEvents) {
        if (maxEvents < 1 || maxEvents > 128) {
            throw new IllegalArgumentException("max_events must be in 1..128");
        }
        return maxEvents;
    }

    private static long saturatingAdd(long left, long right) {
        if (Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    public record StartReceipt(UUID routineId, boolean reused) {
        public StartReceipt {
            Objects.requireNonNull(routineId, "routineId");
        }
    }

    private static final class IdempotencyEntry {
        private final String key;
        private final UUID routineId;
        private final Object requestIdentity;
        private long expiresAtTick = Long.MAX_VALUE;

        private IdempotencyEntry(String key, UUID routineId, Object requestIdentity) {
            this.key = key;
            this.routineId = routineId;
            this.requestIdentity = requestIdentity;
        }
    }

    public static final class IdempotencyConflictException extends IllegalStateException {
        public IdempotencyConflictException(String key) {
            super("idempotency key is already reserved with different arguments: " + key);
        }
    }

    public static final class RoutineBusyException extends IllegalStateException {
        private final UUID activeRoutineId;

        public RoutineBusyException(UUID activeRoutineId) {
            super("another routine is active");
            this.activeRoutineId = activeRoutineId;
        }

        public UUID activeRoutineId() {
            return activeRoutineId;
        }
    }

    public static final class RoutineNotFoundException extends IllegalArgumentException {
        public RoutineNotFoundException(UUID routineId) {
            super("routine was not found: " + routineId);
        }
    }
}
