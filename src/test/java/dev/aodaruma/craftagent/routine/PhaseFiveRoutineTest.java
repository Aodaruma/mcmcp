package dev.aodaruma.craftagent.routine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhaseFiveRoutineTest {
    private static final String DIMENSION = "minecraft:overworld";
    private static final BlockTarget MINIMUM = new BlockTarget(DIMENSION, 0, 60, 0);
    private static final BlockTarget MAXIMUM = new BlockTarget(DIMENSION, 8, 70, 8);

    @Test
    void allSixKindsRequirePositiveServerEvidenceAndExposeTypedSnapshots() {
        for (var kind : PhaseFiveRequest.KINDS) {
            var port = new FakePort();
            var manager = manager(port);
            var request = request(kind, 2, 30);
            var receipt = manager.startPhaseFive(
                    UUID.randomUUID().toString(), request, 10);

            runToBegin(manager, port);
            assertThat(port.currentAttempt.attemptId()).isEqualTo(receipt.routineId());
            advance(manager, port); // pending evidence
            assertThat(manager.getRoutine(receipt.routineId(), 0, 32).state())
                    .isEqualTo(RoutineState.WAITING);

            port.result = new PhaseFiveResult(
                    2,
                    true,
                    Map.of("source", "fresh_full_server_readback"),
                    List.of(new RoutineEffect(
                            "phase_five_goal",
                            Map.of("verified_units", 0),
                            Map.of("verified_units", 2),
                            RoutineEffect.Verification.CONFIRMED)));
            port.mode = Mode.CONFIRMED;
            advance(manager, port);

            var finalizing = manager.getRoutine(receipt.routineId(), 0, 32);
            assertThat(finalizing.state()).as(kind).isEqualTo(RoutineState.FINALIZING);
            assertThat(finalizing.currentStep()).isEqualTo(new RoutineStep(kind, Map.of()));
            assertThat(finalizing.progress()).isEqualTo(new RoutineProgress(2, 2, "items"));
            assertThat(finalizing.verificationSummary())
                    .isEqualTo(new RoutineVerification(2, 2, 0));
            assertThat(finalizing.effects()).hasSize(1);
            assertThat(finalizing.diagnostics().get("result"))
                    .isEqualTo(Map.of("source", "fresh_full_server_readback"));
            assertThat(port.beginCount).isOne();
            assertThat(port.releaseCount).isOne();

            var succeeded = manager.completeFinalization(
                    receipt.routineId(), null, 0, 32);
            assertThat(succeeded.state()).isEqualTo(RoutineState.SUCCEEDED);
            assertThat(succeeded.currentStep()).isNull();
            assertThat(port.releaseCount).isOne();
            assertThat(port.retireCount).isOne();
        }
    }

    @Test
    void pendingAndRetryableFailureNeverDispatchABlindRetry() {
        var port = new FakePort();
        var manager = manager(port);
        var key = UUID.randomUUID().toString();
        var request = request("transfer_items", 1, 30);
        var first = manager.startPhaseFive(key, "canonical", request, 10);

        assertThat(manager.replayPhaseFive(key, "canonical", request, 11))
                .contains(new RoutineManager.StartReceipt(first.routineId(), true));
        assertThatThrownBy(() -> manager.startPhaseFive(
                        UUID.randomUUID().toString(), request, 10))
                .isInstanceOf(RoutineManager.RoutineBusyException.class);

        runToBegin(manager, port);
        for (int tick = 0; tick < 5; tick++) {
            advance(manager, port);
        }
        assertThat(port.beginCount).isOne();
        assertThat(port.maintainCount).isEqualTo(5);

        port.failure = failure("SERVER_BUSY", true, RoutineFailure.Recovery.RETRY);
        port.mode = Mode.FAILED;
        advance(manager, port);
        var failed = manager.getRoutine(first.routineId(), 0, 32);

        assertThat(failed.state()).isEqualTo(RoutineState.FAILED);
        assertThat(failed.failure().code()).isEqualTo("SERVER_BUSY");
        assertThat(failed.diagnostics()).containsEntry("automatic_retries", 0);
        assertThat(port.beginCount).isOne();
        assertThat(port.releaseCount).isOne();
        assertThat(port.retireCount).isOne();
    }

    @Test
    void unknownAmbiguousAndUnverifiedTerminalClaimsFailClosed() {
        for (var certainty : PhaseFiveEvidence.Certainty.values()) {
            var port = new FakePort();
            var manager = manager(port);
            var id = manager.startPhaseFive(
                    UUID.randomUUID().toString(), request("survey_area", 1, 30), 10)
                    .routineId();
            runToBegin(manager, port);
            port.certainty = certainty;
            port.mode = Mode.INCONCLUSIVE;

            advance(manager, port);
            var failed = manager.getRoutine(id, 0, 32);

            assertThat(failed.state()).isEqualTo(RoutineState.FAILED);
            assertThat(failed.failure().code()).isEqualTo(
                    certainty == PhaseFiveEvidence.Certainty.UNKNOWN
                            ? "SERVER_EVIDENCE_UNKNOWN"
                            : "SERVER_EVIDENCE_AMBIGUOUS");
            assertThat(port.releaseCount).isOne();
            assertThat(port.retireCount).isOne();
        }

        var unverifiedPort = new FakePort();
        var unverifiedManager = manager(unverifiedPort);
        var unverifiedId = unverifiedManager.startPhaseFive(
                UUID.randomUUID().toString(), request("craft_items", 2, 30), 10)
                .routineId();
        runToBegin(unverifiedManager, unverifiedPort);
        unverifiedPort.result = new PhaseFiveResult(1, true, Map.of(), List.of());
        unverifiedPort.mode = Mode.CONFIRMED;

        advance(unverifiedManager, unverifiedPort);

        assertThat(unverifiedManager.getRoutine(unverifiedId, 0, 32).failure().code())
                .isEqualTo("POSTCONDITION_NOT_CONFIRMED");
        assertThat(unverifiedPort.releaseCount).isOne();
    }

    @Test
    void deadlineCancellationAndMalformedAuthorityReleaseAtMostOnce() {
        var deadlinePort = new FakePort();
        var deadlineManager = manager(deadlinePort);
        var deadlineId = deadlineManager.startPhaseFive(
                UUID.randomUUID().toString(), request("sleep_at_bed", 1, 1), 10)
                .routineId();
        runToBegin(deadlineManager, deadlinePort);
        deadlinePort.clientTick = 30;
        deadlinePort.observationRevision++;

        deadlineManager.tick();
        var deadlineFailure = deadlineManager.getRoutine(deadlineId, 0, 8);
        deadlineManager.clearSession("disconnect");

        assertThat(deadlineFailure.state()).isEqualTo(RoutineState.FAILED);
        assertThat(deadlineFailure.failure().code()).isEqualTo("HARD_DEADLINE_EXPIRED");
        assertThat(deadlinePort.releaseCount).isOne();
        assertThat(deadlinePort.retireCount).isOne();

        var cancelPort = new FakePort();
        var cancelManager = manager(cancelPort);
        var cancelId = cancelManager.startPhaseFive(
                UUID.randomUUID().toString(), request("harvest_tree_area", 1, 30), 10)
                .routineId();
        runToBegin(cancelManager, cancelPort);
        cancelManager.cancelRoutine(cancelId, "stop", 0, 8);
        cancelManager.cancelRoutine(cancelId, "again", 0, 8);
        cancelManager.clearSession("disconnect");

        assertThat(cancelPort.releaseCount).isOne();
        assertThat(cancelPort.retireCount).isOne();

        var malformedPort = new FakePort();
        malformedPort.wrongAuthority = true;
        var malformedManager = manager(malformedPort);
        var malformedId = malformedManager.startPhaseFive(
                UUID.randomUUID().toString(), request("tend_crop_area", 1, 30), 10)
                .routineId();
        runToBeginAttempt(malformedManager, malformedPort);

        var malformed = malformedManager.getRoutine(malformedId, 0, 8);
        assertThat(malformed.state()).isEqualTo(RoutineState.FAILED);
        assertThat(malformed.failure().code()).isEqualTo("ACTION_ADAPTER_FAILURE");
        assertThat(malformedPort.releaseCount).isOne();
        assertThat(malformedPort.retireCount).isOne();
    }

    @Test
    void phaseFiveGetsItsOwnSixHundredSecondEnvelope() {
        assertThat(new PhaseFiveBounds(
                DIMENSION, MINIMUM, MAXIMUM, 128, 600, false)
                .hardDeadlineClientTick(10)).isEqualTo(12_010);
        assertThatThrownBy(() -> new ActionBounds(
                        DIMENSION, MINIMUM, MAXIMUM, 128, 121, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PhaseFiveRequest(
                        "craft_items",
                        Map.of(),
                        new PhaseFiveBounds(DIMENSION, MINIMUM, MAXIMUM, 33, 120, false),
                        1,
                        "items"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PhaseFiveRequest(
                        "survey_area",
                        Map.of(),
                        new PhaseFiveBounds(DIMENSION, MINIMUM, MAXIMUM, 1, 30, true),
                        1,
                        "cells"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static RoutineManager manager(FakePort port) {
        return new RoutineManager(
                new NoopStationaryPort(),
                null,
                null,
                port,
                64,
                16,
                1_000,
                UUID::randomUUID);
    }

    private static PhaseFiveRequest request(String kind, int expectedUnits, int durationSeconds) {
        boolean allowBreak = kind.equals("tend_crop_area")
                || kind.equals("harvest_tree_area");
        int travel = kind.equals("craft_items") || kind.equals("transfer_items") ? 32 : 128;
        return new PhaseFiveRequest(
                kind,
                Map.of("fixture", kind),
                new PhaseFiveBounds(
                        DIMENSION, MINIMUM, MAXIMUM, travel, durationSeconds, allowBreak),
                expectedUnits,
                "items");
    }

    private static void runToBegin(RoutineManager manager, FakePort port) {
        runToBeginAttempt(manager, port);
        assertThat(port.beginCount).isOne();
        assertThat(port.releaseCount).isZero();
    }

    private static void runToBeginAttempt(RoutineManager manager, FakePort port) {
        advance(manager, port); // queued -> precheck
        advance(manager, port); // precheck -> begin or fail malformed authority
    }

    private static void advance(RoutineManager manager, FakePort port) {
        port.clientTick++;
        port.observationRevision++;
        manager.tick();
    }

    private static RoutineFailure failure(
            String code,
            boolean retryable,
            RoutineFailure.Recovery recovery) {
        return new RoutineFailure(
                RoutineFailure.Category.TRANSIENT,
                code,
                retryable,
                recovery,
                RoutineFailure.Scope.STEP,
                1,
                Map.of("ready", true),
                Map.of("ready", false),
                Map.of(),
                List.of("inventory"),
                false);
    }

    private enum Mode {
        PENDING,
        CONFIRMED,
        INCONCLUSIVE,
        FAILED
    }

    private static final class FakePort implements PhaseFivePort {
        private long clientTick = 10;
        private long observationRevision;
        private Mode mode = Mode.PENDING;
        private PhaseFiveEvidence.Certainty certainty = PhaseFiveEvidence.Certainty.UNKNOWN;
        private PhaseFiveResult result = new PhaseFiveResult(1, true, Map.of(), List.of());
        private RoutineFailure failure = failure(
                "SERVER_BUSY", true, RoutineFailure.Recovery.RETRY);
        private boolean wrongAuthority;
        private int beginCount;
        private int maintainCount;
        private int releaseCount;
        private int retireCount;
        private PhaseFiveAttempt currentAttempt;

        @Override
        public PhaseFiveFrame observe(PhaseFiveRequest request) {
            return new PhaseFiveFrame(clientTick, observationRevision, null);
        }

        @Override
        public PhaseFiveAttempt begin(
                UUID routineId,
                PhaseFiveRequest request,
                long hardDeadlineClientTick) {
            beginCount++;
            currentAttempt = new PhaseFiveAttempt(
                    wrongAuthority ? UUID.randomUUID() : routineId,
                    request.kind(),
                    clientTick,
                    observationRevision,
                    hardDeadlineClientTick,
                    Map.of("begin", beginCount));
            return currentAttempt;
        }

        @Override
        public void maintain(PhaseFiveAttempt attempt) {
            maintainCount++;
        }

        @Override
        public PhaseFiveEvidence evidence(PhaseFiveAttempt attempt) {
            return switch (mode) {
                case PENDING -> new PhaseFiveEvidence.Pending(
                        attempt.attemptId(), clientTick, observationRevision, Map.of());
                case CONFIRMED -> new PhaseFiveEvidence.ServerConfirmed(
                        attempt.attemptId(), clientTick, observationRevision, result,
                        Map.of("source", "server"));
                case INCONCLUSIVE -> new PhaseFiveEvidence.Inconclusive(
                        attempt.attemptId(), clientTick, observationRevision,
                        certainty, "fresh readback was not decisive", Map.of());
                case FAILED -> new PhaseFiveEvidence.Failed(
                        attempt.attemptId(), clientTick, observationRevision, failure, Map.of());
            };
        }

        @Override
        public void release(PhaseFiveAttempt attempt) {
            releaseCount++;
        }

        @Override
        public void retire(PhaseFiveRequest request) {
            retireCount++;
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
