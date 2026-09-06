package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner;
import dev.aod.mcmcp.agent.action.AgentActionStore.RendererRecoveryStage;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.navigation.KnownTraversabilityMap;
import dev.aod.mcmcp.agent.observation.*;
import dev.aod.mcmcp.agent.observation.ObservationRecord.VisibleSurface;
import dev.aod.mcmcp.agent.observation.ObservationValues.*;
import dev.aod.mcmcp.safety.InputReleaseController;
import dev.aod.mcmcp.safety.LocalArmingState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static dev.aod.mcmcp.runtime.SurfacePreflightRecovery.Decision.*;
import static dev.aod.mcmcp.agent.observation.DeliveredPolicyEvidenceStore.SurfaceLeaseStatus.*;

/** Uses the production inbox, lease, recovery gate and actual known-ray reobservation seam. */
class SurfacePreflightRecoveryTest {
    private static final ResourceId DIM = new ResourceId("minecraft:overworld");
    private static final VisibleSurface CHEST = new VisibleSurface(
            new BlockPosition(DIM, 1, 64, 0), ObservationRecord.Face.UP,
            new ResourceId("minecraft:chest"), ObservationRecord.ShapeClass.PARTIAL, null,
            new WorldPosition(DIM, 1.5, 64.875, 0.5),
            new WorldPosition(DIM, -1.5, 65.62, 0.5), 10, 10);
    private static final ObservationFrame RAW = new ObservationFrame("obs-0000000000000001",
            DIM, 10, 16, false, List.of(CHEST));
    private static final ActionDsl.InspectKnownContainer NODE = new ActionDsl.InspectKnownContainer(
            "inspect", new ActionDsl.Position(DIM.value(), 1, 64, 0), "minecraft:chest");
    private static final ActionDsl.Budget BUDGET = new ActionDsl.Budget(1_000, 20, 0, 360, 3, 0, 0);

    @Test
    void captureCommitAndDispatchEachWaitForTheirOwnFreshRenderWithoutRenewingEvidence() {
        var fixture = new Fixture();
        var capture = fixture.submit(RendererRecoveryStage.CAPTURE, false);
        fixture.drain(10, false);
        assertThat(capture).isNotDone();
        assertThat(fixture.rays).hasValue(0);
        fixture.drain(11, true);
        assertThat(capture.join()).isEqualTo("ready");

        var commit = fixture.submit(RendererRecoveryStage.COMMIT, false);
        fixture.drain(12, false);
        assertThat(commit).isNotDone();
        assertThat(fixture.rays).hasValue(1);
        fixture.drain(13, true);
        assertThat(commit.join()).isEqualTo("ready");

        var dispatch = fixture.submit(RendererRecoveryStage.DISPATCH, true);
        fixture.drain(14, false);
        assertThat(dispatch).isNotDone();
        assertThat(fixture.interactions).hasValue(0);
        fixture.drain(15, true);
        assertThat(dispatch.join()).isEqualTo("ready");
        fixture.drain(16, true);
        assertThat(fixture.rays).hasValue(3);
        assertThat(fixture.interactions).hasValue(1);
        assertThat(fixture.recovery.consumedTicks(15)).isEqualTo(5);
        assertThat(fixture.recovery.executionStartNanos(750_000_000)).isEqualTo(500_000_000);
        assertThat(RAW.records()).containsExactly(CHEST);
        assertThat(fixture.store.augment(Optional.of(RAW)).orElseThrow().records()).containsExactly(CHEST);
        assertThat(fixture.recovery.summary().missingStages()).isEqualTo(1 | 2 | 4);
        assertThat(fixture.recovery.summary().revalidatedStages()).isEqualTo(1 | 2 | 4);
    }

    @Test
    void recoveredRendererDoesNotAuthorizeAChangedOrOccludedChest() {
        for (boolean occluded : List.of(false, true)) {
            var fixture = new Fixture();
            var capture = fixture.submit(false);
            fixture.drain(10, true);
            assertThat(capture.join()).isEqualTo("ready");
            var commit = fixture.submit(true);
            fixture.drain(11, false);
            fixture.block = occluded ? null : new ResourceId("minecraft:stone");
            fixture.drain(12, true);
            assertThatThrownBy(commit::join).hasCauseInstanceOf(AgentPrimitivePlanner.PlanningException.class);
            fixture.drain(13, true);
            assertThat(fixture.interactions).hasValue(0);
            assertThat(fixture.rays).hasValue(2);
            assertThat(fixture.recovery.summary().missingStages()).isEqualTo(RendererRecoveryStage.CAPTURE.mask());
            assertThat(fixture.recovery.summary().revalidatedStages()).isZero();
        }
    }

    @Test
    void aRealOneBlockFogLimitStillPreventsTargetAuthorizationAfterRecovery() {
        var fixture = new Fixture();
        var start = fixture.submit(true);
        fixture.drain(10, false);
        fixture.fogDistance = 1.0;
        fixture.drain(11, true);
        assertThatThrownBy(start::join).hasCauseInstanceOf(AgentPrimitivePlanner.PlanningException.class);
        assertThat(fixture.interactions).hasValue(0);
        assertThat(fixture.recovery.summary().revalidatedStages()).isZero();
    }

    @Test
    void deliveryExpiryAndUnknownTargetsAreNeverRendererWaits() {
        var recovery = new SurfacePreflightRecovery(BUDGET);
        assertThat(recovery.evaluate(VALID, 10, 0, false)).isEqualTo(RENDERER_EVIDENCE_MISSING);
        assertThat(recovery.evaluate(DeliveredPolicyEvidenceStore.SurfaceLeaseStatus.DELIVERY_EXPIRED, 11, 1, false))
                .isEqualTo(SurfacePreflightRecovery.Decision.DELIVERY_EXPIRED);
        assertThat(recovery.evaluate(NOT_DELIVERED, 11, 1, false)).isEqualTo(TARGET_NOT_DELIVERED);
        assertThat(new SurfacePreflightRecovery(BUDGET).evaluate(NOT_DELIVERED, 10, 0, true))
                .isEqualTo(TARGET_NOT_DELIVERED);
    }

    @Test
    void successfulIntermediatePhaseCannotResetTheOriginalTickOrWallBudget() {
        var ticks = new SurfacePreflightRecovery(new ActionDsl.Budget(10_000, 3, 0, 360, 3, 0, 0));
        assertThat(ticks.evaluate(VALID, 10, 0, false)).isEqualTo(RENDERER_EVIDENCE_MISSING);
        assertThat(ticks.evaluate(VALID, 11, 1, true)).isEqualTo(READY);
        assertThat(ticks.evaluate(VALID, 12, 2, false)).isEqualTo(RENDERER_EVIDENCE_MISSING);
        assertThat(ticks.evaluate(VALID, 13, 3, true)).isEqualTo(RENDERER_EVIDENCE_TIMEOUT);
        var wall = new SurfacePreflightRecovery(BUDGET);
        wall.evaluate(VALID, 10, 0, false);
        assertThat(wall.evaluate(VALID, 11, 1_000_000_000L, true)).isEqualTo(RENDERER_EVIDENCE_TIMEOUT);
    }

    @Test
    void laterNodesAtTheSameTargetKeepTheOriginalLeaseWithoutEnablingUnrelatedTargets() {
        var fixture = new Fixture();
        var lease = fixture.recovery.lease();
        assertThat(fixture.recovery.applies(new ActionDsl.ApproachKnownSurface(
                "approach", NODE.target(), NODE.expectedBlock()))).isTrue();
        assertThat(fixture.recovery.applies(new ActionDsl.InspectKnownContainer(
                "other", new ActionDsl.Position(DIM.value(), 2, 64, 0), NODE.expectedBlock()))).isFalse();
        fixture.recovery.capture(new ActionDsl.InspectKnownContainer(
                "other", NODE.target(), "minecraft:barrel"), fixture.store);
        assertThat(fixture.recovery.lease()).isSameAs(lease);
        assertThat(fixture.recovery.applies(NODE)).isTrue();
    }

    @Test
    void fogReturningAloneDoesNotMarkRevalidationAndStagesCannotValidateEachOther() {
        var recovery = new SurfacePreflightRecovery(BUDGET);
        recovery.evaluate(VALID, 10, 0, false);
        assertThat(recovery.noteMissing(RendererRecoveryStage.INITIAL_OPEN)).isTrue();
        assertThat(recovery.evaluate(VALID, 11, 50_000_000L, true)).isEqualTo(READY);
        assertThat(recovery.summary().revalidatedStages()).isZero();
        assertThat(recovery.noteRevalidated(RendererRecoveryStage.JIT)).isFalse();
        assertThat(recovery.noteRevalidated(RendererRecoveryStage.INITIAL_OPEN)).isTrue();
        for (int repeat = 0; repeat < 1000; repeat++) {
            assertThat(recovery.noteMissing(RendererRecoveryStage.INITIAL_OPEN)).isFalse();
        }
        // Historical "at least once", not current readiness: a later missing interval remains pending.
        assertThat(recovery.summary().missingStages()).isEqualTo(16);
        assertThat(recovery.summary().revalidatedStages()).isEqualTo(16);
        assertThat(recovery.noteRevalidated(RendererRecoveryStage.INITIAL_OPEN)).isFalse();
        assertThat(recovery.executionStartNanos(1_000_000_000L)).isZero();
    }

    private static final class Fixture {
        private final AtomicLong now = new AtomicLong();
        private final AtomicLong tick = new AtomicLong(10);
        private final AtomicBoolean fog = new AtomicBoolean();
        private final AtomicInteger rays = new AtomicInteger();
        private final AtomicInteger interactions = new AtomicInteger();
        private final DeliveredPolicyEvidenceStore store = new DeliveredPolicyEvidenceStore();
        private final SurfacePreflightRecovery recovery = new SurfacePreflightRecovery(BUDGET);
        private final KnownTraversabilityMap map = new KnownTraversabilityMap();
        private final ClientCommandInbox inbox = new ClientCommandInbox(8, new InputReleaseController(),
                new LocalArmingState(), (reason, session) -> ClientCommandInbox.StopProgress.COMPLETE, now::get);
        private ResourceId block = CHEST.block();
        private double fogDistance = 16;

        private Fixture() {
            var receipt = store.prepareDelivery(new ObservationPage(RAW.frameId(), 10, List.of(CHEST), null));
            assertThat(store.confirmDelivery(receipt)).isTrue();
            recovery.capture(NODE, store);
            map.startSession(new UUID(0, 1), DIM.value(), 65);
        }

        private CompletableFuture<String> submit(boolean dispatch) {
            return submit(RendererRecoveryStage.CAPTURE, dispatch);
        }

        private CompletableFuture<String> submit(RendererRecoveryStage stage, boolean dispatch) {
            return inbox.submitControl("agent_start_action", 1, Long.MAX_VALUE, () -> {
                var decision = recovery.evaluate(store, tick.get(), now.get(), fog.get());
                if (decision == RENDERER_EVIDENCE_MISSING) {
                    recovery.noteMissing(stage);
                    throw new ClientCommandInbox.DeferControl();
                }
                assertThat(decision).isEqualTo(READY);
                var planning = store.reobserveForPlanning(Optional.of(RAW), known ->
                        SurfaceReobservationFixture.reobserve(known, tick.get(), 65, fogDistance, block, rays),
                        tick.get(), entity -> Optional.empty(), recovery.lease()::targets);
                planning = store.restrictToSurfaceLease(planning, recovery.lease());
                AgentPrimitivePlanner.requireKnownSurface(map.snapshot().orElseThrow(), planning,
                        NODE.target(), NODE.expectedBlock(), 65);
                recovery.noteRevalidated(stage);
                if (dispatch) interactions.incrementAndGet();
                return "ready";
            });
        }

        private void drain(long currentTick, boolean currentFog) {
            tick.set(currentTick);
            now.set(currentTick * 50_000_000L);
            fog.set(currentFog);
            inbox.drainControls(1, now.get());
        }
    }
}
