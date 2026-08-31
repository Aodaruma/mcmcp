package dev.aod.mcmcp.agent.observation;

import dev.aod.mcmcp.agent.observation.ObservationRecord.VisibleSurface;
import dev.aod.mcmcp.agent.observation.ObservationValues.ResourceId;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
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
    void sameRevisionUpSupportsSurviveNewerSideRaysAndTheDeliveredEvidenceFence()
            throws Exception {
        var frameStore = new ObservationFrameStore();
        var raw = new java.util.ArrayList<ObservationRecord>();
        for (int x = 1; x <= 3; x++) {
            VisibleSurface up = new VisibleSurface(
                    new ObservationValues.BlockPosition(DIMENSION, x, 64, 0),
                    ObservationRecord.Face.UP,
                    new ResourceId("minecraft:glass"),
                    ObservationRecord.ShapeClass.TRANSPARENT,
                    null,
                    new ObservationValues.WorldPosition(DIMENSION, x + 0.5D, 65.0D, 0.5D),
                    new ObservationValues.WorldPosition(DIMENSION, 0.5D, 65.62D, 0.5D),
                    90,
                    7);
            VisibleSurface laterEast = new VisibleSurface(
                    up.position(),
                    ObservationRecord.Face.EAST,
                    up.block(),
                    up.shapeClass(),
                    up.cropMature(),
                    new ObservationValues.WorldPosition(DIMENSION, x + 1.0D, 64.5D, 0.5D),
                    up.eyeOrigin(),
                    99,
                    up.worldRevision());
            raw.add(up);
            raw.add(laterEast);
        }
        var frame = new ObservationFrame(id(1), DIMENSION, 100, 16, false, raw);
        frameStore.publish(frame);

        ObservationPage delivered = frameStore.page(
                id(1), Set.of(ObservationKind.VISIBLE_SURFACE), null, 256);

        assertThat(delivered.records())
                .hasSize(3)
                .allSatisfy(record -> assertThat(((VisibleSurface) record).face())
                        .isEqualTo(ObservationRecord.Face.UP));

        var evidence = new DeliveredPolicyEvidenceStore();
        assertThat(evidence.confirmDelivery(evidence.prepareDelivery(delivered))).isTrue();
        ObservationFrame plannerFrame = evidence.augment(Optional.of(frame)).orElseThrow();
        assertThat(plannerFrame.records())
                .hasSize(3)
                .allSatisfy(record -> assertThat(((VisibleSurface) record).face())
                        .isEqualTo(ObservationRecord.Face.UP));
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
    void deliveryFilterProjectsOnlyRequestedBlocksAndMaturityWithoutChangingFrame()
            throws Exception {
        var store = new ObservationFrameStore();
        var stone = ObservationModelContractTest.surface(99, 0);
        var matureWheat = new VisibleSurface(
                new ObservationValues.BlockPosition(DIMENSION, 1, 64, 0), stone.face(),
                new ResourceId("minecraft:wheat"), stone.shapeClass(), true,
                stone.rayHit(), stone.eyeOrigin(), stone.observedTick(), stone.worldRevision());
        var immatureWheat = new VisibleSurface(
                new ObservationValues.BlockPosition(DIMENSION, 2, 64, 0), stone.face(),
                new ResourceId("minecraft:wheat"), stone.shapeClass(), false,
                stone.rayHit(), stone.eyeOrigin(), stone.observedTick(), stone.worldRevision());
        store.publish(new ObservationFrame(
                id(1), DIMENSION, 100, 16, false,
                List.of(stone, matureWheat, immatureWheat, hazard(99))));

        var filter = new ObservationFilter(
                Set.of(new ResourceId("minecraft:wheat")), Set.of(), Set.of(),
                Optional.of(true));
        ObservationPage page = store.page(
                id(1), Set.of(ObservationKind.VISIBLE_SURFACE, ObservationKind.HAZARD),
                filter, null, 256);

        assertThat(page.records()).containsExactly(hazard(99), matureWheat);
        assertThat(store.latestFrame()).get().extracting(frame -> frame.records().size())
                .isEqualTo(4);
    }

    @Test
    void paginationCursorIsBoundToTheExactDeliveryFilter() throws Exception {
        var store = new ObservationFrameStore();
        store.publish(frame(1, 3));
        var stone = new ObservationFilter(
                Set.of(new ResourceId("minecraft:stone")), Set.of(), Set.of(), Optional.empty());
        String cursor = store.page(
                id(1), Set.of(ObservationKind.VISIBLE_SURFACE), stone, null, 1).nextCursor();

        assertFailure(
                () -> store.page(
                        id(1), Set.of(ObservationKind.VISIBLE_SURFACE),
                        ObservationFilter.NONE, cursor, 1),
                ObservationStoreException.Code.INVALID_CURSOR);
        assertThat(store.page(
                id(1), Set.of(ObservationKind.VISIBLE_SURFACE), stone, cursor, 1).records())
                .hasSize(1);
    }

    @Test
    void positionBoundsUseTheDocumentedAnchorForEveryRecordKindAndInclusiveEdges()
            throws Exception {
        var eye = ObservationModelContractTest.world(0.5D, 65.62D, 0.5D);
        var surface = ObservationModelContractTest.surface(99, 1);
        var item = new ObservationRecord.VisibleEntity(
                new ResourceId("minecraft:item"),
                new ResourceId("minecraft:wheat"),
                ObservationModelContractTest.world(2.999D, 64.999D, 2.999D),
                new ObservationValues.Vector(0, 0, 0),
                new ObservationValues.Aabb(2.8D, 64.8D, 2.8D, 3.1D, 65.1D, 3.1D),
                ObservationRecord.EntityHazardClass.UNKNOWN,
                eye,
                99,
                7);
        var edge = new ObservationRecord.Traversability(
                ObservationModelContractTest.world(20.5D, 64.0D, 20.5D),
                ObservationModelContractTest.world(2.75D, 64.1D, 1.75D),
                ObservationRecord.TraversabilityStatus.CONFIRMED,
                ObservationRecord.TargetSupport.CONFIRMED,
                ObservationRecord.TransitionClearance.CONFIRMED,
                ObservationRecord.Fluid.NONE,
                eye,
                99,
                7,
                ObservationRecord.EvidenceProvenance.LOCAL_VOLUME);
        var hazard = new ObservationRecord.Hazard(
                ObservationRecord.HazardType.FALL,
                ObservationModelContractTest.world(1.2D, 64.9D, 1.2D),
                ObservationRecord.HazardSeverity.CAUTION,
                eye,
                99,
                7,
                ObservationRecord.EvidenceProvenance.LOCAL_VOLUME);
        var outsideBoundary = new ObservationRecord.UnknownBoundary(
                ObservationModelContractTest.world(3.0D, 64.0D, 2.0D),
                ObservationRecord.UnknownBoundaryReason.RADIUS_LIMIT,
                eye,
                99,
                7);
        var outsideSound = new ObservationRecord.SoundClue(
                new ResourceId("minecraft:entity.zombie.ambient"),
                ObservationRecord.SoundCategory.HOSTILE,
                ObservationModelContractTest.world(0.999D, 64.0D, 1.0D),
                90,
                99,
                1,
                1,
                new ResourceId("minecraft:zombie"),
                7);
        var store = new ObservationFrameStore();
        store.publish(new ObservationFrame(
                id(1), DIMENSION, 100, 16, false,
                List.of(surface, item, edge, hazard, outsideBoundary, outsideSound)));
        var bounds = new ObservationFilter.PositionBounds(
                DIMENSION, 1, 64, 0, 2, 64, 2);
        var filter = new ObservationFilter(
                Set.of(), Set.of(), Set.of(), Optional.empty(), Optional.of(bounds));

        ObservationPage page = store.page(
                id(1), java.util.EnumSet.allOf(ObservationKind.class), filter, null, 256);

        assertThat(page.records()).containsExactlyInAnyOrder(surface, item, edge, hazard);
        assertThat(store.latestFrame()).get()
                .extracting(frame -> frame.records().size()).isEqualTo(6);
    }

    @Test
    void positionBoundsRejectReversedAxesExcludeOtherDimensionsAndBindTheCursor()
            throws Exception {
        assertThatThrownBy(() -> new ObservationFilter.PositionBounds(
                DIMENSION, 2, 64, 0, 1, 64, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimum");

        var bounds = new ObservationFilter.PositionBounds(
                DIMENSION, 0, 64, 0, 2, 64, 0);
        var filter = new ObservationFilter(
                Set.of(), Set.of(), Set.of(), Optional.empty(), Optional.of(bounds));
        var nether = new ResourceId("minecraft:the_nether");
        var otherDimension = new VisibleSurface(
                new ObservationValues.BlockPosition(nether, 1, 64, 0),
                ObservationRecord.Face.UP,
                new ResourceId("minecraft:stone"),
                ObservationRecord.ShapeClass.OPAQUE,
                new ObservationValues.WorldPosition(nether, 1.5D, 65.62D, 0.5D),
                99,
                7);
        assertThat(filter.matches(otherDimension)).isFalse();

        var store = new ObservationFrameStore();
        store.publish(frame(1, 3));
        String cursor = store.page(
                id(1), Set.of(ObservationKind.VISIBLE_SURFACE), filter, null, 1).nextCursor();
        var narrower = new ObservationFilter(
                Set.of(), Set.of(), Set.of(), Optional.empty(),
                Optional.of(new ObservationFilter.PositionBounds(
                        DIMENSION, 0, 64, 0, 1, 64, 0)));

        assertFailure(
                () -> store.page(
                        id(1), Set.of(ObservationKind.VISIBLE_SURFACE), narrower, cursor, 1),
                ObservationStoreException.Code.INVALID_CURSOR);
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
