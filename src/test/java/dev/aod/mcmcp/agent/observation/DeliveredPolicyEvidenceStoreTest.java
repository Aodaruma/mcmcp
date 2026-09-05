package dev.aod.mcmcp.agent.observation;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveredPolicyEvidenceStoreTest {
    @Test
    void reobservesOnlyDeliveredSurfacesWithoutExtendingDeliveryLifetimeOrDynamicEvidence() {
        var clock = new AtomicLong();
        var store = new DeliveredPolicyEvidenceStore(clock::get);
        var chest = surface(1, 64, 1, "minecraft:chest", 10, 3);
        var unseen = surface(2, 64, 2, "minecraft:chest", 10, 3);
        var dynamic = entity(4, 64, 4, 10, 3);
        store.recordDelivered(new ObservationPage("obs-0000000000000001", 10, List.of(chest), null));
        var latest = Optional.of(frame("obs-0000000000000002", 10, List.of(chest, unseen, dynamic)));
        var refreshed = surface(1, 64, 1, "minecraft:chest", 20, 100);
        var result = store.reobserveForPlanning(latest, known -> {
            assertThat(known).isEqualTo(chest);
            return Optional.of(refreshed);
        }).orElseThrow();
        assertThat(result.records()).containsExactly(refreshed, dynamic);
        assertThat(result.frameCompletedTick()).isEqualTo(20);
        assertThat(latest.orElseThrow().frameCompletedTick()).isEqualTo(10);
        // Failed visibility retains only the old static-facing witness, never a fresh revision.
        assertThat(store.reobserveForPlanning(latest, known -> Optional.empty()).orElseThrow().records())
                .containsExactly(chest, dynamic);
        assertThat(store.reobserveForPlanning(latest, known -> Optional.of(unseen)).orElseThrow().records())
                .containsExactly(chest, dynamic);
        clock.set(DeliveredPolicyEvidenceStore.SURFACE_IDLE_TIMEOUT.toNanos());
        assertThat(store.reobserveForPlanning(latest, known -> {
            throw new AssertionError("expired delivery must not trigger a ray");
        }).orElseThrow().records()).containsExactly(dynamic);
    }

    @Test
    void refreshedCompositeAgesExistingSoundsAndExpiresOldOnesWithoutRenewingPlayback() {
        var store = new DeliveredPolicyEvidenceStore();
        var chest = surface(1, 64, 1, "minecraft:chest", 601, 3);
        var sound = new ObservationRecord.SoundClue(
                new ObservationValues.ResourceId("minecraft:entity.zombie.ambient"),
                ObservationRecord.SoundCategory.HOSTILE, chest.eyeOrigin(),
                600, 600, 1, 1, null, 3);
        var expired = new ObservationRecord.SoundClue(sound.soundEvent(), sound.category(),
                sound.position(), 1, 1, 600, 1, null, 3);
        store.recordDelivered(new ObservationPage("obs-0000000000000001", 601, List.of(chest), null));
        var latest = frame("obs-0000000000000002", 601, List.of(chest, sound, expired));
        var result = store.reobserveForPlanning(Optional.of(latest), known ->
                Optional.of(surface(1, 64, 1, "minecraft:chest", 621, 100))).orElseThrow();
        assertThat(result.records()).hasSize(2);
        var aged = (ObservationRecord.SoundClue) result.records().get(1);
        assertThat(aged.ageTicks()).isEqualTo(21);
        assertThat(aged.lastObservedTick()).isEqualTo(600);
        assertThat(aged.worldRevision()).isEqualTo(3);
        assertThat(latest.records()).containsExactly(chest, sound, expired);
    }

    private static final ObservationValues.ResourceId DIMENSION =
            new ObservationValues.ResourceId("minecraft:overworld");

    @Test
    void stagesEvidenceUntilDeliveryConfirmationAndDropsAbandonedPages() {
        var clock = new AtomicLong();
        var ids = new AtomicLong();
        var store = new DeliveredPolicyEvidenceStore(
                clock::get, () -> new UUID(0L, ids.incrementAndGet()));
        var first = surface(1, 64, 1, "minecraft:dirt", 10, 3);
        UUID confirmed = store.prepareDelivery(new ObservationPage(
                "obs-0000000000000001", 10, List.of(first), null));

        assertThat(store.pendingDeliveryCount()).isOne();
        assertThat(store.retainedSurfaceCount()).isZero();
        assertThat(store.confirmDelivery(confirmed)).isTrue();
        assertThat(store.pendingDeliveryCount()).isZero();
        assertThat(store.retainedSurfaceCount()).isOne();

        UUID abandoned = store.prepareDelivery(new ObservationPage(
                "obs-0000000000000002", 11,
                List.of(surface(2, 64, 2, "minecraft:stone", 11, 3)), null));
        assertThat(store.abandonDelivery(abandoned)).isTrue();
        assertThat(store.confirmDelivery(abandoned)).isFalse();
        assertThat(store.retainedSurfaceCount()).isOne();
    }

    @Test
    void boundsAndExpiresUnconfirmedDeliveries() {
        var clock = new AtomicLong();
        var ids = new AtomicLong();
        var store = new DeliveredPolicyEvidenceStore(
                clock::get, () -> new UUID(0L, ids.incrementAndGet()));
        UUID oldest = null;
        for (int index = 0; index <= DeliveredPolicyEvidenceStore.MAX_PENDING_DELIVERIES;
                index++) {
            UUID receipt = store.prepareDelivery(new ObservationPage(
                    "obs-0000000000000001", 10, List.of(), null));
            if (index == 0) oldest = receipt;
        }
        assertThat(store.pendingDeliveryCount())
                .isEqualTo(DeliveredPolicyEvidenceStore.MAX_PENDING_DELIVERIES);
        assertThat(store.confirmDelivery(oldest)).isFalse();

        clock.set(DeliveredPolicyEvidenceStore.PENDING_DELIVERY_TIMEOUT.toNanos());
        assertThat(store.pendingDeliveryCount()).isZero();
    }

    @Test
    void augmentsOnlyWithActuallyDeliveredStaticSurfaces() {
        var clock = new AtomicLong();
        var store = new DeliveredPolicyEvidenceStore(clock::get);
        var delivered = surface(1, 64, 1, "minecraft:dirt", 10, 3);
        var dynamic = entity(2, 64, 2, 10, 3);
        store.recordDelivered(new ObservationPage(
                "obs-0000000000000001", 10, List.of(delivered, dynamic), null));

        ObservationFrame augmented = store.augment(Optional.of(frame(
                "obs-0000000000000002", 11,
                List.of(surface(3, 64, 3, "minecraft:stone", 11, 3),
                        entity(4, 64, 4, 11, 3))))).orElseThrow();

        assertThat(augmented.records()).contains(delivered);
        assertThat(augmented.records()).noneMatch(record ->
                record instanceof ObservationRecord.VisibleSurface surface
                        && surface.position().x() == 3);
        assertThat(augmented.records()).filteredOn(ObservationRecord.VisibleEntity.class::isInstance)
                .containsExactly(entity(4, 64, 4, 11, 3));
    }

    @Test
    void currentSurfaceWinsAnExactDeliveredKey() {
        var clock = new AtomicLong();
        var store = new DeliveredPolicyEvidenceStore(clock::get);
        var prior = surface(1, 64, 1, "minecraft:wheat", false, 10, 3);
        store.recordDelivered(new ObservationPage(
                "obs-0000000000000001", 10, List.of(prior), null));
        var current = surface(1, 64, 1, "minecraft:wheat", true, 20, 4);

        ObservationFrame augmented = store.augment(Optional.of(frame(
                "obs-0000000000000002", 20, List.of(current)))).orElseThrow();

        assertThat(augmented.records()).containsExactly(current);
    }

    @Test
    void undisclosedCurrentPropertiesDoNotReplaceDeliveredStateButCropSignalStaysLive() {
        var clock = new AtomicLong();
        var store = new DeliveredPolicyEvidenceStore(clock::get);
        var delivered = surfaceWithState(
                "minecraft:wheat", Map.of("age", "0"), false, 10, 3);
        store.recordDelivered(new ObservationPage(
                "obs-0000000000000001", 10, List.of(delivered), null));
        var undisclosedCurrent = surfaceWithState(
                "minecraft:wheat", Map.of("age", "7"), true, 20, 4);

        ObservationRecord.VisibleSurface composite = (ObservationRecord.VisibleSurface)
                store.augment(Optional.of(frame(
                        "obs-0000000000000002", 20, List.of(undisclosedCurrent))))
                        .orElseThrow().records().getFirst();

        assertThat(composite.state()).isEqualTo(delivered.state());
        assertThat(composite.cropMature()).isTrue();
        assertThat(composite.observedTick()).isEqualTo(20);
        assertThat(composite.worldRevision()).isEqualTo(4);

        store.recordDelivered(new ObservationPage(
                "obs-0000000000000002", 20, List.of(undisclosedCurrent), null));
        ObservationFrame afterDelivery = store.augment(Optional.of(frame(
                "obs-0000000000000003", 21, List.of(undisclosedCurrent))))
                .orElseThrow();
        assertThat(((ObservationRecord.VisibleSurface) afterDelivery.records().getFirst()).state())
                .isEqualTo(undisclosedCurrent.state());
    }

    @Test
    void excludesAnUndeliveredFaceOfADeliveredBlock() {
        var store = new DeliveredPolicyEvidenceStore();
        var delivered = surface(1, 64, 1, "minecraft:dirt", 10, 3);
        store.recordDelivered(new ObservationPage(
                "obs-0000000000000001", 10, List.of(delivered), null));
        var otherFace = new ObservationRecord.VisibleSurface(
                delivered.position(), ObservationRecord.Face.NORTH, delivered.block(),
                delivered.shapeClass(), delivered.cropMature(), delivered.rayHit(),
                delivered.eyeOrigin(), 11, 3);

        ObservationFrame augmented = store.augment(Optional.of(frame(
                "obs-0000000000000002", 11, List.of(otherFace)))).orElseThrow();

        assertThat(augmented.records()).containsExactly(delivered);
    }

    @Test
    void removesAllUndeliveredSurfacesWhenNothingWasReturned() {
        var store = new DeliveredPolicyEvidenceStore();

        ObservationFrame augmented = store.augment(Optional.of(frame(
                "obs-0000000000000002", 11,
                List.of(surface(3, 64, 3, "minecraft:stone", 11, 3),
                        entity(4, 64, 4, 11, 3))))).orElseThrow();

        assertThat(augmented.records())
                .noneMatch(ObservationRecord.VisibleSurface.class::isInstance)
                .anyMatch(ObservationRecord.VisibleEntity.class::isInstance);
    }

    @Test
    void expiresAtSixtySecondsAndClearsAtSessionBoundary() {
        var clock = new AtomicLong();
        var store = new DeliveredPolicyEvidenceStore(clock::get);
        store.recordDelivered(new ObservationPage(
                "obs-0000000000000001", 10,
                List.of(surface(1, 64, 1, "minecraft:dirt", 10, 3)), null));

        clock.set(Duration.ofSeconds(59).toNanos());
        assertThat(store.retainedSurfaceCount()).isOne();
        clock.set(Duration.ofSeconds(60).toNanos());
        assertThat(store.retainedSurfaceCount()).isZero();

        store.recordDelivered(new ObservationPage(
                "obs-0000000000000002", 20,
                List.of(surface(2, 64, 2, "minecraft:dirt", 20, 3)), null));
        store.clear();
        assertThat(store.retainedSurfaceCount()).isZero();
    }

    @Test
    void deterministicOldestDeliveryEvictionKeepsMemoryBounded() {
        var clock = new AtomicLong();
        var store = new DeliveredPolicyEvidenceStore(clock::get);
        for (int index = 0; index <= DeliveredPolicyEvidenceStore.MAX_RETAINED_SURFACES;
                index++) {
            clock.incrementAndGet();
            store.recordDelivered(new ObservationPage(
                    "obs-0000000000000001", 10,
                    List.of(surface(index, 64, 0, "minecraft:dirt", 10, 3)), null));
        }

        assertThat(store.retainedSurfaceCount())
                .isEqualTo(DeliveredPolicyEvidenceStore.MAX_RETAINED_SURFACES);
        ObservationFrame augmented = store.augment(Optional.of(frame(
                "obs-0000000000000002", 20, List.of()))).orElseThrow();
        assertThat(augmented.records()).noneMatch(record ->
                record instanceof ObservationRecord.VisibleSurface surface
                        && surface.position().x() == 0);
        assertThat(augmented.records()).anyMatch(record ->
                record instanceof ObservationRecord.VisibleSurface surface
                        && surface.position().x()
                                == DeliveredPolicyEvidenceStore.MAX_RETAINED_SURFACES);
    }

    @Test
    void placementStateRefActivatesOnlyAfterDeliveryAndOutlivesSurfaceTtl() {
        var clock = new AtomicLong();
        var ids = new AtomicLong();
        var store = new DeliveredPolicyEvidenceStore(
                clock::get, () -> new UUID(0L, ids.incrementAndGet()));
        var source = copyableSurface(1, "minecraft:oak_stairs", Map.of(
                "facing", "north", "half", "bottom", "shape", "straight",
                "waterlogged", "false"));
        UUID receipt = store.prepareDelivery(new ObservationPage(
                "obs-0000000000000001", 10, List.of(source), null));
        String ref = store.preparedPlacementStateRef(receipt, source).orElseThrow();

        assertThat(ref).matches("psr_[0-9a-f]{32}");
        assertThat(store.resolvePlacementState(ref)).isEmpty();
        assertThat(store.confirmDelivery(receipt)).isTrue();
        assertThat(store.resolvePlacementState(ref)).hasValueSatisfying(remembered -> {
            assertThat(remembered.state()).isEqualTo(source.state());
            assertThat(remembered.placementItem()).isEqualTo(source.placementItem());
        });

        clock.set(DeliveredPolicyEvidenceStore.SURFACE_IDLE_TIMEOUT.toNanos());
        assertThat(store.retainedSurfaceCount()).isZero();
        assertThat(store.resolvePlacementState(ref)).isPresent();

        store.clear();
        assertThat(store.resolvePlacementState(ref)).isEmpty();
        assertThat(store.retainedPlacementStateCount()).isZero();
    }

    @Test
    void abandonedDeliveryNeverActivatesItsPlacementStateRef() {
        var ids = new AtomicLong();
        var store = new DeliveredPolicyEvidenceStore(
                System::nanoTime, () -> new UUID(0L, ids.incrementAndGet()));
        var source = copyableSurface(1, "minecraft:oak_planks", Map.of());
        UUID receipt = store.prepareDelivery(new ObservationPage(
                "obs-0000000000000001", 10, List.of(source), null));
        String ref = store.preparedPlacementStateRef(receipt, source).orElseThrow();

        assertThat(store.abandonDelivery(receipt)).isTrue();
        assertThat(store.resolvePlacementState(ref)).isEmpty();
    }

    @Test
    void placementStateMemoryHasADeterministicCapacityBound() {
        var ids = new AtomicLong();
        var store = new DeliveredPolicyEvidenceStore(
                System::nanoTime, () -> new UUID(0L, ids.incrementAndGet()));
        String oldestRef = null;
        for (int index = 0;
                index <= DeliveredPolicyEvidenceStore.MAX_RETAINED_PLACEMENT_STATES;
                index++) {
            var source = copyableSurface(
                    index, "minecraft:oak_planks", Map.of("test_variant", Integer.toString(index)));
            UUID receipt = store.prepareDelivery(new ObservationPage(
                    "obs-0000000000000001", 10, List.of(source), null));
            String ref = store.preparedPlacementStateRef(receipt, source).orElseThrow();
            if (index == 0) oldestRef = ref;
            assertThat(store.confirmDelivery(receipt)).isTrue();
        }

        assertThat(store.retainedPlacementStateCount())
                .isEqualTo(DeliveredPolicyEvidenceStore.MAX_RETAINED_PLACEMENT_STATES);
        assertThat(store.resolvePlacementState(oldestRef)).isEmpty();
    }

    private static ObservationFrame frame(
            String id, long completedTick, List<ObservationRecord> records) {
        return new ObservationFrame(id, DIMENSION, completedTick, 16.0, false, records);
    }

    private static ObservationRecord.VisibleSurface surface(
            int x, int y, int z, String block, long tick, long revision) {
        return surface(x, y, z, block, null, tick, revision);
    }

    private static ObservationRecord.VisibleSurface surface(
            int x, int y, int z, String block, Boolean mature, long tick, long revision) {
        var position = new ObservationValues.BlockPosition(DIMENSION, x, y, z);
        var eye = new ObservationValues.WorldPosition(DIMENSION, 0.5, 65.62, 0.5);
        var hit = new ObservationValues.WorldPosition(
                DIMENSION, x + 0.5, y + 1.0, z + 0.5);
        return new ObservationRecord.VisibleSurface(
                position,
                ObservationRecord.Face.UP,
                new ObservationValues.ResourceId(block),
                ObservationRecord.ShapeClass.OPAQUE,
                mature,
                hit,
                eye,
                tick,
                revision);
    }

    private static ObservationRecord.VisibleEntity entity(
            double x, double y, double z, long tick, long revision) {
        var position = new ObservationValues.WorldPosition(DIMENSION, x, y, z);
        return new ObservationRecord.VisibleEntity(
                new ObservationValues.ResourceId("minecraft:zombie"),
                position,
                new ObservationValues.Vector(0, 0, 0),
                new ObservationValues.Aabb(x, y, z, x + 0.6, y + 1.8, z + 0.6),
                ObservationRecord.EntityHazardClass.HOSTILE,
                new ObservationValues.WorldPosition(DIMENSION, 0.5, 65.62, 0.5),
                tick,
                revision);
    }

    private static ObservationRecord.VisibleSurface surfaceWithState(
            String block, Map<String, String> properties, Boolean mature,
            long tick, long revision) {
        var position = new ObservationValues.BlockPosition(DIMENSION, 1, 64, 1);
        var eye = new ObservationValues.WorldPosition(DIMENSION, 0.5, 65.62, 0.5);
        var hit = new ObservationValues.WorldPosition(DIMENSION, 1.5, 65.0, 1.5);
        var blockId = new ObservationValues.ResourceId(block);
        return new ObservationRecord.VisibleSurface(
                position,
                ObservationRecord.Face.UP,
                blockId,
                new ObservationRecord.BlockStateView(blockId, properties),
                null,
                ObservationRecord.ShapeClass.CUTOUT,
                mature,
                hit,
                eye,
                tick,
                revision);
    }

    private static ObservationRecord.VisibleSurface copyableSurface(
            int x, String block, Map<String, String> properties) {
        var position = new ObservationValues.BlockPosition(DIMENSION, x, 64, 1);
        var eye = new ObservationValues.WorldPosition(DIMENSION, 0.5, 65.62, 0.5);
        var hit = new ObservationValues.WorldPosition(DIMENSION, x + 0.5, 65.0, 1.5);
        var blockId = new ObservationValues.ResourceId(block);
        return new ObservationRecord.VisibleSurface(
                position,
                ObservationRecord.Face.UP,
                blockId,
                new ObservationRecord.BlockStateView(blockId, properties),
                new ObservationValues.ResourceId(block),
                ObservationRecord.ShapeClass.OPAQUE,
                null,
                hit,
                eye,
                10,
                3);
    }
}
