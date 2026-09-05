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
import java.util.function.Function;

/**
 * Bounded, session-local memory of static visual evidence actually delivered to the MCP client.
 *
 * <p>This is not a hidden-world cache. Only {@link ObservationRecord.VisibleSurface} records from
 * successful {@code agent_get_observation} pages enter the static store. Frame-display identity
 * witnesses are retained separately only to gate matching current entities after delivery; they
 * never fill a sampling gap or extend a dynamic record's lifetime. Other dynamic entities, hazards,
 * traversability, sounds, unknown boundaries, and records on undisclosed pages are not retained.
 * Copyable state/item identities receive opaque session refs only after delivery
 * confirmation; unlike coordinate evidence, those identities have no time TTL. Runtime admission
 * still applies the current session, visual-revision, exact-target, observer-pose, reach, commit,
 * JIT, ray, and server-acknowledgement fences.</p>
 */
public final class DeliveredPolicyEvidenceStore {
    public static final int MAX_RETAINED_SURFACES = 2_048;
    public static final int MAX_RETAINED_FRAME_DISPLAYS = 128;
    public static final long FRAME_DISPLAY_DELIVERY_MAX_AGE_TICKS = 100;
    public static final int MAX_RETAINED_PLACEMENT_STATES = 512;
    public static final Duration SURFACE_IDLE_TIMEOUT = Duration.ofSeconds(60);
    public static final int MAX_PENDING_DELIVERIES = 16;
    public static final Duration PENDING_DELIVERY_TIMEOUT = Duration.ofSeconds(60);

    private final LongSupplier nanoTime;
    private final Supplier<UUID> receiptIds;
    private final LinkedHashMap<SurfaceKey, Entry> surfaces = new LinkedHashMap<>();
    private final LinkedHashMap<String, FrameAuthorization> frameDisplays = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, PendingDelivery> pending = new LinkedHashMap<>();
    private final LinkedHashMap<PlacementStateKey, String> placementRefs =
            new LinkedHashMap<>();
    private final LinkedHashMap<String, PlacementStateResolver.PlacementState> placementStates =
            new LinkedHashMap<>();

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
        var stagedPlacementRefs = new LinkedHashMap<PlacementStateKey, String>();
        for (ObservationRecord record : page.records()) {
            if (!(record instanceof ObservationRecord.VisibleSurface surface)) {
                continue;
            }
            PlacementStateKey key = PlacementStateKey.of(surface);
            if (key == null || stagedPlacementRefs.containsKey(key)) {
                continue;
            }
            stagedPlacementRefs.put(key, existingOrNewPlacementRef(key));
        }
        pending.put(receipt, new PendingDelivery(page, now, stagedPlacementRefs));
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
        for (var staged : delivery.placementRefs().entrySet()) {
            activatePlacementState(staged.getKey(), staged.getValue());
        }
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
        for (ObservationRecord record : page.records()) {
            if (record instanceof ObservationRecord.VisibleSurface surface) {
                PlacementStateKey key = PlacementStateKey.of(surface);
                if (key != null) {
                    activatePlacementState(key, existingOrNewPlacementRef(key));
                }
            }
        }
    }

    /** Returns the ref placed on a staged response; it is not usable until confirmation. */
    public synchronized Optional<String> preparedPlacementStateRef(
            UUID receipt, ObservationRecord.VisibleSurface surface) {
        Objects.requireNonNull(receipt, "receipt");
        Objects.requireNonNull(surface, "surface");
        purgeExpired(nanoTime.getAsLong());
        PendingDelivery delivery = pending.get(receipt);
        PlacementStateKey key = PlacementStateKey.of(surface);
        return delivery == null || key == null
                ? Optional.empty()
                : Optional.ofNullable(delivery.placementRefs().get(key));
    }

    /** Resolves only refs promoted by a successfully confirmed response write. */
    public synchronized Optional<PlacementStateResolver.PlacementState> resolvePlacementState(
            String placementStateRef) {
        Objects.requireNonNull(placementStateRef, "placementStateRef");
        return Optional.ofNullable(placementStates.get(placementStateRef));
    }

    private void recordDelivered(ObservationPage page, long now) {
        for (ObservationRecord record : page.records()) {
            if (record instanceof ObservationRecord.VisibleEntity entity
                    && entity.frameDisplay() != null) {
                FrameAuthorization previous = frameDisplays.get(entity.entityRef());
                if (previous == null || previous.entity().observedTick() <= entity.observedTick()) {
                    frameDisplays.remove(entity.entityRef());
                    frameDisplays.put(entity.entityRef(), new FrameAuthorization(entity, now));
                    while (frameDisplays.size() > MAX_RETAINED_FRAME_DISPLAYS) {
                        frameDisplays.remove(frameDisplays.keySet().iterator().next());
                    }
                }
            }
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
                Entry delivered = surfaces.get(key);
                if (delivered != null) {
                    current.add(key);
                    records.add(dynamicView(delivered.surface(), surface));
                }
            } else if (record instanceof ObservationRecord.VisibleEntity entity) {
                records.add(authorizeFrameDisplay(entity, latest.frameCompletedTick()));
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

    /** Internal planner view only: refresh delivered keys, never undisclosed surfaces/entities. */
    public synchronized Optional<ObservationFrame> reobserveForPlanning(
            Optional<ObservationFrame> latestFrame,
            Function<ObservationRecord.VisibleSurface, Optional<ObservationRecord.VisibleSurface>> reobserve) {
        return augment(latestFrame).map(frame -> {
            var records = new ArrayList<ObservationRecord>();
            long completedTick = frame.frameCompletedTick();
            for (ObservationRecord record : frame.records()) {
                if (record instanceof ObservationRecord.VisibleSurface surface) {
                    var refreshed = reobserve.apply(surface).filter(current ->
                            SurfaceKey.of(current).equals(SurfaceKey.of(surface))
                                    && Objects.equals(current.state(), surface.state())
                                    && Objects.equals(current.placementItem(), surface.placementItem())
                                    && current.shapeClass() == surface.shapeClass()
                                    && current.observedTick() >= surface.observedTick()
                                    && current.worldRevision() >= surface.worldRevision());
                    if (refreshed.isPresent()) {
                        var current = refreshed.orElseThrow();
                        records.add(current);
                        completedTick = Math.max(completedTick, current.observedTick());
                    } else {
                        // Static facing may still use delivered coordinates. Keep the OLD
                        // revision so mutation/approach admission cannot mistake failure for freshness.
                        records.add(surface);
                    }
                } else {
                    records.add(record);
                }
            }
            // Composite completion advanced; age existing sounds without pretending they
            // were heard again, and discard those now outside the ordinary 600-tick TTL.
            var iterator = records.listIterator();
            while (iterator.hasNext()) {
                ObservationRecord record = iterator.next();
                if (record instanceof ObservationRecord.VisibleEntity entity) {
                    // Static refresh may advance the composite clock. It never renews the
                    // delivery authorization or timestamps of a dynamic frame display.
                    iterator.set(authorizeFrameDisplay(entity, completedTick));
                } else if (record instanceof ObservationRecord.SoundClue sound) {
                    long age = completedTick - sound.lastObservedTick();
                    if (age > 600) {
                        iterator.remove();
                    } else {
                        iterator.set(new ObservationRecord.SoundClue(sound.soundEvent(),
                                sound.category(), sound.position(), sound.firstObservedTick(),
                                sound.lastObservedTick(), (int) age, sound.occurrences(),
                                sound.entityHint(), sound.worldRevision()));
                    }
                }
            }
            return new ObservationFrame(frame.frameId(), frame.dimension(), completedTick,
                    frame.configuredVisualRadiusBlocks(), frame.visibleEntitiesTruncated(),
                    frame.recentSoundCluesTruncated(), records);
        });
    }

    private ObservationRecord.VisibleEntity authorizeFrameDisplay(
            ObservationRecord.VisibleEntity current, long completedTick) {
        if (current.frameDisplay() == null) return current;
        FrameAuthorization authorization = frameDisplays.get(current.entityRef());
        if (authorization != null) {
            var delivered = authorization.entity();
            long age = completedTick - delivered.observedTick();
            if (age >= 0 && age <= FRAME_DISPLAY_DELIVERY_MAX_AGE_TICKS
                    && current.observedTick() >= delivered.observedTick()
                    && current.worldRevision() >= delivered.worldRevision()
                    && current.entityRef().equals(delivered.entityRef())
                    && current.entityType().equals(delivered.entityType())
                    && current.position().equals(delivered.position())
                    && current.aabb().equals(delivered.aabb())
                    && current.frameDisplay().equals(delivered.frameDisplay())) {
                return current;
            }
        }
        return new ObservationRecord.VisibleEntity(
                current.entityType(), current.displayedItem(), current.entityRef(),
                current.position(), current.velocity(), current.aabb(), current.hazardClass(),
                current.eyeOrigin(), current.observedTick(), current.worldRevision(),
                current.containerLabel(), null);
    }

    /**
     * Preserves the exact state/item identity which crossed the delivery boundary while adopting
     * only the current ray witness and compact crop-maturity signal. The latter is deliberately a
     * second view: wait_until may follow natural crop growth without turning an undisclosed latest
     * BlockState property change into mutation authority.
     */
    private static ObservationRecord.VisibleSurface dynamicView(
            ObservationRecord.VisibleSurface delivered,
            ObservationRecord.VisibleSurface current) {
        return new ObservationRecord.VisibleSurface(
                delivered.position(),
                delivered.face(),
                delivered.block(),
                delivered.state(),
                delivered.placementItem(),
                delivered.shapeClass(),
                current.cropMature(),
                current.rayHit(),
                current.eyeOrigin(),
                current.observedTick(),
                current.worldRevision());
    }

    /** Clears the complete evidence boundary at a world-session transition. */
    public synchronized void clear() {
        surfaces.clear();
        frameDisplays.clear();
        pending.clear();
        placementRefs.clear();
        placementStates.clear();
    }

    synchronized int retainedSurfaceCount() {
        purgeExpired(nanoTime.getAsLong());
        return surfaces.size();
    }

    synchronized int pendingDeliveryCount() {
        purgeExpired(nanoTime.getAsLong());
        return pending.size();
    }

    synchronized int retainedPlacementStateCount() {
        return placementStates.size();
    }

    private String existingOrNewPlacementRef(PlacementStateKey key) {
        String active = placementRefs.get(key);
        if (active != null) {
            return active;
        }
        for (PendingDelivery delivery : pending.values()) {
            String staged = delivery.placementRefs().get(key);
            if (staged != null) {
                return staged;
            }
        }
        return "psr_" + Objects.requireNonNull(receiptIds.get(), "placementStateRef")
                .toString().replace("-", "");
    }

    private void activatePlacementState(PlacementStateKey key, String ref) {
        String active = placementRefs.get(key);
        if (active != null) {
            return;
        }
        placementRefs.put(key, ref);
        placementStates.put(ref, key.value());
        while (placementStates.size() > MAX_RETAINED_PLACEMENT_STATES) {
            Iterator<Map.Entry<String, PlacementStateResolver.PlacementState>> oldest =
                    placementStates.entrySet().iterator();
            Map.Entry<String, PlacementStateResolver.PlacementState> removed = oldest.next();
            oldest.remove();
            placementRefs.entrySet().removeIf(entry -> entry.getValue().equals(removed.getKey()));
        }
    }

    private void purgeExpired(long now) {
        frameDisplays.values().removeIf(entry ->
                elapsed(now, entry.deliveredAtNanos()) >= SURFACE_IDLE_TIMEOUT.toNanos());
        long timeout = SURFACE_IDLE_TIMEOUT.toNanos();
        surfaces.entrySet().removeIf(entry -> elapsed(now, entry.getValue().deliveredNanos())
                >= timeout);
        long pendingTimeout = PENDING_DELIVERY_TIMEOUT.toNanos();
        pending.entrySet().removeIf(entry -> elapsed(now, entry.getValue().preparedNanos())
                >= pendingTimeout);
    }

    private record FrameAuthorization(ObservationRecord.VisibleEntity entity, long deliveredAtNanos) { }

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

    private record PendingDelivery(
            ObservationPage page,
            long preparedNanos,
            Map<PlacementStateKey, String> placementRefs) {
        private PendingDelivery {
            Objects.requireNonNull(page, "page");
            placementRefs = Map.copyOf(Objects.requireNonNull(placementRefs, "placementRefs"));
        }
    }

    private record PlacementStateKey(
            ObservationRecord.BlockStateView state,
            ObservationValues.ResourceId placementItem) {
        private PlacementStateKey {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(placementItem, "placementItem");
        }

        private static PlacementStateKey of(ObservationRecord.VisibleSurface surface) {
            return surface.state() == null || surface.placementItem() == null
                    ? null
                    : new PlacementStateKey(surface.state(), surface.placementItem());
        }

        private PlacementStateResolver.PlacementState value() {
            return new PlacementStateResolver.PlacementState(state, placementItem);
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
