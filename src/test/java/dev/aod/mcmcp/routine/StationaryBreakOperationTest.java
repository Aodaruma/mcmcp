package dev.aod.mcmcp.routine;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StationaryBreakOperationTest {
    private static final BlockStateFingerprint COBBLESTONE =
            new BlockStateFingerprint("minecraft:cobblestone", Map.of());
    private static final BlockStateFingerprint AIR =
            new BlockStateFingerprint("minecraft:air", Map.of());

    @Test
    void completesThroughTheExistingAckVerifiedStationaryRoutine() {
        var port = new FakePort();
        var operation = new StationaryBreakOperation(port, request(36_010), 1, 10);

        tick(operation, port, 11);
        tick(operation, port, 12);
        tick(operation, port, 13);
        port.evidence = new PredictionEvidence(
                1, true, true, Optional.of(AIR), 14, 4);
        port.target = Optional.empty();
        tick(operation, port, 14);
        port.goalCount = 1;
        StationaryBreakOperation.TickResult result = tick(operation, port, 15);

        assertThat(result.status()).isEqualTo(StationaryBreakOperation.Status.SUCCEEDED);
        assertThat(result.snapshot().checkpoint().seq()).isOne();
        assertThat(port.releaseCount).isOne();
        operation.close();
        assertThat(port.retireCount).isOne();
    }

    @Test
    void releasesAfterTheFiniteBreakCountWithoutWaitingForTheLongDeadline() {
        var port = new FakePort();
        var operation = new StationaryBreakOperation(port, request(6_010), 1, 10);

        tick(operation, port, 11);
        tick(operation, port, 12);
        tick(operation, port, 13);
        port.evidence = new PredictionEvidence(
                1, true, true, Optional.of(AIR), 14, 4);
        port.target = Optional.empty();
        tick(operation, port, 14);
        StationaryBreakOperation.TickResult result = tick(operation, port, 15);

        assertThat(result.status())
                .isEqualTo(StationaryBreakOperation.Status.MAX_BREAKS_REACHED);
        assertThat(result.snapshot().checkpoint().seq()).isOne();
        assertThat(result.snapshot().state()).isEqualTo(RoutineState.CANCELLED);
        assertThat(port.releaseCount).isOne();
        operation.close();
    }

    @Test
    void acceptsThirtyMinutesButRejectsAnyLongerDeadline() {
        var port = new FakePort();
        assertThat(new StationaryBreakOperation(port, request(36_010), 64, 10))
                .isNotNull();
        assertThatThrownBy(() -> new StationaryBreakOperation(
                port, request(36_011), 64, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("36000");
    }

    private static StationaryBreakOperation.TickResult tick(
            StationaryBreakOperation operation, FakePort port, long clientTick) {
        port.clientTick = clientTick;
        port.observationRevision++;
        return operation.tick();
    }

    private static StationaryBreakRequest request(long deadline) {
        return new StationaryBreakRequest(
                new BlockTarget("minecraft:overworld", 1, 64, 2),
                COBBLESTONE,
                new StationaryBreakGoal("minecraft:cobblestone", 1),
                deadline,
                StationaryBreakRequest.MAX_ATTACK_LEASE_TICKS,
                20);
    }

    private static final class FakePort implements StationaryBreakPort {
        private long clientTick = 10;
        private long observationRevision;
        private Optional<BlockStateFingerprint> target = Optional.of(COBBLESTONE);
        private int goalCount;
        private int releaseCount;
        private int retireCount;
        private PredictionEvidence evidence =
                new PredictionEvidence(0, false, false, Optional.empty(), 10, 0);

        @Override
        public StationaryBreakFrame observe(StationaryBreakRequest request) {
            return new StationaryBreakFrame(
                    clientTick, observationRevision, target, goalCount, true,
                    true, true, true, true, true, true, true);
        }

        @Override
        public AttackAttempt beginAttack(
                StationaryBreakRequest request, long leaseExpiresAtClientTick) {
            evidence = new PredictionEvidence(
                    1, false, false, Optional.empty(), clientTick, observationRevision);
            return new AttackAttempt(1, request.target(), leaseExpiresAtClientTick);
        }

        @Override public void holdAttack(AttackAttempt attempt) { }
        @Override public void stopAttackInput(AttackAttempt attempt) { }
        @Override public PredictionEvidence predictionEvidence(AttackAttempt attempt) {
            return evidence;
        }
        @Override public void releaseAttack(AttackAttempt attempt) { releaseCount++; }
        @Override public void retire(StationaryBreakRequest request) { retireCount++; }
    }
}
