package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.brewing.StandardPotionStackSpec;
import dev.aod.mcmcp.routine.BlockTarget;
import dev.aod.mcmcp.routine.KnownBrewingRequest;
import dev.aod.mcmcp.routine.PhaseFiveAttempt;
import dev.aod.mcmcp.routine.PhaseFiveEvidence;
import dev.aod.mcmcp.routine.PhaseFiveFrame;
import dev.aod.mcmcp.routine.PhaseFivePort;
import dev.aod.mcmcp.routine.PhaseFiveRequest;
import dev.aod.mcmcp.routine.PhaseFiveResult;
import dev.aod.mcmcp.routine.RoutineFailure;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnownBrewingAttemptTest {
    @Test
    void publishesOnlyExactServerConfirmedBatchAfterReleasingTheMenuAttempt() {
        var port = new FakePort();
        var operation = new KnownBrewingAttempt(port, request(), 10, 1_410);

        port.tick = 10;
        assertThat(operation.tick(10).status()).isEqualTo(KnownBrewingAttempt.Status.RUNNING);

        port.tick = 11;
        port.interactions = 5;
        var pending = operation.tick(11);
        assertThat(pending.status()).isEqualTo(KnownBrewingAttempt.Status.RUNNING);
        assertThat(pending.interactionDelta()).isEqualTo(5);
        assertThat(port.maintained).isTrue();

        port.tick = 12;
        port.interactions = 14;
        port.confirmed = true;
        var result = operation.tick(12);

        assertThat(result.status()).isEqualTo(KnownBrewingAttempt.Status.SUCCEEDED);
        assertThat(result.interactionDelta()).isEqualTo(9);
        assertThat(result.verifiedPotions()).isEqualTo(3);
        assertThat(port.releases).isOne();
        assertThat(port.retires).isOne();
    }

    @Test
    void rejectsUnverifiedGoalButRetainsAttemptUntilTheRuntimeReleaseFenceClosesIt() {
        var port = new FakePort();
        var operation = new KnownBrewingAttempt(port, request(), 10, 1_410);
        port.tick = 10;
        operation.tick(10);

        port.tick = 11;
        port.confirmed = true;
        port.goalVerified = false;
        var result = operation.tick(11);

        assertThat(result.status()).isEqualTo(KnownBrewingAttempt.Status.FAILED);
        assertThat(result.evidence()).isEqualTo("brewing_postcondition_unconfirmed");
        assertThat(port.begins).isOne();
        assertThat(port.releases).isZero();
        assertThat(port.retires).isZero();

        operation.close();
        assertThat(port.releases).isOne();
        assertThat(port.retires).isOne();
    }

    @Test
    void retainsTheFixedAdapterFailureCodeForDiagnosis() {
        var port = new FakePort();
        var operation = new KnownBrewingAttempt(port, request(), 10, 1_410);
        port.tick = 10;
        operation.tick(10);
        port.tick = 11;
        port.failure = new RoutineFailure(
                "BREWING_READBACK_DELTA_MISMATCH",
                RoutineFailure.Category.DIVERGENCE,
                RoutineFailure.Recovery.REPLAN,
                Map.of(), Map.of());

        var result = operation.tick(11);

        assertThat(result.status()).isEqualTo(KnownBrewingAttempt.Status.FAILED);
        assertThat(result.evidence())
                .isEqualTo("brewing_readback_delta_mismatch");
    }

    @Test
    void deadlineWaitsForCleanupAndThenRetainsItsOriginalCause() {
        var port = new FakePort();
        var operation = new KnownBrewingAttempt(port, request(), 10, 12);
        port.tick = 10;
        operation.tick(10);

        port.tick = 12;
        port.interactions = 1;
        var deadline = operation.tick(12);

        assertThat(deadline.status()).isEqualTo(KnownBrewingAttempt.Status.RUNNING);
        assertThat(deadline.evidence()).isNull();
        assertThat(deadline.interactionDelta()).isOne();
        assertThat(port.releasePending).isTrue();
        assertThat(port.releases).isZero();

        port.tick = 13;
        assertThat(operation.tick(13).status()).isEqualTo(KnownBrewingAttempt.Status.RUNNING);
        port.releasePending = false;
        port.inconclusive = true;
        port.tick = 14;
        var failed = operation.tick(14);
        assertThat(failed.status()).isEqualTo(KnownBrewingAttempt.Status.FAILED);
        assertThat(failed.evidence()).isEqualTo("brewing_deadline");

        operation.close();
        assertThat(port.releases).isOne();
        assertThat(port.retires).isOne();
    }

    @Test
    void serverConfirmedEvidenceAtTheDeadlineEdgeWinsOverTimeout() {
        var port = new FakePort();
        var operation = new KnownBrewingAttempt(port, request(), 10, 12);
        port.tick = 10;
        operation.tick(10);

        port.tick = 12;
        port.interactions = 14;
        port.confirmed = true;
        var result = operation.tick(12);

        assertThat(result.status()).isEqualTo(KnownBrewingAttempt.Status.SUCCEEDED);
        assertThat(result.interactionDelta()).isEqualTo(14);
        assertThat(result.verifiedPotions()).isEqualTo(3);
        assertThat(port.releases).isOne();
        assertThat(port.retires).isOne();
    }

    @Test
    void failedReleaseDoesNotRetireAndCanBeRetriedByTheSharedFence() {
        var port = new FakePort();
        var operation = new KnownBrewingAttempt(port, request(), 10, 20);
        port.tick = 10;
        operation.tick(10);
        port.releaseFailures = 1;

        assertThatThrownBy(operation::close).isInstanceOf(IllegalStateException.class);
        assertThat(port.retires).isZero();
        operation.close();
        assertThat(port.releases).isEqualTo(2);
        assertThat(port.retires).isOne();
    }

    @Test
    void requestClosesFuelCountAndStationaryDeadline() {
        BlockTarget target = new BlockTarget("minecraft:overworld", 4, 64, 8);
        var input = new StandardPotionStackSpec("minecraft:potion", "minecraft:water", 3);
        var output = new StandardPotionStackSpec("minecraft:potion", "minecraft:awkward", 3);

        assertThat(new KnownBrewingRequest(
                target, input, "minecraft:nether_wart", "minecraft:blaze_powder", output)
                .operation().bounds().maxDurationSeconds()).isEqualTo(70);
        assertThat(KnownBrewingRequest.MAX_TICKS).isEqualTo(1_400);
        assertThat(KnownBrewingRequest.MAX_ROUND_TRIP_CAMERA_DEGREES).isEqualTo(540.0F);
        assertThatThrownBy(() -> new KnownBrewingRequest(
                target, input, "minecraft:nether_wart", "minecraft:coal", output))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnownBrewingRequest(
                target, input, "minecraft:nether_wart", "minecraft:blaze_powder",
                output, Float.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnownBrewingRequest(
                target, input, "minecraft:nether_wart", "minecraft:blaze_powder",
                output, 0.5F)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnownBrewingRequest(
                target, input, "minecraft:nether_wart", "minecraft:blaze_powder",
                output, 18.5F)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnownBrewingRequest(
                target, input, "minecraft:nether_wart", "minecraft:blaze_powder",
                new StandardPotionStackSpec("minecraft:potion", "minecraft:awkward", 2)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static KnownBrewingRequest request() {
        return new KnownBrewingRequest(
                new BlockTarget("minecraft:overworld", 4, 64, 8),
                new StandardPotionStackSpec("minecraft:potion", "minecraft:water", 3),
                "minecraft:nether_wart",
                "minecraft:blaze_powder",
                new StandardPotionStackSpec("minecraft:potion", "minecraft:awkward", 3));
    }

    private static final class FakePort implements PhaseFivePort {
        private long tick;
        private int interactions;
        private boolean maintained;
        private boolean confirmed;
        private boolean goalVerified = true;
        private boolean releasePending;
        private boolean inconclusive;
        private int begins;
        private int releases;
        private int retires;
        private int releaseFailures;
        private RoutineFailure failure;

        @Override
        public PhaseFiveFrame observe(PhaseFiveRequest request) {
            return new PhaseFiveFrame(tick, tick, null);
        }

        @Override
        public PhaseFiveAttempt begin(
                UUID routineId, PhaseFiveRequest request, long hardDeadlineClientTick) {
            begins++;
            return new PhaseFiveAttempt(
                    routineId, request.kind(), tick, tick, hardDeadlineClientTick, Map.of());
        }

        @Override
        public void maintain(PhaseFiveAttempt attempt) {
            maintained = true;
            if (tick >= attempt.hardDeadlineClientTick()) {
                releasePending = true;
            }
        }

        @Override
        public PhaseFiveEvidence evidence(PhaseFiveAttempt attempt) {
            Map<String, Object> basis = Map.of(
                    "open_count", Math.min(interactions, 1),
                    "container_clicks", Math.max(0, interactions - 1),
                    "release_pending", releasePending,
                    "release_confirmed", false,
                    "release_fault", false);
            if (inconclusive) {
                return new PhaseFiveEvidence.Inconclusive(
                        attempt.attemptId(), tick, tick,
                        PhaseFiveEvidence.Certainty.UNKNOWN,
                        "brewing_action_hard_deadline_exceeded", basis);
            }
            if (failure != null) {
                return new PhaseFiveEvidence.Failed(
                        attempt.attemptId(), tick, tick, failure, basis);
            }
            if (!confirmed) {
                return new PhaseFiveEvidence.Pending(
                        attempt.attemptId(), tick, tick, basis);
            }
            return new PhaseFiveEvidence.ServerConfirmed(
                    attempt.attemptId(), tick, tick,
                    new PhaseFiveResult(3, goalVerified, Map.of(), List.of()),
                    basis);
        }

        @Override
        public void release(PhaseFiveAttempt attempt) {
            releases++;
            if (releaseFailures-- > 0) {
                throw new IllegalStateException("release pending");
            }
            releasePending = false;
        }

        @Override
        public void retire(PhaseFiveRequest request) {
            retires++;
        }
    }
}
