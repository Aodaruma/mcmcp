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
    private final SemanticActionPort semanticActionPort;
    private final ApplyBlockPlanPort applyBlockPlanPort;
    private final PhaseFivePort phaseFivePort;
    private final FinitePlanPort finitePlanPort;
    private final int eventCapacity;
    private final int maxRetainedRoutines;
    private final long terminalTtlTicks;
    private final Supplier<UUID> routineIds;
    private final LinkedHashMap<UUID, ManagedRoutine> routines = new LinkedHashMap<>();
    private final Map<String, IdempotencyEntry> idempotency = new LinkedHashMap<>();
    private UUID activeRoutineId;

    public RoutineManager(StationaryBreakPort stationaryBreakPort) {
        this(
                stationaryBreakPort,
                null,
                null,
                null,
                DEFAULT_EVENT_CAPACITY,
                DEFAULT_RETAINED_ROUTINES,
                DEFAULT_TERMINAL_TTL_TICKS,
                UUID::randomUUID);
    }

    /** Production constructor enabling both Phase 2 and Phase 3 routine families. */
    public RoutineManager(
            StationaryBreakPort stationaryBreakPort,
            SemanticActionPort semanticActionPort) {
        this(
                stationaryBreakPort,
                Objects.requireNonNull(semanticActionPort, "semanticActionPort"),
                null,
                null,
                DEFAULT_EVENT_CAPACITY,
                DEFAULT_RETAINED_ROUTINES,
                DEFAULT_TERMINAL_TTL_TICKS,
                UUID::randomUUID);
    }

    /** Production constructor enabling only Phase 2 and the parent-only finite-plan family. */
    public RoutineManager(
            StationaryBreakPort stationaryBreakPort,
            FinitePlanPort finitePlanPort) {
        this(
                stationaryBreakPort,
                null,
                null,
                null,
                Objects.requireNonNull(finitePlanPort, "finitePlanPort"),
                DEFAULT_EVENT_CAPACITY,
                DEFAULT_RETAINED_ROUTINES,
                DEFAULT_TERMINAL_TTL_TICKS,
                UUID::randomUUID);
    }

    /** Production constructor enabling Phase 2, Phase 3 and Phase 4 routine families. */
    public RoutineManager(
            StationaryBreakPort stationaryBreakPort,
            SemanticActionPort semanticActionPort,
            ApplyBlockPlanPort applyBlockPlanPort) {
        this(
                stationaryBreakPort,
                Objects.requireNonNull(semanticActionPort, "semanticActionPort"),
                Objects.requireNonNull(applyBlockPlanPort, "applyBlockPlanPort"),
                null,
                DEFAULT_EVENT_CAPACITY,
                DEFAULT_RETAINED_ROUTINES,
                DEFAULT_TERMINAL_TTL_TICKS,
                UUID::randomUUID);
    }

    /** Production constructor enabling Phase 2 through Phase 5 routine families. */
    public RoutineManager(
            StationaryBreakPort stationaryBreakPort,
            SemanticActionPort semanticActionPort,
            ApplyBlockPlanPort applyBlockPlanPort,
            PhaseFivePort phaseFivePort) {
        this(
                stationaryBreakPort,
                Objects.requireNonNull(semanticActionPort, "semanticActionPort"),
                Objects.requireNonNull(applyBlockPlanPort, "applyBlockPlanPort"),
                Objects.requireNonNull(phaseFivePort, "phaseFivePort"),
                DEFAULT_EVENT_CAPACITY,
                DEFAULT_RETAINED_ROUTINES,
                DEFAULT_TERMINAL_TTL_TICKS,
                UUID::randomUUID);
    }

    /** Production constructor enabling Phase 2 through Phase 5 and finite plans. */
    public RoutineManager(
            StationaryBreakPort stationaryBreakPort,
            SemanticActionPort semanticActionPort,
            ApplyBlockPlanPort applyBlockPlanPort,
            PhaseFivePort phaseFivePort,
            FinitePlanPort finitePlanPort) {
        this(
                stationaryBreakPort,
                Objects.requireNonNull(semanticActionPort, "semanticActionPort"),
                Objects.requireNonNull(applyBlockPlanPort, "applyBlockPlanPort"),
                Objects.requireNonNull(phaseFivePort, "phaseFivePort"),
                Objects.requireNonNull(finitePlanPort, "finitePlanPort"),
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
        this(
                stationaryBreakPort,
                null,
                null,
                null,
                eventCapacity,
                maxRetainedRoutines,
                terminalTtlTicks,
                routineIds);
    }

    RoutineManager(
            StationaryBreakPort stationaryBreakPort,
            SemanticActionPort semanticActionPort,
            int eventCapacity,
            int maxRetainedRoutines,
            long terminalTtlTicks,
            Supplier<UUID> routineIds) {
        this(
                stationaryBreakPort,
                semanticActionPort,
                null,
                null,
                eventCapacity,
                maxRetainedRoutines,
                terminalTtlTicks,
                routineIds);
    }

    RoutineManager(
            StationaryBreakPort stationaryBreakPort,
            SemanticActionPort semanticActionPort,
            ApplyBlockPlanPort applyBlockPlanPort,
            int eventCapacity,
            int maxRetainedRoutines,
            long terminalTtlTicks,
            Supplier<UUID> routineIds) {
        this(
                stationaryBreakPort,
                semanticActionPort,
                applyBlockPlanPort,
                null,
                eventCapacity,
                maxRetainedRoutines,
                terminalTtlTicks,
                routineIds);
    }

    RoutineManager(
            StationaryBreakPort stationaryBreakPort,
            SemanticActionPort semanticActionPort,
            ApplyBlockPlanPort applyBlockPlanPort,
            PhaseFivePort phaseFivePort,
            int eventCapacity,
            int maxRetainedRoutines,
            long terminalTtlTicks,
            Supplier<UUID> routineIds) {
        this(
                stationaryBreakPort,
                semanticActionPort,
                applyBlockPlanPort,
                phaseFivePort,
                null,
                eventCapacity,
                maxRetainedRoutines,
                terminalTtlTicks,
                routineIds);
    }

    RoutineManager(
            StationaryBreakPort stationaryBreakPort,
            SemanticActionPort semanticActionPort,
            ApplyBlockPlanPort applyBlockPlanPort,
            PhaseFivePort phaseFivePort,
            FinitePlanPort finitePlanPort,
            int eventCapacity,
            int maxRetainedRoutines,
            long terminalTtlTicks,
            Supplier<UUID> routineIds) {
        this.stationaryBreakPort = Objects.requireNonNull(stationaryBreakPort, "stationaryBreakPort");
        this.semanticActionPort = semanticActionPort;
        this.applyBlockPlanPort = applyBlockPlanPort;
        this.phaseFivePort = phaseFivePort;
        this.finitePlanPort = finitePlanPort;
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
        Objects.requireNonNull(request, "request");
        request.validateAdmissionTick(admittedClientTick);
        return admitRoutine(
                StationaryBreakRoutine.KIND,
                idempotencyKey,
                request,
                admittedClientTick,
                (routineId, eventCapacity, tick) -> new StationaryBreakRoutine(
                        routineId, request, stationaryBreakPort, eventCapacity, tick));
    }

    /** Starts one typed Phase 3 action using the request itself as the local identity. */
    public synchronized StartReceipt startSemanticAction(
            String idempotencyKey,
            SemanticActionRequest request,
            long admittedClientTick) {
        Objects.requireNonNull(request, "request");
        request.validateAdmissionTick(admittedClientTick);
        return admitSemanticAction(idempotencyKey, request, request, admittedClientTick);
    }

    /** Starts one typed Phase 3 action with a stable external canonical identity. */
    public synchronized StartReceipt startSemanticAction(
            String idempotencyKey,
            String requestIdentity,
            SemanticActionRequest request,
            long admittedClientTick) {
        Objects.requireNonNull(request, "request");
        request.validateAdmissionTick(admittedClientTick);
        return admitSemanticAction(
                idempotencyKey,
                requireRequestIdentity(requestIdentity),
                request,
                admittedClientTick);
    }

    private StartReceipt admitSemanticAction(
            String idempotencyKey,
            Object requestIdentity,
            SemanticActionRequest request,
            long admittedClientTick) {
        var port = requireSemanticActionPort();
        return admitRoutine(
                request.kind(),
                idempotencyKey,
                requestIdentity,
                admittedClientTick,
                (routineId, eventCapacity, tick) -> request instanceof NavigateToRequest navigation
                        ? new NavigateRoutine(routineId, navigation, port, eventCapacity, tick)
                        : new FiniteActionRoutine(routineId, request, port, eventCapacity, tick));
    }

    /** Starts one bounded Phase 4 plan while keeping all child actions private to its parent. */
    public synchronized StartReceipt startApplyBlockPlan(
            String idempotencyKey,
            ApplyBlockPlanRequest request,
            long admittedClientTick) {
        Objects.requireNonNull(request, "request");
        request.validateAdmissionTick(admittedClientTick);
        return admitApplyBlockPlan(idempotencyKey, request, request, admittedClientTick);
    }

    public synchronized StartReceipt startApplyBlockPlan(
            String idempotencyKey,
            String requestIdentity,
            ApplyBlockPlanRequest request,
            long admittedClientTick) {
        Objects.requireNonNull(request, "request");
        request.validateAdmissionTick(admittedClientTick);
        return admitApplyBlockPlan(
                idempotencyKey,
                requireRequestIdentity(requestIdentity),
                request,
                admittedClientTick);
    }

    private StartReceipt admitApplyBlockPlan(
            String idempotencyKey,
            Object requestIdentity,
            ApplyBlockPlanRequest request,
            long admittedClientTick) {
        var port = requireApplyBlockPlanPort();
        return admitRoutine(
                ApplyBlockPlanRoutine.KIND,
                idempotencyKey,
                requestIdentity,
                admittedClientTick,
                (routineId, eventCapacity, tick) -> new ApplyBlockPlanRoutine(
                        routineId, request, port, eventCapacity, tick));
    }

    /** Starts one bounded Phase 5 operation using the request as its local identity. */
    public synchronized StartReceipt startPhaseFive(
            String idempotencyKey,
            PhaseFiveRequest request,
            long admittedClientTick) {
        Objects.requireNonNull(request, "request");
        request.validateAdmissionTick(admittedClientTick);
        return admitPhaseFive(idempotencyKey, request, request, admittedClientTick);
    }

    /** Starts one Phase 5 operation with a stable external canonical identity. */
    public synchronized StartReceipt startPhaseFive(
            String idempotencyKey,
            String requestIdentity,
            PhaseFiveRequest request,
            long admittedClientTick) {
        Objects.requireNonNull(request, "request");
        request.validateAdmissionTick(admittedClientTick);
        return admitPhaseFive(
                idempotencyKey,
                requireRequestIdentity(requestIdentity),
                request,
                admittedClientTick);
    }

    private StartReceipt admitPhaseFive(
            String idempotencyKey,
            Object requestIdentity,
            PhaseFiveRequest request,
            long admittedClientTick) {
        var port = requirePhaseFivePort();
        return admitRoutine(
                request.kind(),
                idempotencyKey,
                requestIdentity,
                admittedClientTick,
                (routineId, eventCapacity, tick) -> new PhaseFiveRoutine(
                        routineId, request, port, eventCapacity, tick));
    }

    /** Starts one bounded plan while keeping every child action private to its parent. */
    public synchronized StartReceipt startFinitePlan(
            String idempotencyKey,
            FinitePlanRequest request,
            long admittedClientTick) {
        Objects.requireNonNull(request, "request");
        return admitFinitePlan(idempotencyKey, request, request, admittedClientTick);
    }

    public synchronized StartReceipt startFinitePlan(
            String idempotencyKey,
            String requestIdentity,
            FinitePlanRequest request,
            long admittedClientTick) {
        Objects.requireNonNull(request, "request");
        return admitFinitePlan(
                idempotencyKey,
                requireRequestIdentity(requestIdentity),
                request,
                admittedClientTick);
    }

    private StartReceipt admitFinitePlan(
            String idempotencyKey,
            Object requestIdentity,
            FinitePlanRequest request,
            long admittedClientTick) {
        var port = requireFinitePlanPort();
        return admitRoutine(
                FinitePlanRoutine.KIND,
                idempotencyKey,
                requestIdentity,
                admittedClientTick,
                (routineId, eventCapacity, tick) -> new FinitePlanRoutine(
                        routineId, request, port, eventCapacity, tick));
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
        Objects.requireNonNull(request, "request");
        request.validateAdmissionTick(admittedClientTick);
        return admitRoutine(
                StationaryBreakRoutine.KIND,
                idempotencyKey,
                requireRequestIdentity(requestIdentity),
                admittedClientTick,
                (routineId, eventCapacity, tick) -> new StationaryBreakRoutine(
                        routineId, request, stationaryBreakPort, eventCapacity, tick));
    }

    /** Shared atomic admission path used by typed routine-specific public entry points. */
    synchronized StartReceipt admitRoutine(
            String kind,
            String idempotencyKey,
            Object requestIdentity,
            long admittedClientTick,
            RoutineFactory factory) {
        var normalizedKind = normalizeKind(kind);
        var normalizedIdentity = new TypedRequestIdentity(
                normalizedKind, Objects.requireNonNull(requestIdentity, "requestIdentity"));
        Objects.requireNonNull(factory, "factory");
        if (admittedClientTick < 0) {
            throw new IllegalArgumentException("admission tick must be non-negative");
        }
        var normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        purgeExpired(admittedClientTick);

        var existing = idempotency.get(normalizedKey);
        if (existing != null) {
            if (!existing.requestIdentity.equals(normalizedIdentity)) {
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
        var routine = Objects.requireNonNull(
                factory.create(routineId, eventCapacity, admittedClientTick),
                "routine factory returned null");
        if (!routineId.equals(routine.routineId())
                || !normalizedKind.equals(routine.kind())
                || routine.state() != RoutineState.QUEUED
                || routine.lastClientTick() != admittedClientTick) {
            try {
                routine.retire();
            } catch (RuntimeException | LinkageError ignored) {
                // A malformed factory cannot prevent deterministic admission rejection.
            }
            throw new IllegalArgumentException("routine factory violated the admission contract");
        }
        routines.put(routineId, routine);
        idempotency.put(normalizedKey, new IdempotencyEntry(
                normalizedKey, routineId, normalizedIdentity));
        activeRoutineId = routineId;
        return new StartReceipt(routineId, false);
    }

    /** Returns an existing same-request receipt without requiring mutable world validation. */
    public synchronized Optional<StartReceipt> replayStationaryBreak(
            String idempotencyKey,
            String requestIdentity,
            long currentClientTick) {
        return replayRoutine(
                StationaryBreakRoutine.KIND,
                idempotencyKey,
                requireRequestIdentity(requestIdentity),
                currentClientTick);
    }

    public synchronized Optional<StartReceipt> replaySemanticAction(
            String idempotencyKey,
            String requestIdentity,
            SemanticActionRequest request,
            long currentClientTick) {
        Objects.requireNonNull(request, "request");
        requireSemanticActionPort();
        return replayRoutine(
                request.kind(),
                idempotencyKey,
                requireRequestIdentity(requestIdentity),
                currentClientTick);
    }

    public synchronized Optional<StartReceipt> replayApplyBlockPlan(
            String idempotencyKey,
            String requestIdentity,
            long currentClientTick) {
        requireApplyBlockPlanPort();
        return replayRoutine(
                ApplyBlockPlanRoutine.KIND,
                idempotencyKey,
                requireRequestIdentity(requestIdentity),
                currentClientTick);
    }

    public synchronized Optional<StartReceipt> replayPhaseFive(
            String idempotencyKey,
            String requestIdentity,
            PhaseFiveRequest request,
            long currentClientTick) {
        Objects.requireNonNull(request, "request");
        requirePhaseFivePort();
        return replayRoutine(
                request.kind(),
                idempotencyKey,
                requireRequestIdentity(requestIdentity),
                currentClientTick);
    }

    public synchronized Optional<StartReceipt> replayFinitePlan(
            String idempotencyKey,
            String requestIdentity,
            long currentClientTick) {
        requireFinitePlanPort();
        return replayRoutine(
                FinitePlanRoutine.KIND,
                idempotencyKey,
                requireRequestIdentity(requestIdentity),
                currentClientTick);
    }

    /** Shared replay path; callers must retain any external finalization gate before invoking it. */
    synchronized Optional<StartReceipt> replayRoutine(
            String kind,
            String idempotencyKey,
            Object requestIdentity,
            long currentClientTick) {
        var normalizedIdentity = new TypedRequestIdentity(
                normalizeKind(kind), Objects.requireNonNull(requestIdentity, "requestIdentity"));
        if (currentClientTick < 0) {
            throw new IllegalArgumentException("current tick must be non-negative");
        }
        var normalizedKey = normalizeIdempotencyKey(idempotencyKey);
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
                routine.retire();
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

    private Optional<ManagedRoutine> activeRoutine() {
        if (activeRoutineId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(routines.get(activeRoutineId));
    }

    private ManagedRoutine requireRoutine(UUID routineId) {
        Objects.requireNonNull(routineId, "routineId");
        var routine = routines.get(routineId);
        if (routine == null) {
            throw new RoutineNotFoundException(routineId);
        }
        return routine;
    }

    private void markTerminal(ManagedRoutine routine) {
        try {
            routine.retire();
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
            Iterator<Map.Entry<UUID, ManagedRoutine>> iterator = routines.entrySet().iterator();
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

    private static String normalizeKind(String kind) {
        Objects.requireNonNull(kind, "kind");
        if (!kind.matches("[a-z][a-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("invalid routine kind");
        }
        return kind;
    }

    private static String requireRequestIdentity(String identity) {
        Objects.requireNonNull(identity, "requestIdentity");
        if (identity.isBlank() || identity.length() > 4_096) {
            throw new IllegalArgumentException("request identity must be 1..4096 characters");
        }
        return identity;
    }

    private SemanticActionPort requireSemanticActionPort() {
        if (semanticActionPort == null) {
            throw new IllegalStateException("semantic action port is not configured");
        }
        return semanticActionPort;
    }

    private ApplyBlockPlanPort requireApplyBlockPlanPort() {
        if (applyBlockPlanPort == null) {
            throw new IllegalStateException("apply block plan port is not configured");
        }
        return applyBlockPlanPort;
    }

    private PhaseFivePort requirePhaseFivePort() {
        if (phaseFivePort == null) {
            throw new IllegalStateException("Phase 5 port is not configured");
        }
        return phaseFivePort;
    }

    private FinitePlanPort requireFinitePlanPort() {
        if (finitePlanPort == null) {
            throw new IllegalStateException("finite plan port is not configured");
        }
        return finitePlanPort;
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

    @FunctionalInterface
    interface RoutineFactory {
        ManagedRoutine create(UUID routineId, int eventCapacity, long admittedClientTick);
    }

    private record TypedRequestIdentity(String kind, Object requestIdentity) {
        private TypedRequestIdentity {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(requestIdentity, "requestIdentity");
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
