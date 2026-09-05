package dev.aod.mcmcp.agent.observation;

import dev.aod.mcmcp.agent.observation.ObservationRecord.FrameDisplay;
import dev.aod.mcmcp.agent.observation.ObservationRecord.VisibleEntity;
import dev.aod.mcmcp.agent.observation.ObservationValues.Aabb;
import dev.aod.mcmcp.agent.observation.ObservationValues.ResourceId;
import dev.aod.mcmcp.agent.observation.ObservationValues.WorldPosition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveredFrameDisplayTest {
    private static final ResourceId DIMENSION = new ResourceId("minecraft:overworld");
    private static final String REF = "abcdefghijklmnopqrstuvwx";
    private static final Aabb BOX = new Aabb(1.5, 63.5, 1.96875, 2.5, 64.5, 2.03125);
    private static final FrameDisplay DISPLAY = new FrameDisplay(new ResourceId("minecraft:stone"), 3, point(2, 64, 2.03125));

    @Test
    void onlyConfirmedDeliveryAuthorizesTheExactFreshDisplay() {
        var store = new DeliveredPolicyEvidenceStore();
        var visible = frame(REF, "minecraft:item_frame", point(2, 64, 2), BOX, DISPLAY, 10);
        var latest = observation(10, List.of(visible));
        var receipt = store.prepareDelivery(page(10, visible));
        assertThat(onlyFrame(store.augment(Optional.of(latest))).frameDisplay()).isNull();
        assertThat(latest.records()).containsExactly(visible);
        assertThat(store.confirmDelivery(receipt)).isTrue();
        assertThat(onlyFrame(store.augment(Optional.of(latest)))).isEqualTo(visible);
        var second = frame("abcdefghijklmnopqrstuvwy", "minecraft:item_frame", point(2, 64, 2), BOX, DISPLAY, 10);
        var abandoned = store.prepareDelivery(page(10, second));
        store.abandonDelivery(abandoned);
        assertThat(store.confirmDelivery(abandoned)).isFalse();
        assertThat(onlyFrame(store.augment(Optional.of(observation(10, List.of(second))))).frameDisplay()).isNull();
    }

    @Test
    void changedRefTypePositionBoundsItemRotationOrAimNeedsAnotherDelivery() {
        var store = new DeliveredPolicyEvidenceStore();
        var original = frame(REF, "minecraft:item_frame", point(2, 64, 2), BOX, DISPLAY, 10);
        store.recordDelivered(page(10, original));
        var changed = List.of(
                frame("abcdefghijklmnopqrstuvwy", "minecraft:item_frame", original.position(), BOX, DISPLAY, 11),
                frame(REF, "minecraft:glow_item_frame", original.position(), BOX, DISPLAY, 11),
                frame(REF, "minecraft:item_frame", point(3, 64, 2), BOX, DISPLAY, 11),
                frame(REF, "minecraft:item_frame", original.position(), new Aabb(1, 63, 1, 3, 65, 3), DISPLAY, 11),
                frame(REF, "minecraft:item_frame", original.position(), BOX, new FrameDisplay(null, 3, DISPLAY.aimPoint()), 11),
                frame(REF, "minecraft:item_frame", original.position(), BOX, new FrameDisplay(DISPLAY.item(), 4, DISPLAY.aimPoint()), 11),
                frame(REF, "minecraft:item_frame", original.position(), BOX, new FrameDisplay(DISPLAY.item(), 3, point(2.1, 64, 2.03125)), 11));
        for (var candidate : changed) {
            var result = onlyFrame(store.augment(Optional.of(observation(11, List.of(candidate)))));
            assertThat(result.frameDisplay()).isNull();
            assertThat(result.observedTick()).isEqualTo(candidate.observedTick());
            assertThat(result.worldRevision()).isEqualTo(candidate.worldRevision());
            assertThat(result.position()).isEqualTo(candidate.position());
            assertThat(result.aabb()).isEqualTo(candidate.aabb());
            assertThat(result.containerLabel()).isEqualTo(candidate.containerLabel());
        }
        var updated = changed.getLast();
        store.recordDelivered(page(11, updated));
        assertThat(onlyFrame(store.augment(Optional.of(observation(11, List.of(updated)))))).isEqualTo(updated);
    }

    @Test
    void deliveryAgeIsBoundedWithoutRenewingEntityTicksAndStaticRefreshCannotExtendIt() {
        var store = new DeliveredPolicyEvidenceStore();
        var delivered = frame(REF, "minecraft:item_frame", point(2, 64, 2), BOX, DISPLAY, 10);
        store.recordDelivered(page(10, delivered));
        var atBoundary = frame(REF, "minecraft:item_frame", point(2, 64, 2), BOX, DISPLAY, 110);
        assertThat(onlyFrame(store.augment(Optional.of(observation(110, List.of(atBoundary)))))).isEqualTo(atBoundary);
        var expired = frame(REF, "minecraft:item_frame", point(2, 64, 2), BOX, DISPLAY, 111);
        assertThat(onlyFrame(store.augment(Optional.of(observation(111, List.of(expired))))).frameDisplay()).isNull();

        var surface = new ObservationRecord.VisibleSurface(
                new ObservationValues.BlockPosition(DIMENSION, 0, 64, 0), ObservationRecord.Face.UP,
                new ResourceId("minecraft:stone"), ObservationRecord.ShapeClass.OPAQUE,
                null, point(0.5, 65, 0.5), point(0, 65.62, 0), 10, 10);
        store.recordDelivered(new ObservationPage("obs-0000000000000001", 10, List.of(surface), null));
        var raw = observation(110, List.of(atBoundary));
        var composite = store.reobserveForPlanning(Optional.of(raw), old -> Optional.of(
                new ObservationRecord.VisibleSurface(old.position(), old.face(), old.block(), old.shapeClass(),
                        old.cropMature(), old.rayHit(), old.eyeOrigin(), 111, 111))).orElseThrow();
        assertThat(onlyFrame(Optional.of(composite)).frameDisplay()).isNull();
        assertThat(onlyFrame(Optional.of(composite)).observedTick()).isEqualTo(110);
        assertThat(raw.frameCompletedTick()).isEqualTo(110);
        assertThat(raw.records()).containsExactly(atBoundary);
    }

    @Test
    void frameRefreshRejectsChangedIdentityAndCannotRenewWallTimeOrUndeliveredEvidence() {
        var clock = new AtomicLong();
        var store = new DeliveredPolicyEvidenceStore(clock::get);
        var original = frame(REF, "minecraft:item_frame", point(2, 64, 2), BOX, DISPLAY, 10);
        var raw = Optional.of(observation(10, List.of(original)));
        assertThat(store.frameDisplayRejection(REF, raw, 11))
                .contains(DeliveredPolicyEvidenceStore.FrameDisplayRejection.NOT_DELIVERED);
        store.reobserveForPlanning(raw, surface -> Optional.empty(), 11,
                entity -> { throw new AssertionError("undelivered frame must not be reobserved"); });
        store.recordDelivered(page(10, original));
        var changed = List.of(
                frame(REF, "minecraft:item_frame", point(3, 64, 2), BOX, DISPLAY, 11),
                frame(REF, "minecraft:item_frame", original.position(), new Aabb(1, 63, 1, 3, 65, 3), DISPLAY, 11),
                frame(REF, "minecraft:item_frame", original.position(), BOX, new FrameDisplay(null, 3, DISPLAY.aimPoint()), 11),
                frame(REF, "minecraft:item_frame", original.position(), BOX, new FrameDisplay(DISPLAY.item(), 4, DISPLAY.aimPoint()), 11),
                frame(REF, "minecraft:item_frame", original.position(), BOX, new FrameDisplay(DISPLAY.item(), 3, point(2.1, 64, 2.03125)), 11));
        for (var candidate : changed) {
            var rejected = store.reobserveForPlanning(raw, surface -> Optional.empty(), 11,
                    entity -> Optional.of(candidate));
            assertThat(onlyFrame(rejected)).isEqualTo(original);
            assertThat(store.frameDisplayRejection(REF, Optional.of(observation(11, List.of(candidate))), 11))
                    .contains(DeliveredPolicyEvidenceStore.FrameDisplayRejection.DISPLAY_CHANGED);
        }
        assertThat(store.frameDisplayRejection(REF, Optional.of(observation(11, List.of())), 11))
                .contains(DeliveredPolicyEvidenceStore.FrameDisplayRejection.NOT_VISIBLE);
        clock.set(DeliveredPolicyEvidenceStore.SURFACE_IDLE_TIMEOUT.toNanos() - 1);
        var fresh = frame(REF, "minecraft:item_frame", original.position(), BOX, DISPLAY, 11);
        var planning = store.reobserveForPlanning(raw, surface -> Optional.empty(), 11,
                entity -> Optional.of(fresh));
        assertThat(onlyFrame(planning)).isEqualTo(fresh);
        clock.incrementAndGet();
        assertThat(store.frameDisplayRejection(REF, planning, 12))
                .contains(DeliveredPolicyEvidenceStore.FrameDisplayRejection.DELIVERY_EXPIRED);
        var expired = store.reobserveForPlanning(planning, surface -> Optional.empty(), 12,
                entity -> { throw new AssertionError("wall-expired delivery must not be reobserved"); });
        assertThat(onlyFrame(expired).frameDisplay()).isNull();
    }

    @Test
    void missingFreshEntityClearExpiryAndCapacityNeverResurrectADeliveredDisplay() {
        var clock = new AtomicLong();
        var store = new DeliveredPolicyEvidenceStore(clock::get);
        var entity = frame(REF, "minecraft:item_frame", point(2, 64, 2), BOX, DISPLAY, 10);
        store.recordDelivered(page(10, entity));
        assertThat(store.augment(Optional.of(observation(11, List.of()))).orElseThrow().records()).isEmpty();
        assertThat(store.augment(Optional.empty())).isEmpty();
        clock.set(DeliveredPolicyEvidenceStore.SURFACE_IDLE_TIMEOUT.toNanos());
        assertThat(onlyFrame(store.augment(Optional.of(observation(10, List.of(entity))))).frameDisplay()).isNull();
        store.recordDelivered(page(10, entity));
        store.clear();
        assertThat(onlyFrame(store.augment(Optional.of(observation(10, List.of(entity))))).frameDisplay()).isNull();
        store.recordDelivered(page(10, entity));
        for (int i = 0; i < DeliveredPolicyEvidenceStore.MAX_RETAINED_FRAME_DISPLAYS; i++) {
            store.recordDelivered(page(10, frame(String.format("%024d", i), "minecraft:item_frame",
                    point(2, 64, 2), BOX, DISPLAY, 10)));
        }
        assertThat(onlyFrame(store.augment(Optional.of(observation(10, List.of(entity))))).frameDisplay()).isNull();
    }

    private static VisibleEntity frame(String ref, String type, WorldPosition position,
                                       Aabb box, FrameDisplay display, long tick) {
        return new VisibleEntity(new ResourceId(type), null, ref, position,
                new ObservationValues.Vector(0, 0, 0), box, ObservationRecord.EntityHazardClass.UNKNOWN,
                point(0, 65.62, 0), tick, tick, null, display);
    }

    private static WorldPosition point(double x, double y, double z) { return new WorldPosition(DIMENSION, x, y, z); }
    private static ObservationPage page(long tick, VisibleEntity entity) {
        return new ObservationPage("obs-0000000000000001", tick, List.of(entity), null);
    }
    private static ObservationFrame observation(long tick, List<ObservationRecord> records) {
        return new ObservationFrame("obs-0000000000000001", DIMENSION, tick, 16, false, records);
    }
    private static VisibleEntity onlyFrame(Optional<ObservationFrame> frame) {
        return frame.orElseThrow().records().stream().filter(VisibleEntity.class::isInstance)
                .map(VisibleEntity.class::cast).findFirst().orElseThrow();
    }
}
