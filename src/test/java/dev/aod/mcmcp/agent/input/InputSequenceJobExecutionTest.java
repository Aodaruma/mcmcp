package dev.aod.mcmcp.agent.input;

import dev.aod.mcmcp.agent.action.AgentJobStore;
import dev.aod.mcmcp.routine.BoundedInputLease;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static dev.aod.mcmcp.agent.action.AgentJobStore.Kind.INPUT_SEQUENCE;
import static dev.aod.mcmcp.agent.action.AgentJobStore.State.CANCELLED;
import static dev.aod.mcmcp.agent.action.AgentJobStore.State.FAILED;
import static dev.aod.mcmcp.agent.action.AgentJobStore.State.RUNNING;
import static dev.aod.mcmcp.agent.action.AgentJobStore.State.SUCCEEDED;
import static dev.aod.mcmcp.agent.action.AgentJobStore.State.UNCONFIRMED;
import static org.assertj.core.api.Assertions.assertThat;

class InputSequenceJobExecutionTest {
    @Test
    void noInputBeforeDeliveryAndSuccessOnlyAfterRelease() {
        var store = new AgentJobStore();
        var session = UUID.randomUUID();
        var id = store.reserve(INPUT_SEQUENCE, session, 1, 100);
        var control = new RecordingControl();
        var released = new AtomicBoolean(false);
        var execution = execution(store, id, session, control, released::get, 1);
        assertThat(execution.tick(session, 1, 1, true, false).state()).isEqualTo(UNCONFIRMED);
        assertThat(control.events).isEmpty();
        store.confirm(id, 2);
        assertThat(execution.tick(session, 2, 2, true, false).state()).isEqualTo(RUNNING);
        assertThat(control.events).containsExactly("publish");
        assertThat(execution.tick(session, 3, 3, true, false).state()).isEqualTo(RUNNING);
        assertThat(control.events).containsExactly("publish", "release");
        released.set(true);
        assertThat(execution.tick(session, 4, 4, true, false).state()).isEqualTo(SUCCEEDED);
        assertThat(control.events).containsExactly("publish", "release");
        assertThat(store.get(id).completedOperations()).isEqualTo(1);
    }

    @Test
    void reachedStopConditionReleasesTheHeldInputBeforeSuccess() {
        var store = new AgentJobStore();
        var session = UUID.randomUUID();
        var id = store.reserve(INPUT_SEQUENCE, session, 20, 100);
        var control = new RecordingControl();
        var execution = execution(store, id, session, control, () -> true, 20);
        store.confirm(id, 1);
        assertThat(execution.tick(session, 1, 1, true, false).state()).isEqualTo(RUNNING);
        assertThat(execution.tick(session, 2, 2, true, true).state()).isEqualTo(SUCCEEDED);
        assertThat(store.get(id).completedOperations()).isEqualTo(1);
        assertThat(control.events).containsExactly("publish", "release");
    }

    @Test
    void finalClickCanReleaseAfterItOpensAMenuWithoutSendingAnotherInput() {
        var store = new AgentJobStore();
        var session = UUID.randomUUID();
        var id = store.reserve(AgentJobStore.Kind.CLICK, session, 1, 100);
        var control = new RecordingControl();
        var sequence = new FiniteInputSequence(List.of(
                new FiniteInputSequence.Step(Set.of(BoundedInputLease.Input.USE), 1, 0, 1)));
        var driver = new InputSequenceLeaseDriver(sequence, (inputs, nowNanos) ->
                BoundedInputLease.acquire(control, inputs, nowNanos, Duration.ofSeconds(1)));
        var execution = new InputSequenceJobExecution(store, id, session,
                AgentJobStore.Kind.CLICK, driver, () -> true, ignored -> { });
        store.confirm(id, 1);
        assertThat(execution.tick(session, 1, 1, true, false).state()).isEqualTo(RUNNING);
        assertThat(execution.tick(session, 2, 2, false, true, false).state()).isEqualTo(SUCCEEDED);
        assertThat(control.events).containsExactly("publish", "release");
        assertThat(store.get(id).completedOperations()).isEqualTo(1);
    }

    @Test
    void finalInputDoesNotClaimSuccessAfterWorldOwnerIsLost() {
        var store = new AgentJobStore();
        var session = UUID.randomUUID();
        var id = store.reserve(INPUT_SEQUENCE, session, 1, 100);
        var control = new RecordingControl();
        var execution = execution(store, id, session, control, () -> true, 1);
        store.confirm(id, 1);
        execution.tick(session, 1, 1, true, false);
        assertThat(execution.tick(session, 2, 2, false, false, false).state())
                .isEqualTo(FAILED);
        assertThat(store.get(id).failure()).isEqualTo("safety_interrupted");
        assertThat(control.events).containsExactly("publish", "release");
    }

    @Test
    void cancelKeepsJobNonterminalUntilFailedReleaseCanBeRetried() {
        var store = new AgentJobStore();
        var session = UUID.randomUUID();
        var id = store.reserve(INPUT_SEQUENCE, session, 2, 100);
        var control = new RecordingControl();
        var execution = execution(store, id, session, control, () -> true, 2);
        store.confirm(id, 1);
        execution.tick(session, 1, 1, true, false);
        store.requestCancel(id);
        control.failRelease = true;
        assertThat(execution.tick(session, 2, 2, true, false).state()).isEqualTo(RUNNING);
        assertThat(control.events).containsExactly("publish");
        assertThat(execution.tick(session, 3, 3, true, false).state()).isEqualTo(CANCELLED);
        assertThat(control.events).containsExactly("publish", "release");
        assertThat(store.get(id).completedOperations()).isEqualTo(1);
    }

    @Test
    void unsafeOrChangedWorldStopsBeforeFurtherInput() {
        var store = new AgentJobStore();
        var session = UUID.randomUUID();
        var id = store.reserve(INPUT_SEQUENCE, session, 2, 100);
        var control = new RecordingControl();
        var execution = execution(store, id, session, control, () -> true, 2);
        store.confirm(id, 1);
        execution.tick(session, 1, 1, true, false);
        assertThat(execution.tick(UUID.randomUUID(), 2, 2, true, false).state()).isEqualTo(FAILED);
        assertThat(store.get(id).failure()).isEqualTo("world_session_changed");
        assertThat(control.events).containsExactly("publish", "release");

        var nextId = store.reserve(INPUT_SEQUENCE, session, 2, 100);
        var nextControl = new RecordingControl();
        var next = execution(store, nextId, session, nextControl, () -> true, 2);
        store.confirm(nextId, 1);
        assertThat(next.tick(session, 1, 1, false, false).state()).isEqualTo(FAILED);
        assertThat(nextControl.events).isEmpty();
    }

    @Test
    void cancelWhileSuccessfulResultWaitsForReleaseBecomesCancelled() {
        var store = new AgentJobStore();
        var session = UUID.randomUUID();
        var id = store.reserve(INPUT_SEQUENCE, session, 1, 100);
        var control = new RecordingControl();
        var released = new AtomicBoolean(false);
        var execution = execution(store, id, session, control, released::get, 1);
        store.confirm(id, 1);
        execution.tick(session, 1, 1, true, false);
        assertThat(execution.tick(session, 2, 2, true, false).state()).isEqualTo(RUNNING);
        store.requestCancel(id);
        released.set(true);
        assertThat(execution.tick(session, 3, 3, true, false).state()).isEqualTo(CANCELLED);
        assertThat(control.events).containsExactly("publish", "release");
    }

    @Test
    void cancelBeforeDeliveryNeverPublishesInput() {
        var store = new AgentJobStore();
        var session = UUID.randomUUID();
        var id = store.reserve(INPUT_SEQUENCE, session, 1, 100);
        var control = new RecordingControl();
        var execution = execution(store, id, session, control, () -> true, 1);
        assertThat(execution.cancel().state()).isEqualTo(CANCELLED);
        assertThat(control.events).isEmpty();
        assertThat(store.confirm(id, 1)).isEqualTo(AgentJobStore.Confirmation.STALE);
    }

    @Test
    void movementWitnessFailureReleasesPublishedInputBeforeFailure() {
        var store = new AgentJobStore();
        var session = UUID.randomUUID();
        var id = store.reserve(INPUT_SEQUENCE, session, 1, 100);
        var control = new RecordingControl();
        var sequence = new FiniteInputSequence(List.of(
                new FiniteInputSequence.Step(Set.of(BoundedInputLease.Input.FORWARD), 1, 0, 1)));
        var driver = new InputSequenceLeaseDriver(sequence, (inputs, nowNanos) ->
                BoundedInputLease.acquire(control, inputs, nowNanos, Duration.ofSeconds(1)));
        var execution = new InputSequenceJobExecution(
                store, id, session, driver, () -> true,
                frame -> { throw new IllegalStateException("movement proof unavailable"); });
        store.confirm(id, 1);
        assertThat(execution.tick(session, 1, 1, true, false).state()).isEqualTo(FAILED);
        assertThat(control.events).containsExactly("publish", "release");
        assertThat(store.get(id).completedOperations()).isZero();
    }

    @Test
    void cancellationDoesNotOverwriteEarlierSafetyFailureWaitingForRelease() {
        var store = new AgentJobStore();
        var session = UUID.randomUUID();
        var id = store.reserve(INPUT_SEQUENCE, session, 2, 100);
        var control = new RecordingControl();
        var released = new AtomicBoolean(false);
        var execution = execution(store, id, session, control, released::get, 2);
        store.confirm(id, 1);
        execution.tick(session, 1, 1, true, false);
        assertThat(execution.tick(session, 2, 2, false, false).state()).isEqualTo(RUNNING);
        released.set(true);
        assertThat(execution.cancel().state()).isEqualTo(FAILED);
        assertThat(store.get(id).failure()).isEqualTo("safety_interrupted");
        assertThat(control.events).containsExactly("publish", "release");
    }

    @Test
    void screenConditionCanCompleteAfterOpeningButNeverAfterLosingWorldSafety() {
        for (boolean safeToFinalize : List.of(true, false)) {
            var store = new AgentJobStore();
            var session = UUID.randomUUID();
            var id = store.reserve(INPUT_SEQUENCE, session, 20, 100);
            var control = new RecordingControl();
            var execution = execution(store, id, session, control, () -> true, 20);
            store.confirm(id, 1);
            execution.tick(session, 1, 1, true, false);
            var stopped = execution.tick(session, 2, 2, false, safeToFinalize, true);
            assertThat(stopped.state()).isEqualTo(safeToFinalize ? SUCCEEDED : FAILED);
            assertThat(control.events).containsExactly("publish", "release");
        }
    }

    @Test
    void conditionAlreadyTrueCompletesAfterDeliveryWithoutPublishingInput() {
        var store = new AgentJobStore();
        var session = UUID.randomUUID();
        var id = store.reserve(INPUT_SEQUENCE, session, 20, 100);
        var control = new RecordingControl();
        var execution = execution(store, id, session, control, () -> true, 20);
        store.confirm(id, 1);
        assertThat(execution.tick(session, 1, 1, true, true).state()).isEqualTo(SUCCEEDED);
        assertThat(control.events).isEmpty();
        assertThat(store.get(id).result()).containsEntry("stop_condition_met", true);
    }

    @Test
    void longInputCrossesOldTickCeilingAndReleasesOnCompletion() {
        var jobs=new AgentJobStore(); var session=UUID.randomUUID();
        var id=jobs.reserve(INPUT_SEQUENCE,session,1500,100);
        var control=new RecordingControl();
        var execution=execution(jobs,id,session,control,()->true,1500);
        jobs.confirm(id,1);
        for(int tick=1;tick<=1500;tick++)
            assertThat(execution.tick(session,tick,tick*50_000_000L,true,false).state()).isEqualTo(RUNNING);
        assertThat(execution.tick(session,1501,75_050_000_000L,true,false).state()).isEqualTo(SUCCEEDED);
        assertThat(jobs.get(id).completedOperations()).isEqualTo(1500);
        assertThat(control.events).hasSize(1501);
        assertThat(control.events.subList(0,1500)).containsOnly("publish");
        assertThat(control.events.getLast()).isEqualTo("release");
    }

    @Test
    void stoppedTicksCannotRenewExpired24HourInputAndCleanupRemainsRequired() {
        var jobs=new AgentJobStore();var session=UUID.randomUUID();
        var id=jobs.reserve(INPUT_SEQUENCE,session,1728000,100);
        var control=new RecordingControl(); var released=new AtomicBoolean(false);
        var execution=execution(jobs,id,session,control,released::get,1728000);
        jobs.confirm(id,1); execution.tick(session,1,1,true,false);
        long end=Duration.ofHours(24).toNanos()+1;
        assertThat(execution.tick(session,2,end,true,false).state()).isEqualTo(RUNNING);
        assertThat(control.events).containsExactly("publish","release");
        released.set(true);
        assertThat(execution.tick(session,3,end+1,true,false).failure()).isEqualTo("duration_limit");
        assertThat(jobs.get(id).completedOperations()).isEqualTo(1);
    }

    @Test
    void refillReleasesBeforeWaitingAndCancelWinsPendingDurationSuccess() {
        var jobs=new AgentJobStore();var session=UUID.randomUUID();
        var id=jobs.reserve(INPUT_SEQUENCE,session,20,100);
        var control=new RecordingControl();var released=new AtomicBoolean(false);
        var execution=execution(jobs,id,session,control,released::get,20);
        jobs.confirm(id,1);execution.tick(session,1,1,true,false);
        assertThat(execution.waitForMaterial().result()).containsEntry("phase","waiting_for_item");
        assertThat(control.events).containsExactly("publish","release");
        execution.tick(session,2,2,true,false);
        assertThat(control.events).containsExactly("publish","release","publish");
        assertThat(execution.durationCompleted().state()).isEqualTo(RUNNING);
        released.set(true);
        assertThat(execution.cancel().state()).isEqualTo(CANCELLED);
        assertThat(control.events).containsExactly("publish","release","publish","release");
    }

    private static InputSequenceJobExecution execution(AgentJobStore store, UUID id,
                                                        UUID session, RecordingControl control,
                                                        java.util.function.BooleanSupplier release,
                                                        int holdTicks) {
        var sequence = new FiniteInputSequence(List.of(
                new FiniteInputSequence.Step(Set.of(BoundedInputLease.Input.USE), holdTicks, 0, 1)));
        var driver = new InputSequenceLeaseDriver(sequence, (inputs, nowNanos) ->
                BoundedInputLease.acquire(control, inputs, nowNanos, Duration.ofSeconds(1)));
        return new InputSequenceJobExecution(store, id, session, driver, release);
    }

    private static final class RecordingControl implements BoundedInputLease.Control {
        final List<String> events = new ArrayList<>();
        boolean failRelease;

        @Override
        public void publish(Set<BoundedInputLease.Input> inputs, long validUntilNanos) {
            events.add("publish");
        }

        @Override
        public void release(Set<BoundedInputLease.Input> inputs) {
            if (failRelease) {
                failRelease = false;
                throw new IllegalStateException("test release fault");
            }
            events.add("release");
        }
    }
}
