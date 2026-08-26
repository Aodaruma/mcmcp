package dev.aod.mcmcp.agent.observation;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Thread-safe rolling frame store with at most two independently expiring pagination leases.
 * Minecraft objects never enter this boundary.
 */
public final class ObservationFrameStore {
    public static final int ROLLING_FRAME_LIMIT = 2;
    public static final int PAGINATION_LEASE_LIMIT = 2;
    public static final Duration LEASE_IDLE_TIMEOUT = Duration.ofSeconds(60);
    public static final Duration LEASE_ABSOLUTE_TIMEOUT = Duration.ofMinutes(5);

    private static final int CURSOR_ENTROPY_BYTES = 16;

    private final LongSupplier nanoTime;
    private final SecureRandom secureRandom;
    private final ArrayDeque<ObservationFrame> rollingFrames = new ArrayDeque<>(ROLLING_FRAME_LIMIT);
    private final Set<Lease> leases = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<String, CursorState> cursors = new HashMap<>();

    public ObservationFrameStore() {
        this(System::nanoTime, new SecureRandom());
    }

    ObservationFrameStore(LongSupplier nanoTime, SecureRandom secureRandom) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
    }

    public synchronized void publish(ObservationFrame frame) {
        Objects.requireNonNull(frame, "frame");
        purgeExpired(nanoTime.getAsLong());
        if (findRetainedFrame(frame.frameId()) != null) {
            throw new IllegalArgumentException("Observation frame ID is already retained");
        }
        rollingFrames.addLast(frame);
        while (rollingFrames.size() > ROLLING_FRAME_LIMIT) {
            rollingFrames.removeFirst();
        }
    }

    public synchronized Optional<ObservationFrame> latestFrame() {
        purgeExpired(nanoTime.getAsLong());
        return Optional.ofNullable(rollingFrames.peekLast());
    }

    public synchronized Optional<ObservationFrameSummary> latestSummary() {
        return latestFrame().map(ObservationFrame::summary);
    }

    public synchronized int activePaginationLeases() {
        purgeExpired(nanoTime.getAsLong());
        return leases.size();
    }

    public synchronized ObservationPage page(
            String frameId,
            Set<ObservationKind> requestedKinds,
            String cursor,
            int limit) throws ObservationStoreException {
        ObservationFrame.requireFrameId(frameId);
        Set<ObservationKind> kinds = canonicalKinds(requestedKinds);
        if (limit < 1 || limit > 256) {
            throw new IllegalArgumentException("limit must be in 1..256");
        }

        long now = nanoTime.getAsLong();
        purgeExpired(now);
        if (cursor != null) {
            return continuePage(frameId, kinds, cursor, limit, now);
        }
        return firstPage(frameId, kinds, limit, now);
    }

    /** Invalidates every frame, pagination lease, and cursor at a world-session boundary. */
    public synchronized void clear() {
        rollingFrames.clear();
        leases.clear();
        cursors.clear();
    }

    private ObservationPage firstPage(
            String frameId,
            Set<ObservationKind> kinds,
            int limit,
            long now) throws ObservationStoreException {
        ObservationFrame frame = findRetainedFrame(frameId);
        if (frame == null) {
            throw failure(ObservationStoreException.Code.FRAME_EXPIRED,
                    "The requested observation frame is no longer retained");
        }
        List<ObservationRecord> selected = frame.records().stream()
                .filter(record -> kinds.contains(record.kind()))
                .toList();
        int end = Math.min(limit, selected.size());
        if (end == selected.size()) {
            return page(frame, selected.subList(0, end), null);
        }
        if (leases.size() >= PAGINATION_LEASE_LIMIT) {
            throw failure(ObservationStoreException.Code.SERVER_BUSY,
                    "All observation pagination leases are in use");
        }

        Lease lease = new Lease(frame, kinds, selected, now);
        leases.add(lease);
        String nextCursor = createCursor(lease, end);
        return page(frame, selected.subList(0, end), nextCursor);
    }

    private ObservationPage continuePage(
            String frameId,
            Set<ObservationKind> kinds,
            String cursor,
            int limit,
            long now) throws ObservationStoreException {
        CursorState state = cursors.get(cursor);
        if (state == null
                || !state.lease.frame.frameId().equals(frameId)
                || !state.lease.kinds.equals(kinds)) {
            throw failure(ObservationStoreException.Code.INVALID_CURSOR,
                    "The observation cursor is invalid, expired, or belongs to another query");
        }

        Lease lease = state.lease;
        lease.lastAccessNanos = now;
        if (state.cachedPage == null) {
            int end = Math.min(state.offset + limit, lease.selectedRecords.size());
            String nextCursor = end < lease.selectedRecords.size()
                    ? createCursor(lease, end)
                    : null;
            state.cachedPage = page(
                    lease.frame,
                    lease.selectedRecords.subList(state.offset, end),
                    nextCursor);
        }
        return state.cachedPage;
    }

    private String createCursor(Lease lease, int offset) {
        byte[] entropy = new byte[CURSOR_ENTROPY_BYTES];
        String token;
        do {
            secureRandom.nextBytes(entropy);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        } while (cursors.containsKey(token));
        cursors.put(token, new CursorState(lease, offset));
        lease.cursorTokens.add(token);
        return token;
    }

    private static ObservationPage page(
            ObservationFrame frame, List<ObservationRecord> records, String nextCursor) {
        return new ObservationPage(
                frame.frameId(), frame.frameCompletedTick(),
                frame.visibleEntitiesTruncated(), records, nextCursor);
    }

    private ObservationFrame findRetainedFrame(String frameId) {
        for (ObservationFrame frame : rollingFrames) {
            if (frame.frameId().equals(frameId)) {
                return frame;
            }
        }
        for (Lease lease : leases) {
            if (lease.frame.frameId().equals(frameId)) {
                return lease.frame;
            }
        }
        return null;
    }

    private void purgeExpired(long now) {
        var iterator = leases.iterator();
        while (iterator.hasNext()) {
            Lease lease = iterator.next();
            if (elapsed(now, lease.lastAccessNanos) >= LEASE_IDLE_TIMEOUT.toNanos()
                    || elapsed(now, lease.createdNanos) >= LEASE_ABSOLUTE_TIMEOUT.toNanos()) {
                iterator.remove();
                for (String token : lease.cursorTokens) {
                    cursors.remove(token);
                }
            }
        }
    }

    private static long elapsed(long now, long start) {
        // System.nanoTime is meaningful only by subtraction; natural wrap is intentional.
        return now - start;
    }

    private static Set<ObservationKind> canonicalKinds(Set<ObservationKind> requestedKinds) {
        Objects.requireNonNull(requestedKinds, "requestedKinds");
        if (requestedKinds.isEmpty() || requestedKinds.size() > ObservationKind.values().length) {
            throw new IllegalArgumentException("kinds must contain 1..6 values");
        }
        if (requestedKinds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("kinds must not contain null");
        }
        var copied = EnumSet.copyOf(requestedKinds);
        return Collections.unmodifiableSet(copied);
    }

    private static ObservationStoreException failure(
            ObservationStoreException.Code code, String message) {
        return new ObservationStoreException(code, message);
    }

    private static final class Lease {
        private final ObservationFrame frame;
        private final Set<ObservationKind> kinds;
        private final List<ObservationRecord> selectedRecords;
        private final long createdNanos;
        private long lastAccessNanos;
        private final List<String> cursorTokens = new ArrayList<>();

        private Lease(
                ObservationFrame frame,
                Set<ObservationKind> kinds,
                List<ObservationRecord> selectedRecords,
                long now) {
            this.frame = frame;
            this.kinds = kinds;
            this.selectedRecords = List.copyOf(selectedRecords);
            createdNanos = now;
            lastAccessNanos = now;
        }
    }

    private static final class CursorState {
        private final Lease lease;
        private final int offset;
        private ObservationPage cachedPage;

        private CursorState(Lease lease, int offset) {
            this.lease = lease;
            this.offset = offset;
        }
    }
}
