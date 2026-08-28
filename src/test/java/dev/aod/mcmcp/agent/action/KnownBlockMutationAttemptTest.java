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

class KnownBlockMutationAttemptTest {
    private static final BlockStateFingerprint DIRT =
            new BlockStateFingerprint("minecraft:dirt", Map.of());
    private static final BlockStateFingerprint FARMLAND =
            new BlockStateFingerprint("minecraft:farmland", Map.of("moisture", "0"));

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

    private static final class FakePort implements SemanticActionPort {
        private long tick = 1;
        private SemanticActionPreparationAttempt preparation;
        private SemanticActionAttempt action;
        private boolean dispatched;
        private boolean stopped;
        private boolean retired;
        private boolean prepared = true;
        private Optional<BlockStateFingerprint> unpreparedLive = Optional.empty();
        private BlockStateFingerprint preparedLive = DIRT;
        private BlockStateFingerprint confirmedLive = FARMLAND;

        @Override
        public SemanticActionFrame observe(SemanticActionRequest request) {
            return new SemanticActionFrame(
                    tick, tick, true, true, true, true, true, true,
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

        @Override public void releasePreparation(SemanticActionPreparationAttempt attempt) { }

        @Override
        public SemanticActionAttempt dispatchPrepared(
                SemanticActionRequest request,
                SemanticActionPreparationAttempt preparation,
                long deadline) {
            dispatched = true;
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
                    action.attemptId(), tick, tick, true, Optional.of(confirmedLive),
                    false, true, 0, null, false, Map.of());
        }

        @Override public void release(SemanticActionAttempt attempt) { }
        @Override public void retire(SemanticActionRequest request) { retired = true; }
    }
}
