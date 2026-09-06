package dev.aod.mcmcp.agent.mining;

import dev.aod.mcmcp.agent.action.AgentActionStore;
import dev.aod.mcmcp.agent.action.KnownConstructionAttempt;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.routine.BlockStateFingerprint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.aod.mcmcp.agent.mining.ExcavationPort.*;
import static org.assertj.core.api.Assertions.*;

class ExcavationEngineTest {
    @Test void sixteenAndOneHundredSixtyBlocksFinishInOneEngineRunWithOneAckPerBreak() {
        for (int length : new int[] { 16, 160 }) {
            var plan = plan(length, false);
            var port = new FakePort(plan);
            var engine = engine(plan, port, 100_000, 20);
            var result = run(engine, port);
            assertThat(result.status()).isEqualTo(ExcavationEngine.Status.SUCCEEDED);
            assertThat(engine.drainRendererRecoveryEvidence()).isEmpty();
            assertThat(result.confirmedBreaks()).isEqualTo(length * 2);
            assertThat(result.completedCells()).isEqualTo(length);
            assertThat(port.breaks).hasSize(length * 2).doesNotHaveDuplicates();
            assertThat(port.moves).containsExactlyElementsOf(plan.route());
            assertThat(port.released).isTrue();
            assertThat(port.effects).hasSize(length * 2).allSatisfy(effect -> {
                assertThat(effect.verification()).isEqualTo(AgentActionStore.Verification.CONFIRMED);
                assertThat(effect.observedAfter()).doesNotContainKey("inventory_count");
            });
        }
    }

    @Test void branchesReturnWithoutRepeatingBreaksAndKeepEveryOperationInTheFootprint() {
        var plan = plan(16, true);
        var port = new FakePort(plan);
        var result = run(engine(plan, port, 100_000, 20), port);
        assertThat(result.status()).isEqualTo(ExcavationEngine.Status.SUCCEEDED);
        assertThat(port.moves).containsExactlyElementsOf(plan.route());
        assertThat(port.breaks).hasSize(plan.maxBreaks()).doesNotHaveDuplicates();
        assertThat(result.completedCells()).isEqualTo(plan.excavationCells().size());
        assertThat(result.completedMoves()).isEqualTo(plan.travelBlocks());
    }

    @Test void revealedFluidStopsBeforeAnotherAttackOrAnyForwardMovement() {
        for (var hazard : List.of(StopReason.FLUID, StopReason.FALLING_BLOCK, StopReason.UNSUPPORTED_BLOCK)) {
            var plan = plan(16, false);
            var port = new FakePort(plan);
            port.stopAfterFirstBreak = hazard;
            var result = run(engine(plan, port, 100_000, 20), port);
            assertThat(result.status()).isEqualTo(ExcavationEngine.Status.FAILED);
            assertThat(result.reason()).isEqualTo(hazard);
            assertThat(port.breaks).hasSize(1);
            assertThat(port.moves).isEmpty();
            assertThat(port.released).isTrue();
        }
    }

    @Test void anUnsafeOrUnknownFloorCannotBeCrossedAfterSuccessfulBreaks() {
        var plan = plan(16, false);
        var port = new FakePort(plan);
        port.moveStop = StopReason.UNSAFE_FLOOR;
        var result = run(engine(plan, port, 100_000, 20), port);
        assertThat(result.reason()).isEqualTo(StopReason.UNSAFE_FLOOR);
        assertThat(result.confirmedBreaks()).isEqualTo(2);
        assertThat(port.moves).isEmpty();
    }

    @Test void rendererGapsRecoverWithinTheOriginalDeadlineWithoutRepeatingMutation() {
        var plan = plan(16, false);
        var port = new FakePort(plan);
        port.gapUntilTick = 6;
        var engine = engine(plan, port, 100_000, 20);
        var result = run(engine, port);
        assertThat(result.status()).isEqualTo(ExcavationEngine.Status.SUCCEEDED);
        assertThat(port.breaks).hasSize(32).doesNotHaveDuplicates();
        assertThat(port.firstBreakTick).isEqualTo(6);
        assertThat(engine.rendererRecoverySummary().detail())
                .isEqualTo("tunnel_renderer_missing=1,revalidated=1,scope=block_probe");
        assertThat(engine.drainRendererRecoveryEvidence())
                .contains("tunnel_renderer_missing=1,revalidated=1,scope=block_probe");
        assertThat(engine.drainRendererRecoveryEvidence()).isEmpty();
    }

    @Test void recoveryCountsEpisodesAndRequiresTheSameFreshProbeAfterEachGap() {
        var plan = plan(1, false);
        var port = new FakePort(plan);
        var engine = engine(plan, port, 100_000, 20);
        port.gapUntilTick = 3;
        step(engine, port);
        var firstGap = engine.rendererRecoverySummary();
        assertThat(engine.drainRendererRecoveryEvidence()).isEmpty();
        engine.tick(port.tick, port.tick); // Reentrant same-tick delivery is not another episode.
        step(engine, port);
        assertThat(engine.rendererRecoverySummary()).isEqualTo(firstGap);
        step(engine, port); // Current BREAKABLE proof, before any server ACK.
        assertThat(engine.rendererRecoverySummary().revalidated()).isEqualTo(1);
        assertThat(engine.drainRendererRecoveryEvidence()).isEmpty();
        assertThat(port.air).isEmpty();
        step(engine, port); // ACK; the next probe must be observed again.
        port.gapUntilTick = 7;
        step(engine, port);
        assertThat(engine.rendererRecoverySummary().missing()).isEqualTo(2);
        assertThat(engine.rendererRecoverySummary().revalidated()).isEqualTo(1);
        step(engine, port);
        step(engine, port); // Fresh CLEAR of the same head cell.
        assertThat(engine.rendererRecoverySummary().revalidated()).isEqualTo(2);
        assertThat(firstGap.missing()).isEqualTo(1);
        assertThat(firstGap.revalidated()).isZero(); // Earlier snapshots are immutable.
        assertThat(run(engine, port).status()).isEqualTo(ExcavationEngine.Status.SUCCEEDED);
        assertThat(port.breaks).hasSize(2).doesNotHaveDuplicates();
    }

    @Test void unknownStaleMoveAndAckWaitsDoNotBecomeRendererRecoveryEvidence() {
        for (int mode = 0; mode < 4; mode++) {
            var plan = plan(1, false);
            var port = new FakePort(plan);
            var engine = engine(plan, port, 8, 3);
            port.unknownUntilTick = mode == 0 ? Long.MAX_VALUE : 0;
            port.staleAtStart = mode == 1;
            port.neverAck = mode == 2;
            port.moveWait = mode == 3;
            assertThat(run(engine, port).status()).isEqualTo(ExcavationEngine.Status.FAILED);
            assertThat(engine.rendererRecoverySummary().missing()).isZero();
            assertThat(engine.rendererRecoverySummary().revalidated()).isZero();
            assertThat(engine.drainRendererRecoveryEvidence()).isEmpty();
        }
    }

    @Test void currentTickProofBeforeTheLastServerRevisionCannotClaimRendererRecovery() {
        var plan = plan(1, false);
        var port = new FakePort(plan);
        var engine = engine(plan, port, 100_000, 3);
        step(engine, port); // Dispatch head break.
        step(engine, port); // ACK establishes revision 1.
        port.gapUntilTick = 4;
        step(engine, port);
        port.staleRevisionAfterFirstBreak = true;
        assertThat(run(engine, port).reason()).isEqualTo(StopReason.OBSERVATION_TIMEOUT);
        assertThat(engine.rendererRecoverySummary().missing()).isEqualTo(1);
        assertThat(engine.rendererRecoverySummary().revalidated()).isZero();
        assertThat(port.breaks).hasSize(1);
        assertThat(port.moves).isEmpty();
    }

    @Test void rendererReturnWithoutUsableCurrentProofNeverCountsAsRevalidated() {
        for (int mode = 0; mode < 5; mode++) {
            var plan = plan(1, false);
            var port = new FakePort(plan);
            var engine = new ExcavationEngine(plan, port, new ExcavationEngine.Limits(
                    0, 0, 100_000, 100_000, mode == 4 ? 0 : plan.maxBreaks(), 3));
            port.gapUntilTick = 2;
            step(engine, port);
            port.unknownUntilTick = mode == 0 ? Long.MAX_VALUE : 0;
            port.staleAtStart = mode == 1;
            port.wrongWitness = mode == 2;
            port.unsafe = mode == 3 ? StopReason.FLUID : StopReason.NONE;
            assertThat(run(engine, port).status()).isEqualTo(ExcavationEngine.Status.FAILED);
            assertThat(engine.rendererRecoverySummary().missing()).isEqualTo(1);
            assertThat(engine.rendererRecoverySummary().revalidated()).isZero();
            assertThat(port.breaks).isEmpty();
            assertThat(port.moves).isEmpty();
        }
    }

    @Test void gapHistorySurvivesTimeoutDeadlineAndOuterCancellationWithoutClaimingRecovery() {
        for (int mode = 0; mode < 4; mode++) {
            var plan = plan(1, false);
            var port = new FakePort(plan);
            var engine = new ExcavationEngine(plan, port, new ExcavationEngine.Limits(
                    0, 0, mode == 1 ? 3 : 100_000,
                    mode == 2 ? 3 : 100_000, plan.maxBreaks(), 3));
            port.gapUntilTick = Long.MAX_VALUE;
            step(engine, port);
            if (mode == 3) engine.close(); // Mirrors the outer cancellation/cleanup path.
            var result = run(engine, port);
            assertThat(result.reason()).isEqualTo(mode == 0 ? StopReason.OBSERVATION_TIMEOUT
                    : mode == 3 ? StopReason.CANCELLED : StopReason.DEADLINE);
            assertThat(engine.rendererRecoverySummary().detail())
                    .isEqualTo("tunnel_renderer_missing=1,revalidated=0,scope=block_probe");
            assertThat(engine.drainRendererRecoveryEvidence())
                    .contains("tunnel_renderer_missing=1,revalidated=0,scope=block_probe");
            engine.close();
            assertThat(engine.drainRendererRecoveryEvidence()).isEmpty();
            assertThat(engine.rendererRecoverySummary().revalidated()).isZero();
            assertThat(port.breaks).isEmpty();
            assertThat(port.moves).isEmpty();
            assertThat(port.released).isTrue();
        }
    }

    @Test void recoverySummaryHasOnlyBoundedFixedFieldsAndCannotAssertMoreRecoveriesThanGaps() {
        var maximum = new ExcavationEngine.RendererRecoverySummary(65_535, 65_535);
        assertThat(maximum.detail()).hasSizeLessThan(256)
                .isEqualTo("tunnel_renderer_missing=65535,revalidated=65535,scope=block_probe");
        assertThatThrownBy(() -> new ExcavationEngine.RendererRecoverySummary(-1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExcavationEngine.RendererRecoverySummary(65_536, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExcavationEngine.RendererRecoverySummary(1, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void aPermanentGapAndAStalePostAckObservationHaveFiniteStops() {
        var plan = plan(16, false);
        var gap = new FakePort(plan);
        gap.gapUntilTick = Long.MAX_VALUE;
        var gapResult = run(engine(plan, gap, 100_000, 3), gap);
        assertThat(gapResult.reason()).isEqualTo(StopReason.OBSERVATION_TIMEOUT);
        assertThat(gap.breaks).isEmpty();
        var stale = new FakePort(plan);
        stale.staleAfterFirstBreak = true;
        var staleResult = run(engine(plan, stale, 100_000, 3), stale);
        assertThat(staleResult.reason()).isEqualTo(StopReason.OBSERVATION_TIMEOUT);
        assertThat(stale.breaks).hasSize(1);
        assertThat(stale.moves).isEmpty();
    }

    @Test void waitingForAnAckNeverResendsAndUnknownEffectIsKeptThroughCleanup() {
        var plan = plan(16, false);
        var port = new FakePort(plan);
        port.neverAck = true;
        var result = run(engine(plan, port, 8, 20), port);
        assertThat(result.reason()).isEqualTo(StopReason.DEADLINE);
        assertThat(port.breaks).hasSize(1);
        assertThat(result.confirmedBreaks()).isZero();
        assertThat(port.effects).singleElement().satisfies(effect ->
                assertThat(effect.verification()).isEqualTo(AgentActionStore.Verification.UNKNOWN));
    }

    @Test void cancellationReleasesInEveryOperationalPhaseAndNeverStartsTheSuffix() {
        for (var phase : List.of(ExcavationEngine.Phase.HEAD, ExcavationEngine.Phase.FEET,
                ExcavationEngine.Phase.BREAKING_HEAD, ExcavationEngine.Phase.BREAKING_FEET,
                ExcavationEngine.Phase.MOVE_READY, ExcavationEngine.Phase.MOVING)) {
            var plan = plan(16, false);
            var port = new FakePort(plan);
            var engine = engine(plan, port, 100_000, 20);
            if (phase != ExcavationEngine.Phase.HEAD) {
                for (int index = 0; index < 20; index++) {
                    if (step(engine, port).phase() == phase) break;
                }
            }
            int beforeBreaks = port.breaks.size();
            int beforeMoves = port.moves.size();
            engine.cancel();
            var result = step(engine, port);
            assertThat(result.status()).isEqualTo(ExcavationEngine.Status.CANCELLED);
            assertThat(port.released).isTrue();
            assertThat(port.breaks).hasSize(beforeBreaks);
            assertThat(port.moves).hasSize(beforeMoves);
        }
    }

    @Test void failedReleaseRetainsTheFirstTerminalIntentAndDoesNotReplayAnOperation() {
        var plan = plan(1, false);
        var port = new FakePort(plan);
        port.releaseFailures = 2;
        var engine = engine(plan, port, 100_000, 20);
        step(engine, port);
        engine.cancel();
        assertThat(step(engine, port).status()).isEqualTo(ExcavationEngine.Status.RUNNING);
        assertThatThrownBy(engine::close).isInstanceOf(IllegalStateException.class);
        var result = step(engine, port);
        assertThat(result.status()).isEqualTo(ExcavationEngine.Status.CANCELLED);
        assertThat(result.reason()).isEqualTo(StopReason.CANCELLED);
        assertThat(port.breaks).hasSize(1);
        assertThat(port.effects).hasSize(1);
    }

    @Test void exhaustedBreakBudgetStopsWithoutBeginningAnotherBlock() {
        var plan = plan(16, false);
        var port = new FakePort(plan);
        var engine = new ExcavationEngine(plan, port, new ExcavationEngine.Limits(
                0, 0, 100_000, 100_000, 1, 20));
        var result = run(engine, port);
        assertThat(result.reason()).isEqualTo(StopReason.BUDGET);
        assertThat(port.breaks).hasSize(1);
    }

    @Test void anOutOfScopeOrFutureWitnessNeverDispatches() {
        var plan = plan(16, false);
        var port = new FakePort(plan);
        port.wrongWitness = true;
        var result = run(engine(plan, port, 100_000, 20), port);
        assertThat(result.reason()).isEqualTo(StopReason.ADAPTER_FAILURE);
        assertThat(port.breaks).isEmpty();
    }

    @Test void aPreviouslyClearedCellReplacedDuringBranchReturnIsNotMinedAgain() {
        var plan = plan(4, true);
        var port = new FakePort(plan);
        port.replaceReturningCell = true;
        var result = run(engine(plan, port, 100_000, 20), port);
        assertThat(result.reason()).isEqualTo(StopReason.TARGET_CHANGED);
        assertThat(port.breaks).doesNotHaveDuplicates();
    }

    @Test void aDispatchExceptionKeepsAnUnknownAttemptAndNeverRetriesIt() {
        var plan = plan(16, false);
        var port = new FakePort(plan);
        port.throwAfterBeginBreak = true;
        var result = run(engine(plan, port, 100_000, 20), port);
        assertThat(result.reason()).isEqualTo(StopReason.ADAPTER_FAILURE);
        assertThat(port.breaks).hasSize(1);
        assertThat(port.effects).singleElement().satisfies(effect ->
                assertThat(effect.verification()).isEqualTo(AgentActionStore.Verification.UNKNOWN));
        assertThat(port.released).isTrue();
    }

    @Test void repeatedTickDoesNotAdvanceButRealtimeDeadlineStillStopsTheAttempt() {
        var plan = plan(16, false);
        var port = new FakePort(plan);
        var engine = new ExcavationEngine(plan, port, new ExcavationEngine.Limits(
                0, 0, 100_000, 10, plan.maxBreaks(), 20));
        step(engine, port);
        assertThat(engine.tick(port.tick, 2).phase()).isEqualTo(ExcavationEngine.Phase.BREAKING_HEAD);
        assertThat(port.air).isEmpty();
        var result = engine.tick(port.tick, 10);
        assertThat(result.reason()).isEqualTo(StopReason.DEADLINE);
        assertThat(result.effects()).singleElement().satisfies(effect ->
                assertThat(effect.verification()).isEqualTo(AgentActionStore.Verification.UNKNOWN));
        assertThat(port.breaks).hasSize(1);
        assertThat(port.released).isTrue();
    }

    @Test void healthToolCapacityAndControlChangesStopDuringAnActiveBreak() {
        for (var reason : List.of(StopReason.HEALTH_CHANGED, StopReason.TOOL_UNAVAILABLE,
                StopReason.INVENTORY_FULL, StopReason.CONTROL_LOST, StopReason.THREAT)) {
            var plan = plan(16, false);
            var port = new FakePort(plan);
            var engine = engine(plan, port, 100_000, 20);
            step(engine, port);
            port.unsafe = reason;
            var result = step(engine, port);
            assertThat(result.reason()).isEqualTo(reason);
            assertThat(result.status()).isEqualTo(ExcavationEngine.Status.FAILED);
            assertThat(port.breaks).hasSize(1);
            assertThat(port.moves).isEmpty();
            assertThat(port.released).isTrue();
        }
    }

    @Test void confirmedEffectsCountBeforeFinalVerificationEvenIfCancelledOrFailedLater() {
        for (boolean cancel : List.of(false, true)) {
            var plan = plan(16, false);
            var port = new FakePort(plan);
            port.confirmBeforeTerminal = true;
            var engine = engine(plan, port, 100_000, 20);
            step(engine, port);
            var confirmed = step(engine, port);
            assertThat(confirmed.status()).isEqualTo(ExcavationEngine.Status.RUNNING);
            assertThat(confirmed.confirmedBreaks()).isEqualTo(1);
            assertThat(confirmed.brokenDelta()).isEqualTo(1);
            if (cancel) engine.cancel();
            var result = step(engine, port);
            assertThat(result.status()).isEqualTo(cancel
                    ? ExcavationEngine.Status.CANCELLED : ExcavationEngine.Status.FAILED);
            assertThat(result.confirmedBreaks()).isEqualTo(1);
            assertThat(result.brokenDelta()).isZero();
            assertThat(port.effects).singleElement().satisfies(effect ->
                    assertThat(effect.verification()).isEqualTo(AgentActionStore.Verification.CONFIRMED));
            assertThat(port.breaks).hasSize(1);
            assertThat(port.moves).isEmpty();
        }
    }

    @Test void cleanupConfirmationCanBeDrainedWithItsBreakChargeExactlyOnce() {
        var plan = plan(16, false);
        var port = new FakePort(plan);
        port.confirmDuringRelease = true;
        var engine = engine(plan, port, 100_000, 20);
        step(engine, port);
        engine.close();
        assertThat(engine.drainEffects()).singleElement().satisfies(effect ->
                assertThat(effect.verification()).isEqualTo(AgentActionStore.Verification.CONFIRMED));
        assertThat(engine.drainBrokenDelta()).isEqualTo(1);
        assertThat(engine.drainEffects()).isEmpty();
        assertThat(engine.drainBrokenDelta()).isZero();
    }

    @Test void aPastTickProofAfterTheMinimumBoundaryCannotAuthorizeCurrentDispatch() {
        var plan = plan(16, false);
        var port = new FakePort(plan);
        port.staleAtStart = true;
        var result = run(engine(plan, port, 100_000, 3), port);
        assertThat(result.reason()).isEqualTo(StopReason.OBSERVATION_TIMEOUT);
        assertThat(port.breaks).isEmpty();
        assertThat(port.moves).isEmpty();
    }

    @Test void visibleLavaBeyondTheClearedHeadStopsBeforeBreakingTheStillSolidFeet() {
        var plan = plan(16, false);
        var port = new FakePort(plan);
        port.visibleLavaBeyondHead = true;
        var result = run(engine(plan, port, 100_000, 20), port);
        assertThat(result.reason()).isEqualTo(StopReason.FLUID);
        assertThat(result.confirmedBreaks()).isEqualTo(1);
        assertThat(port.air).contains(plan.route().getFirst().head()).doesNotContain(plan.route().getFirst());
        assertThat(port.feet).isEqualTo(plan.startFeet()); // Current feet remain dry and unmoved.
        assertThat(port.breaks).containsExactly(plan.route().getFirst().head());
        assertThat(port.moves).isEmpty();
        assertThat(port.released).isTrue();
    }

    @Test void permanentlyFailedReleaseEscalatesAfterThreeAttemptsWithoutPublishingTerminal() {
        for (boolean throwsFailure : List.of(false, true)) {
            var plan = plan(16, false);
            var port = new FakePort(plan);
            port.releaseFailures = Integer.MAX_VALUE;
            port.releaseThrows = throwsFailure;
            var engine = engine(plan, port, 100_000, 20);
            step(engine, port);
            port.unsafe = StopReason.HEALTH_CHANGED;
            for (int attempt = 1; attempt <= ExcavationEngine.MAX_LOCAL_RELEASE_ATTEMPTS; attempt++) {
                var result = step(engine, port);
                assertThat(result.status()).isEqualTo(ExcavationEngine.Status.RUNNING);
                assertThat(result.phase()).isEqualTo(ExcavationEngine.Phase.RELEASING);
                assertThat(result.reason()).isEqualTo(StopReason.HEALTH_CHANGED);
                assertThat(result.releaseEscalationRequired())
                        .isEqualTo(attempt == ExcavationEngine.MAX_LOCAL_RELEASE_ATTEMPTS);
                assertThat(engine.pendingTerminalStatus()).contains(ExcavationEngine.Status.FAILED);
                assertThat(port.activeBreak).isNotNull();
                assertThat(port.released).isFalse();
                engine.cancel(); // A later cancellation cannot overwrite the original failure.
            }
            assertThat(port.breaks).hasSize(1);
            assertThat(port.moves).isEmpty();
        }
    }

    @Test void successfulWorkWithStuckCleanupPreservesSuccessIntentForTheOuterReleaseFence() {
        var plan = plan(1, false);
        var port = new FakePort(plan);
        port.releaseFailures = Integer.MAX_VALUE;
        var engine = engine(plan, port, 100_000, 20);
        ExcavationEngine.TickResult result = step(engine, port);
        for (int tick = 0; tick < 100 && !result.releaseEscalationRequired(); tick++) {
            result = step(engine, port);
        }
        assertThat(result.releaseEscalationRequired()).isTrue();
        assertThat(result.status()).isEqualTo(ExcavationEngine.Status.RUNNING);
        assertThat(result.completedCells()).isEqualTo(1);
        assertThat(result.confirmedBreaks()).isEqualTo(2);
        assertThat(engine.pendingTerminalStatus()).contains(ExcavationEngine.Status.SUCCEEDED);
        assertThat(port.released).isFalse();
    }

    private static TunnelGeometry.Plan plan(int length, boolean branches) {
        return TunnelGeometry.plan(new ActionDsl.Position("minecraft:overworld", 1, 64, 0),
                ActionDsl.BlockFace.WEST, length, branches, branches ? 2 : 0, branches ? 3 : 0);
    }

    private static ExcavationEngine engine(TunnelGeometry.Plan plan, FakePort port, long deadline, int wait) {
        return new ExcavationEngine(plan, port, new ExcavationEngine.Limits(0, 0, deadline, deadline,
                plan.maxBreaks(), wait));
    }

    private static ExcavationEngine.TickResult run(ExcavationEngine engine, FakePort port) {
        for (int index = 0; index < 100_000; index++) {
            var result = step(engine, port);
            if (result.status() != ExcavationEngine.Status.RUNNING) return result;
        }
        throw new AssertionError("finite engine did not terminate");
    }

    private static ExcavationEngine.TickResult step(ExcavationEngine engine, FakePort port) {
        port.tick++;
        var result = engine.tick(port.tick, port.tick);
        port.effects.addAll(result.effects());
        return result;
    }

    private static final class FakePort implements ExcavationPort {
        final TunnelGeometry.Plan plan;
        final Set<TunnelGeometry.Cell> air = new HashSet<>();
        final Set<TunnelGeometry.Cell> entered = new HashSet<>();
        final List<TunnelGeometry.Cell> breaks = new ArrayList<>();
        final List<TunnelGeometry.Cell> moves = new ArrayList<>();
        final List<KnownConstructionAttempt.EffectDelta> effects = new ArrayList<>();
        final List<KnownConstructionAttempt.EffectDelta> releaseEffects = new ArrayList<>();
        TunnelGeometry.Cell feet;
        TunnelGeometry.Cell activeBreak;
        TunnelGeometry.Cell activeMove;
        long tick;
        long revision;
        long firstBreakTick;
        long gapUntilTick;
        long unknownUntilTick;
        boolean staleAfterFirstBreak;
        boolean staleRevisionAfterFirstBreak;
        boolean neverAck;
        boolean moveWait;
        boolean released;
        boolean wrongWitness;
        boolean replaceReturningCell;
        boolean throwAfterBeginBreak;
        boolean confirmBeforeTerminal;
        boolean confirmedActive;
        boolean confirmDuringRelease;
        boolean staleAtStart;
        boolean visibleLavaBeyondHead;
        boolean releaseThrows;
        int releaseFailures;
        StopReason stopAfterFirstBreak = StopReason.NONE;
        StopReason moveStop = StopReason.NONE;
        StopReason unsafe = StopReason.NONE;

        FakePort(TunnelGeometry.Plan plan) { this.plan = plan; feet = plan.startFeet(); }

        @Override public BlockInspection inspectBlock(TunnelGeometry.Cell block, long minTick, long minRevision) {
            assertThat(plan.containsBlock(block)).isTrue();
            if (tick < gapUntilTick) return BlockInspection.waiting(StopReason.RENDERER_GAP);
            if (tick < unknownUntilTick) return BlockInspection.waiting(StopReason.UNKNOWN_BLOCK);
            if (!air.isEmpty() && stopAfterFirstBreak != StopReason.NONE) return BlockInspection.stopped(stopAfterFirstBreak);
            if (staleAfterFirstBreak && !air.isEmpty()) return BlockInspection.clear(0, 0);
            if (staleRevisionAfterFirstBreak && !air.isEmpty()) return BlockInspection.clear(tick, 0);
            var lower = block.y() == feet.y() ? block : block.offset(0, -1, 0);
            assertThat(feet.adjacent(lower)).isTrue(); // No probe of the sealed suffix.
            if (air.contains(block) && !(replaceReturningCell && entered.contains(lower))) {
                return BlockInspection.clear(tick, revision);
            }
            var target = wrongWitness ? block.offset(0, 3, 0) : block;
            return BlockInspection.breakable(new Witness(target, ActionDsl.BlockFace.WEST,
                    new BlockStateFingerprint("minecraft:stone", Map.of()), staleAtStart ? tick - 1 : tick, revision));
        }

        @Override public void beginBreak(Witness witness) {
            assertThat(activeBreak).isNull();
            assertThat(activeMove).isNull();
            activeBreak = witness.cell();
            breaks.add(activeBreak);
            if (firstBreakTick == 0) firstBreakTick = tick;
            if (throwAfterBeginBreak) throw new IllegalStateException("simulated post-dispatch fault");
        }

        @Override public OperationResult pollBreak() {
            assertThat(activeBreak).isNotNull();
            if (neverAck) return new OperationResult(OperationStatus.RUNNING, tick, revision, List.of());
            if (confirmedActive) return new OperationResult(OperationStatus.FAILED, tick, revision, List.of());
            var cell = activeBreak;
            air.add(cell);
            revision++;
            if (confirmBeforeTerminal) {
                confirmedActive = true;
                return new OperationResult(OperationStatus.RUNNING, tick, revision,
                        List.of(effect(cell, AgentActionStore.Verification.CONFIRMED)));
            }
            activeBreak = null;
            return new OperationResult(OperationStatus.SUCCEEDED, tick, revision,
                    List.of(effect(cell, AgentActionStore.Verification.CONFIRMED)));
        }

        @Override public MoveInspection inspectMove(TunnelGeometry.Cell from, TunnelGeometry.Cell to,
                long minTick, long minRevision) {
            if (moveWait) return MoveInspection.waiting(StopReason.UNSAFE_MOVEMENT);
            if (moveStop != StopReason.NONE) return MoveInspection.stopped(moveStop);
            assertThat(air).contains(to, to.head());
            assertThat(from).isEqualTo(feet);
            assertThat(tick).isGreaterThanOrEqualTo(minTick);
            assertThat(revision).isGreaterThanOrEqualTo(minRevision);
            return MoveInspection.ready(tick, revision);
        }

        @Override public void beginMove(TunnelGeometry.Cell to) {
            assertThat(activeBreak).isNull();
            assertThat(activeMove).isNull();
            activeMove = to;
            moves.add(to);
        }

        @Override public OperationResult pollMove() {
            feet = activeMove;
            entered.add(feet);
            activeMove = null;
            return new OperationResult(OperationStatus.SUCCEEDED, tick, revision, List.of());
        }

        @Override public StopReason safety() {
            return visibleLavaBeyondHead && air.contains(plan.route().getFirst().head())
                    ? StopReason.FLUID : unsafe;
        }

        @Override public boolean release() {
            if (releaseThrows) throw new IllegalStateException("simulated stuck release");
            if (releaseFailures-- > 0) return false;
            if (activeBreak != null && !confirmedActive) {
                releaseEffects.add(effect(activeBreak, confirmDuringRelease
                        ? AgentActionStore.Verification.CONFIRMED : AgentActionStore.Verification.UNKNOWN));
            }
            activeBreak = null;
            activeMove = null;
            released = true;
            return true;
        }

        @Override public List<KnownConstructionAttempt.EffectDelta> drainEffects() {
            var result = List.copyOf(releaseEffects);
            releaseEffects.clear();
            return result;
        }

        private KnownConstructionAttempt.EffectDelta effect(TunnelGeometry.Cell cell,
                AgentActionStore.Verification verification) {
            return new KnownConstructionAttempt.EffectDelta("block_break", "block:" + cell.dimension() + ":"
                    + cell.x() + "," + cell.y() + "," + cell.z(),
                    Map.of("block", "minecraft:stone"), verification == AgentActionStore.Verification.CONFIRMED
                            ? Map.of("block", "minecraft:air") : Map.of(), verification, tick, revision);
        }
    }
}
