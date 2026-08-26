package dev.aod.mcmcp.routine;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApplyBlockPlanRoutineTest {
    private static final String DIMENSION = "minecraft:overworld";
    private static final BlockStateFingerprint AIR = state("minecraft:air");
    private static final BlockStateFingerprint WATER = state("minecraft:water", "level", "0");
    private static final BlockStateFingerprint STONE = state("minecraft:stone");
    private static final BlockStateFingerprint DIRT = state("minecraft:dirt");
    private static final BlockStateFingerprint LIT_STONE =
            state("minecraft:stone", "lit", "true");
    private static final BlockTarget VERIFY_TARGET = target(0);
    private static final BlockTarget PLACE_TARGET = target(1);
    private static final BlockTarget REPLACE_TARGET = target(2);

    @Test
    void replaceUsesTwoPrivateChildrenAndFinalizesOnlyAfterSameFrameExactVerification() {
        var request = mixedPlan();
        var port = new FakePlanPort(request, Map.of(
                VERIFY_TARGET, AIR,
                PLACE_TARGET, WATER,
                REPLACE_TARGET, STONE));
        var manager = manager(port);
        var id = manager.startApplyBlockPlan(
                UUID.randomUUID().toString(), "canonical-plan", request, 10).routineId();

        driveAllActionsToFinalizing(manager, port);
        var result = manager.getRoutine(id, 0, 128);

        assertThat(result.state()).isEqualTo(RoutineState.FINALIZING);
        assertThat(result.goalVerified()).isTrue();
        assertThat(result.progress()).isEqualTo(new RoutineProgress(3, 3, "cells"));
        assertThat(result.verificationSummary()).isEqualTo(new RoutineVerification(3, 3, 0));
        assertThat(result.checkpoint().seq()).isEqualTo(4); // skip + place + replace break/place
        assertThat(port.dispatchedStages).containsExactly(
                ApplyBlockPlanChildStage.PLACE,
                ApplyBlockPlanChildStage.BREAK,
                ApplyBlockPlanChildStage.PLACE);
        assertThat(result.effects()).extracting(RoutineEffect::type)
                .containsExactly("block_placed", "block_broken", "block_placed");
        assertThat(port.preparationReleaseCount).isEqualTo(3);
        assertThat(port.actionReleaseCount).isEqualTo(3);
        assertThat(manager.activeRoutineId()).contains(id);

        var succeeded = manager.completeFinalization(id, null, 0, 128);
        assertThat(succeeded.state()).isEqualTo(RoutineState.SUCCEEDED);
        assertThat(port.retireCount).isOne();
    }

    @Test
    void verifyOnlyAirIsAcceptedButRequiresExactFullStateAndNeverDispatches() {
        var request = plan(List.of(step(
                "clearance", ApplyBlockPlanOperation.VERIFY_ONLY,
                VERIFY_TARGET, AIR, AIR, Optional.empty())), false);
        var port = new FakePlanPort(request, Map.of(VERIFY_TARGET, AIR));
        var manager = manager(port);
        var id = manager.startApplyBlockPlan(UUID.randomUUID().toString(), request, 10).routineId();

        advance(manager, port);
        advance(manager, port);
        advance(manager, port);
        advance(manager, port);

        assertThat(manager.getRoutine(id, 0, 32).state()).isEqualTo(RoutineState.FINALIZING);
        assertThat(port.preparationCount).isZero();
        assertThat(port.dispatchCount).isZero();

        var exactRequest = plan(List.of(step(
                "exact", ApplyBlockPlanOperation.VERIFY_ONLY,
                VERIFY_TARGET, LIT_STONE, LIT_STONE, Optional.empty())), false);
        var exactPort = new FakePlanPort(exactRequest, Map.of(VERIFY_TARGET, STONE));
        var exactManager = manager(exactPort);
        var exactId = exactManager.startApplyBlockPlan(
                UUID.randomUUID().toString(), exactRequest, 10).routineId();
        advance(exactManager, exactPort);
        advance(exactManager, exactPort);

        assertThat(exactManager.getRoutine(exactId, 0, 32).failure().code())
                .isEqualTo("VERIFY_ONLY_MISMATCH");
    }

    @Test
    void alreadySatisfiedPlacementNeedsNeitherInventorySyncNorDispatch() {
        var request = singlePlace();
        var port = new FakePlanPort(request, Map.of(PLACE_TARGET, STONE));
        port.inventoryServerSynchronized = false;
        var manager = manager(port);
        var id = manager.startApplyBlockPlan(
                UUID.randomUUID().toString(), request, 10).routineId();

        for (int guard = 0; guard < 8
                && manager.getRoutine(id, 0, 8).state() != RoutineState.FINALIZING; guard++) {
            advance(manager, port);
        }

        var result = manager.getRoutine(id, 0, 32);
        assertThat(result.state()).isEqualTo(RoutineState.FINALIZING);
        assertThat(result.goalVerified()).isTrue();
        assertThat(port.preparationCount).isZero();
        assertThat(port.dispatchCount).isZero();
    }

    @Test
    void preflightDefersFutureAimFeasibilityUntilItsOrderedChild() {
        var secondTarget = target(3);
        var request = plan(List.of(
                step("first", ApplyBlockPlanOperation.PLACE,
                        PLACE_TARGET, AIR, STONE, Optional.of("minecraft:stone")),
                step("supported_later", ApplyBlockPlanOperation.PLACE,
                        secondTarget, AIR, STONE, Optional.of("minecraft:stone"))), false);
        var port = new FakePlanPort(request, Map.of(
                PLACE_TARGET, AIR,
                secondTarget, AIR));
        port.blockedAimTargets.add(secondTarget);
        var manager = manager(port);
        var id = manager.startApplyBlockPlan(
                UUID.randomUUID().toString(), request, 10).routineId();

        advance(manager, port); // queued -> preflight
        advance(manager, port); // preflight -> prepare first child

        var snapshot = manager.getRoutine(id, 0, 32);
        assertThat(snapshot.state()).isEqualTo(RoutineState.RUNNING);
        assertThat(snapshot.phase()).isEqualTo("prepare");
        assertThat(snapshot.failure()).isNull();
        assertThat(port.preparationCount).isZero();
    }

    @Test
    void resourceDropAfterPreflightStopsBeforePreparationOrPacketDispatch() {
        var secondTarget = target(3);
        var request = plan(List.of(
                step("first", ApplyBlockPlanOperation.PLACE,
                        PLACE_TARGET, AIR, STONE, Optional.of("minecraft:stone")),
                step("second", ApplyBlockPlanOperation.PLACE,
                        secondTarget, AIR, STONE, Optional.of("minecraft:stone"))), false);
        var port = new FakePlanPort(request, Map.of(
                PLACE_TARGET, AIR,
                secondTarget, AIR));
        var manager = manager(port);
        var id = manager.startApplyBlockPlan(UUID.randomUUID().toString(), request, 10).routineId();
        advance(manager, port); // queued -> preflight
        advance(manager, port); // preflight succeeds with four available stone
        assertThat(manager.getRoutine(id, 0, 32).phase()).isEqualTo("prepare");

        port.inventoryCounts.put("minecraft:stone", 1);
        advance(manager, port);

        var failed = manager.getRoutine(id, 0, 64);
        assertThat(failed.state()).isEqualTo(RoutineState.FAILED);
        assertThat(failed.failure().code()).isEqualTo("RESOURCE_OR_HOTBAR_CHANGED");
        assertThat(failed.failure().expected()).containsEntry("minimum_count", 2);
        assertThat(failed.failure().observed()).containsEntry("available_count", 1);
        assertThat(port.preparationCount).isZero();
        assertThat(port.dispatchCount).isZero();
    }

    @Test
    void rejectsInvalidBoundsPhaseAndDuplicateEntryIdentityAtConstruction() {
        var one = step("same", ApplyBlockPlanOperation.VERIFY_ONLY,
                VERIFY_TARGET, AIR, AIR, Optional.empty());
        var two = step("same", ApplyBlockPlanOperation.VERIFY_ONLY,
                PLACE_TARGET, AIR, AIR, Optional.empty());

        assertThatThrownBy(() -> new ApplyBlockPlanRequest(
                "Bad:Phase", 1, 1, List.of(one), bounds(false, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApplyBlockPlanRequest(
                "phase", 1, 65, List.of(one), bounds(false, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApplyBlockPlanRequest(
                "phase", 1, 1, List.of(one), bounds(false, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not own movement");
        assertThatThrownBy(() -> new ApplyBlockPlanRequest(
                "phase", 1, 1, List.of(one, two), bounds(false, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ids must be unique");
        assertThatThrownBy(() -> new ApplyBlockPlanRequest(
                "phase", 1, 1, List.of(one), bounds(true, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowBreak");
        assertThatThrownBy(() -> step(
                "bad_break", ApplyBlockPlanOperation.BREAK_TO_AIR,
                VERIFY_TARGET, AIR, AIR, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-air");
        assertThatThrownBy(() -> step(
                "noop_place", ApplyBlockPlanOperation.PLACE,
                VERIFY_TARGET, STONE, STONE, Optional.of("minecraft:stone")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no-ops");
        assertThatThrownBy(() -> step(
                "noop_replace", ApplyBlockPlanOperation.REPLACE,
                VERIFY_TARGET, STONE, STONE, Optional.of("minecraft:stone")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no-ops");
    }

    @Test
    void unknownOrUnsynchronizedResourcePreflightFailsBeforePreparation() {
        var request = plan(List.of(step(
                "place", ApplyBlockPlanOperation.PLACE,
                PLACE_TARGET, WATER, STONE, Optional.of("minecraft:stone"))), false);
        var unknownPort = new FakePlanPort(request, Map.of());
        var unknownManager = manager(unknownPort);
        var unknownId = unknownManager.startApplyBlockPlan(
                UUID.randomUUID().toString(), request, 10).routineId();
        advance(unknownManager, unknownPort);
        advance(unknownManager, unknownPort);

        var unknown = unknownManager.getRoutine(unknownId, 0, 32);
        assertThat(unknown.failure().code()).isEqualTo("REQUIRED_CELL_UNKNOWN");
        assertThat(unknown.verificationSummary().unknown()).isOne();
        assertThat(unknownPort.dispatchCount).isZero();

        var syncPort = new FakePlanPort(request, Map.of(PLACE_TARGET, WATER));
        syncPort.inventoryServerSynchronized = false;
        var syncManager = manager(syncPort);
        var syncId = syncManager.startApplyBlockPlan(
                UUID.randomUUID().toString(), request, 10).routineId();
        advance(syncManager, syncPort);
        advance(syncManager, syncPort);

        assertThat(syncManager.getRoutine(syncId, 0, 32).failure().code())
                .isEqualTo("INVENTORY_NOT_SERVER_SYNCHRONIZED");
        assertThat(syncPort.preparationCount).isZero();
    }

    @Test
    void preparationOwnsAimAndSlotAndCancelReleasesAndRetiresExactlyOnce() {
        var request = singlePlace();
        var port = new FakePlanPort(request, Map.of(PLACE_TARGET, WATER));
        port.prepared = false;
        var manager = manager(port);
        var id = manager.startApplyBlockPlan(UUID.randomUUID().toString(), request, 10).routineId();
        runUntilPhase(manager, port, id, "wait_prepare");

        advance(manager, port);
        assertThat(port.maintainPreparationCount).isOne();
        var cancelled = manager.cancelRoutine(id, "operator", 0, 64);

        assertThat(cancelled.state()).isEqualTo(RoutineState.CANCELLED);
        assertThat(port.preparationReleaseCount).isOne();
        assertThat(port.actionReleaseCount).isZero();
        assertThat(port.retireCount).isOne();
        manager.clearSession("disconnect");
        assertThat(port.retireCount).isOne();
    }

    @Test
    void partialAckAndWorldDiffEvidenceWaitsWithoutRedispatchOrBlindRetry() {
        var request = singlePlace();
        var port = new FakePlanPort(request, Map.of(PLACE_TARGET, WATER));
        var manager = manager(port);
        var id = manager.startApplyBlockPlan(UUID.randomUUID().toString(), request, 10).routineId();
        runUntilPhase(manager, port, id, "wait_world_diff");

        port.acknowledged = true;
        port.worldDiff = false;
        advance(manager, port);
        assertThat(manager.getRoutine(id, 0, 32).phase()).isEqualTo("wait_world_diff");
        assertThat(port.maintainActionCount).isOne();

        port.acknowledged = false;
        port.worldDiff = true;
        port.actionAfter = Optional.of(STONE);
        port.states.put(PLACE_TARGET, STONE);
        advance(manager, port);
        assertThat(manager.getRoutine(id, 0, 32).phase()).isEqualTo("wait_world_diff");
        assertThat(port.maintainActionCount).isEqualTo(2);
        assertThat(port.dispatchCount).isOne();

        port.acknowledged = true;
        advance(manager, port);
        assertThat(manager.getRoutine(id, 0, 32).phase()).isEqualTo("fresh_observe");
        assertThat(port.dispatchCount).isOne();
        assertThat(manager.getRoutine(id, 0, 32).diagnostics())
                .containsEntry("blind_retries", 0);
    }

    @Test
    void retryableChildFailureIsExposedAsReplanWithoutRedispatch() {
        var request = singlePlace();
        var port = new FakePlanPort(request, Map.of(PLACE_TARGET, WATER));
        var manager = manager(port);
        var id = manager.startApplyBlockPlan(UUID.randomUUID().toString(), request, 10).routineId();
        runUntilPhase(manager, port, id, "wait_world_diff");
        port.actionFailure = new RoutineFailure(
                RoutineFailure.Category.TRANSIENT, "PACKET_TIMEOUT", true,
                RoutineFailure.Recovery.RETRY, RoutineFailure.Scope.STEP, 1,
                Map.of(), Map.of(), Map.of(), List.of("target"), false);
        advance(manager, port);

        var failed = manager.getRoutine(id, 0, 64);
        assertThat(failed.failure().retryable()).isFalse();
        assertThat(failed.failure().recovery()).isEqualTo(RoutineFailure.Recovery.REPLAN);
        assertThat(port.dispatchCount).isOne();
        assertThat(failed.diagnostics()).containsEntry("blind_retries", 0);
    }

    @Test
    void normalPreparationReleaseFailureBecomesAdapterFailureAndBestEffortReleasesAction() {
        var request = singlePlace();
        var port = new FakePlanPort(request, Map.of(PLACE_TARGET, WATER));
        port.failPreparationRelease = true;
        var manager = manager(port);
        var id = manager.startApplyBlockPlan(UUID.randomUUID().toString(), request, 10).routineId();
        runUntilTerminal(manager, port, id);

        var failed = manager.getRoutine(id, 0, 64);
        assertThat(failed.failure().code()).isEqualTo("PLAN_ACTION_ADAPTER_FAILURE");
        assertThat(port.dispatchCount).isOne();
        assertThat(port.actionReleaseCount).isOne();
        assertThat(port.retireCount).isOne();
    }

    @Test
    void packetBoundaryUnsafeBreakSourceKeepsPreconditionFailureClassification() {
        var request = plan(List.of(step(
                "break", ApplyBlockPlanOperation.BREAK_TO_AIR,
                PLACE_TARGET, STONE, AIR, Optional.empty())), true);
        var port = new FakePlanPort(request, Map.of(PLACE_TARGET, STONE));
        port.unsafeOnDispatch = true;
        var manager = manager(port);
        var id = manager.startApplyBlockPlan(
                UUID.randomUUID().toString(), request, 10).routineId();

        runUntilTerminal(manager, port, id);

        var failed = manager.getRoutine(id, 0, 64);
        assertThat(failed.failure().code()).isEqualTo("UNSAFE_BREAK_SOURCE");
        assertThat(failed.failure().category()).isEqualTo(RoutineFailure.Category.PRECONDITION);
        assertThat(port.dispatchCount).isOne();
        assertThat(port.actionReleaseCount).isZero();
    }

    @Test
    void packetBoundaryUnsafePlacementSupportKeepsPreconditionFailureClassification() {
        var request = singlePlace();
        var port = new FakePlanPort(request, Map.of(PLACE_TARGET, WATER));
        port.unsafePlacementOnDispatch = true;
        var manager = manager(port);
        var id = manager.startApplyBlockPlan(
                UUID.randomUUID().toString(), request, 10).routineId();

        runUntilTerminal(manager, port, id);

        var failed = manager.getRoutine(id, 0, 64);
        assertThat(failed.failure().code()).isEqualTo("UNSAFE_PLACEMENT_SUPPORT");
        assertThat(failed.failure().category()).isEqualTo(RoutineFailure.Category.PRECONDITION);
        assertThat(port.dispatchCount).isOne();
        assertThat(port.actionReleaseCount).isZero();
    }

    @Test
    void normalActionReleaseFailureNeverAdvancesCheckpointOrReportsSuccess() {
        var request = singlePlace();
        var port = new FakePlanPort(request, Map.of(PLACE_TARGET, WATER));
        port.failActionRelease = true;
        var manager = manager(port);
        var id = manager.startApplyBlockPlan(UUID.randomUUID().toString(), request, 10).routineId();
        runUntilPhase(manager, port, id, "wait_world_diff");
        port.confirmCurrentAction();
        advance(manager, port);

        var failed = manager.getRoutine(id, 0, 64);
        assertThat(failed.state()).isEqualTo(RoutineState.FAILED);
        assertThat(failed.failure().code()).isEqualTo("PLAN_ACTION_ADAPTER_FAILURE");
        assertThat(failed.checkpoint().seq()).isZero();
        assertThat(failed.progress().completed()).isZero();
    }

    @Test
    void finalVerificationWaitsForInventorySyncAndRejectsSameFrameWorldDivergence() {
        var request = singlePlace();
        var port = new FakePlanPort(request, Map.of(PLACE_TARGET, WATER));
        var manager = manager(port);
        var id = manager.startApplyBlockPlan(UUID.randomUUID().toString(), request, 10).routineId();
        runUntilPhase(manager, port, id, "wait_world_diff");
        port.confirmCurrentAction();
        advance(manager, port);
        port.inventoryServerSynchronized = false;
        advance(manager, port);

        assertThat(manager.getRoutine(id, 0, 32).phase()).isEqualTo("fresh_observe");
        port.inventoryServerSynchronized = true;
        advance(manager, port);
        assertThat(manager.getRoutine(id, 0, 32).phase()).isEqualTo("final_verify");
        port.states.put(PLACE_TARGET, DIRT);
        advance(manager, port);

        var failed = manager.getRoutine(id, 0, 64);
        assertThat(failed.failure().code()).isEqualTo("WORLD_DIVERGED");
        assertThat(failed.verificationSummary()).isEqualTo(new RoutineVerification(0, 1, 0));
    }

    @Test
    void standLossRejectsCompletedActionBeforeCheckpointingItsWorldDiff() {
        var request = singlePlace();
        var port = new FakePlanPort(request, Map.of(PLACE_TARGET, WATER));
        var manager = manager(port);
        var id = manager.startApplyBlockPlan(UUID.randomUUID().toString(), request, 10).routineId();
        runUntilPhase(manager, port, id, "wait_world_diff");
        port.confirmCurrentAction();
        port.safeStandReady = false;

        advance(manager, port);

        var failed = manager.getRoutine(id, 0, 64);
        assertThat(failed.state()).isEqualTo(RoutineState.FAILED);
        assertThat(failed.failure().code()).isEqualTo("SAFE_STAND_CHANGED");
        assertThat(failed.failure().recovery()).isEqualTo(RoutineFailure.Recovery.REPLAN);
        assertThat(failed.checkpoint().seq()).isZero();
        assertThat(failed.progress().completed()).isZero();
        assertThat(port.actionReleaseCount).isOne();
    }

    @Test
    void standLossFailsClosedDuringExactFinalVerification() {
        var request = singlePlace();
        var port = new FakePlanPort(request, Map.of(PLACE_TARGET, WATER));
        var manager = manager(port);
        var id = manager.startApplyBlockPlan(UUID.randomUUID().toString(), request, 10).routineId();
        runUntilPhase(manager, port, id, "wait_world_diff");
        port.confirmCurrentAction();
        advance(manager, port); // action -> fresh_observe
        advance(manager, port); // fresh exact frame -> final_verify
        assertThat(manager.getRoutine(id, 0, 32).phase()).isEqualTo("final_verify");
        port.safeStandReady = false;

        advance(manager, port);

        var failed = manager.getRoutine(id, 0, 64);
        assertThat(failed.state()).isEqualTo(RoutineState.FAILED);
        assertThat(failed.failure().code()).isEqualTo("SAFE_STAND_CHANGED");
        assertThat(failed.goalVerified()).isFalse();
    }

    @Test
    void reacquiresUnknownGlobalCellsForThreeTicksBeforeStartingTheNextPreparation() {
        var request = mixedPlan();
        var port = new FakePlanPort(request, Map.of(
                VERIFY_TARGET, AIR,
                PLACE_TARGET, WATER,
                REPLACE_TARGET, STONE));
        var manager = manager(port);
        var id = manager.startApplyBlockPlan(UUID.randomUUID().toString(), request, 10).routineId();
        runUntilPhase(manager, port, id, "wait_world_diff");
        port.confirmCurrentAction();
        advance(manager, port); // confirmed place -> fresh global observation before replace
        assertThat(port.preparationCount).isOne();
        port.states.remove(VERIFY_TARGET);

        for (int graceTick = 1; graceTick <= 3; graceTick++) {
            advance(manager, port);
            var waiting = manager.getRoutine(id, 0, 32);
            assertThat(waiting.state()).isEqualTo(RoutineState.WAITING);
            assertThat(waiting.phase()).isEqualTo("fresh_observe");
            assertThat(waiting.failure()).isNull();
            assertThat(port.preparationCount).isOne();
        }

        port.states.put(VERIFY_TARGET, AIR);
        advance(manager, port);
        assertThat(manager.getRoutine(id, 0, 32).phase()).isEqualTo("prepare");
        assertThat(port.preparationCount).isOne();
        advance(manager, port);
        assertThat(manager.getRoutine(id, 0, 32).phase()).isEqualTo("wait_prepare");
        assertThat(port.preparationCount).isEqualTo(2);
    }

    @Test
    void reacquiresUnknownFinalCellsForThreeTicksBeforeExactFinalVerification() {
        var request = singlePlace();
        var port = new FakePlanPort(request, Map.of(PLACE_TARGET, WATER));
        var manager = manager(port);
        var id = manager.startApplyBlockPlan(UUID.randomUUID().toString(), request, 10).routineId();
        runUntilPhase(manager, port, id, "wait_world_diff");
        port.confirmCurrentAction();
        advance(manager, port); // action -> fresh_observe
        advance(manager, port); // exact fresh observation -> final_verify
        assertThat(manager.getRoutine(id, 0, 32).phase()).isEqualTo("final_verify");
        port.states.remove(PLACE_TARGET);

        for (int graceTick = 1; graceTick <= 3; graceTick++) {
            advance(manager, port);
            var waiting = manager.getRoutine(id, 0, 32);
            assertThat(waiting.phase()).isEqualTo("final_verify");
            assertThat(waiting.state()).isEqualTo(RoutineState.VALIDATING);
            assertThat(waiting.failure()).isNull();
        }

        port.states.put(PLACE_TARGET, STONE);
        advance(manager, port);
        var finalizing = manager.getRoutine(id, 0, 64);
        assertThat(finalizing.state()).isEqualTo(RoutineState.FINALIZING);
        assertThat(finalizing.verificationSummary())
                .isEqualTo(new RoutineVerification(1, 1, 0));
    }

    @Test
    void fourthUnknownReacquisitionTickFailsClosed() {
        var request = singlePlace();
        var port = new FakePlanPort(request, Map.of(PLACE_TARGET, WATER));
        var manager = manager(port);
        var id = manager.startApplyBlockPlan(UUID.randomUUID().toString(), request, 10).routineId();
        runUntilPhase(manager, port, id, "wait_world_diff");
        port.confirmCurrentAction();
        advance(manager, port);
        port.states.remove(PLACE_TARGET);

        advance(manager, port);
        advance(manager, port);
        advance(manager, port);
        assertThat(manager.getRoutine(id, 0, 32).failure()).isNull();
        advance(manager, port);

        var failed = manager.getRoutine(id, 0, 64);
        assertThat(failed.state()).isEqualTo(RoutineState.FAILED);
        assertThat(failed.failure().code()).isEqualTo("WORLD_DIVERGED");
        assertThat(failed.failure().evidence())
                .containsEntry("reason", "reacquisition_grace_expired")
                .containsEntry("elapsed_client_ticks", 4)
                .containsEntry("maximum_client_ticks", 3);
    }

    @Test
    void sameTickReentryAndLargeTickGapDuringReacquisitionFailClosed() {
        var request = plan(List.of(step(
                "verified", ApplyBlockPlanOperation.VERIFY_ONLY,
                VERIFY_TARGET, AIR, AIR, Optional.empty())), false);

        var sameTickPort = new FakePlanPort(request, Map.of(VERIFY_TARGET, AIR));
        var sameTickManager = manager(sameTickPort);
        var sameTickId = sameTickManager.startApplyBlockPlan(
                UUID.randomUUID().toString(), request, 10).routineId();
        advance(sameTickManager, sameTickPort);
        advance(sameTickManager, sameTickPort);
        sameTickPort.states.remove(VERIFY_TARGET);
        advance(sameTickManager, sameTickPort); // first contiguous grace tick
        sameTickManager.tick(); // same client tick must not consume another grace unit

        var sameTickFailure = sameTickManager.getRoutine(sameTickId, 0, 64).failure();
        assertThat(sameTickFailure.code()).isEqualTo("WORLD_DIVERGED");
        assertThat(sameTickFailure.evidence())
                .containsEntry("reason", "non_contiguous_reacquisition_tick");

        var gapPort = new FakePlanPort(request, Map.of(VERIFY_TARGET, AIR));
        var gapManager = manager(gapPort);
        var gapId = gapManager.startApplyBlockPlan(
                UUID.randomUUID().toString(), request, 10).routineId();
        advance(gapManager, gapPort);
        advance(gapManager, gapPort);
        gapPort.states.remove(VERIFY_TARGET);
        advance(gapManager, gapPort); // first contiguous grace tick
        gapPort.tick += 2;
        gapPort.revision++;
        gapManager.tick();

        var gapFailure = gapManager.getRoutine(gapId, 0, 64).failure();
        assertThat(gapFailure.code()).isEqualTo("WORLD_DIVERGED");
        assertThat(gapFailure.evidence())
                .containsEntry("reason", "non_contiguous_reacquisition_tick");
    }

    @Test
    void alreadyVerifiedCellDivergenceStopsBeforeTheNextChildDispatch() {
        var request = mixedPlan();
        var port = new FakePlanPort(request, Map.of(
                VERIFY_TARGET, AIR,
                PLACE_TARGET, WATER,
                REPLACE_TARGET, STONE));
        var manager = manager(port);
        var id = manager.startApplyBlockPlan(UUID.randomUUID().toString(), request, 10).routineId();
        advance(manager, port); // queued -> preflight
        advance(manager, port); // preflight skips verify-only and selects first child
        port.states.remove(VERIFY_TARGET);
        advance(manager, port);

        var failed = manager.getRoutine(id, 0, 64);
        assertThat(failed.failure().code()).isEqualTo("WORLD_DIVERGED");
        assertThat(failed.failure().observed()).containsEntry("currentness", "unknown");
        assertThat(port.preparationCount).isZero();
        assertThat(port.dispatchCount).isZero();
    }

    @Test
    void hardDeadlineFailsAndReleasesAnyOwnedPreparation() {
        var request = singlePlace();
        var port = new FakePlanPort(request, Map.of(PLACE_TARGET, WATER));
        port.prepared = false;
        var manager = manager(port);
        var id = manager.startApplyBlockPlan(UUID.randomUUID().toString(), request, 10).routineId();
        runUntilPhase(manager, port, id, "wait_prepare");
        port.tick = 30;
        port.revision++;
        manager.tick();

        var failed = manager.getRoutine(id, 0, 32);
        assertThat(failed.failure().code()).isEqualTo("HARD_DEADLINE_EXPIRED");
        assertThat(port.preparationReleaseCount).isOne();
        assertThat(port.retireCount).isOne();
    }

    private static void driveAllActionsToFinalizing(RoutineManager manager, FakePlanPort port) {
        for (int guard = 0; guard < 80; guard++) {
            var id = manager.activeRoutineId().orElseThrow();
            var snapshot = manager.getRoutine(id, 0, 8);
            if (snapshot.state() == RoutineState.FINALIZING) {
                return;
            }
            if (snapshot.phase().equals("wait_world_diff")) {
                port.confirmCurrentAction();
            }
            advance(manager, port);
        }
        throw new AssertionError("plan did not reach finalizing");
    }

    private static void runUntilPhase(
            RoutineManager manager, FakePlanPort port, UUID id, String phase) {
        for (int guard = 0; guard < 30; guard++) {
            if (manager.getRoutine(id, 0, 8).phase().equals(phase)) {
                return;
            }
            advance(manager, port);
        }
        throw new AssertionError("plan did not reach phase " + phase);
    }

    private static void runUntilTerminal(RoutineManager manager, FakePlanPort port, UUID id) {
        for (int guard = 0; guard < 30; guard++) {
            if (manager.getRoutine(id, 0, 8).state().terminal()) {
                return;
            }
            advance(manager, port);
        }
        throw new AssertionError("plan did not terminate");
    }

    private static void advance(RoutineManager manager, FakePlanPort port) {
        port.tick++;
        port.revision++;
        manager.tick();
    }

    private static RoutineManager manager(FakePlanPort port) {
        return new RoutineManager(
                new UnusedStationaryPort(), null, port,
                256, 128, 12_000, UUID::randomUUID);
    }

    private static ApplyBlockPlanRequest mixedPlan() {
        return new ApplyBlockPlanRequest(
                "local_phase", 2, 4,
                List.of(
                        step("clearance", ApplyBlockPlanOperation.VERIFY_ONLY,
                                VERIFY_TARGET, AIR, AIR, Optional.empty()),
                        step("foundation", ApplyBlockPlanOperation.PLACE,
                                PLACE_TARGET, WATER, STONE, Optional.of("minecraft:stone")),
                        step("surface", ApplyBlockPlanOperation.REPLACE,
                                REPLACE_TARGET, STONE, DIRT, Optional.of("minecraft:dirt"))),
                bounds(true, 0));
    }

    private static ApplyBlockPlanRequest singlePlace() {
        return plan(List.of(step(
                "foundation", ApplyBlockPlanOperation.PLACE,
                PLACE_TARGET, WATER, STONE, Optional.of("minecraft:stone"))), false);
    }

    private static ApplyBlockPlanRequest plan(List<ApplyBlockPlanStep> steps, boolean allowBreak) {
        return new ApplyBlockPlanRequest("phase", 1, 1, steps, bounds(allowBreak, 0));
    }

    private static ApplyBlockPlanStep step(
            String id,
            ApplyBlockPlanOperation operation,
            BlockTarget target,
            BlockStateFingerprint before,
            BlockStateFingerprint after,
            Optional<String> item) {
        return new ApplyBlockPlanStep(id, operation, target, before, after, item);
    }

    private static ActionBounds bounds(boolean allowBreak, int travel) {
        return new ActionBounds(
                DIMENSION, target(-4), target(4), travel, 1, allowBreak);
    }

    private static BlockTarget target(int x) {
        return new BlockTarget(DIMENSION, x, 64, 0);
    }

    private static BlockStateFingerprint state(String id, String... property) {
        return new BlockStateFingerprint(id,
                property.length == 0 ? Map.of() : Map.of(property[0], property[1]));
    }

    private static final class FakePlanPort implements ApplyBlockPlanPort {
        private final ApplyBlockPlanRequest request;
        private final Map<BlockTarget, BlockStateFingerprint> states = new LinkedHashMap<>();
        private long tick = 10;
        private long revision = 1;
        private boolean inventoryServerSynchronized = true;
        private boolean safeStandReady = true;
        private boolean prepared = true;
        private boolean acknowledged;
        private boolean worldDiff;
        private Optional<BlockStateFingerprint> actionAfter = Optional.empty();
        private boolean failPreparationRelease;
        private boolean failActionRelease;
        private boolean unsafeOnDispatch;
        private boolean unsafePlacementOnDispatch;
        private RoutineFailure actionFailure;
        private final Set<BlockTarget> blockedAimTargets = new java.util.HashSet<>();
        private final Map<String, Integer> inventoryCounts = new LinkedHashMap<>(
                Map.of("minecraft:stone", 4, "minecraft:dirt", 4));
        private final Set<String> hotbarItems = new java.util.HashSet<>(
                Set.of("minecraft:stone", "minecraft:dirt"));
        private int preparationCount;
        private int maintainPreparationCount;
        private int preparationReleaseCount;
        private int dispatchCount;
        private int maintainActionCount;
        private int actionReleaseCount;
        private int retireCount;
        private ApplyBlockPlanChildAction currentChild;
        private ApplyBlockPlanPreparationAttempt preparation;
        private ApplyBlockPlanActionAttempt action;
        private final List<ApplyBlockPlanChildStage> dispatchedStages = new java.util.ArrayList<>();

        private FakePlanPort(
                ApplyBlockPlanRequest request,
                Map<BlockTarget, BlockStateFingerprint> initialStates) {
            this.request = request;
            states.putAll(initialStates);
        }

        @Override
        public ApplyBlockPlanFrame observe(ApplyBlockPlanRequest observedRequest) {
            assertThat(observedRequest).isSameAs(request);
            var cells = new LinkedHashMap<BlockTarget, ApplyBlockPlanCellObservation>();
            for (var step : request.steps()) {
                var live = Optional.ofNullable(states.get(step.target()));
                boolean replaceable = live
                        .map(value -> value.blockId().equals("minecraft:air")
                                || value.blockId().equals("minecraft:water"))
                        .orElse(false);
                cells.put(step.target(), new ApplyBlockPlanCellObservation(
                        step.target(), live, replaceable, safeStandReady,
                        !blockedAimTargets.contains(step.target())));
            }
            return new ApplyBlockPlanFrame(
                    tick, revision,
                    true, true, true, true, true, true,
                    inventoryServerSynchronized,
                    cells,
                    inventoryCounts,
                    hotbarItems);
        }

        @Override
        public ApplyBlockPlanPreparationAttempt beginPreparation(
                ApplyBlockPlanRequest observedRequest,
                ApplyBlockPlanChildAction child,
                long deadline) {
            preparationCount++;
            currentChild = child;
            preparation = new ApplyBlockPlanPreparationAttempt(
                    UUID.randomUUID(), child.stepIndex(), tick, revision, deadline);
            return preparation;
        }

        @Override
        public void maintainPreparation(ApplyBlockPlanPreparationAttempt attempt) {
            maintainPreparationCount++;
        }

        @Override
        public ApplyBlockPlanPreparationEvidence preparationEvidence(
                ApplyBlockPlanPreparationAttempt attempt) {
            var live = Optional.ofNullable(states.get(currentChild.target()));
            boolean replaceable = live
                    .map(value -> value.blockId().equals("minecraft:air")
                            || value.blockId().equals("minecraft:water"))
                    .orElse(false);
            return new ApplyBlockPlanPreparationEvidence(
                    attempt.attemptId(), tick, revision, live, replaceable,
                    prepared, prepared, prepared, null);
        }

        @Override
        public void releasePreparation(ApplyBlockPlanPreparationAttempt attempt) {
            preparationReleaseCount++;
            preparation = null;
            if (failPreparationRelease) {
                throw new IllegalStateException("restore aim/slot failed");
            }
        }

        @Override
        public ApplyBlockPlanActionAttempt dispatchPrepared(
                ApplyBlockPlanRequest observedRequest,
                ApplyBlockPlanChildAction child,
                ApplyBlockPlanPreparationAttempt preparationAttempt,
                long deadline) {
            dispatchCount++;
            if (unsafeOnDispatch) {
                throw new SafeBreakSourcePolicy.UnsafeBreakSourceException();
            }
            if (unsafePlacementOnDispatch) {
                throw new SafePlacementSupportPolicy.UnsafePlacementSupportException();
            }
            currentChild = child;
            acknowledged = false;
            worldDiff = false;
            actionAfter = Optional.empty();
            action = new ApplyBlockPlanActionAttempt(
                    UUID.randomUUID(), child.stepIndex(), tick, revision, deadline);
            dispatchedStages.add(child.stage());
            return action;
        }

        @Override
        public void maintainAction(ApplyBlockPlanActionAttempt attempt) {
            maintainActionCount++;
        }

        @Override
        public ApplyBlockPlanActionEvidence actionEvidence(ApplyBlockPlanActionAttempt attempt) {
            return new ApplyBlockPlanActionEvidence(
                    attempt.attemptId(), tick, revision,
                    acknowledged, worldDiff, actionAfter,
                    Map.of("server_packet", acknowledged, "world_diff", worldDiff), actionFailure);
        }

        @Override
        public void releaseAction(ApplyBlockPlanActionAttempt attempt) {
            actionReleaseCount++;
            action = null;
            if (failActionRelease) {
                throw new IllegalStateException("action release failed");
            }
        }

        @Override
        public void retire(ApplyBlockPlanRequest retiredRequest) {
            retireCount++;
        }

        private void confirmCurrentAction() {
            acknowledged = true;
            worldDiff = true;
            states.put(currentChild.target(), currentChild.expectedAfter());
            actionAfter = Optional.of(currentChild.expectedAfter());
        }
    }

    private static final class UnusedStationaryPort implements StationaryBreakPort {
        @Override public StationaryBreakFrame observe(StationaryBreakRequest request) {
            throw new UnsupportedOperationException();
        }
        @Override public AttackAttempt beginAttack(StationaryBreakRequest request, long deadline) {
            throw new UnsupportedOperationException();
        }
        @Override public void holdAttack(AttackAttempt attempt) { }
        @Override public void stopAttackInput(AttackAttempt attempt) { }
        @Override public PredictionEvidence predictionEvidence(AttackAttempt attempt) {
            throw new UnsupportedOperationException();
        }
        @Override public void releaseAttack(AttackAttempt attempt) { }
        @Override public void retire(StationaryBreakRequest request) { }
    }
}
