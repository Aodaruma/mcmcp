package dev.aod.mcmcp.routine;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhaseFivePortRouterTest {
    @Test
    void releaseRetriesTheSameDelegateAfterATransientFailure() {
        var inventory = new FakePort();
        var world = new FakePort();
        var router = new PhaseFivePortRouter(inventory, world);
        var request = request("transfer_items");
        var attempt = router.begin(UUID.randomUUID(), request, 101L);
        inventory.releaseFailuresRemaining = 1;

        assertThatThrownBy(() -> router.release(attempt))
                .hasMessage("release failed once");
        router.release(attempt);

        assertThat(inventory.releaseCalls).isEqualTo(2);
        assertThat(world.releaseCalls).isZero();
    }

    private static PhaseFiveRequest request(String kind) {
        var target = new BlockTarget("minecraft:overworld", 1, 64, 2);
        return new PhaseFiveRequest(
                kind,
                Map.of(),
                new PhaseFiveBounds(target.dimension(), target, target, 0, 120, false),
                0,
                "items");
    }

    private static final class FakePort implements PhaseFivePort {
        private int releaseCalls;
        private int releaseFailuresRemaining;

        @Override
        public PhaseFiveFrame observe(PhaseFiveRequest request) {
            return new PhaseFiveFrame(1L, 1L, null);
        }

        @Override
        public PhaseFiveAttempt begin(
                UUID routineId, PhaseFiveRequest request, long hardDeadlineClientTick) {
            return new PhaseFiveAttempt(
                    routineId,
                    request.kind(),
                    1L,
                    1L,
                    hardDeadlineClientTick,
                    Map.of());
        }

        @Override
        public void maintain(PhaseFiveAttempt attempt) {
        }

        @Override
        public PhaseFiveEvidence evidence(PhaseFiveAttempt attempt) {
            return new PhaseFiveEvidence.Pending(
                    attempt.attemptId(), 1L, 1L, Map.of());
        }

        @Override
        public void release(PhaseFiveAttempt attempt) {
            releaseCalls++;
            if (releaseFailuresRemaining > 0) {
                releaseFailuresRemaining--;
                throw new IllegalStateException("release failed once");
            }
        }

        @Override
        public void retire(PhaseFiveRequest request) {
        }
    }
}
