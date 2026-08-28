package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.routine.BlockTarget;
import dev.aod.mcmcp.routine.PhaseFiveAttempt;
import dev.aod.mcmcp.routine.PhaseFiveBounds;
import dev.aod.mcmcp.routine.PhaseFiveEvidence;
import dev.aod.mcmcp.routine.PhaseFiveFrame;
import dev.aod.mcmcp.routine.PhaseFivePort;
import dev.aod.mcmcp.routine.PhaseFiveRequest;
import dev.aod.mcmcp.routine.PhaseFiveResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class KnownContainerAttemptTest {
    @Test
    void exposesOnlyServerConfirmedItemsAndAccountsForTheActualOpen() {
        var port = new FakePort();
        var operation = new KnownContainerAttempt(port, request(), 1, 101);

        port.tick = 1;
        assertThat(operation.tick(1).status()).isEqualTo(KnownContainerAttempt.Status.RUNNING);

        port.tick = 2;
        assertThat(operation.tick(2).status()).isEqualTo(KnownContainerAttempt.Status.RUNNING);
        assertThat(port.maintained).isTrue();

        port.tick = 3;
        var result = operation.tick(3);
        assertThat(result.status()).isEqualTo(KnownContainerAttempt.Status.SUCCEEDED);
        assertThat(result.interactionDelta()).isOne();
        assertThat(result.items()).containsExactly(
                new KnownContainerAttempt.ItemCount("minecraft:iron_hoe", 1),
                new KnownContainerAttempt.ItemCount("minecraft:wheat_seeds", 64));
        assertThat(port.releases).isOne();
        assertThat(port.retires).isOne();
    }

    private static PhaseFiveRequest request() {
        var target = new BlockTarget("minecraft:overworld", 1, 64, 2);
        return new PhaseFiveRequest(
                "transfer_items",
                Map.of(),
                new PhaseFiveBounds(target.dimension(), target, target, 0, 120, false),
                0,
                "items");
    }

    private static final class FakePort implements PhaseFivePort {
        private long tick;
        private PhaseFiveAttempt attempt;
        private boolean maintained;
        private int releases;
        private int retires;

        @Override
        public PhaseFiveFrame observe(PhaseFiveRequest request) {
            return new PhaseFiveFrame(tick, maintained ? 2 : 1, null);
        }

        @Override
        public PhaseFiveAttempt begin(
                UUID routineId, PhaseFiveRequest request, long hardDeadlineClientTick) {
            attempt = new PhaseFiveAttempt(
                    routineId, request.kind(), tick, 1, hardDeadlineClientTick, Map.of());
            return attempt;
        }

        @Override
        public void maintain(PhaseFiveAttempt attempt) {
            maintained = true;
        }

        @Override
        public PhaseFiveEvidence evidence(PhaseFiveAttempt attempt) {
            Map<String, Object> basis = Map.of(
                    "open_count", maintained ? 1 : 0,
                    "container_clicks", 0);
            if (!maintained) {
                return new PhaseFiveEvidence.Pending(attempt.attemptId(), tick, 1, basis);
            }
            var items = List.of(
                    Map.of("item", "minecraft:iron_hoe", "count", 1),
                    Map.of("item", "minecraft:wheat_seeds", "count", 64));
            var result = new PhaseFiveResult(
                    0, true, Map.of("available_source_items", items), List.of());
            return new PhaseFiveEvidence.ServerConfirmed(
                    attempt.attemptId(), tick, 2, result, basis);
        }

        @Override
        public void release(PhaseFiveAttempt attempt) {
            releases++;
        }

        @Override
        public void retire(PhaseFiveRequest request) {
            retires++;
        }
    }
}
