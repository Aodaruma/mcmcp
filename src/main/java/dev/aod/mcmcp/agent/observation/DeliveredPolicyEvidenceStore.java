package dev.aod.mcmcp.agent.observation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Bounded, session-local memory of static visual surfaces actually delivered to the MCP client.
 *
 * <p>This is not a hidden-world cache. Only {@link ObservationRecord.VisibleSurface} records from
 * successful {@code agent_get_observation} pages enter the store. Dynamic entities, hazards,
 * traversability, sounds, unknown boundaries, and records on undisclosed pages are never retained
 * here. Runtime admission still applies the current session, visual-revision, exact-target,
 * observer-pose, reach, commit, JIT, ray, and server-acknowledgement fences.</p>
 */
public final class DeliveredPolicyEvidenceStore {
    public static final int MAX_RETAINED_SURFACES = 2_048;
    public static final Duration SURFACE_IDLE_TIMEOUT = Duration.ofSeconds(60);
    public static final int MAX_PENDING_DELIVERIES = 16;
    public static final Duration PENDING_DELIVERY_TIMEOUT = Duration.ofSeconds(60);

    private final LongSupplier nanoTime;
    private final Supplier<UUID> receiptIds;
    private final LinkedHashMap<SurfaceKey, Entry> surfaces = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, PendingDelivery> pending = new LinkedHashMap<>();

    public DeliveredPolicyEvidenceStore() {
        this(System::nanoTime, UUID::randomUUID);
    }

    DeliveredPolicyEvidenceStore(LongSupplier nanoTime) {
        this(nanoTime, UUID::randomUUID);
    }

    DeliveredPolicyEvidenceStore(LongSupplier nanoTime, Supplier<UUID> receiptIds) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.receiptIds = Objects.requireNonNull(receiptIds, "receiptIds");
    }

    /** Stages one runtime-produced page until the HTTP response write succeeds. */
    public synchronized UUID prepareDelivery(ObservationPage page) {
        Objects.requireNonNull(page, "page");
        long now = nanoTime.getAsLong();
        purgeExpired(now);
        UUID receipt;
        do {
            receipt = Objects.requireNonNull(receiptIds.get(), "receiptId");
        } while (pending.containsKey(receipt));
        pending.put(receipt, new PendingDelivery(page, now));
        while (pending.size() > MAX_PENDING_DELIVERIES) {
            Iterator<UUID> oldest = pending.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        return receipt;
    }

    /** Promotes a staged page only after the HTTP layer confirms a successful response write. */
    public synchronized boolean confirmDelivery(UUID receipt) {
        Objects.requireNonNull(receipt, "receipt");
        long now = nanoTime.getAsLong();
        purgeExpired(now);
        PendingDelivery delivery = pending.remove(receipt);
        if (delivery == null) {
            return false;
        }
        recordDelivered(delivery.page(), now);
        return true;
    }

    /** Drops a staged page after cancellation, timeout, or response-write failure. */
    public synchronized boolean abandonDelivery(UUID receipt) {
        Objects.requireNonNull(receipt, "receipt");
        purgeExpired(nanoTime.getAsLong());
        return pending.remove(receipt) != null;
    }

    /** Test helper for an already-confirmed page. Production uses the two-phase methods above. */
    public synchronized void recordDelivered(ObservationPage page) {
        Objects.requireNonNull(page, "page");
        long now = nanoTime.getAsLong();
        purgeExpired(now);
        recordDelivered(page, now);
    }

    private void recordDelivered(ObservationPage page, long now) {
        for (ObservationRecord record : page.records()) {
            if (!(record instanceof ObservationRecord.VisibleSurface surface)) {
                continue;
            }
            SurfaceKey key = SurfaceKey.of(surface);
            surfaces.remove(key);
            surfaces.put(key, new Entry(surface, now));
            while (surfaces.size() > MAX_RETAINED_SURFACES) {
                Iterator<SurfaceKey> oldest = surfaces.keySet().iterator();
                oldest.next();
                oldest.remove();
            }
        }
    }

    /**
     * Builds a planner frame whose static surfaces are limited to delivered face keys. Current
     * matching records refresh those keys; retained records fill sampling gaps. Non-surface
     * records remain current-frame-only, so no dynamic evidence receives a TTL extension.
     */
    public synchronized Optional<ObservationFrame> augment(
            Optional<ObservationFrame> latestFrame) {
        Objects.requireNonNull(latestFrame, "latestFrame");
        long now = nanoTime.getAsLong();
        purgeExpired(now);
        if (latestFrame.isEmpty()) {
            return latestFrame;
        }

        ObservationFrame latest = latestFrame.orElseThrow();
        Set<SurfaceKey> current = new LinkedHashSet<>();
        var records = new ArrayList<ObservationRecord>(latest.records().size());
        for (ObservationRecord record : latest.records()) {
            if (record instanceof ObservationRecord.VisibleSurface surface) {
                SurfaceKey key = SurfaceKey.of(surface);
                if (surfaces.containsKey(key)) {
                    current.add(key);
                    records.add(surface);
                }
            } else {
                records.add(record);
            }
        }

        for (Map.Entry<SurfaceKey, Entry> retained : surfaces.entrySet()) {
            if (!current.contains(retained.getKey())) {
                records.add(retained.getValue().surface());
            }
        }
        return Optional.of(new ObservationFrame(
                latest.frameId(),
                latest.dimension(),
                latest.frameCompletedTick(),
                latest.configuredVisualRadiusBlocks(),
                latest.visibleEntitiesTruncated(),
                latest.recentSoundCluesTruncated(),
                records));
    }

    /** Clears the complete evidence boundary at a world-session transition. */
    public synchronized void clear() {
        surfaces.clear();
        pending.clear();
    }

    synchronized int retainedSurfaceCount() {
        purgeExpired(nanoTime.getAsLong());
        return surfaces.size();
    }

    synchronized int pendingDeliveryCount() {
        purgeExpired(nanoTime.getAsLong());
        return pending.size();
    }

    private void purgeExpired(long now) {
        long timeout = SURFACE_IDLE_TIMEOUT.toNanos();
        surfaces.entrySet().removeIf(entry -> elapsed(now, entry.getValue().deliveredNanos())
                >= timeout);
        long pendingTimeout = PENDING_DELIVERY_TIMEOUT.toNanos();
        pending.entrySet().removeIf(entry -> elapsed(now, entry.getValue().preparedNanos())
                >= pendingTimeout);
    }

    private static long elapsed(long now, long start) {
        return now - start;
    }

    private record Entry(
            ObservationRecord.VisibleSurface surface,
            long deliveredNanos) {
        private Entry {
            Objects.requireNonNull(surface, "surface");
        }
    }

    private record PendingDelivery(ObservationPage page, long preparedNanos) {
        private PendingDelivery {
            Objects.requireNonNull(page, "page");
        }
    }

    private record SurfaceKey(
            ObservationValues.BlockPosition position,
            ObservationRecord.Face face,
            ObservationValues.ResourceId block) {
        private SurfaceKey {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(face, "face");
            Objects.requireNonNull(block, "block");
        }

        private static SurfaceKey of(ObservationRecord.VisibleSurface surface) {
            return new SurfaceKey(surface.position(), surface.face(), surface.block());
        }
    }
}
