package dev.aod.mcmcp.agent.observation;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Thread-safe rolling frame store with bounded announced-frame handles and at most two
 * independently expiring pagination leases.
 * Minecraft objects never enter this boundary.
 */
public final class ObservationFrameStore {
    public static final int ROLLING_FRAME_LIMIT = 2;
    public static final int ANNOUNCED_FRAME_LIMIT = 16;
    public static final int ANNOUNCED_RECORD_LIMIT = 65_536;
    public static final Duration ANNOUNCED_FRAME_IDLE_TIMEOUT = Duration.ofSeconds(60);
    public static final int PAGINATION_LEASE_LIMIT = 2;
    public static final Duration LEASE_IDLE_TIMEOUT = Duration.ofSeconds(60);
    public static final Duration LEASE_ABSOLUTE_TIMEOUT = Duration.ofMinutes(5);

    private static final int CURSOR_ENTROPY_BYTES = 16;

    private final LongSupplier nanoTime;
    private final SecureRandom secureRandom;
    private final ArrayDeque<ObservationFrame> rollingFrames = new ArrayDeque<>(ROLLING_FRAME_LIMIT);
    private final LinkedHashMap<String, AnnouncedFrame> announcedFrames = new LinkedHashMap<>();
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

    /**
     * Returns and pins the latest summary as an externally announced frame handle.
     *
     * <p>The handle is independent of pagination leases. Re-announcement and an initial page
     * read refresh its monotonic idle deadline. The least-recently used handle is evicted when
     * the fixed cap is exceeded.</p>
     */
    public synchronized Optional<ObservationFrameSummary> announceLatestSummary() {
        long now = nanoTime.getAsLong();
        purgeExpired(now);
        ObservationFrame frame = rollingFrames.peekLast();
        if (frame == null) {
            return Optional.empty();
        }
        touchAnnounced(frame, now);
        return Optional.of(frame.summary());
    }

    synchronized int announcedFrameCount() {
        purgeExpired(nanoTime.getAsLong());
        return announcedFrames.size();
    }

    synchronized int announcedRecordCount() {
        purgeExpired(nanoTime.getAsLong());
        return announcedFrames.values().stream()
                .mapToInt(frame -> frame.frame.records().size())
                .sum();
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
        return page(frameId, requestedKinds, ObservationFilter.NONE, cursor, limit);
    }

    public synchronized ObservationPage page(
            String frameId,
            Set<ObservationKind> requestedKinds,
            ObservationFilter filter,
            String cursor,
            int limit) throws ObservationStoreException {
        ObservationFrame.requireFrameId(frameId);
        Set<ObservationKind> kinds = canonicalKinds(requestedKinds);
        Objects.requireNonNull(filter, "filter");
        if (limit < 1 || limit > 256) {
            throw new IllegalArgumentException("limit must be in 1..256");
        }

        long now = nanoTime.getAsLong();
        purgeExpired(now);
        if (cursor != null) {
            return continuePage(frameId, kinds, filter, cursor, limit, now);
        }
        return firstPage(frameId, kinds, filter, limit, now);
    }

    /** Invalidates every frame, pagination lease, and cursor at a world-session boundary. */
    public synchronized void clear() {
        rollingFrames.clear();
        announcedFrames.clear();
        leases.clear();
        cursors.clear();
    }

    private ObservationPage firstPage(
            String frameId,
            Set<ObservationKind> kinds,
            ObservationFilter filter,
            int limit,
            long now) throws ObservationStoreException {
        ObservationFrame frame = findRetainedFrame(frameId, now, true);
        if (frame == null) {
            throw failure(ObservationStoreException.Code.FRAME_EXPIRED,
                    "The requested observation frame is no longer retained");
        }
        List<ObservationRecord> selected = selectForDelivery(frame.records(), kinds, filter);
        int end = Math.min(limit, selected.size());
        if (end == selected.size()) {
            return page(frame, selected.subList(0, end), null);
        }
        if (leases.size() >= PAGINATION_LEASE_LIMIT) {
            throw failure(ObservationStoreException.Code.SERVER_BUSY,
                    "All observation pagination leases are in use");
        }

        Lease lease = new Lease(frame, kinds, filter, selected, now);
        leases.add(lease);
        String nextCursor = createCursor(lease, end);
        return page(frame, selected.subList(0, end), nextCursor);
    }

    private ObservationPage continuePage(
            String frameId,
            Set<ObservationKind> kinds,
            ObservationFilter filter,
            String cursor,
            int limit,
            long now) throws ObservationStoreException {
        CursorState state = cursors.get(cursor);
        if (state == null
                || !state.lease.frame.frameId().equals(frameId)
                || !state.lease.kinds.equals(kinds)
                || !state.lease.filter.equals(filter)) {
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
        return findRetainedFrame(frameId, nanoTime.getAsLong(), false);
    }

    private ObservationFrame findRetainedFrame(String frameId, long now, boolean touchAnnounced) {
        AnnouncedFrame announced = announcedFrames.get(frameId);
        if (announced != null) {
            if (touchAnnounced) {
                announcedFrames.remove(frameId);
                announced.lastAccessNanos = now;
                announcedFrames.put(frameId, announced);
            }
            return announced.frame;
        }
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

    private void touchAnnounced(ObservationFrame frame, long now) {
        AnnouncedFrame announced = announcedFrames.remove(frame.frameId());
        if (announced == null) {
            announced = new AnnouncedFrame(frame, now);
        } else {
            announced.lastAccessNanos = now;
        }
        announcedFrames.put(frame.frameId(), announced);
        while (announcedFrames.size() > ANNOUNCED_FRAME_LIMIT
                || announcedRecordCount() > ANNOUNCED_RECORD_LIMIT) {
            announcedFrames.remove(announcedFrames.keySet().iterator().next());
        }
    }

    private void purgeExpired(long now) {
        var announcedIterator = announcedFrames.entrySet().iterator();
        while (announcedIterator.hasNext()) {
            AnnouncedFrame announced = announcedIterator.next().getValue();
            if (elapsed(now, announced.lastAccessNanos)
                    >= ANNOUNCED_FRAME_IDLE_TIMEOUT.toNanos()) {
                announcedIterator.remove();
            }
        }
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

    /**
     * Compacts duplicate visual faces to one deterministic record per block and fairly
     * interleaves requested kinds. The complete internal frame remains unchanged for safety
     * planning; this projection only prevents actionable evidence from being buried in the MCP
     * response by hundreds of duplicate faces or unknown-boundary records.
     */
    static List<ObservationRecord> selectForDelivery(
            List<ObservationRecord> records, Set<ObservationKind> kinds) {
        return selectForDelivery(records, kinds, ObservationFilter.NONE);
    }

    static List<ObservationRecord> selectForDelivery(
            List<ObservationRecord> records,
            Set<ObservationKind> kinds,
            ObservationFilter filter) {
        Objects.requireNonNull(records, "records");
        Objects.requireNonNull(kinds, "kinds");
        Objects.requireNonNull(filter, "filter");
        var buckets = new EnumMap<ObservationKind, List<ObservationRecord>>(
                ObservationKind.class);
        for (ObservationKind kind : ObservationKind.values()) {
            buckets.put(kind, new ArrayList<>());
        }
        var surfaces = new LinkedHashMap<ObservationValues.BlockPosition,
                ObservationRecord.VisibleSurface>();
        for (ObservationRecord record : records) {
            Objects.requireNonNull(record, "record");
            if (!kinds.contains(record.kind()) || !filter.matches(record)) {
                continue;
            }
            if (record instanceof ObservationRecord.VisibleSurface surface) {
                surfaces.merge(surface.position(), surface,
                        ObservationFrameStore::preferredSurface);
            } else {
                buckets.get(record.kind()).add(record);
            }
        }
        buckets.get(ObservationKind.VISIBLE_SURFACE).addAll(surfaces.values().stream()
                .sorted(Comparator
                        .comparingInt(ObservationFrameStore::cropDeliveryRank)
                        .thenComparingDouble(ObservationFrameStore::observationDistanceSquared)
                        .thenComparingInt(surface -> surface.position().x())
                        .thenComparingInt(surface -> surface.position().y())
                        .thenComparingInt(surface -> surface.position().z())
                        .thenComparingInt(surface -> surface.face().ordinal()))
                .toList());

        List<ObservationKind> priority = List.of(
                ObservationKind.VISIBLE_ENTITY,
                ObservationKind.TRAVERSABILITY,
                ObservationKind.HAZARD,
                ObservationKind.VISIBLE_SURFACE,
                ObservationKind.SOUND_CLUE,
                ObservationKind.UNKNOWN_BOUNDARY);
        int maximum = buckets.values().stream().mapToInt(List::size).max().orElse(0);
        var selected = new ArrayList<ObservationRecord>();
        for (int index = 0; index < maximum; index++) {
            for (ObservationKind kind : priority) {
                List<ObservationRecord> bucket = buckets.get(kind);
                if (index < bucket.size()) {
                    selected.add(bucket.get(index));
                }
            }
        }
        return List.copyOf(selected);
    }

    private static ObservationRecord.VisibleSurface preferredSurface(
            ObservationRecord.VisibleSurface retained,
            ObservationRecord.VisibleSurface candidate) {
        if (candidate.worldRevision() != retained.worldRevision()) {
            return candidate.worldRevision() > retained.worldRevision() ? candidate : retained;
        }
        // A later ray in the same unchanged world does not make its face more authoritative.
        // Prefer the action-enabling UP face before scan timing so multi-tick direction order
        // cannot erase an exact placement support at the delivery boundary.
        int candidateRank = faceRank(candidate.face());
        int retainedRank = faceRank(retained.face());
        if (candidateRank != retainedRank) {
            return candidateRank < retainedRank ? candidate : retained;
        }
        if (candidate.observedTick() != retained.observedTick()) {
            return candidate.observedTick() > retained.observedTick() ? candidate : retained;
        }
        if (!Objects.equals(candidate.cropMature(), retained.cropMature())) {
            return Boolean.TRUE.equals(candidate.cropMature()) ? candidate : retained;
        }
        if (!"minecraft:farmland".equals(retained.block().value())) {
            double candidateDistance = observationDistanceSquared(candidate);
            double retainedDistance = observationDistanceSquared(retained);
            if (candidateDistance != retainedDistance) {
                return candidateDistance < retainedDistance ? candidate : retained;
            }
        }
        return candidate.face().ordinal() < retained.face().ordinal() ? candidate : retained;
    }

    private static int faceRank(ObservationRecord.Face face) {
        return switch (face) {
            case UP -> 0;
            case NORTH, SOUTH, WEST, EAST -> 1;
            case DOWN -> 2;
        };
    }

    private static int cropDeliveryRank(ObservationRecord.VisibleSurface surface) {
        if (Boolean.TRUE.equals(surface.cropMature())) {
            return 0;
        }
        return surface.cropMature() != null ? 1 : 2;
    }

    private static double observationDistanceSquared(
            ObservationRecord.VisibleSurface surface) {
        if (surface.rayHit() == null) {
            return Double.POSITIVE_INFINITY;
        }
        double dx = surface.rayHit().x() - surface.eyeOrigin().x();
        double dy = surface.rayHit().y() - surface.eyeOrigin().y();
        double dz = surface.rayHit().z() - surface.eyeOrigin().z();
        return dx * dx + dy * dy + dz * dz;
    }

    private static ObservationStoreException failure(
            ObservationStoreException.Code code, String message) {
        return new ObservationStoreException(code, message);
    }

    private static final class Lease {
        private final ObservationFrame frame;
        private final Set<ObservationKind> kinds;
        private final ObservationFilter filter;
        private final List<ObservationRecord> selectedRecords;
        private final long createdNanos;
        private long lastAccessNanos;
        private final List<String> cursorTokens = new ArrayList<>();

        private Lease(
                ObservationFrame frame,
                Set<ObservationKind> kinds,
                ObservationFilter filter,
                List<ObservationRecord> selectedRecords,
                long now) {
            this.frame = frame;
            this.kinds = kinds;
            this.filter = filter;
            this.selectedRecords = List.copyOf(selectedRecords);
            createdNanos = now;
            lastAccessNanos = now;
        }
    }

    private static final class AnnouncedFrame {
        private final ObservationFrame frame;
        private long lastAccessNanos;

        private AnnouncedFrame(ObservationFrame frame, long now) {
            this.frame = Objects.requireNonNull(frame, "frame");
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
