package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.routine.AttackAttempt;
import dev.aod.mcmcp.routine.BlockStateFingerprint;
import dev.aod.mcmcp.routine.BlockTarget;
import dev.aod.mcmcp.routine.PredictionEvidence;
import dev.aod.mcmcp.routine.StationaryBreakFrame;
import dev.aod.mcmcp.routine.StationaryBreakGoal;
import dev.aod.mcmcp.routine.StationaryBreakPort;
import dev.aod.mcmcp.routine.StationaryBreakRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class KnownBlockBreakAttemptTest {
    @Test
    void succeedsOnlyAfterAckAndAuthoritativeAirThenReleases() {
        var port = new FakePort();
        var attempt = new KnownBlockBreakAttempt(port, request(), 10);

        assertThat(attempt.tick(11, true)).isEqualTo(KnownBlockBreakAttempt.TickResult.RUNNING);
        port.evidence = new PredictionEvidence(
                7, true, false, Optional.empty(), 12, 1);
        assertThat(attempt.tick(12, false)).isEqualTo(KnownBlockBreakAttempt.TickResult.RUNNING);
        assertThat(port.stopped).isTrue();

        port.evidence = new PredictionEvidence(
                7, true, true, Optional.of(state("minecraft:air")), 13, 2);
        assertThat(attempt.tick(13, false)).isEqualTo(KnownBlockBreakAttempt.TickResult.SUCCEEDED);
        assertThat(port.released).isTrue();
        assertThat(port.retired).isTrue();
    }

    private static StationaryBreakRequest request() {
        return new StationaryBreakRequest(
                new BlockTarget("minecraft:overworld", 1, 64, 2),
                state("minecraft:oak_log"),
                new StationaryBreakGoal("minecraft:oak_log", 1),
                70,
                40,
                1);
    }

    private static BlockStateFingerprint state(String id) {
        return new BlockStateFingerprint(id, Map.of());
    }

    private static final class FakePort implements StationaryBreakPort {
        private PredictionEvidence evidence = new PredictionEvidence(
                7, false, false, Optional.empty(), 10, 0);
        private boolean stopped;
        private boolean released;
        private boolean retired;

        @Override public StationaryBreakFrame observe(StationaryBreakRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override public AttackAttempt beginAttack(
                StationaryBreakRequest request, long leaseExpiresAtClientTick) {
            return new AttackAttempt(7, request.target(), leaseExpiresAtClientTick);
        }

        @Override public void holdAttack(AttackAttempt attempt) { }
        @Override public void stopAttackInput(AttackAttempt attempt) { stopped = true; }
        @Override public PredictionEvidence predictionEvidence(AttackAttempt attempt) {
            return evidence;
        }
        @Override public void releaseAttack(AttackAttempt attempt) { released = true; }
        @Override public void retire(StationaryBreakRequest request) { retired = true; }
    }
}
