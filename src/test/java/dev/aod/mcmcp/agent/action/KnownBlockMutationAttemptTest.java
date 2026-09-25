package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.routine.ActionBounds;
import dev.aod.mcmcp.routine.BlockStateFingerprint;
import dev.aod.mcmcp.routine.BlockTarget;
import dev.aod.mcmcp.routine.PlaceBlockRequest;
import dev.aod.mcmcp.routine.SemanticActionAttempt;
import dev.aod.mcmcp.routine.SemanticActionEvidence;
import dev.aod.mcmcp.routine.SemanticActionFrame;
import dev.aod.mcmcp.routine.SemanticActionPort;
import dev.aod.mcmcp.routine.SemanticActionPreparationAttempt;
import dev.aod.mcmcp.routine.SemanticActionPreparationEvidence;
import dev.aod.mcmcp.routine.SemanticActionRequest;
import dev.aod.mcmcp.routine.UseItemOnBlockRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnownBlockMutationAttemptTest {
    private static final BlockStateFingerprint DIRT =
            new BlockStateFingerprint("minecraft:dirt", Map.of());
    private static final BlockStateFingerprint FARMLAND =
            new BlockStateFingerprint("minecraft:farmland", Map.of("moisture", "0"));
    private static final BlockStateFingerprint LEVER_OFF = new BlockStateFingerprint(
            "minecraft:lever", Map.of("face", "wall", "facing", "north", "powered", "false"));
    private static final BlockStateFingerprint LEVER_ON = new BlockStateFingerprint(
            "minecraft:lever", Map.of("face", "wall", "facing", "north", "powered", "true"));

    private static KnownBlockMutationAttempt lever(FakePort port, boolean powered) {
        return lever(port, powered, Optional::empty);
    }

    private static KnownBlockMutationAttempt lever(FakePort port, boolean powered,
            java.util.function.Supplier<Optional<String>> witness) {
        var target = new BlockTarget("minecraft:overworld", 1, 64, 1);
        port.preparedLive = LEVER_OFF;
        port.confirmedLive = LEVER_ON;
        return new KnownBlockMutationAttempt(port, new dev.aod.mcmcp.routine.InteractBlockRequest(
                target, LEVER_OFF, powered ? LEVER_ON : LEVER_OFF,
                new ActionBounds(target.dimension(), target, target, 0, 5, false)), 1, 101, witness);
    }

    @Test
    void leverNoOpWaitsForCurrentVisibilityAndKeepsItsOriginalDeadline() {
        var port = new FakePort();
        port.unpreparedLive = Optional.of(LEVER_OFF);
        var attempt = lever(port, false, () -> Optional.of("renderer_evidence_missing"));
        assertThat(attempt.tick(1).status()).isEqualTo(KnownBlockMutationAttempt.Status.RUNNING);
        assertThat(port.observations).isZero();
        assertThat(port.preparation).isNull();
        assertThat(attempt.tick(100).status()).isEqualTo(KnownBlockMutationAttempt.Status.RUNNING);
        assertThat(attempt.tick(101).status()).isEqualTo(KnownBlockMutationAttempt.Status.FAILED);
        assertThat(port.dispatchCalls).isZero();
        assertThat(attempt.drainLeverEffect()).isEmpty();
    }

    @Test
    void leverRechecksVisibilityAfterPreparationButDoesNotGateSentAckOrCleanup() {
        var port = new FakePort();
        var rejection = new java.util.concurrent.atomic.AtomicReference<Optional<String>>(Optional.empty());
        var attempt = lever(port, true, () -> {
            assertThat(port.dispatched).as("visibility gate is only for unsent use").isFalse();
            return rejection.get();
        });
        attempt.tick(1);
        assertThat(port.preparation).isNotNull();
        rejection.set(Optional.of("renderer_evidence_missing"));
        port.tick = 2;
        assertThat(attempt.tick(2).dispatchedThisTick()).isFalse();
        assertThat(port.dispatchCalls).isZero();
        rejection.set(Optional.empty());
        port.tick = 3;
        assertThat(attempt.tick(3).dispatchedThisTick()).isTrue();
        rejection.set(Optional.of("delivery_expired"));
        port.tick = 4;
        assertThat(attempt.tick(4).status()).isEqualTo(KnownBlockMutationAttempt.Status.SUCCEEDED);
        attempt.close();
        assertThat(port.dispatchCalls).isOne();
        assertThat(attempt.drainLeverEffect().orElseThrow().verification())
                .isEqualTo(AgentActionStore.Verification.CONFIRMED);
    }

    @Test
    void leverRejectsExpiredOrChangedEvidenceAfterWaitingWithoutAnyUse() {
        for (String failure : java.util.List.of("delivery_expired", "surface_reobservation_mismatch", "safety_changed")) {
            for (boolean prepared : new boolean[] {false, true}) {
                var port = new FakePort();
                port.unpreparedLive = prepared ? Optional.empty() : Optional.of(LEVER_OFF);
                var rejection = new java.util.concurrent.atomic.AtomicReference<Optional<String>>(
                        prepared ? Optional.empty() : Optional.of("renderer_evidence_missing"));
                var attempt = lever(port, prepared, rejection::get);
                attempt.tick(1);
                rejection.set(Optional.of("renderer_evidence_missing"));
                port.tick = 2;
                assertThat(attempt.tick(2).status()).isEqualTo(KnownBlockMutationAttempt.Status.RUNNING);
                rejection.set(Optional.of(failure));
                port.tick = 3;
                var result = attempt.tick(3);
                assertThat(result.status()).isEqualTo(KnownBlockMutationAttempt.Status.FAILED);
                assertThat(result.evidence()).isEqualTo(failure);
                assertThat(port.dispatchCalls).isZero();
                assertThat(attempt.drainLeverEffect()).isEmpty();
            }
        }
    }

    @Test
    void leverConfirmsOnlyAfterAckAndDrainsTheActualBeforeAndAfterOnce() {
        var port = new FakePort();
        var attempt = lever(port, true);
        attempt.tick(1);
        port.tick = 2;
        attempt.tick(2);
        assertThat(attempt.hasPotentialLeverDispatch()).isTrue();
        assertThat(attempt.drainLeverInteractions()).isOne();
        assertThat(attempt.drainLeverInteractions()).isZero();
        assertThat(attempt.drainLeverEffect()).isEmpty();
        port.acknowledged = false;
        port.tick = 3;
        assertThat(attempt.tick(3).status()).isEqualTo(KnownBlockMutationAttempt.Status.RUNNING);
        assertThat(attempt.drainLeverEffect()).isEmpty();
        port.acknowledged = true;
        port.tick = 4;
        assertThat(attempt.tick(4).status()).isEqualTo(KnownBlockMutationAttempt.Status.SUCCEEDED);
        var effect = attempt.drainLeverEffect().orElseThrow();
        assertThat(effect.verification()).isEqualTo(AgentActionStore.Verification.CONFIRMED);
        assertThat(effect.observedBefore()).containsEntry("properties", LEVER_OFF.properties());
        assertThat(effect.observedAfter()).containsEntry("properties", LEVER_ON.properties());
        attempt.close();
        assertThat(attempt.drainLeverEffect()).isEmpty();
        assertThat(port.dispatchCalls).isOne();
    }

    @Test
    void satisfiedLeverHasNoInteractionOrEffectButStillRequiresSafety() {
        var port = new FakePort();
        port.unpreparedLive = Optional.of(LEVER_OFF);
        var attempt = lever(port, false);
        assertThat(attempt.tick(1).status()).isEqualTo(KnownBlockMutationAttempt.Status.SUCCEEDED);
        assertThat(port.dispatchCalls).isZero();
        assertThat(attempt.drainLeverInteractions()).isZero();
        assertThat(attempt.drainLeverEffect()).isEmpty();
        var unsafe = new FakePort();
        unsafe.unpreparedLive = Optional.of(LEVER_OFF);
        unsafe.controlContextClear = false;
        assertThat(lever(unsafe, false).tick(1).status()).isEqualTo(KnownBlockMutationAttempt.Status.FAILED);
    }

    @Test
    void leverTimeoutOrCancellationRetainsUnknownDispatchWithoutInventingAfterState() {
        for (boolean timeout : new boolean[] {false, true}) {
            var port = new FakePort();
            var attempt = lever(port, true);
            attempt.tick(1);
            port.tick = 2;
            attempt.tick(2);
            port.acknowledged = false;
            if (timeout) assertThat(attempt.tick(101).status()).isEqualTo(KnownBlockMutationAttempt.Status.FAILED);
            else attempt.close();
            var effect = attempt.drainLeverEffect().orElseThrow();
            assertThat(effect.verification()).isEqualTo(AgentActionStore.Verification.UNKNOWN);
            assertThat(effect.observedAfter()).isEmpty();
            assertThat(attempt.drainLeverInteractions()).isOne();
            attempt.close();
            assertThat(attempt.drainLeverEffect()).isEmpty();
            assertThat(port.dispatchCalls).isOne();
        }
    }

    @Test
    void leverDispatchExceptionStillRetainsAnUnknownEffectThroughCleanup() {
        var port = new FakePort();
        port.dispatchThrows = true;
        var attempt = lever(port, true);
        attempt.tick(1);
        port.tick = 2;
        assertThatThrownBy(() -> attempt.tick(2)).isInstanceOf(IllegalStateException.class);
        attempt.close();
        assertThat(attempt.drainLeverEffect().orElseThrow().verification())
                .isEqualTo(AgentActionStore.Verification.UNKNOWN);
        assertThat(attempt.drainLeverInteractions()).isOne();
        assertThat(port.dispatchCalls).isOne();
    }

    @Test
    void changedLeverMountingFailsBeforeDispatch() {
        var port = new FakePort();
        var attempt = lever(port, true);
        attempt.tick(1);
        port.preparedLive = new BlockStateFingerprint("minecraft:lever",
                Map.of("face", "floor", "facing", "north", "powered", "false"));
        port.tick = 2;
        assertThat(attempt.tick(2).status()).isEqualTo(KnownBlockMutationAttempt.Status.FAILED);
        assertThat(port.dispatchCalls).isZero();
        assertThat(attempt.drainLeverEffect()).isEmpty();
    }

    @Test
    void succeedsOnlyAfterPreparedDispatchAndAuthoritativeAck() {
        var port = new FakePort();
        var target = new BlockTarget("minecraft:overworld", 1, 64, 1);
        var request = new UseItemOnBlockRequest(
                target, DIRT, "minecraft:iron_hoe", FARMLAND,
                new ActionBounds(target.dimension(), target, target, 0, 5, false));
        var attempt = new KnownBlockMutationAttempt(port, request, 1, 101);

        assertThat(attempt.tick(1).status())
                .isEqualTo(KnownBlockMutationAttempt.Status.RUNNING);
        port.tick = 2;
        var dispatch = attempt.tick(2);
        assertThat(dispatch.status())
                .isEqualTo(KnownBlockMutationAttempt.Status.RUNNING);
        assertThat(dispatch.dispatchedThisTick()).isTrue();
        port.tick = 3;
        var result = attempt.tick(3);
        assertThat(result.status()).isEqualTo(KnownBlockMutationAttempt.Status.SUCCEEDED);
        assertThat(result.performed()).isTrue();
        assertThat(port.dispatched).isTrue();
        assertThat(port.stopped).isTrue();
        assertThat(port.retired).isTrue();
    }

    @Test
    void alreadySatisfiedPostconditionSucceedsWithoutCountingAnInteraction() {
        var port = new FakePort();
        port.unpreparedLive = Optional.of(FARMLAND);
        var target = new BlockTarget("minecraft:overworld", 1, 64, 1);
        var request = new UseItemOnBlockRequest(
                target, DIRT, "minecraft:iron_hoe", FARMLAND,
                new ActionBounds(target.dimension(), target, target, 0, 5, false));

        var result = new KnownBlockMutationAttempt(port, request, 1, 101).tick(1);

        assertThat(result.status()).isEqualTo(KnownBlockMutationAttempt.Status.SUCCEEDED);
        assertThat(result.performed()).isFalse();
        assertThat(port.dispatched).isFalse();
        assertThat(port.retired).isTrue();
    }

    @Test
    void hydratedFarmlandAfterAckStillConfirmsTheCompletedTill() {
        var port = new FakePort();
        port.confirmedLive = new BlockStateFingerprint(
                "minecraft:farmland", Map.of("moisture", "7"));
        var target = new BlockTarget("minecraft:overworld", 1, 64, 1);
        var request = new UseItemOnBlockRequest(
                target, DIRT, "minecraft:iron_hoe", FARMLAND,
                new ActionBounds(target.dimension(), target, target, 0, 5, false));
        var attempt = new KnownBlockMutationAttempt(port, request, 1, 101);

        assertThat(attempt.tick(1).status())
                .isEqualTo(KnownBlockMutationAttempt.Status.RUNNING);
        port.tick = 2;
        assertThat(attempt.tick(2).status())
                .isEqualTo(KnownBlockMutationAttempt.Status.RUNNING);
        port.tick = 3;
        assertThat(attempt.tick(3).status())
                .isEqualTo(KnownBlockMutationAttempt.Status.SUCCEEDED);
    }

    @Test
    void grownWheatAfterAckStillConfirmsTheCompletedPlant() {
        var port = new FakePort();
        port.preparedLive = new BlockStateFingerprint("minecraft:air", Map.of());
        port.confirmedLive = new BlockStateFingerprint(
                "minecraft:wheat", Map.of("age", "7"));
        var target = new BlockTarget("minecraft:overworld", 1, 65, 1);
        var request = new PlaceBlockRequest(
                target,
                port.preparedLive,
                "minecraft:wheat_seeds",
                new BlockStateFingerprint("minecraft:wheat", Map.of("age", "0")),
                new ActionBounds(target.dimension(), target, target, 0, 5, false));
        var attempt = new KnownBlockMutationAttempt(port, request, 1, 101);

        assertThat(attempt.tick(1).status())
                .isEqualTo(KnownBlockMutationAttempt.Status.RUNNING);
        port.tick = 2;
        assertThat(attempt.tick(2).status())
                .isEqualTo(KnownBlockMutationAttempt.Status.RUNNING);
        port.tick = 3;
        assertThat(attempt.tick(3).status())
                .isEqualTo(KnownBlockMutationAttempt.Status.SUCCEEDED);
    }

    @Test
    void dispatchSignalIsAbsentWhilePreparationWaitsAndFiresOnlyOnActualDispatch() {
        var port = new FakePort();
        port.prepared = false;
        var target = new BlockTarget("minecraft:overworld", 1, 64, 1);
        var request = new UseItemOnBlockRequest(
                target, DIRT, "minecraft:iron_hoe", FARMLAND,
                new ActionBounds(target.dimension(), target, target, 0, 5, false));
        var attempt = new KnownBlockMutationAttempt(port, request, 1, 101);

        assertThat(attempt.tick(1).dispatchedThisTick()).isFalse();
        for (int tick = 2; tick <= 5; tick++) {
            port.tick = tick;
            assertThat(attempt.tick(tick).dispatchedThisTick()).isFalse();
            assertThat(port.dispatched).isFalse();
        }
        port.prepared = true;
        port.tick = 6;
        assertThat(attempt.tick(6).dispatchedThisTick()).isTrue();
        assertThat(port.dispatched).isTrue();
        port.tick = 7;
        assertThat(attempt.tick(7).dispatchedThisTick()).isFalse();
    }

    @Test
    void reportsSafetyChangeSeparatelyFromMutationPreconditionChange() {
        var port = new FakePort();
        port.controlContextClear = false;
        var target = new BlockTarget("minecraft:overworld", 1, 64, 1);
        var request = new UseItemOnBlockRequest(
                target, DIRT, "minecraft:iron_hoe", FARMLAND,
                new ActionBounds(target.dimension(), target, target, 0, 5, false));

        var result = new KnownBlockMutationAttempt(port, request, 1, 101).tick(1);

        assertThat(result.status()).isEqualTo(KnownBlockMutationAttempt.Status.FAILED);
        assertThat(result.evidence()).isEqualTo("mutation_safety_changed");
        assertThat(port.preparation).isNull();
        assertThat(port.retired).isTrue();
    }

    @Test
    void keepsLiveBlockMismatchAsMutationPreconditionChange() {
        var port = new FakePort();
        port.unpreparedLive = Optional.of(new BlockStateFingerprint(
                "minecraft:coarse_dirt", Map.of()));
        var target = new BlockTarget("minecraft:overworld", 1, 64, 1);
        var request = new UseItemOnBlockRequest(
                target, DIRT, "minecraft:iron_hoe", FARMLAND,
                new ActionBounds(target.dimension(), target, target, 0, 5, false));

        var result = new KnownBlockMutationAttempt(port, request, 1, 101).tick(1);

        assertThat(result.status()).isEqualTo(KnownBlockMutationAttempt.Status.FAILED);
        assertThat(result.evidence()).isEqualTo("mutation_precondition_changed");
        assertThat(port.preparation).isNull();
        assertThat(port.retired).isTrue();
    }

    @Test
    void closeRetriesTheSamePreparationAfterATransientReleaseFailure() {
        var port = new FakePort();
        port.releasePreparationFailuresRemaining = 1;
        var target = new BlockTarget("minecraft:overworld", 1, 64, 1);
        var request = new UseItemOnBlockRequest(
                target, DIRT, "minecraft:iron_hoe", FARMLAND,
                new ActionBounds(target.dimension(), target, target, 0, 5, false));
        var attempt = new KnownBlockMutationAttempt(port, request, 1, 101);
        assertThat(attempt.tick(1).status()).isEqualTo(KnownBlockMutationAttempt.Status.RUNNING);

        assertThatThrownBy(attempt::close).hasMessage("preparation release failed once");
        attempt.close();

        assertThat(port.releasePreparationCalls).isEqualTo(2);
        assertThat(port.retireCalls).isEqualTo(2);
    }

    private static final class FakePort implements SemanticActionPort {
        private long tick = 1;
        private SemanticActionPreparationAttempt preparation;
        private SemanticActionAttempt action;
        private boolean dispatched;
        private int observations;
        private int dispatchCalls;
        private boolean dispatchThrows;
        private boolean acknowledged = true;
        private boolean stopped;
        private boolean retired;
        private boolean prepared = true;
        private boolean controlContextClear = true;
        private int releasePreparationCalls;
        private int releasePreparationFailuresRemaining;
        private int retireCalls;
        private Optional<BlockStateFingerprint> unpreparedLive = Optional.empty();
        private BlockStateFingerprint preparedLive = DIRT;
        private BlockStateFingerprint confirmedLive = FARMLAND;

        @Override
        public SemanticActionFrame observe(SemanticActionRequest request) {
            observations++;
            return new SemanticActionFrame(
                    tick, tick, true, controlContextClear, true, true, true, true,
                    preparation == null ? unpreparedLive : Optional.of(preparedLive), true, true,
                    false, Optional.empty(), false, false, false, false,
                    0, true, 0, 64, 0, 0, true, true, "not_applicable", 0, true);
        }

        @Override
        public SemanticActionPreparationAttempt beginPreparation(
                SemanticActionRequest request, long deadline) {
            preparation = new SemanticActionPreparationAttempt(
                    UUID.randomUUID(), request.kind(), tick, tick, deadline, 0);
            return preparation;
        }

        @Override public void maintainPreparation(SemanticActionPreparationAttempt attempt) { }

        @Override
        public SemanticActionPreparationEvidence preparationEvidence(
                SemanticActionPreparationAttempt attempt) {
            return new SemanticActionPreparationEvidence(
                    attempt.attemptId(), tick, tick, Optional.of(preparedLive),
                    prepared, true, true, null);
        }

        @Override public void releasePreparation(SemanticActionPreparationAttempt attempt) {
            releasePreparationCalls++;
            if (releasePreparationFailuresRemaining-- > 0) {
                throw new IllegalStateException("preparation release failed once");
            }
        }

        @Override
        public SemanticActionAttempt dispatchPrepared(
                SemanticActionRequest request,
                SemanticActionPreparationAttempt preparation,
                long deadline) {
            dispatched = true;
            dispatchCalls++;
            if (dispatchThrows) throw new IllegalStateException("ambiguous dispatch");
            action = new SemanticActionAttempt(
                    UUID.randomUUID(), request.kind(), tick, tick, deadline, 0, Map.of());
            return action;
        }

        @Override
        public SemanticActionAttempt dispatch(SemanticActionRequest request, long deadline) {
            throw new UnsupportedOperationException();
        }

        @Override public void maintain(SemanticActionAttempt attempt) { }
        @Override public void stopInput(SemanticActionAttempt attempt) { stopped = true; }

        @Override
        public SemanticActionEvidence evidence(SemanticActionAttempt attempt) {
            return new SemanticActionEvidence(
                    action.attemptId(), tick, tick, acknowledged, Optional.of(confirmedLive),
                    false, true, 0, null, false, Map.of());
        }

        @Override public void release(SemanticActionAttempt attempt) { }
        @Override public void retire(SemanticActionRequest request) {
            retireCalls++;
            retired = true;
        }
    }
}
