package dev.aod.mcmcp.agent.observation;

import dev.aod.mcmcp.agent.observation.ObservationRecord.VisibleSurface;
import dev.aod.mcmcp.agent.observation.ObservationValues.ResourceId;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservationFrameStoreTest {
    private static final ResourceId DIMENSION = new ResourceId("minecraft:overworld");

    @Test
    void rollingRetentionKeepsOnlyTheLatestTwoUnpinnedFrames() throws Exception {
        var clock = new FakeClock();
        var store = store(clock);
        store.publish(frame(1, 1));
        store.publish(frame(2, 1));

        assertThat(store.page(id(1), Set.of(ObservationKind.VISIBLE_SURFACE), null, 256).records())
                .hasSize(1);
        store.publish(frame(3, 1));

        assertThat(store.latestSummary()).get().extracting(ObservationFrameSummary::latestFrameId)
                .isEqualTo(id(3));
        assertFailure(
                () -> store.page(id(1), Set.of(ObservationKind.VISIBLE_SURFACE), null, 256),
                ObservationStoreException.Code.FRAME_EXPIRED);
        assertThat(store.page(id(2), Set.of(ObservationKind.VISIBLE_SURFACE), null, 256).records())
                .hasSize(1);
    }

    @Test
    void announcedLatestFrameSurvivesRollingEvictionAndIdleAccessRefreshesIt()
            throws Exception {
        var clock = new FakeClock();
        var store = store(clock);
        store.publish(frame(1, 1));

        assertThat(store.announceLatestSummary()).get()
                .extracting(ObservationFrameSummary::latestFrameId)
                .isEqualTo(id(1));
        store.publish(frame(2, 1));
        store.publish(frame(3, 1));
        clock.advance(Duration.ofSeconds(59));

        assertThat(store.page(
                id(1), Set.of(ObservationKind.VISIBLE_SURFACE), null, 256).records())
                .hasSize(1);
        clock.advance(Duration.ofSeconds(59));
        assertThat(store.page(
                id(1), Set.of(ObservationKind.VISIBLE_SURFACE), null, 256).records())
                .hasSize(1);

        clock.advance(ObservationFrameStore.ANNOUNCED_FRAME_IDLE_TIMEOUT);
        assertFailure(
                () -> store.page(id(1), Set.of(ObservationKind.VISIBLE_SURFACE), null, 256),
                ObservationStoreException.Code.FRAME_EXPIRED);
    }

    @Test
    void announcedFrameCapEvictsTheLeastRecentlyUsedHandleDeterministically()
            throws Exception {
        var clock = new FakeClock();
        var store = store(clock);
        for (int number = 1; number <= ObservationFrameStore.ANNOUNCED_FRAME_LIMIT; number++) {
            store.publish(frame(number, 1));
            store.announceLatestSummary().orElseThrow();
        }
        assertThat(store.announcedFrameCount())
                .isEqualTo(ObservationFrameStore.ANNOUNCED_FRAME_LIMIT);

        assertThat(store.page(
                id(1), Set.of(ObservationKind.VISIBLE_SURFACE), null, 256).records())
                .hasSize(1);
        store.publish(frame(ObservationFrameStore.ANNOUNCED_FRAME_LIMIT + 1L, 1));
        store.announceLatestSummary().orElseThrow();

        assertThat(store.announcedFrameCount())
                .isEqualTo(ObservationFrameStore.ANNOUNCED_FRAME_LIMIT);
        assertThat(store.page(
                id(1), Set.of(ObservationKind.VISIBLE_SURFACE), null, 256).records())
                .hasSize(1);
        assertFailure(
                () -> store.page(id(2), Set.of(ObservationKind.VISIBLE_SURFACE), null, 256),
                ObservationStoreException.Code.FRAME_EXPIRED);
    }

    @Test
    void announcedFramesAlsoStayInsideTheGlobalRecordBudget() {
        var store = new ObservationFrameStore();
        ObservationRecord repeated = ObservationModelContractTest.surface(99, 0);
        for (int number = 1; number <= 14; number++) {
            store.publish(new ObservationFrame(
                    id(number), DIMENSION, 100, 16, false,
                    java.util.Collections.nCopies(5_000, repeated)));
            store.announceLatestSummary().orElseThrow();
        }

        assertThat(store.announcedRecordCount())
                .isLessThanOrEqualTo(ObservationFrameStore.ANNOUNCED_RECORD_LIMIT);
        assertThat(store.announcedFrameCount()).isLessThan(14);
    }

    @Test
    void announcedHandlesAreIndependentFromPaginationLeasesAndClearRemovesBoth()
            throws Exception {
        var clock = new FakeClock();
        var store = store(clock);
        store.publish(frame(1, 3));
        store.announceLatestSummary().orElseThrow();
        String cursor = store.page(
                id(1), Set.of(ObservationKind.VISIBLE_SURFACE), null, 1).nextCursor();

        assertThat(store.announcedFrameCount()).isOne();
        assertThat(store.activePaginationLeases()).isOne();

        clock.advance(Duration.ofSeconds(59));
        assertThat(store.page(
                id(1), Set.of(ObservationKind.VISIBLE_SURFACE), cursor, 1).records())
                .hasSize(1);
        clock.advance(Duration.ofSeconds(2));
        assertThat(store.announcedFrameCount()).isZero();
        assertThat(store.activePaginationLeases()).isOne();
        assertThat(store.page(
                id(1), Set.of(ObservationKind.VISIBLE_SURFACE), cursor, 1).records())
                .hasSize(1);

        store.clear();

        assertThat(store.announcedFrameCount()).isZero();
        assertThat(store.activePaginationLeases()).isZero();
        assertFailure(
                () -> store.page(id(1), Set.of(ObservationKind.VISIBLE_SURFACE), cursor, 1),
                ObservationStoreException.Code.INVALID_CURSOR);
        assertFailure(
                () -> store.page(id(1), Set.of(ObservationKind.VISIBLE_SURFACE), null, 1),
                ObservationStoreException.Code.FRAME_EXPIRED);
    }

    @Test
    void pinSurvivesRollingEvictionAndEveryCursorReplayReturnsTheExactSamePage() throws Exception {
        var clock = new FakeClock();
        var store = store(clock);
        store.publish(frame(1, 5));

        ObservationPage first = store.page(
                id(1), Set.of(ObservationKind.VISIBLE_SURFACE), null, 2);
        assertThat(first.records()).extracting(record -> ((VisibleSurface) record).position().x())
                .containsExactly(0, 1);
        assertThat(first.nextCursor()).isNotNull();
        assertThat(Base64.getUrlDecoder().decode(first.nextCursor())).hasSizeGreaterThanOrEqualTo(16);

        store.publish(frame(2, 1));
        store.publish(frame(3, 1));
        ObservationPage second = store.page(
                id(1), Set.of(ObservationKind.VISIBLE_SURFACE), first.nextCursor(), 2);
        ObservationPage replayWithDifferentLimit = store.page(
                id(1), Set.of(ObservationKind.VISIBLE_SURFACE), first.nextCursor(), 1);

        assertThat(replayWithDifferentLimit).isSameAs(second);
        assertThat(second.records()).extracting(record -> ((VisibleSurface) record).position().x())
                .containsExactly(2, 3);
        assertThat(second.nextCursor()).isNotNull();
        ObservationPage last = store.page(
                id(1), Set.of(ObservationKind.VISIBLE_SURFACE), second.nextCursor(), 256);
        assertThat(last.records()).extracting(record -> ((VisibleSurface) record).position().x())
                .containsExactly(4);
        assertThat(last.nextCursor()).isNull();
    }

    @Test
    void cursorIsBoundToFrameKindSetAndOffsetAndUnknownValuesAreInvalidCursor() throws Exception {
        var clock = new FakeClock();
        var store = store(clock);
        store.publish(frame(1, 4));
        store.publish(frame(2, 4));
        var kinds = new LinkedHashSet<>(List.of(
                ObservationKind.VISIBLE_SURFACE,
                ObservationKind.HAZARD));
        ObservationPage first = store.page(id(1), kinds, null, 1);

        var reordered = new LinkedHashSet<>(List.of(
                ObservationKind.HAZARD,
                ObservationKind.VISIBLE_SURFACE));
        assertThat(store.page(id(1), reordered, first.nextCursor(), 1).records()).hasSize(1);
        assertFailure(
                () -> store.page(id(2), reordered, first.nextCursor(), 1),
                ObservationStoreException.Code.INVALID_CURSOR);
        assertFailure(
                () -> store.page(id(1), Set.of(ObservationKind.HAZARD), first.nextCursor(), 1),
                ObservationStoreException.Code.INVALID_CURSOR);
        assertFailure(
                () -> store.page(id(1), kinds, "not-a-real-cursor", 1),
                ObservationStoreException.Code.INVALID_CURSOR);
    }

    @Test
    void thirdPaginationLeaseIsBusyButNonPagedReadsConsumeNoLease() throws Exception {
        var clock = new FakeClock();
        var store = store(clock);
        store.publish(frame(1, 3));

        assertThat(store.page(id(1), Set.of(ObservationKind.VISIBLE_SURFACE), null, 256).nextCursor())
                .isNull();
        assertThat(store.activePaginationLeases()).isZero();
        ObservationPage first = store.page(id(1), Set.of(ObservationKind.VISIBLE_SURFACE), null, 1);
        ObservationPage second = store.page(id(1), Set.of(ObservationKind.VISIBLE_SURFACE), null, 1);
        assertThat(first.nextCursor()).isNotEqualTo(second.nextCursor());
        assertThat(store.activePaginationLeases()).isEqualTo(2);

        assertFailure(
                () -> store.page(id(1), Set.of(ObservationKind.VISIBLE_SURFACE), null, 1),
                ObservationStoreException.Code.SERVER_BUSY);
        clock.advance(ObservationFrameStore.LEASE_IDLE_TIMEOUT);
        assertThat(store.page(id(1), Set.of(ObservationKind.VISIBLE_SURFACE), null, 1).nextCursor())
                .isNotNull();
        assertFailure(
                () -> store.page(id(1), Set.of(ObservationKind.VISIBLE_SURFACE), first.nextCursor(), 1),
                ObservationStoreException.Code.INVALID_CURSOR);
    }

    @Test
    void validAccessRefreshesIdleButAbsoluteFiveMinuteExpiryStillInvalidatesCursor() throws Exception {
        var clock = new FakeClock();
        var store = store(clock);
        store.publish(frame(1, 3));
        String cursor = store.page(
                id(1), Set.of(ObservationKind.VISIBLE_SURFACE), null, 1).nextCursor();

        for (int access = 0; access < 5; access++) {
            clock.advance(Duration.ofSeconds(59));
            assertThat(store.page(id(1), Set.of(ObservationKind.VISIBLE_SURFACE), cursor, 1).records())
                    .hasSize(1);
        }
        clock.advance(Duration.ofSeconds(5));
        assertFailure(
                () -> store.page(id(1), Set.of(ObservationKind.VISIBLE_SURFACE), cursor, 1),
                ObservationStoreException.Code.INVALID_CURSOR);
    }

    @Test
    void clearInvalidatesFramesLeasesAndCursorsAtWorldBoundary() throws Exception {
        var clock = new FakeClock();
        var store = store(clock);
        store.publish(frame(1, 3));
        String cursor = store.page(
                id(1), Set.of(ObservationKind.VISIBLE_SURFACE), null, 1).nextCursor();

        store.clear();

        assertThat(store.latestFrame()).isEmpty();
        assertThat(store.activePaginationLeases()).isZero();
        assertFailure(
                () -> store.page(id(1), Set.of(ObservationKind.VISIBLE_SURFACE), cursor, 1),
                ObservationStoreException.Code.INVALID_CURSOR);
        assertFailure(
                () -> store.page(id(1), Set.of(ObservationKind.VISIBLE_SURFACE), null, 1),
                ObservationStoreException.Code.FRAME_EXPIRED);
    }

    @Test
    void expiryArithmeticRemainsCorrectAcrossNanoTimeWrap() throws Exception {
        var clock = new FakeClock(Long.MAX_VALUE - Duration.ofSeconds(30).toNanos());
        var store = store(clock);
        store.publish(frame(1, 3));
        String cursor = store.page(
                id(1), Set.of(ObservationKind.VISIBLE_SURFACE), null, 1).nextCursor();

        clock.advance(Duration.ofSeconds(59));
        assertThat(store.page(id(1), Set.of(ObservationKind.VISIBLE_SURFACE), cursor, 1).records())
                .hasSize(1);
        clock.advance(Duration.ofSeconds(60));
        assertFailure(
                () -> store.page(id(1), Set.of(ObservationKind.VISIBLE_SURFACE), cursor, 1),
                ObservationStoreException.Code.INVALID_CURSOR);
    }

    @Test
    void deliveryCompactsDuplicateFacesToThePreferredUpSurface() throws Exception {
        var store = new ObservationFrameStore();
        VisibleSurface up = ObservationModelContractTest.surface(99, 1);
        VisibleSurface north = new VisibleSurface(
                up.position(),
                ObservationRecord.Face.NORTH,
                up.block(),
                up.shapeClass(),
                up.cropMature(),
                up.eyeOrigin(),
                up.observedTick(),
                up.worldRevision());
        ObservationRecord.Hazard hazard = hazard(99);
        store.publish(new ObservationFrame(
                id(1), DIMENSION, 100, 16, false, List.of(north, up, hazard)));

        ObservationPage page = store.page(id(1), Set.of(
                ObservationKind.VISIBLE_SURFACE, ObservationKind.HAZARD), null, 256);

        assertThat(page.records()).hasSize(2);
        assertThat(page.records()).filteredOn(VisibleSurface.class::isInstance)
                .singleElement()
                .extracting(record -> ((VisibleSurface) record).face())
                .isEqualTo(ObservationRecord.Face.UP);
    }

    @Test
    void deliveryFairlyIncludesSparseKindsBeforeLargeSurfaceSets() throws Exception {
        var store = new ObservationFrameStore();
        var records = new java.util.ArrayList<ObservationRecord>();
        IntStream.range(0, 300)
                .mapToObj(index -> ObservationModelContractTest.surface(99, index))
                .forEach(records::add);
        records.add(hazard(99));
        store.publish(new ObservationFrame(id(1), DIMENSION, 100, 16, false, records));

        ObservationPage page = store.page(id(1), Set.of(
                ObservationKind.VISIBLE_SURFACE, ObservationKind.HAZARD), null, 10);

        assertThat(page.records()).hasSize(10);
        assertThat(page.records()).anyMatch(ObservationRecord.Hazard.class::isInstance);
        assertThat(page.nextCursor()).isNotNull();
    }

    @Test
    void matureAndImmatureCropsLeadTheSurfaceBucket() throws Exception {
        var store = new ObservationFrameStore();
        var records = new java.util.ArrayList<ObservationRecord>();
        IntStream.range(0, 300)
                .mapToObj(index -> ObservationModelContractTest.surface(99, index))
                .forEach(records::add);
        var base = ObservationModelContractTest.surface(99, 301);
        var immature = new VisibleSurface(
                base.position(), base.face(),
                new ObservationValues.ResourceId("minecraft:wheat"), base.shapeClass(),
                false, base.rayHit(), base.eyeOrigin(), base.observedTick(),
                base.worldRevision());
        var mature = new VisibleSurface(
                new ObservationValues.BlockPosition(DIMENSION, 302, 64, 0), base.face(),
                new ObservationValues.ResourceId("minecraft:wheat"), base.shapeClass(),
                true, null, base.eyeOrigin(), base.observedTick(), base.worldRevision());
        records.add(immature);
        records.add(mature);
        store.publish(new ObservationFrame(id(1), DIMENSION, 100, 16, false, records));

        ObservationPage page = store.page(
                id(1), Set.of(ObservationKind.VISIBLE_SURFACE), null, 2);

        assertThat(page.records()).containsExactly(mature, immature);
    }

    @Test
    void validatesTheBoundedQuerySurfaceBeforeStoreLookup() {
        var store = new ObservationFrameStore();
        assertThatThrownBy(() -> store.page(id(1), Set.of(), null, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.page(id(1), Set.of(ObservationKind.HAZARD), null, 257))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.page("bad-frame", Set.of(ObservationKind.HAZARD), null, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ObservationFrameStore store(FakeClock clock) {
        return new ObservationFrameStore(clock, new SecureRandom());
    }

    private static ObservationFrame frame(long number, int recordCount) {
        List<ObservationRecord> records = IntStream.range(0, recordCount)
                .mapToObj(index -> ObservationModelContractTest.surface(99, index))
                .map(ObservationRecord.class::cast)
                .toList();
        return new ObservationFrame(id(number), DIMENSION, 100, 16, false, records);
    }

    private static ObservationRecord.Hazard hazard(long tick) {
        var position = ObservationModelContractTest.world(0, 64, 0);
        return new ObservationRecord.Hazard(
                ObservationRecord.HazardType.FALL,
                position,
                ObservationRecord.HazardSeverity.CAUTION,
                position,
                tick,
                7,
                ObservationRecord.EvidenceProvenance.LOCAL_VOLUME);
    }

    private static String id(long number) {
        return "obs-" + String.format("%016x", number);
    }

    private static void assertFailure(
            ThrowingCall call, ObservationStoreException.Code code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ObservationStoreException.class,
                        failure -> assertThat(failure.code()).isEqualTo(code));
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }

    private static final class FakeClock implements LongSupplier {
        private long now;

        private FakeClock() {
        }

        private FakeClock(long now) {
            this.now = now;
        }

        @Override
        public long getAsLong() {
            return now;
        }

        private void advance(Duration duration) {
            now += duration.toNanos();
        }
    }
}
