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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void closeRetriesTheSameRoutedAttemptAfterATransientReleaseFailure() {
        var port = new FakePort();
        var operation = new KnownContainerAttempt(port, request(), 1, 101);
        port.tick = 1;
        assertThat(operation.tick(1).status()).isEqualTo(KnownContainerAttempt.Status.RUNNING);
        port.releaseFailuresRemaining = 1;

        assertThatThrownBy(operation::close).hasMessage("container release failed once");
        operation.close();

        assertThat(port.releases).isEqualTo(2);
        assertThat(port.retires).isOne();
    }

    @Test
    void cleanupUsageRemainsDrainableWhileReleaseIsRetried() {
        var port = new FakePort();
        var operation = new KnownContainerAttempt(port, request(), 1, 101);
        port.tick = 1;
        operation.tick(1);
        port.releaseFailuresRemaining = 1;
        port.releaseAddsInteraction = true;

        assertThatThrownBy(operation::close).hasMessage("container release failed once");
        assertThat(operation.releaseStatus())
                .isEqualTo(KnownContainerAttempt.ReleaseStatus.PROGRESSING);
        assertThat(operation.drainReleaseInteractionDelta()).isOne();
        assertThat(port.retires).isZero();

        operation.close();
        assertThat(port.retires).isOne();
    }

    @Test
    void successfulEvidenceStaysPrivateWhileReleaseProgressesAcrossTicks() {
        var port = new FakePort();
        var operation = new KnownContainerAttempt(port, request(), 1, 4);
        port.tick = 1;
        operation.tick(1);
        port.tick = 2;
        operation.tick(2);
        port.releaseFailuresRemaining = 1;
        port.releaseAddsInteraction = true;

        port.tick = 3;
        var releasing = operation.tick(3);
        assertThat(releasing.status()).isEqualTo(KnownContainerAttempt.Status.RUNNING);
        assertThat(releasing.items()).isEmpty();
        assertThat(operation.releaseStatus())
                .isEqualTo(KnownContainerAttempt.ReleaseStatus.PROGRESSING);
        assertThat(port.releases).isOne();
        assertThat(port.retires).isZero();

        port.tick = 4;
        var released = operation.tick(4);
        assertThat(released.status()).isEqualTo(KnownContainerAttempt.Status.SUCCEEDED);
        assertThat(released.interactionDelta()).isOne();
        assertThat(released.items()).containsExactly(
                new KnownContainerAttempt.ItemCount("minecraft:iron_hoe", 1),
                new KnownContainerAttempt.ItemCount("minecraft:wheat_seeds", 64));
        assertThat(port.releases).isEqualTo(2);
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
        private int releaseFailuresRemaining;
        private int interactions;
        private boolean releaseAddsInteraction;
        private boolean releasePending;
        private boolean releaseConfirmed;

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
            interactions = Math.max(interactions, 1);
        }

        @Override
        public PhaseFiveEvidence evidence(PhaseFiveAttempt attempt) {
            Map<String, Object> basis = Map.of(
                    "open_count", Math.min(interactions, 1),
                    "container_clicks", Math.max(0, interactions - 1),
                    "recipe_placements", 0,
                    "release_pending", releasePending,
                    "release_confirmed", releaseConfirmed,
                    "release_fault", false);
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
            releasePending = true;
            if (releaseAddsInteraction) {
                interactions++;
                releaseAddsInteraction = false;
            }
            if (releaseFailuresRemaining-- > 0) {
                throw new IllegalStateException("container release failed once");
            }
            releasePending = false;
            releaseConfirmed = true;
        }

        @Override
        public void retire(PhaseFiveRequest request) {
            retires++;
        }
    }
}
