package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.safety.InputReleaseController;
import dev.aod.mcmcp.safety.LocalArmingState;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientCommandInboxTest {
    @Test
    void successfulPhysicalEscReturnsAnActiveLeaseToReady() {
        var arming = new LocalArmingState();
        var session = UUID.randomUUID();
        arming.arm(session, Set.of("navigate_to", "stationary_break"));
        assertThat(arming.beginAction(session)).isTrue();
        var beforeStop = arming.snapshot(session);
        arming.lock("local_emergency_key");

        assertThat(ClientCommandInbox.settleArmingAfterStop(
                arming, beforeStop, session, true, true, "local_emergency_key")).isFalse();
        assertThat(arming.snapshot(session).mode()).isEqualTo(LocalArmingState.Mode.READY);
        assertThat(arming.snapshot(session).capabilities())
                .containsExactlyInAnyOrder("navigate_to", "stationary_break");
    }

    @Test
    void nonLocalOrFailedStopLocksInsteadOfRestoringReady() {
        var arming = new LocalArmingState();
        var session = UUID.randomUUID();
        arming.arm(session, Set.of("navigate_to"));
        assertThat(arming.beginAction(session)).isTrue();
        var beforeStop = arming.snapshot(session);

        assertThat(ClientCommandInbox.settleArmingAfterStop(
                arming, beforeStop, session, false, true, "operator_stop")).isTrue();
        assertThat(arming.snapshot(session).mode()).isEqualTo(LocalArmingState.Mode.OFF);

        arming.arm(session, Set.of("navigate_to"));
        assertThat(arming.beginAction(session)).isTrue();
        beforeStop = arming.snapshot(session);
        assertThat(ClientCommandInbox.settleArmingAfterStop(
                arming, beforeStop, session, true, false, "release_failed")).isTrue();
        assertThat(arming.snapshot(session).mode()).isEqualTo(LocalArmingState.Mode.OFF);
    }

    @Test
    void rejectsExpiredAndWrongGenerationCommandsBeforeCallingAction() {
        var inbox = new ClientCommandInbox(4, new InputReleaseController(), new LocalArmingState());
        var called = new boolean[1];
        var expired = inbox.submit("expired", 7, 100, () -> {
            called[0] = true;
            return "bad";
        });
        inbox.drainNormal(7, 101);
        assertThatThrownBy(() -> expired.get(1, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ClientCommandInbox.CommandTimeoutException.class);
        assertThat(called[0]).isFalse();

        var stale = inbox.submit("stale", 7, Long.MAX_VALUE, () -> {
            called[0] = true;
            return "bad";
        });
        inbox.drainNormal(8, 102);
        assertThatThrownBy(() -> stale.get(1, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ClientCommandInbox.CommandInvalidatedException.class);
        assertThat(called[0]).isFalse();
    }

    @Test
    void stopEpochInvalidatesAlreadyQueuedStart() {
        var inbox = new ClientCommandInbox(4, new InputReleaseController(), new LocalArmingState());
        var queued = inbox.submit("start", 1, Long.MAX_VALUE, () -> "started");
        inbox.requestEmergencyStop("test");
        inbox.drainNormal(1, 10);

        assertThatThrownBy(() -> queued.get(1, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ClientCommandInbox.CommandInvalidatedException.class);
    }

    @Test
    void stopRequestIsAcceptedWithoutWaitingForAnAlreadyClaimedAction() throws Exception {
        var inbox = new ClientCommandInbox(4, new InputReleaseController(), new LocalArmingState());
        var actionStarted = new CountDownLatch(1);
        var allowActionToFinish = new CountDownLatch(1);
        var stopAttempted = new CountDownLatch(1);
        var queued = inbox.submit("read", 1, Long.MAX_VALUE, () -> {
            actionStarted.countDown();
            assertThat(allowActionToFinish.await(2, TimeUnit.SECONDS)).isTrue();
            return "done";
        });

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var draining = executor.submit(() -> inbox.drainNormal(1, 10));
            assertThat(actionStarted.await(1, TimeUnit.SECONDS)).isTrue();

            var stopping = executor.submit(() -> {
                stopAttempted.countDown();
                return inbox.requestEmergencyStop("race");
            });
            assertThat(stopAttempted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(stopping.get(1, TimeUnit.SECONDS)).isNotNull();

            allowActionToFinish.countDown();
            draining.get(1, TimeUnit.SECONDS);
            assertThat(queued.get(1, TimeUnit.SECONDS)).isEqualTo("done");
        }
    }

    @Test
    void abandonedClaimedCompletionRunsItsClientThreadRollback() throws Exception {
        var inbox = new ClientCommandInbox(4, new InputReleaseController(), new LocalArmingState());
        var actionStarted = new CountDownLatch(1);
        var allowActionToFinish = new CountDownLatch(1);
        var abandoned = new AtomicBoolean();
        var rolledBack = new AtomicBoolean();
        var queued = inbox.submit(
                "agent_start_action",
                1,
                Long.MAX_VALUE,
                () -> {
                    actionStarted.countDown();
                    assertThat(allowActionToFinish.await(2, TimeUnit.SECONDS)).isTrue();
                    return "accepted-action";
                },
                abandoned::get,
                ignored -> rolledBack.set(true));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var draining = executor.submit(() -> inbox.drainNormal(1, 10));
            assertThat(actionStarted.await(1, TimeUnit.SECONDS)).isTrue();
            abandoned.set(true);
            allowActionToFinish.countDown();
            draining.get(1, TimeUnit.SECONDS);
        }

        assertThat(rolledBack).isTrue();
        assertThatThrownBy(() -> queued.get(1, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ClientCommandInbox.CommandTimeoutException.class);
    }

    @Test
    void cancellingTheReturnedMappedFutureWinsBeforeAClaimedStartCanRemain() throws Exception {
        var inbox = new ClientCommandInbox(4, new InputReleaseController(), new LocalArmingState());
        var actionStarted = new CountDownLatch(1);
        var allowActionToFinish = new CountDownLatch(1);
        var rolledBack = new AtomicBoolean();
        var queued = inbox.submitMapped(
                "agent_start_action",
                1,
                Long.MAX_VALUE,
                () -> {
                    actionStarted.countDown();
                    assertThat(allowActionToFinish.await(2, TimeUnit.SECONDS)).isTrue();
                    return "accepted-action";
                },
                Throwable::getMessage,
                () -> false,
                ignored -> rolledBack.set(true));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var draining = executor.submit(() -> inbox.drainNormal(1, 10));
            assertThat(actionStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(queued.cancel(true)).isTrue();
            allowActionToFinish.countDown();
            draining.get(1, TimeUnit.SECONDS);
        }

        assertThat(queued).isCancelled();
        assertThat(rolledBack).isTrue();
    }

    @Test
    void emergencyStopAfterShutdownReturnsTheTerminalReceiptInsteadOfTimingOut() throws Exception {
        var inbox = new ClientCommandInbox(4, new InputReleaseController(), new LocalArmingState());
        var terminal = new ClientCommandInbox.StopReceipt(
                "client_shutdown", 4, 9, true, true, 0);
        var accepting = ClientCommandInbox.class.getDeclaredField("accepting");
        var terminalReceipt = ClientCommandInbox.class.getDeclaredField("terminalStopReceipt");
        accepting.setAccessible(true);
        terminalReceipt.setAccessible(true);
        accepting.setBoolean(inbox, false);
        terminalReceipt.set(inbox, terminal);

        var receipt = inbox.requestEmergencyStop("after_shutdown").get(1, TimeUnit.SECONDS);

        assertThat(receipt).isSameAs(terminal);
    }

    @Test
    void discardedPendingStartsCountsOnlyStartCommands() throws Exception {
        var inbox = new ClientCommandInbox(8, new InputReleaseController(), new LocalArmingState());
        var start = inbox.submit("start_routine", 1, Long.MAX_VALUE, () -> "started");
        var read = inbox.submit("get_snapshot", 1, Long.MAX_VALUE, () -> "read");
        var cancel = inbox.submitControl("cancel_routine", 1, Long.MAX_VALUE, () -> "cancelled");
        Method failPending = ClientCommandInbox.class.getDeclaredMethod("failPending", Throwable.class);
        failPending.setAccessible(true);

        int discardedStarts = (int) failPending.invoke(
                inbox, new ClientCommandInbox.CommandInvalidatedException("test stop"));

        assertThat(discardedStarts).isEqualTo(1);
        assertThat(start).isCompletedExceptionally();
        assertThat(read).isCompletedExceptionally();
        assertThat(cancel).isCompletedExceptionally();
    }
}
