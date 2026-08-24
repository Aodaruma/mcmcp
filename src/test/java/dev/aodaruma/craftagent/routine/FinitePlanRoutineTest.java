package dev.aodaruma.craftagent.routine;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FinitePlanRoutineTest {
    @Test
    void managerRunsOnlyTheParentThroughActionWaitRepeatAndAssert() {
        var port = new FakePlanPort();
        var manager = new RoutineManager(new NoopStationaryPort(), port);
        var key = UUID.randomUUID().toString();
        var receipt = manager.startFinitePlan(key, "same-plan", successfulPlan(), port.tick);

        assertThat(manager.replayFinitePlan(key, "same-plan", port.tick))
                .contains(new RoutineManager.StartReceipt(receipt.routineId(), true));
        assertThat(manager.retainedRoutineCount()).isEqualTo(1);

        advance(manager, port); // queued -> execute
        advance(manager, port); // begin take-seeds
        advance(manager, port); // pending action
        assertThat(manager.getRoutine(receipt.routineId(), 0, 32).state())
                .isEqualTo(RoutineState.WAITING);
        assertThat(port.maintainCount).isEqualTo(1);

        port.confirm = true;
        advance(manager, port); // confirm take-seeds
        advance(manager, port); // wait condition is already true
        advance(manager, port); // enter repeat
        advance(manager, port); // known-false condition starts body
        advance(manager, port); // begin harvest
        advance(manager, port); // pending harvest
        port.confirm = true;
        advance(manager, port); // confirm harvest
        advance(manager, port); // finish repeat body
        advance(manager, port); // repeat condition is now positively true
        advance(manager, port); // final assertion is positively true
        advance(manager, port); // sequence complete -> finalizing

        var finalizing = manager.getRoutine(receipt.routineId(), 0, 64);
        assertThat(finalizing.state()).isEqualTo(RoutineState.FINALIZING);
        assertThat(finalizing.goalVerified()).isTrue();
        assertThat(finalizing.diagnostics()).containsEntry("action_attempts", 2);
        assertThat(port.beginCount).isEqualTo(2);
        assertThat(port.releaseCount).isEqualTo(2);
        assertThat(manager.retainedRoutineCount()).isEqualTo(1);

        var succeeded = manager.completeFinalization(receipt.routineId(), null, 0, 16);
        assertThat(succeeded.state()).isEqualTo(RoutineState.SUCCEEDED);
        assertThat(port.retireCount).isEqualTo(1);
    }

    @Test
    void cancellationReleasesThePrivateChildAndUnknownAssertionNeverPasses() {
        var cancelPort = new FakePlanPort();
        var cancelManager = new RoutineManager(new NoopStationaryPort(), cancelPort);
        var cancelledReceipt = cancelManager.startFinitePlan(
                UUID.randomUUID().toString(), oneActionPlan(), cancelPort.tick);
        advance(cancelManager, cancelPort);
        advance(cancelManager, cancelPort);

        var cancelled = cancelManager.cancelRoutine(
                cancelledReceipt.routineId(), "operator", 0, 16);

        assertThat(cancelled.state()).isEqualTo(RoutineState.CANCELLED);
        assertThat(cancelManager.activeRoutineId()).isEmpty();
        assertThat(cancelManager.retainedRoutineCount()).isEqualTo(1);
        assertThat(cancelPort.releaseCount).isEqualTo(1);
        assertThat(cancelPort.retireCount).isEqualTo(1);

        var unknownPort = new FakePlanPort();
        unknownPort.conditionUnknown = true;
        var unknownManager = new RoutineManager(new NoopStationaryPort(), unknownPort);
        var unknownReceipt = unknownManager.startFinitePlan(
                UUID.randomUUID().toString(), assertionPlan(), unknownPort.tick);
        advance(unknownManager, unknownPort);
        advance(unknownManager, unknownPort);

        var failed = unknownManager.getRoutine(unknownReceipt.routineId(), 0, 16);
        assertThat(failed.state()).isEqualTo(RoutineState.FAILED);
        assertThat(failed.failure().code()).isEqualTo("ASSERTION_UNKNOWN");
        assertThat(unknownPort.beginCount).isZero();
    }

    private static FinitePlanRequest successfulPlan() {
        var goal = new FinitePlanRequest.InventoryAtLeast("minecraft:wheat", 2);
        return new FinitePlanRequest("wheat-stack", 200, java.util.List.of(
                action("take-seeds", 1),
                new FinitePlanRequest.WaitUntil(
                        "seeds-ready",
                        new FinitePlanRequest.InventoryAtLeast("minecraft:wheat", 1),
                        20),
                new FinitePlanRequest.RepeatUntil(
                        "harvest-until-goal",
                        goal,
                        3,
                        100,
                        java.util.List.of(action("harvest", 1))),
                new FinitePlanRequest.Assert("goal-check", goal)));
    }

    private static FinitePlanRequest oneActionPlan() {
        return new FinitePlanRequest(
                "one-action", 100, java.util.List.of(action("work", 1)));
    }

    private static FinitePlanRequest assertionPlan() {
        return new FinitePlanRequest(
                "assertion", 100, java.util.List.of(new FinitePlanRequest.Assert(
                        "check", new FinitePlanRequest.InventoryAtLeast("minecraft:wheat", 1))));
    }

    private static FinitePlanRequest.Action action(String id, int delta) {
        return new FinitePlanRequest.Action(
                id,
                FinitePlanRequest.RoutineKind.TRANSFER_ITEMS,
                Map.of("parameters", Map.of("delta", delta), "bounds", Map.of()));
    }

    private static void advance(RoutineManager manager, FakePlanPort port) {
        port.tick++;
        manager.tick();
    }

    private static final class FakePlanPort implements FinitePlanPort {
        private long tick = 10;
        private long revision = 1;
        private int wheat;
        private int beginCount;
        private int maintainCount;
        private int releaseCount;
        private int retireCount;
        private boolean confirm;
        private boolean conditionUnknown;
        private boolean applied;
        private FinitePlanRequest.Action activeAction;
        private ActionAttempt activeAttempt;

        @Override
        public Frame observe(FinitePlanRequest request) {
            return new Frame(tick, revision, null);
        }

        @Override
        public ConditionEvidence evaluate(FinitePlanRequest.Condition condition) {
            var status = conditionUnknown
                    ? ConditionStatus.UNKNOWN
                    : condition instanceof FinitePlanRequest.InventoryAtLeast inventory
                            && wheat >= inventory.minimumCount()
                            ? ConditionStatus.SATISFIED : ConditionStatus.UNSATISFIED;
            return new ConditionEvidence(tick, revision, status, Map.of("wheat", wheat));
        }

        @Override
        public ActionAttempt begin(
                UUID parentRoutineId,
                FinitePlanRequest.Action action,
                long hardDeadlineClientTick) {
            beginCount++;
            activeAction = action;
            activeAttempt = new ActionAttempt(
                    UUID.randomUUID(), parentRoutineId, action.id(), tick, revision,
                    hardDeadlineClientTick);
            applied = false;
            confirm = false;
            return activeAttempt;
        }

        @Override
        public void maintain(ActionAttempt attempt) {
            assertThat(attempt).isEqualTo(activeAttempt);
            maintainCount++;
        }

        @Override
        public ActionEvidence evidence(ActionAttempt attempt) {
            assertThat(attempt).isEqualTo(activeAttempt);
            if (confirm && !applied) {
                @SuppressWarnings("unchecked")
                var parameters = (Map<String, Object>) activeAction.arguments().get("parameters");
                wheat += ((Number) parameters.get("delta")).intValue();
                revision++;
                applied = true;
            }
            return new ActionEvidence(
                    attempt.attemptId(), tick, revision, confirm, null,
                    Map.of("server_positive", confirm));
        }

        @Override
        public void release(ActionAttempt attempt) {
            assertThat(attempt).isEqualTo(activeAttempt);
            releaseCount++;
            activeAttempt = null;
            activeAction = null;
            confirm = false;
        }

        @Override
        public void retire(FinitePlanRequest request) {
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
