package dev.aodaruma.craftagent.routine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticActionRoutineTest {
    private static final String DIMENSION = "minecraft:overworld";
    private static final BlockTarget TARGET = new BlockTarget(DIMENSION, 1, 64, 2);
    private static final BlockStateFingerprint STONE =
            new BlockStateFingerprint("minecraft:stone", Map.of());
    private static final BlockStateFingerprint AIR =
            new BlockStateFingerprint("minecraft:air", Map.of());
    private static final BlockStateFingerprint LEVER_OFF =
            new BlockStateFingerprint("minecraft:lever", Map.of("powered", "false"));
    private static final BlockStateFingerprint LEVER_ON =
            new BlockStateFingerprint("minecraft:lever", Map.of("powered", "true"));

    @Test
    void allFiveKindsReachVerifiedSuccessAndReleaseTheirAttempt() {
        var requests = List.<SemanticActionRequest>of(
                breakRequest(),
                placeRequest(),
                interactBlockRequest(),
                entityRequest(),
                navigateRequest());

        for (var request : requests) {
            var port = new FakePort();
            if (request instanceof InteractBlockRequest) {
                port.blockState = Optional.of(LEVER_OFF);
            }
            var manager = manager(port);
            var receipt = manager.startSemanticAction(
                    UUID.randomUUID().toString(), request, 10);

            runToDispatch(manager, port);
            if (request instanceof NavigateToRequest navigation) {
                port.playerX = navigation.target().x() + 0.5D;
                port.playerY = navigation.target().y();
                port.playerZ = navigation.target().z() + 0.5D;
                advance(manager, port); // move -> settle
                port.confirmReconciled();
                for (int tick = 0; tick < 10; tick++) {
                    advance(manager, port);
                }
                advance(manager, port); // verify -> finalizing
            } else {
                if (request instanceof InteractEntityRequest) {
                    port.confirmInventory(1);
                } else {
                    port.confirmBlock(expectedAfter(request));
                }
                advance(manager, port); // sync -> verify
                advance(manager, port); // verify -> finalizing
            }
            var finalizing = manager.getRoutine(receipt.routineId(), 0, 64);

            assertThat(finalizing.state()).as(request.kind()).isEqualTo(RoutineState.FINALIZING);
            assertThat(finalizing.goalVerified()).isTrue();
            assertThat(finalizing.verificationSummary())
                    .isEqualTo(new RoutineVerification(1, 1, 0));
            assertThat(port.releaseCount).isEqualTo(1);

            var succeeded = manager.completeFinalization(receipt.routineId(), null, 0, 64);
            assertThat(succeeded.state()).isEqualTo(RoutineState.SUCCEEDED);
        }
    }

    @Test
    void alreadySatisfiedFiniteActionGoalsDispatchNothing() {
        var cases = List.of(breakRequest(), entityRequest());
        for (var request : cases) {
            var port = new FakePort();
            if (request instanceof BreakBlockRequest) {
                port.blockState = Optional.of(AIR);
            } else if (request instanceof InteractEntityRequest) {
                port.goalItemCount = 1;
            }
            var manager = manager(port);
            var id = manager.startSemanticAction(
                    UUID.randomUUID().toString(), request, 10).routineId();

            advance(manager, port);
            advance(manager, port);

            assertThat(manager.getRoutine(id, 0, 32).state()).isEqualTo(RoutineState.FINALIZING);
            assertThat(port.dispatchCount).isZero();
            assertThat(port.releaseCount).isZero();
        }
    }

    @Test
    void blockAndEntityInteractionPreconditionsFailBeforeAnyDispatch() {
        for (var request : List.<SemanticActionRequest>of(interactBlockRequest(), entityRequest())) {
            var port = new FakePort();
            if (request instanceof InteractBlockRequest) {
                port.blockState = Optional.of(LEVER_OFF);
                port.blockInReach = false;
            } else {
                port.entityInReach = false;
            }
            var manager = manager(port);
            var id = manager.startSemanticAction(
                    UUID.randomUUID().toString(), request, 10).routineId();

            advance(manager, port); // queued -> precheck
            advance(manager, port); // precheck -> failed

            var failed = manager.getRoutine(id, 0, 32);
            assertThat(failed.state()).isEqualTo(RoutineState.FAILED);
            assertThat(failed.failure().code()).isEqualTo(request instanceof InteractBlockRequest
                    ? "BLOCK_NOT_INTERACTABLE" : "ENTITY_NOT_INTERACTABLE");
            assertThat(port.dispatchCount).isZero();
        }
    }

    @Test
    void resolvedEntityTypeAndAdultAllowlistProduceExactFailuresBeforeDispatch() {
        var wrongType = new FakePort();
        wrongType.entityType = Optional.of("minecraft:pig");
        wrongType.entityInReach = false;
        var wrongTypeManager = manager(wrongType);
        var wrongTypeId = wrongTypeManager.startSemanticAction(
                UUID.randomUUID().toString(), entityRequest(), 10).routineId();

        advance(wrongTypeManager, wrongType); // queued -> precheck
        advance(wrongTypeManager, wrongType); // precheck -> failed

        assertThat(wrongTypeManager.getRoutine(wrongTypeId, 0, 32).failure().code())
                .isEqualTo("ENTITY_TYPE_MISMATCH");
        assertThat(wrongType.dispatchCount).isZero();

        // The Minecraft adapter represents a visible baby cow as resolved/type-matched but not
        // interactable, allowing the domain layer to distinguish it from a stale opaque ref.
        var babyCow = new FakePort();
        babyCow.entityInReach = false;
        var babyManager = manager(babyCow);
        var babyId = babyManager.startSemanticAction(
                UUID.randomUUID().toString(), entityRequest(), 10).routineId();

        advance(babyManager, babyCow); // queued -> precheck
        advance(babyManager, babyCow); // precheck -> failed

        assertThat(babyManager.getRoutine(babyId, 0, 32).failure().code())
                .isEqualTo("ENTITY_NOT_INTERACTABLE");
        assertThat(babyCow.dispatchCount).isZero();
    }

    @Test
    void dispatchMayCaptureANewerObservationRevisionInTheSameClientTick() {
        var port = new FakePort();
        port.advanceRevisionOnDispatch = true;
        var manager = manager(port);
        var id = manager.startSemanticAction(
                UUID.randomUUID().toString(), breakRequest(), 10).routineId();

        runToDispatch(manager, port);

        assertThat(manager.getRoutine(id, 0, 32).state()).isEqualTo(RoutineState.RUNNING);
        assertThat(port.dispatchCount).isEqualTo(1);
        assertThat(port.releaseCount).isZero();
    }

    @Test
    void initiallySatisfiedNavigationStillRequiresReconciledSettleAttempt() {
        var port = new FakePort();
        var request = navigateRequest();
        port.playerX = request.target().x() + 0.5D;
        port.playerY = request.target().y();
        port.playerZ = request.target().z() + 0.5D;
        var manager = manager(port);
        var id = manager.startSemanticAction(
                UUID.randomUUID().toString(), request, 10).routineId();

        runToDispatch(manager, port);
        advance(manager, port); // move -> settle, with input stopped but attempt retained
        for (int tick = 0; tick < 12; tick++) {
            advance(manager, port);
        }
        assertThat(manager.getRoutine(id, 0, 32).phase()).isEqualTo("settle");
        assertThat(port.dispatchCount).isEqualTo(1);
        assertThat(port.releaseCount).isZero();

        port.confirmReconciled();
        advance(manager, port);
        advance(manager, port);

        var result = manager.getRoutine(id, 0, 64);
        assertThat(result.state()).isEqualTo(RoutineState.FINALIZING);
        assertThat(result.diagnostics())
                .containsEntry("reconciliation_acknowledged", true);
        assertThat(port.releaseCount).isEqualTo(1);
    }

    @Test
    void navigationReportsTheFailClosedLiveVisibilityReasonWithoutDispatching() {
        var port = new FakePort();
        port.routeSafe = false;
        port.routeReason = "probe_not_currently_visible";
        var manager = manager(port);
        var id = manager.startSemanticAction(
                UUID.randomUUID().toString(), navigateRequest(), 10).routineId();

        advance(manager, port); // queued -> precheck
        advance(manager, port); // precheck -> failed

        var failed = manager.getRoutine(id, 0, 32);
        assertThat(failed.failure().code()).isEqualTo("ROUTE_NOT_SAFE");
        assertThat(failed.failure().evidence())
                .containsEntry("route_check_reason", "probe_not_currently_visible");
        assertThat(port.dispatchCount).isZero();
    }

    @Test
    void activeNavigationVisibilityGracePausesWithoutStoppingOrVerifying() {
        var port = new FakePort();
        var manager = manager(port);
        var id = manager.startSemanticAction(
                UUID.randomUUID().toString(), navigateRequest(), 10).routineId();
        runToDispatch(manager, port);

        port.routeReason = SemanticActionFrame.PROBE_VISIBILITY_GRACE;
        advance(manager, port);

        var paused = manager.getRoutine(id, 0, 32);
        assertThat(paused.phase()).isEqualTo("move");
        assertThat(port.maintainCount).isEqualTo(1);
        assertThat(port.stopCount).isZero();
        assertThat(port.releaseCount).isZero();
    }

    @Test
    void navigationInsideToleranceStopsInsteadOfWaitingForVisibilityGrace() {
        var port = new FakePort();
        var request = navigateRequest();
        var manager = manager(port);
        var id = manager.startSemanticAction(
                UUID.randomUUID().toString(), request, 10).routineId();
        runToDispatch(manager, port);
        port.playerX = request.target().x() + 0.5D;
        port.playerY = request.target().y();
        port.playerZ = request.target().z() + 0.5D;
        port.routeReason = SemanticActionFrame.PROBE_VISIBILITY_GRACE;

        advance(manager, port);

        assertThat(manager.getRoutine(id, 0, 32).phase()).isEqualTo("settle");
        assertThat(port.stopCount).isOne();
        assertThat(port.maintainCount).isZero();
        assertThat(port.releaseCount).isZero();
    }

    @Test
    void activeNavigationFailsAfterTheBoundedVisibilityGrace() {
        var port = new FakePort();
        var manager = manager(port);
        var id = manager.startSemanticAction(
                UUID.randomUUID().toString(), navigateRequest(), 10).routineId();
        runToDispatch(manager, port);

        port.routeReason = SemanticActionFrame.PROBE_VISIBILITY_GRACE;
        for (int tick = 0; tick < MinecraftSemanticActionPort.MAX_PROBE_VISIBILITY_GRACE_TICKS; tick++) {
            advance(manager, port);
        }
        assertThat(manager.getRoutine(id, 0, 32).phase()).isEqualTo("move");
        assertThat(port.releaseCount).isZero();

        port.routeSafe = false;
        port.routeReason = "probe_not_currently_visible";
        advance(manager, port);

        var failed = manager.getRoutine(id, 0, 32);
        assertThat(failed.failure().code()).isEqualTo("ROUTE_BECAME_UNSAFE");
        assertThat(failed.failure().evidence())
                .containsEntry("route_check_reason", "probe_not_currently_visible");
        assertThat(port.releaseCount).isOne();
    }

    @Test
    void activeNavigationDoesNotGraceAnyOtherUnsafeReason() {
        var port = new FakePort();
        var manager = manager(port);
        var id = manager.startSemanticAction(
                UUID.randomUUID().toString(), navigateRequest(), 10).routineId();
        runToDispatch(manager, port);

        port.routeSafe = false;
        port.routeReason = "feet_hazard";
        advance(manager, port);

        var failed = manager.getRoutine(id, 0, 32);
        assertThat(failed.failure().code()).isEqualTo("ROUTE_BECAME_UNSAFE");
        assertThat(failed.failure().evidence())
                .containsEntry("route_check_reason", "feet_hazard");
        assertThat(port.releaseCount).isOne();
    }

    @Test
    void navigationVisibilityGraceInterruptsCompletedSettleBeforeVerification() {
        var port = new FakePort();
        var request = navigateRequest();
        var manager = manager(port);
        var id = manager.startSemanticAction(
                UUID.randomUUID().toString(), request, 10).routineId();
        runToDispatch(manager, port);
        port.playerX = request.target().x() + 0.5D;
        port.playerY = request.target().y();
        port.playerZ = request.target().z() + 0.5D;
        advance(manager, port); // move -> settle
        port.confirmReconciled();
        for (int tick = 0; tick < 10; tick++) {
            advance(manager, port);
        }
        assertThat(manager.getRoutine(id, 0, 32).phase()).isEqualTo("verify");

        port.routeReason = SemanticActionFrame.PROBE_VISIBILITY_GRACE;
        advance(manager, port);

        assertThat(manager.getRoutine(id, 0, 32).phase()).isEqualTo("settle");
        assertThat(port.releaseCount).isZero();
        port.routeReason = "safe";
        advance(manager, port);
        assertThat(manager.getRoutine(id, 0, 32).phase()).isEqualTo("settle");
    }

    @Test
    void stationaryNavigationDriftReturnsThroughLiveRoutePrecheckBeforeRedispatch() {
        var port = new FakePort();
        var request = navigateRequest();
        var manager = manager(port);
        var id = manager.startSemanticAction(
                UUID.randomUUID().toString(), request, 10).routineId();
        runToDispatch(manager, port);
        port.playerX = request.target().x() + 0.5D;
        port.playerY = request.target().y();
        port.playerZ = request.target().z() + 0.5D;
        advance(manager, port); // move -> settle and close movement input

        port.routeReason = SemanticActionFrame.STATIONARY_NAVIGATION;
        port.playerX -= 1.0D;
        advance(manager, port); // settle -> retry_fresh_observation
        assertThat(manager.getRoutine(id, 0, 32).phase())
                .isEqualTo("retry_fresh_observation");
        assertThat(port.releaseCount).isOne();

        advance(manager, port); // later tick -> precheck
        port.routeSafe = false;
        port.routeReason = "probe_not_currently_visible";
        advance(manager, port); // fresh precheck must fail before a second dispatch

        var failed = manager.getRoutine(id, 0, 32);
        assertThat(failed.failure().code()).isEqualTo("ROUTE_NOT_SAFE");
        assertThat(port.dispatchCount).isOne();
    }

    @Test
    void blockVerificationRequiresBothAckAndMatchingRawServerState() {
        var port = new FakePort();
        var manager = manager(port);
        var id = manager.startSemanticAction(
                UUID.randomUUID().toString(), breakRequest(), 10).routineId();
        runToDispatch(manager, port);

        port.setEvidence(true, Optional.empty(), false, 0, null, false);
        advance(manager, port);
        assertThat(manager.getRoutine(id, 0, 32).phase()).isEqualTo("wait_server_sync");

        port.setEvidence(false, Optional.of(AIR), false, 0, null, false);
        advance(manager, port);
        assertThat(manager.getRoutine(id, 0, 32).phase()).isEqualTo("wait_server_sync");

        port.confirmBlock(AIR);
        advance(manager, port);
        advance(manager, port);

        assertThat(manager.getRoutine(id, 0, 32).state()).isEqualTo(RoutineState.FINALIZING);
        assertThat(port.releaseCount).isEqualTo(1);
    }

    @Test
    void unknownPreconditionAndMismatchingServerStateNeverSucceed() {
        var unknownPort = new FakePort();
        unknownPort.blockState = Optional.empty();
        var unknownManager = manager(unknownPort);
        var unknownId = unknownManager.startSemanticAction(
                UUID.randomUUID().toString(), breakRequest(), 10).routineId();
        advance(unknownManager, unknownPort);
        advance(unknownManager, unknownPort);

        var unknown = unknownManager.getRoutine(unknownId, 0, 32);
        assertThat(unknown.state()).isEqualTo(RoutineState.FAILED);
        assertThat(unknown.failure().code()).isEqualTo("TARGET_NOT_CURRENTLY_OBSERVABLE");
        assertNeedsReplanBeforeFailed(unknown);

        var mismatchPort = new FakePort();
        var mismatchManager = manager(mismatchPort);
        var mismatchId = mismatchManager.startSemanticAction(
                UUID.randomUUID().toString(), breakRequest(), 10).routineId();
        runToDispatch(mismatchManager, mismatchPort);
        mismatchPort.confirmBlock(new BlockStateFingerprint("minecraft:dirt", Map.of()));
        advance(mismatchManager, mismatchPort);
        advance(mismatchManager, mismatchPort);

        var mismatch = mismatchManager.getRoutine(mismatchId, 0, 64);
        assertThat(mismatch.state()).isEqualTo(RoutineState.FAILED);
        assertThat(mismatch.failure().code()).isEqualTo("POSTCONDITION_MISMATCH");
        assertNeedsReplanBeforeFailed(mismatch);
    }

    @Test
    void safeRetryReleasesThenRequiresFreshTickAndRevisionBeforeRedispatch() {
        var port = new FakePort();
        var manager = manager(port);
        var id = manager.startSemanticAction(
                UUID.randomUUID().toString(), breakRequest(), 10).routineId();
        runToDispatch(manager, port);
        port.transientRetryFailure();

        advance(manager, port); // failure -> retry wait, release first attempt
        assertThat(manager.getRoutine(id, 0, 32).phase()).isEqualTo("retry_fresh_observation");
        assertThat(port.releaseCount).isEqualTo(1);

        port.clientTick++; // revision deliberately stale
        manager.tick();
        assertThat(port.dispatchCount).isEqualTo(1);

        port.observationRevision++;
        manager.tick(); // fresh -> precheck only
        assertThat(port.dispatchCount).isEqualTo(1);
        advance(manager, port); // precheck -> execute
        advance(manager, port); // execute -> dispatch
        assertThat(port.dispatchCount).isEqualTo(2);

        port.confirmBlock(AIR);
        advance(manager, port);
        advance(manager, port);
        assertThat(manager.getRoutine(id, 0, 64).state()).isEqualTo(RoutineState.FINALIZING);
        assertThat(port.releaseCount).isEqualTo(2);
    }

    @Test
    void entityDispatchFailureIsNeverRetriedEvenWhenAdapterMarksItSafe() {
        var port = new FakePort();
        var manager = manager(port);
        var id = manager.startSemanticAction(
                UUID.randomUUID().toString(), entityRequest(), 10).routineId();
        runToDispatch(manager, port);
        port.transientRetryFailure();

        advance(manager, port);

        var failed = manager.getRoutine(id, 0, 64);
        assertThat(failed.state()).isEqualTo(RoutineState.FAILED);
        assertThat(failed.failure().recovery()).isEqualTo(RoutineFailure.Recovery.REPLAN);
        assertThat(port.dispatchCount).isEqualTo(1);
        assertThat(port.releaseCount).isEqualTo(1);
        assertNeedsReplanBeforeFailed(failed);
    }

    @Test
    void retryRequiresBothEvidenceAndCurrentFrameToBeExplicitlySafe() {
        var port = new FakePort();
        var manager = manager(port);
        var id = manager.startSemanticAction(
                UUID.randomUUID().toString(), breakRequest(), 10).routineId();
        runToDispatch(manager, port);
        port.safeToRetry = false;
        port.transientRetryFailure();

        advance(manager, port);

        var failed = manager.getRoutine(id, 0, 64);
        assertThat(failed.state()).isEqualTo(RoutineState.FAILED);
        assertThat(failed.failure().recovery()).isEqualTo(RoutineFailure.Recovery.REPLAN);
        assertThat(port.dispatchCount).isEqualTo(1);
        assertThat(port.releaseCount).isEqualTo(1);
        assertNeedsReplanBeforeFailed(failed);
    }

    @Test
    void navigateRetriesExplicitSafeTransientFailureAndRequiresStabilityPlusAck() {
        var port = new FakePort();
        var manager = manager(port);
        var request = navigateRequest();
        var id = manager.startSemanticAction(
                UUID.randomUUID().toString(), request, 10).routineId();
        runToDispatch(manager, port);

        port.transientRetryFailure();
        advance(manager, port);
        assertThat(manager.getRoutine(id, 0, 32).phase()).isEqualTo("retry_fresh_observation");
        assertThat(port.releaseCount).isEqualTo(1);

        port.clientTick++; // navigation freshness is the new live-position tick, not WorldMemory
        manager.tick();
        assertThat(manager.getRoutine(id, 0, 32).phase()).isEqualTo("precheck");
        advance(manager, port); // precheck -> execute
        advance(manager, port); // second dispatch
        port.playerX = request.target().x() + 0.5D;
        port.playerY = request.target().y();
        port.playerZ = request.target().z() + 0.5D;
        advance(manager, port); // settle begins
        port.horizontalVelocitySquared = 0.04D;
        for (int tick = 0; tick < 4; tick++) {
            advance(manager, port);
        }
        assertThat(manager.getRoutine(id, 0, 32).phase()).isEqualTo("settle");
        port.horizontalVelocitySquared = 0.0D;
        for (int tick = 0; tick < 5; tick++) {
            advance(manager, port);
        }
        port.playerX += 0.2D; // still in tolerance, but outside the settle drift envelope
        advance(manager, port); // consecutive count resets and anchor is recaptured
        for (int tick = 0; tick < 9; tick++) {
            advance(manager, port);
        }
        assertThat(manager.getRoutine(id, 0, 32).phase()).isEqualTo("settle");
        advance(manager, port); // tenth domain-stable tick, but no adapter ACK
        assertThat(manager.getRoutine(id, 0, 32).phase()).isEqualTo("settle");
        assertThat(port.releaseCount).isEqualTo(1);
        port.confirmReconciled();
        advance(manager, port); // adapter ACK plus continued stability -> verify
        advance(manager, port); // finalizing

        var result = manager.getRoutine(id, 0, 64);
        assertThat(result.state()).isEqualTo(RoutineState.FINALIZING);
        assertThat(result.diagnostics()).containsEntry("verification_basis", "server_reconciled");
        assertThat(port.dispatchCount).isEqualTo(2);
        assertThat(port.releaseCount).isEqualTo(2);
    }

    @Test
    void navigationPositionRotationAndMotionCorrectionsAreObservedBeforeSuccess() {
        var correctionCodes = List.of(
                "SERVER_POSITION_CORRECTION",
                "SERVER_ROTATION_CORRECTION",
                "SERVER_MOTION_APPLIED");

        for (var correctionCode : correctionCodes) {
            var port = new FakePort();
            var manager = manager(port);
            var request = navigateRequest();
            var id = manager.startSemanticAction(
                    UUID.randomUUID().toString(), request, 10).routineId();
            runToDispatch(manager, port);
            port.playerX = request.target().x() + 0.5D;
            port.playerY = request.target().y();
            port.playerZ = request.target().z() + 0.5D;
            advance(manager, port); // retain the active attempt in settle

            port.reconciliationFailure(correctionCode);
            advance(manager, port);

            var failed = manager.getRoutine(id, 0, 64);
            assertThat(failed.state()).as(correctionCode).isEqualTo(RoutineState.FAILED);
            assertThat(failed.failure().code()).isEqualTo(correctionCode);
            assertThat(failed.failure().evidence())
                    .containsEntry("source", correctionCode.toLowerCase());
            assertThat(port.releaseCount).isEqualTo(1);
            assertNeedsReplanBeforeFailed(failed);
        }
    }

    @Test
    void renewableInputWatchdogsDoNotBecomeFixedFortyTickActionTimeouts() {
        var blockPort = new FakePort();
        var blockManager = manager(blockPort);
        var blockId = blockManager.startSemanticAction(
                UUID.randomUUID().toString(), breakRequest(), 10).routineId();
        runToDispatch(blockManager, blockPort);
        assertThat(blockPort.currentAttempt.leaseExpiresAtClientTick())
                .isGreaterThan(blockPort.currentAttempt.issuedClientTick() + 40);
        for (int tick = 0; tick < 45; tick++) {
            advance(blockManager, blockPort);
        }
        assertThat(blockManager.getRoutine(blockId, 0, 16).phase())
                .isEqualTo("wait_server_sync");
        assertThat(blockPort.maintainCount).isGreaterThanOrEqualTo(45);

        var navigationPort = new FakePort();
        var navigationManager = manager(navigationPort);
        var navigationId = navigationManager.startSemanticAction(
                UUID.randomUUID().toString(), navigateRequest(), 10).routineId();
        runToDispatch(navigationManager, navigationPort);
        assertThat(navigationPort.currentAttempt.leaseExpiresAtClientTick())
                .isGreaterThan(navigationPort.currentAttempt.issuedClientTick() + 40);
        for (int tick = 0; tick < 45; tick++) {
            advance(navigationManager, navigationPort);
        }
        assertThat(navigationManager.getRoutine(navigationId, 0, 16).phase())
                .isEqualTo("move");
        assertThat(navigationPort.maintainCount).isGreaterThanOrEqualTo(45);
    }

    @Test
    void deadlineCancelAndDispatchContractViolationAlwaysRelease() {
        var deadlinePort = new FakePort();
        var deadlineManager = manager(deadlinePort);
        var shortRequest = new BreakBlockRequest(
                TARGET, STONE, AIR, bounds(TARGET, 0, 1, true));
        var deadlineId = deadlineManager.startSemanticAction(
                UUID.randomUUID().toString(), shortRequest, 10).routineId();
        runToDispatch(deadlineManager, deadlinePort);
        deadlinePort.clientTick = 30;
        deadlinePort.observationRevision++;
        deadlineManager.tick();
        assertThat(deadlineManager.getRoutine(deadlineId, 0, 32).failure().code())
                .isEqualTo("HARD_DEADLINE_EXPIRED");
        assertThat(deadlinePort.releaseCount).isEqualTo(1);

        var cancelPort = new FakePort();
        var cancelManager = manager(cancelPort);
        var cancelId = cancelManager.startSemanticAction(
                UUID.randomUUID().toString(), breakRequest(), 10).routineId();
        runToDispatch(cancelManager, cancelPort);
        cancelManager.cancelRoutine(cancelId, "stop", 0, 32);
        cancelManager.cancelRoutine(cancelId, "again", 0, 32);
        assertThat(cancelPort.releaseCount).isEqualTo(1);

        var invalidPort = new FakePort();
        invalidPort.wrongAttemptKind = true;
        var invalidManager = manager(invalidPort);
        var invalidId = invalidManager.startSemanticAction(
                UUID.randomUUID().toString(), breakRequest(), 10).routineId();
        runToExecute(invalidManager, invalidPort);
        advance(invalidManager, invalidPort);
        var invalid = invalidManager.getRoutine(invalidId, 0, 32);
        assertThat(invalid.state()).isEqualTo(RoutineState.FAILED);
        assertThat(invalid.failure().code()).isEqualTo("ACTION_ADAPTER_FAILURE");
        assertThat(invalidPort.releaseCount).isEqualTo(1);
    }

    @Test
    void managerSemanticReplayPreservesKindScopedIdentity() {
        var port = new FakePort();
        var manager = manager(port);
        var key = UUID.randomUUID().toString();
        var first = manager.startSemanticAction(key, "canonical", breakRequest(), 10);

        assertThat(manager.replaySemanticAction(key, "canonical", breakRequest(), 11))
                .contains(new RoutineManager.StartReceipt(first.routineId(), true));
    }

    private static RoutineManager manager(FakePort port) {
        return new RoutineManager(new NoopStationaryPort(), port);
    }

    private static void runToDispatch(RoutineManager manager, FakePort port) {
        runToExecute(manager, port);
        advance(manager, port);
        assertThat(port.dispatchCount).isEqualTo(1);
    }

    private static void runToExecute(RoutineManager manager, FakePort port) {
        advance(manager, port); // queued -> precheck
        advance(manager, port); // precheck -> execute
    }

    private static void advance(RoutineManager manager, FakePort port) {
        port.clientTick++;
        port.observationRevision++;
        manager.tick();
    }

    private static BreakBlockRequest breakRequest() {
        return new BreakBlockRequest(TARGET, STONE, AIR, bounds(TARGET, 0, 30, true));
    }

    private static PlaceBlockRequest placeRequest() {
        return new PlaceBlockRequest(
                TARGET, STONE, "minecraft:cobblestone", AIR,
                bounds(TARGET, 0, 30, false));
    }

    private static InteractBlockRequest interactBlockRequest() {
        return new InteractBlockRequest(
                TARGET, LEVER_OFF, LEVER_ON, bounds(TARGET, 0, 30, false));
    }

    private static InteractEntityRequest entityRequest() {
        return new InteractEntityRequest(
                "abcdefghijklmnopqrstuvwx",
                "minecraft:cow",
                "main_hand",
                "minecraft:bucket",
                new StationaryBreakGoal("minecraft:milk_bucket", 1),
                bounds(TARGET, 0, 30, false));
    }

    private static NavigateToRequest navigateRequest() {
        var target = new BlockTarget(DIMENSION, 3, 64, 0);
        var min = new BlockTarget(DIMENSION, 0, 63, 0);
        var max = new BlockTarget(DIMENSION, 3, 65, 0);
        return new NavigateToRequest(
                target,
                0.5D,
                new ActionBounds(DIMENSION, min, max, 8, 120, false));
    }

    private static ActionBounds bounds(
            BlockTarget target, int travel, int duration, boolean allowBreak) {
        return new ActionBounds(
                target.dimension(), target, target, travel, duration, allowBreak);
    }

    private static BlockStateFingerprint expectedAfter(SemanticActionRequest request) {
        return switch (request) {
            case BreakBlockRequest action -> action.expectedAfter();
            case PlaceBlockRequest action -> action.expectedAfter();
            case InteractBlockRequest action -> action.expectedAfter();
            default -> throw new IllegalArgumentException("not a block action");
        };
    }

    private static void assertNeedsReplanBeforeFailed(RoutineSnapshot snapshot) {
        var types = snapshot.eventPage().events().stream().map(RoutineEvent::type).toList();
        assertThat(types.indexOf(RoutineEventType.NEEDS_REPLAN)).isGreaterThanOrEqualTo(0);
        assertThat(types.indexOf(RoutineEventType.FAILED))
                .isGreaterThan(types.indexOf(RoutineEventType.NEEDS_REPLAN));
    }

    private static final class FakePort implements SemanticActionPort {
        private long clientTick = 10;
        private long observationRevision;
        private Optional<BlockStateFingerprint> blockState = Optional.of(STONE);
        private boolean entityResolved = true;
        private Optional<String> entityType = Optional.of("minecraft:cow");
        private boolean entityInReach = true;
        private boolean blockInReach = true;
        private int goalItemCount;
        private double playerX = 0.5D;
        private double playerY = 64.0D;
        private double playerZ = 0.5D;
        private double horizontalVelocitySquared;
        private boolean routeSafe = true;
        private String routeReason = "safe";
        private long positionCorrectionRevision;
        private boolean safeToRetry = true;
        private boolean wrongAttemptKind;
        private boolean advanceRevisionOnDispatch;
        private int dispatchCount;
        private int maintainCount;
        private int stopCount;
        private int releaseCount;
        private int retireCount;
        private SemanticActionAttempt currentAttempt;
        private SemanticActionEvidence currentEvidence;

        @Override
        public SemanticActionFrame observe(SemanticActionRequest request) {
            return new SemanticActionFrame(
                    clientTick,
                    observationRevision,
                    true, true, true, true, true, true,
                    blockState,
                    blockInReach,
                    true,
                    entityResolved,
                    entityType,
                    true,
                    true,
                    entityInReach,
                    true,
                    goalItemCount,
                    true,
                    playerX,
                    playerY,
                    playerZ,
                    horizontalVelocitySquared,
                    true,
                    routeSafe,
                    routeReason,
                    positionCorrectionRevision,
                    safeToRetry);
        }

        @Override
        public SemanticActionAttempt dispatch(
                SemanticActionRequest request, long leaseExpiresAtClientTick) {
            dispatchCount++;
            if (advanceRevisionOnDispatch) {
                observationRevision++;
            }
            currentAttempt = new SemanticActionAttempt(
                    UUID.randomUUID(),
                    wrongAttemptKind ? "wrong_kind" : request.kind(),
                    clientTick,
                    observationRevision,
                    leaseExpiresAtClientTick,
                    positionCorrectionRevision,
                    Map.of("dispatch", dispatchCount));
            currentEvidence = evidence(
                    false, Optional.empty(), false, goalItemCount, null, false);
            return currentAttempt;
        }

        @Override
        public void maintain(SemanticActionAttempt attempt) {
            maintainCount++;
        }

        @Override
        public void stopInput(SemanticActionAttempt attempt) {
            stopCount++;
        }

        @Override
        public SemanticActionEvidence evidence(SemanticActionAttempt attempt) {
            return currentEvidence;
        }

        @Override
        public void release(SemanticActionAttempt attempt) {
            releaseCount++;
        }

        @Override
        public void retire(SemanticActionRequest request) {
            retireCount++;
        }

        private void confirmBlock(BlockStateFingerprint state) {
            setEvidence(true, Optional.of(state), false, goalItemCount, null, false);
        }

        private void confirmInventory(int count) {
            goalItemCount = count;
            setEvidence(false, Optional.empty(), true, count, null, false);
        }

        private void confirmReconciled() {
            currentEvidence = evidence(
                    true, Optional.empty(), false, goalItemCount, null, false,
                    Map.of("server_reconciled", true, "settle_ticks", 10));
        }

        private void reconciliationFailure(String code) {
            if (code.equals("SERVER_POSITION_CORRECTION")) {
                positionCorrectionRevision++;
            }
            var failure = new RoutineFailure(
                    code.equals("SERVER_MOTION_APPLIED")
                            ? RoutineFailure.Category.SAFETY
                            : RoutineFailure.Category.DIVERGENCE,
                    code,
                    false,
                    RoutineFailure.Recovery.REPLAN,
                    RoutineFailure.Scope.STEP,
                    dispatchCount,
                    Map.of("server_correction", false),
                    Map.of("server_correction", true),
                    Map.of("source", code.toLowerCase()),
                    List.of("player"),
                    false);
            currentEvidence = evidence(
                    false, Optional.empty(), false, goalItemCount, failure, false,
                    Map.of("source", code.toLowerCase()));
        }

        private void transientRetryFailure() {
            var failure = new RoutineFailure(
                    RoutineFailure.Category.TRANSIENT,
                    "SERVER_BUSY",
                    true,
                    RoutineFailure.Recovery.RETRY,
                    RoutineFailure.Scope.STEP,
                    dispatchCount,
                    Map.of("ready", true),
                    Map.of("ready", false),
                    Map.of(),
                    List.of("target"),
                    false);
            setEvidence(false, Optional.empty(), false, goalItemCount, failure, true);
        }

        private void setEvidence(
                boolean acknowledged,
                Optional<BlockStateFingerprint> serverState,
                boolean inventoryUpdate,
                int count,
                RoutineFailure failure,
                boolean retrySafe) {
            currentEvidence = evidence(
                    acknowledged, serverState, inventoryUpdate, count, failure, retrySafe);
        }

        private SemanticActionEvidence evidence(
                boolean acknowledged,
                Optional<BlockStateFingerprint> serverState,
                boolean inventoryUpdate,
                int count,
                RoutineFailure failure,
                boolean retrySafe) {
            return evidence(
                    acknowledged, serverState, inventoryUpdate, count, failure, retrySafe,
                    Map.of("source", inventoryUpdate ? "inventory_packet" : "prediction_ack"));
        }

        private SemanticActionEvidence evidence(
                boolean acknowledged,
                Optional<BlockStateFingerprint> serverState,
                boolean inventoryUpdate,
                int count,
                RoutineFailure failure,
                boolean retrySafe,
                Map<String, Object> basis) {
            return new SemanticActionEvidence(
                    currentAttempt.attemptId(),
                    clientTick,
                    observationRevision,
                    acknowledged,
                    serverState,
                    inventoryUpdate,
                    true,
                    count,
                    failure,
                    retrySafe,
                    basis);
        }
    }

    private static final class NoopStationaryPort implements StationaryBreakPort {
        @Override
        public StationaryBreakFrame observe(StationaryBreakRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AttackAttempt beginAttack(
                StationaryBreakRequest request, long leaseExpiresAtClientTick) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void holdAttack(AttackAttempt attempt) {
        }

        @Override
        public void stopAttackInput(AttackAttempt attempt) {
        }

        @Override
        public PredictionEvidence predictionEvidence(AttackAttempt attempt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void releaseAttack(AttackAttempt attempt) {
        }

        @Override
        public void retire(StationaryBreakRequest request) {
        }
    }
}
