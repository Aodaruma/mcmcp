package dev.aodaruma.craftagent.routine;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutineManagerTest {
    @Test
    void atomicallyReusesEqualArgumentsAndRejectsDifferentArguments() {
        var port = new FakeStationaryBreakPort();
        var manager = new RoutineManager(port);
        var key = UUID.randomUUID().toString();
        var request = request(100);

        var first = manager.startStationaryBreak(key, request, 10);
        var repeated = manager.startStationaryBreak(key, request, 10);

        assertThat(repeated.routineId()).isEqualTo(first.routineId());
        assertThat(repeated.reused()).isTrue();
        assertThatThrownBy(() -> manager.startStationaryBreak(
                        key,
                        new StationaryBreakRequest(
                                request.target(),
                                request.expectedSourceState(),
                                new StationaryBreakGoal("minecraft:cobblestone", 65),
                                100,
                                5,
                                20),
                        10))
                .isInstanceOf(RoutineManager.IdempotencyConflictException.class);
        assertThat(manager.retainedRoutineCount()).isEqualTo(1);
        assertThat(manager.retainedIdempotencyCount()).isEqualTo(1);
    }

    @Test
    void permitsOnlyOneActiveRoutineButTerminalIdsRemainQueryable() {
        var port = new FakeStationaryBreakPort();
        var manager = new RoutineManager(port);
        var first = manager.startStationaryBreak(UUID.randomUUID().toString(), request(100), 10);

        assertThatThrownBy(() -> manager.startStationaryBreak(
                        UUID.randomUUID().toString(), request(101), 10))
                .isInstanceOf(RoutineManager.RoutineBusyException.class);

        var cancelled = manager.cancelRoutine(first.routineId(), "test", 0, 32);
        assertThat(cancelled.state()).isEqualTo(RoutineState.CANCELLED);
        assertThat(manager.activeRoutineId()).isEmpty();

        var second = manager.startStationaryBreak(UUID.randomUUID().toString(), request(101), 10);
        assertThat(second.routineId()).isNotEqualTo(first.routineId());
        assertThat(manager.getRoutine(first.routineId(), 0, 32).state())
                .isEqualTo(RoutineState.CANCELLED);
    }

    @Test
    void replaysStableExternalIdentityBeforeRevalidatingDerivedDeadline() {
        var manager = new RoutineManager(new FakeStationaryBreakPort());
        var key = UUID.randomUUID().toString();
        var first = manager.startStationaryBreak(key, "same-tool-arguments", request(100), 10);

        var replay = manager.replayStationaryBreak(key, "same-tool-arguments", 11);

        assertThat(replay).contains(new RoutineManager.StartReceipt(first.routineId(), true));
        assertThatThrownBy(() -> manager.replayStationaryBreak(key, "different-arguments", 11))
                .isInstanceOf(RoutineManager.IdempotencyConflictException.class);
    }

    @Test
    void expiresTerminalIdempotencyRecordsWithinConfiguredBounds() {
        var ids = new ArrayDeque<>(java.util.List.of(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002")));
        var manager = new RoutineManager(new FakeStationaryBreakPort(), 8, 2, 10, ids::removeFirst);
        var firstKey = UUID.randomUUID().toString();
        var first = manager.startStationaryBreak(firstKey, request(100), 10);
        manager.cancelRoutine(first.routineId(), "done", 0, 8);

        manager.startStationaryBreak(UUID.randomUUID().toString(), request(120), 20);

        assertThat(manager.retainedRoutineCount()).isEqualTo(1);
        assertThat(manager.retainedIdempotencyCount()).isEqualTo(1);
        assertThatThrownBy(() -> manager.getRoutine(first.routineId(), 0, 8))
                .isInstanceOf(RoutineManager.RoutineNotFoundException.class);
    }

    @Test
    void clearsRetainedRoutinesAndIdempotencyAtAWorldSessionBoundary() {
        var manager = new RoutineManager(new FakeStationaryBreakPort());
        var receipt = manager.startStationaryBreak(
                UUID.randomUUID().toString(), request(100), 10);

        manager.clearSession("disconnect");

        assertThat(manager.activeRoutineId()).isEmpty();
        assertThat(manager.retainedRoutineCount()).isZero();
        assertThat(manager.retainedIdempotencyCount()).isZero();
        assertThatThrownBy(() -> manager.getRoutine(receipt.routineId(), 0, 8))
                .isInstanceOf(RoutineManager.RoutineNotFoundException.class);
    }

    @Test
    void recordsTerminalCleanupExactlyOnceWithoutChangingTheCancelledOutcome() {
        var manager = new RoutineManager(new FakeStationaryBreakPort());
        var receipt = manager.startStationaryBreak(
                UUID.randomUUID().toString(), request(100), 10);
        manager.cancelRoutine(receipt.routineId(), "operator_stop", 0, 8);
        var cleanupFailure = finalizationFailure("VOICECHAT_RESTORE_FAILED");

        var finalized = manager.recordTerminalFinalization(
                receipt.routineId(), cleanupFailure, 0, 8);
        var replayed = manager.recordTerminalFinalization(
                receipt.routineId(), cleanupFailure, 0, 8);

        assertThat(finalized.state()).isEqualTo(RoutineState.CANCELLED);
        assertThat(finalized.failure()).isNull();
        assertThat(finalized.finalizationCompleted()).isTrue();
        assertThat(finalized.finalizationFailure()).isEqualTo(cleanupFailure);
        assertThat(replayed.finalizationFailure()).isEqualTo(cleanupFailure);
        assertThatThrownBy(() -> manager.recordTerminalFinalization(
                        receipt.routineId(), null, 0, 8))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already recorded");
    }

    @Test
    void oneHundredStartCancelCyclesNeverRetainAnAttackLease() {
        var port = new FakeStationaryBreakPort();
        var manager = new RoutineManager(port);

        for (int cycle = 0; cycle < 100; cycle++) {
            var receipt = manager.startStationaryBreak(
                    UUID.randomUUID().toString(), request(1_000), 0);
            manager.tick();
            manager.tick();
            manager.tick();

            assertThat(manager.getRoutine(receipt.routineId(), 0, 8).phase())
                    .isEqualTo("wait_server_sync");
            var cancelled = manager.cancelRoutine(receipt.routineId(), "cycle_" + cycle, 0, 8);

            assertThat(cancelled.state()).isEqualTo(RoutineState.CANCELLED);
            assertThat(manager.activeRoutineId()).isEmpty();
            assertThat(port.releaseCount).isEqualTo(cycle + 1);
        }
    }

    private static StationaryBreakRequest request(long deadline) {
        return new StationaryBreakRequest(
                new BlockTarget("minecraft:overworld", 1, 64, 2),
                new BlockStateFingerprint("minecraft:cobblestone", Map.of()),
                new StationaryBreakGoal("minecraft:cobblestone", 64),
                deadline,
                5,
                20);
    }

    private static RoutineFailure finalizationFailure(String code) {
        return new RoutineFailure(
                RoutineFailure.Category.EXTERNAL,
                code,
                false,
                RoutineFailure.Recovery.USER,
                RoutineFailure.Scope.FINALIZATION,
                0,
                Map.of("inputs_released", true, "voicechat_restored", true),
                Map.of("inputs_released", true, "voicechat_restored", false),
                Map.of(),
                List.of("player"),
                true);
    }

    private static final class FakeStationaryBreakPort implements StationaryBreakPort {
        private int releaseCount;

        @Override
        public StationaryBreakFrame observe(StationaryBreakRequest request) {
            return new StationaryBreakFrame(
                    11,
                    1,
                    Optional.of(request.expectedSourceState()),
                    0,
                    true,
                    true,
                    true,
                    true,
                    true,
                    true,
                    true,
                    true);
        }

        @Override
        public AttackAttempt beginAttack(StationaryBreakRequest request, long leaseExpiresAtClientTick) {
            return new AttackAttempt(1, request.target(), leaseExpiresAtClientTick);
        }

        @Override
        public void holdAttack(AttackAttempt attempt) {
        }

        @Override
        public void stopAttackInput(AttackAttempt attempt) {
        }

        @Override
        public PredictionEvidence predictionEvidence(AttackAttempt attempt) {
            return new PredictionEvidence(
                    attempt.predictionSequence(), false, false, Optional.empty(), 11, 1);
        }

        @Override
        public void releaseAttack(AttackAttempt attempt) {
            releaseCount++;
        }

        @Override
        public void retire(StationaryBreakRequest request) {
        }
    }
}
