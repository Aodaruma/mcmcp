package dev.aodaruma.craftagent.routine;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StationaryBreakRoutineTest {
    private static final BlockStateFingerprint COBBLESTONE =
            new BlockStateFingerprint("minecraft:cobblestone", Map.of());
    private static final BlockStateFingerprint AIR =
            new BlockStateFingerprint("minecraft:air", Map.of());
    private static final BlockStateFingerprint DIRT =
            new BlockStateFingerprint("minecraft:dirt", Map.of());

    @Test
    void skipsAttackWhenTargetInventoryStateIsAlreadyConfirmed() {
        var port = new FakePort();
        port.goalCount = 64;
        var manager = manager(port, request(100, 5));
        var id = manager.activeRoutineId().orElseThrow();

        tick(manager, port, 11);
        assertThat(manager.getRoutine(id, 0, 32).state()).isEqualTo(RoutineState.VALIDATING);
        tick(manager, port, 12);

        var result = manager.getRoutine(id, 0, 32);
        assertThat(result.state()).isEqualTo(RoutineState.SUCCEEDED);
        assertThat(result.goalVerified()).isTrue();
        assertThat(port.beginCount).isZero();
        assertThat(result.eventPage().events())
                .extracting(RoutineEvent::type)
                .contains(RoutineEventType.GOAL_VERIFIED, RoutineEventType.SUCCEEDED);
    }

    @Test
    void requiresAckAndServerVerifiedTransitionBeforeVerifyingTheStep() {
        var port = new FakePort();
        var manager = manager(port, request(100, 8));
        var id = manager.activeRoutineId().orElseThrow();
        runToAttack(manager, port);

        port.evidence = evidence(port, true, false, Optional.empty());
        tick(manager, port, 14);
        assertThat(manager.getRoutine(id, 0, 32).phase()).isEqualTo("wait_server_sync");

        port.evidence = evidence(port, false, true, Optional.of(AIR));
        tick(manager, port, 15);
        assertThat(manager.getRoutine(id, 0, 32).phase()).isEqualTo("wait_server_sync");

        port.evidence = evidence(port, true, true, Optional.of(AIR));
        tick(manager, port, 16);
        assertThat(manager.getRoutine(id, 0, 32).phase()).isEqualTo("verify");
        assertThat(port.releaseCount).isEqualTo(1);

        port.goalCount = 64;
        tick(manager, port, 17);
        var succeeded = manager.getRoutine(id, 0, 64);
        assertThat(succeeded.state()).isEqualTo(RoutineState.SUCCEEDED);
        assertThat(succeeded.goalVerified()).isTrue();
        assertThat(succeeded.verification()).containsEntry("verified_breaks", 1);
        assertThat(succeeded.currentStep()).isNull();
        assertThat(succeeded.checkpoint()).isEqualTo(new RoutineCheckpoint(1, 7));
        assertThat(succeeded.verificationSummary())
                .isEqualTo(new RoutineVerification(64, 64, 0));
        assertThat(succeeded.eventPage().events())
                .extracting(RoutineEvent::type)
                .containsSubsequence(
                        RoutineEventType.STEP_VERIFIED,
                        RoutineEventType.GOAL_VERIFIED,
                        RoutineEventType.SUCCEEDED);
    }

    @Test
    void releasesInputAndStillVerifiesWhenTheBrokenTargetLeavesTheCrosshair() {
        var port = new FakePort();
        var manager = manager(port, request(100, 8));
        var id = manager.activeRoutineId().orElseThrow();
        runToAttack(manager, port);

        port.target = Optional.empty();
        port.crosshairOnTarget = false;
        port.targetInReach = false;
        port.evidence = evidence(port, true, true, Optional.of(AIR));
        tick(manager, port, 14);

        assertThat(manager.getRoutine(id, 0, 32).phase()).isEqualTo("verify");
        assertThat(port.stopInputCount).isEqualTo(1);
        assertThat(port.releaseCount).isEqualTo(1);

        tick(manager, port, 15);
        var waiting = manager.getRoutine(id, 0, 32);
        assertThat(waiting.state()).isEqualTo(RoutineState.WAITING);
        assertThat(waiting.waitState()).isEqualTo(new RoutineWait(
                "target_regeneration",
                35,
                "target matches the original full block state"));
    }

    @Test
    void doesNotMineARegeneratedTargetWhileWaitingForTheFirstAck() {
        var port = new FakePort();
        var manager = manager(port, request(100, 8));
        var id = manager.activeRoutineId().orElseThrow();
        runToAttack(manager, port);

        // The server reported air and then regenerated the expected source before its ACK.
        port.evidence = evidence(port, false, true, Optional.of(AIR));
        tick(manager, port, 14);
        assertThat(manager.getRoutine(id, 0, 32).phase()).isEqualTo("wait_server_sync");
        assertThat(port.stopInputCount).isEqualTo(1);
        assertThat(port.holdCount).isZero();

        port.evidence = evidence(port, true, true, Optional.of(AIR));
        tick(manager, port, 15);
        assertThat(manager.getRoutine(id, 0, 32).phase()).isEqualTo("verify");
        assertThat(port.stopInputCount).isEqualTo(1);
        assertThat(port.holdCount).isZero();
    }

    @Test
    void acceptsTheLaterStopPredictionAsTheAuthoritativeBreakSequence() {
        var port = new FakePort();
        var manager = manager(port, request(100, 8));
        var id = manager.activeRoutineId().orElseThrow();
        runToAttack(manager, port);

        port.evidence = new PredictionEvidence(
                port.lastAttempt.predictionSequence() + 1,
                true,
                true,
                Optional.of(AIR),
                port.clientTick,
                port.observationRevision);
        tick(manager, port, 14);

        var verifying = manager.getRoutine(id, 0, 32);
        assertThat(verifying.phase()).isEqualTo("verify");
        assertThat(verifying.failure()).isNull();
        assertThat(port.releaseCount).isEqualTo(1);
    }

    @Test
    void doesNotBlindlyRetryWhenAckArrivesWithoutAuthoritativeTransition() {
        var port = new FakePort();
        var manager = manager(port, request(100, 3));
        var id = manager.activeRoutineId().orElseThrow();
        runToAttack(manager, port);
        port.evidence = evidence(port, true, false, Optional.empty());
        // Even if the target inventory changes concurrently, an attack already issued
        // is not verified without both pieces of vanilla server evidence.
        port.goalCount = 64;

        tick(manager, port, 14);
        tick(manager, port, 15);
        tick(manager, port, 16);
        tick(manager, port, 17);

        var failed = manager.getRoutine(id, 0, 64);
        assertThat(failed.state()).isEqualTo(RoutineState.FAILED);
        assertThat(failed.goalVerified()).isFalse();
        assertThat(failed.failure().code()).isEqualTo("SERVER_TRANSITION_TIMEOUT");
        assertThat(failed.failure().attempts()).isEqualTo(1);
        assertThat(port.beginCount).isEqualTo(1);
        assertThat(port.releaseCount).isEqualTo(1);
        assertThat(failed.eventPage().events())
                .extracting(RoutineEvent::type)
                .contains(RoutineEventType.NEEDS_REPLAN, RoutineEventType.FAILED)
                .doesNotContain(RoutineEventType.RETRYING);
    }

    @Test
    void reportsPostconditionMismatchWhenAckCoversANonAirReplacement() {
        var port = new FakePort();
        var manager = manager(port, request(100, 3));
        var id = manager.activeRoutineId().orElseThrow();
        runToAttack(manager, port);
        port.evidence = evidence(port, true, false, Optional.of(DIRT));

        tick(manager, port, 14);
        tick(manager, port, 15);
        tick(manager, port, 16);

        var failed = manager.getRoutine(id, 0, 64);
        assertThat(failed.state()).isEqualTo(RoutineState.FAILED);
        assertThat(failed.failure().code()).isEqualTo("POSTCONDITION_MISMATCH");
        assertThat(failed.failure().category()).isEqualTo(RoutineFailure.Category.DIVERGENCE);
        assertThat(failed.eventPage().events())
                .extracting(RoutineEvent::type)
                .contains(RoutineEventType.FAILED)
                .doesNotContain(RoutineEventType.STEP_VERIFIED);
    }

    @Test
    void hardDeadlineReleasesAnActiveLeaseAndNeverExecutesLater() {
        var port = new FakePort();
        var manager = manager(port, request(15, 8));
        var id = manager.activeRoutineId().orElseThrow();
        runToAttack(manager, port);

        tick(manager, port, 14);
        tick(manager, port, 15);
        tick(manager, port, 16);

        var failed = manager.getRoutine(id, 0, 64);
        assertThat(failed.state()).isEqualTo(RoutineState.FAILED);
        assertThat(failed.failure().code()).isEqualTo("HARD_DEADLINE_EXPIRED");
        assertThat(port.beginCount).isEqualTo(1);
        assertThat(port.releaseCount).isEqualTo(1);
        assertThat(port.holdCount).isEqualTo(1);
    }

    @Test
    void cancelIsIdempotentAndReleasesTheLeaseBeforeReturning() {
        var port = new FakePort();
        var manager = manager(port, request(100, 8));
        var id = manager.activeRoutineId().orElseThrow();
        runToAttack(manager, port);

        var first = manager.cancelRoutine(id, "operator\nstopped", 0, 32);
        var second = manager.cancelRoutine(id, "again", 0, 32);

        assertThat(first.state()).isEqualTo(RoutineState.CANCELLED);
        assertThat(second.state()).isEqualTo(RoutineState.CANCELLED);
        assertThat(port.releaseCount).isEqualTo(1);
        assertThat(second.eventPage().events())
                .filteredOn(event -> event.type() == RoutineEventType.CANCELLED)
                .hasSize(1)
                .first()
                .extracting(event -> event.details().get("reason"))
                .isEqualTo("operator stopped");
    }

    @Test
    void cancelStillBecomesTerminalWhenTheAdapterThrowsALinkageErrorDuringRelease() {
        var port = new FakePort();
        var manager = manager(port, request(100, 8));
        var id = manager.activeRoutineId().orElseThrow();
        runToAttack(manager, port);
        port.throwLinkageOnRelease = true;

        var cancelled = manager.cancelRoutine(id, "adapter changed", 0, 32);

        assertThat(cancelled.state()).isEqualTo(RoutineState.CANCELLED);
        assertThat(manager.activeRoutineId()).isEmpty();
        assertThat(port.releaseCount).isEqualTo(1);
    }

    @Test
    void releasesAnAdapterLeaseWhoseReturnedMetadataViolatesTheContract() {
        var port = new FakePort();
        port.returnWrongTarget = true;
        var manager = manager(port, request(100, 8));
        var id = manager.activeRoutineId().orElseThrow();

        tick(manager, port, 11);
        tick(manager, port, 12);
        tick(manager, port, 13);

        var failed = manager.getRoutine(id, 0, 32);
        assertThat(failed.state()).isEqualTo(RoutineState.FAILED);
        assertThat(failed.failure().code()).isEqualTo("ATTACK_ADAPTER_CONTRACT_VIOLATION");
        assertThat(port.beginCount).isEqualTo(1);
        assertThat(port.releaseCount).isEqualTo(1);
    }

    @Test
    void aNewIdempotencyKeyStillSkipsWorkAfterTheWorldTargetStateWasReached() {
        var port = new FakePort();
        var firstManager = manager(port, request(100, 8));
        var firstId = firstManager.activeRoutineId().orElseThrow();
        firstManager.cancelRoutine(firstId, "replan", 0, 8);
        port.goalCount = 64;

        var secondManager = new RoutineManager(port);
        var second = secondManager.startStationaryBreak(
                UUID.randomUUID().toString(), request(120, 8), 10);
        tick(secondManager, port, 20);
        tick(secondManager, port, 21);

        var result = secondManager.getRoutine(second.routineId(), 0, 32);
        assertThat(result.state()).isEqualTo(RoutineState.SUCCEEDED);
        assertThat(port.beginCount).isZero();
    }

    @Test
    void goalVerificationSurvivesARequiredFinalizationFailure() {
        var port = new FakePort();
        port.goalCount = 64;
        var manager = manager(port, request(100, 5));
        var id = manager.activeRoutineId().orElseThrow();

        advanceWithoutFinalization(manager, port, 11);
        advanceWithoutFinalization(manager, port, 12);
        var finalizing = manager.getRoutine(id, 0, 32);
        assertThat(finalizing.state()).isEqualTo(RoutineState.FINALIZING);
        assertThat(finalizing.goalVerified()).isTrue();
        assertThat(finalizing.finalizationCompleted()).isFalse();

        var cleanupFailure = new RoutineFailure(
                RoutineFailure.Category.EXTERNAL,
                "INPUT_RELEASE_FAILED",
                false,
                RoutineFailure.Recovery.USER,
                RoutineFailure.Scope.FINALIZATION,
                0,
                Map.of("inputs_released", true, "voicechat_restored", true),
                Map.of("inputs_released", false, "voicechat_restored", true),
                Map.of("goal_verified", true),
                List.of("player"),
                true);
        var failed = manager.completeFinalization(id, cleanupFailure, 0, 32);

        assertThat(failed.state()).isEqualTo(RoutineState.FAILED);
        assertThat(failed.goalVerified()).isTrue();
        assertThat(failed.finalizationCompleted()).isTrue();
        assertThat(failed.finalizationFailure()).isEqualTo(cleanupFailure);
        assertThat(failed.failure()).isEqualTo(cleanupFailure);
    }

    private static RoutineManager manager(FakePort port, StationaryBreakRequest request) {
        var manager = new RoutineManager(port);
        manager.startStationaryBreak(UUID.randomUUID().toString(), request, 10);
        return manager;
    }

    private static StationaryBreakRequest request(long deadline, int leaseTicks) {
        return new StationaryBreakRequest(
                new BlockTarget("minecraft:overworld", 1, 64, 2),
                COBBLESTONE,
                new StationaryBreakGoal("minecraft:cobblestone", 64),
                deadline,
                leaseTicks,
                20);
    }

    private static void runToAttack(RoutineManager manager, FakePort port) {
        tick(manager, port, 11);
        tick(manager, port, 12);
        tick(manager, port, 13);
        assertThat(port.beginCount).isEqualTo(1);
    }

    private static void tick(RoutineManager manager, FakePort port, long clientTick) {
        advanceWithoutFinalization(manager, port, clientTick);
        var active = manager.activeRoutineId();
        if (active.isPresent()
                && manager.getRoutine(active.orElseThrow(), 0, 1).state() == RoutineState.FINALIZING) {
            manager.completeFinalization(active.orElseThrow(), null, 0, 1);
        }
    }

    private static void advanceWithoutFinalization(
            RoutineManager manager,
            FakePort port,
            long clientTick) {
        port.clientTick = clientTick;
        port.observationRevision++;
        manager.tick();
    }

    private static PredictionEvidence evidence(
            FakePort port,
            boolean acknowledged,
            boolean serverVerifiedTransition,
            Optional<BlockStateFingerprint> transitionedTo) {
        return new PredictionEvidence(
                port.lastAttempt.predictionSequence(),
                acknowledged,
                serverVerifiedTransition,
                transitionedTo,
                port.clientTick,
                port.observationRevision);
    }

    private static final class FakePort implements StationaryBreakPort {
        private long clientTick = 10;
        private long observationRevision;
        private Optional<BlockStateFingerprint> target = Optional.of(COBBLESTONE);
        private int goalCount;
        private boolean inventorySynchronized = true;
        private int beginCount;
        private int holdCount;
        private int stopInputCount;
        private int releaseCount;
        private boolean targetInReach = true;
        private boolean crosshairOnTarget = true;
        private boolean throwLinkageOnRelease;
        private boolean returnWrongTarget;
        private AttackAttempt lastAttempt;
        private PredictionEvidence evidence =
                new PredictionEvidence(0, false, false, Optional.empty(), 10, 0);

        @Override
        public StationaryBreakFrame observe(StationaryBreakRequest request) {
            return new StationaryBreakFrame(
                    clientTick,
                    observationRevision,
                    target,
                    goalCount,
                    inventorySynchronized,
                    true,
                    true,
                    true,
                    true,
                    true,
                    targetInReach,
                    crosshairOnTarget);
        }

        @Override
        public AttackAttempt beginAttack(
                StationaryBreakRequest request,
                long leaseExpiresAtClientTick) {
            beginCount++;
            var returnedTarget = returnWrongTarget
                    ? new BlockTarget(
                            request.target().dimension(),
                            request.target().x() + 1,
                            request.target().y(),
                            request.target().z())
                    : request.target();
            lastAttempt = new AttackAttempt(beginCount, returnedTarget, leaseExpiresAtClientTick);
            evidence = new PredictionEvidence(
                    lastAttempt.predictionSequence(),
                    false,
                    false,
                    Optional.empty(),
                    clientTick,
                    observationRevision);
            return lastAttempt;
        }

        @Override
        public void holdAttack(AttackAttempt attempt) {
            holdCount++;
        }

        @Override
        public void stopAttackInput(AttackAttempt attempt) {
            stopInputCount++;
        }

        @Override
        public PredictionEvidence predictionEvidence(AttackAttempt attempt) {
            return evidence;
        }

        @Override
        public void releaseAttack(AttackAttempt attempt) {
            releaseCount++;
            if (throwLinkageOnRelease) {
                throw new LinkageError("simulated adapter mismatch");
            }
        }


        @Override
        public void retire(StationaryBreakRequest request) {
        }
    }
}
