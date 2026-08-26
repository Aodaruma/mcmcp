package dev.aod.mcmcp.voice;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class VoiceChatSafetyControllerTest {
    @Test
    void mutesWithReadbackAndRestoresOnlyItsOwnChange() {
        var adapter = new FakeAdapter(true, false);
        var guard = new VoiceTransmissionGuard();
        var events = readyEvents();
        var stops = new ArrayList<String>();
        try (var controller = new VoiceChatSafetyController(adapter, guard, events, true, stops::add)) {
            adapter.muteObserver = events::muteChanged;

            var begin = controller.beginAutomation();

            assertThat(begin.permitted()).isTrue();
            assertThat(begin.ownsMute()).isTrue();
            assertThat(adapter.muted).isTrue();
            assertThat(guard.blocksTransmission()).isTrue();
            assertThat(stops).isEmpty();

            var end = controller.endAutomation();

            assertThat(end.restored()).isTrue();
            assertThat(adapter.muted).isFalse();
            assertThat(guard.blocksTransmission()).isFalse();
        }
    }

    @Test
    void preservesPreexistingMuteWithoutClaimingOwnership() {
        var adapter = new FakeAdapter(true, true);
        var guard = new VoiceTransmissionGuard();
        var events = readyEvents();
        try (var controller = new VoiceChatSafetyController(adapter, guard, events, true, ignored -> { })) {
            var begin = controller.beginAutomation();
            var end = controller.endAutomation();

            assertThat(begin.ownsMute()).isFalse();
            assertThat(end.restoreAttempted()).isFalse();
            assertThat(adapter.writeCount).isZero();
            assertThat(adapter.muted).isTrue();
        }
    }

    @Test
    void externalUnmuteRequestsStopAndPreventsRestore() {
        var adapter = new FakeAdapter(true, false);
        var guard = new VoiceTransmissionGuard();
        var events = readyEvents();
        var stops = new ArrayList<String>();
        try (var controller = new VoiceChatSafetyController(adapter, guard, events, true, stops::add)) {
            adapter.muteObserver = events::muteChanged;
            assertThat(controller.beginAutomation().permitted()).isTrue();
            int writesAfterBegin = adapter.writeCount;

            adapter.muted = false;
            events.muteChanged(false);
            var end = controller.endAutomation();

            assertThat(stops).containsExactly("voicechat_unmuted_externally");
            assertThat(end.failureCode()).isEqualTo("mute_changed_externally");
            assertThat(adapter.writeCount).isEqualTo(writesAfterBegin);
            assertThat(guard.blocksTransmission()).isFalse();
        }
    }

    @Test
    void disconnectRequestsStopAndLeavesGuardRaisedUntilEnd() {
        var adapter = new FakeAdapter(true, true);
        var guard = new VoiceTransmissionGuard();
        var events = readyEvents();
        var stops = new ArrayList<String>();
        try (var controller = new VoiceChatSafetyController(adapter, guard, events, true, stops::add)) {
            assertThat(controller.beginAutomation().permitted()).isTrue();

            events.connectionChanged(false);

            assertThat(stops).containsExactly("voicechat_disconnected");
            assertThat(guard.blocksTransmission()).isTrue();
            controller.endAutomation();
            assertThat(guard.blocksTransmission()).isFalse();
        }
    }

    @Test
    void callbackDoesNotHoldControllerMonitorWhileRequestingClientStop() throws Exception {
        var adapter = new FakeAdapter(true, true);
        var events = readyEvents();
        var clientAdmissionGate = new Object();
        var callbackReachedAdmissionGate = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try (var controller = new VoiceChatSafetyController(
                adapter,
                new VoiceTransmissionGuard(),
                events,
                true,
                ignored -> {
                    callbackReachedAdmissionGate.countDown();
                    synchronized (clientAdmissionGate) {
                        // Models ClientCommandInbox admission while a pre-tick stop owns it.
                    }
                })) {
            assertThat(controller.beginAutomation().permitted()).isTrue();

            java.util.concurrent.Future<?> callback;
            synchronized (clientAdmissionGate) {
                callback = executor.submit(() -> events.connectionChanged(false));
                assertThat(callbackReachedAdmissionGate.await(1, TimeUnit.SECONDS)).isTrue();

                // A pre-tick stop holds the inbox gate and then enters the Voice controller.
                // This completes only if the callback released the controller monitor before
                // trying to request that stop.
                var clientStop = executor.submit(controller::endAutomation);
                assertThat(clientStop.get(1, TimeUnit.SECONDS).failureCode()).isNull();
            }
            callback.get(1, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void installedButUnknownDisconnectedOrGuardlessVoiceChatFailsClosed() {
        var events = new VoiceChatEventBridge();
        var adapter = new FakeAdapter(true, false);
        try (var controller = new VoiceChatSafetyController(
                adapter, new VoiceTransmissionGuard(), events, true, ignored -> { })) {
            assertThat(controller.beginAutomation().failureCode())
                    .isEqualTo("voice_transmission_guard_unavailable");
        }

        events = readyEvents();
        adapter = new FakeAdapter(false, false);
        try (var controller = new VoiceChatSafetyController(
                adapter, new VoiceTransmissionGuard(), events, true, ignored -> { })) {
            assertThat(controller.beginAutomation().failureCode()).isEqualTo("voicechat_disconnected");
        }

        events = readyEvents();
        adapter = new FakeAdapter(true, false);
        adapter.readFailure = "state_unknown";
        try (var controller = new VoiceChatSafetyController(
                adapter, new VoiceTransmissionGuard(), events, true, ignored -> { })) {
            assertThat(controller.beginAutomation().failureCode()).isEqualTo("state_unknown");
        }
    }

    @Test
    void absentVoiceChatFollowsLocalPolicyWithoutRaisingAudioGuard() {
        VoiceChatAdapter absent = new VoiceChatAdapter() {
            @Override
            public Probe probe() {
                return new Probe(Availability.NOT_INSTALLED, null, "test", null);
            }

            @Override
            public ReadResult readState() {
                throw new AssertionError("must not read an absent mod");
            }

            @Override
            public WriteResult setMuted(boolean muted) {
                throw new AssertionError("must not write an absent mod");
            }
        };
        var guard = new VoiceTransmissionGuard();
        try (var allowed = new VoiceChatSafetyController(
                absent, guard, new VoiceChatEventBridge(), true, ignored -> { })) {
            assertThat(allowed.beginAutomation().permitted()).isTrue();
            assertThat(guard.blocksTransmission()).isFalse();
        }
        try (var denied = new VoiceChatSafetyController(
                absent, guard, new VoiceChatEventBridge(), false, ignored -> { })) {
            assertThat(denied.beginAutomation().failureCode()).isEqualTo("voicechat_required");
        }
    }

    @Test
    void failedMuteReadbackRollsBackBeforeReleasingTransmissionGuard() {
        var adapter = new FakeAdapter(true, false);
        adapter.failReadAt = 2;
        var guard = new VoiceTransmissionGuard();
        var guardStatesAtWrite = new ArrayList<Boolean>();
        adapter.writeObserver = ignored -> guardStatesAtWrite.add(guard.blocksTransmission());
        try (var controller = new VoiceChatSafetyController(
                adapter, guard, readyEvents(), true, ignored -> { })) {
            var result = controller.beginAutomation();

            assertThat(result.permitted()).isFalse();
            assertThat(result.failureCode()).isEqualTo("mute_readback_state_unknown");
            assertThat(result.rollbackAttempted()).isTrue();
            assertThat(result.rollbackRestored()).isTrue();
            assertThat(result.rollbackFailureCode()).isNull();
            assertThat(result.ownsMute()).isFalse();
            assertThat(adapter.muted).isFalse();
            assertThat(adapter.writeCount).isEqualTo(2);
            assertThat(guardStatesAtWrite).containsExactly(true, true);
            assertThat(guard.blocksTransmission()).isFalse();
            assertThat(controller.snapshot().recoveryRequired()).isFalse();
        }
    }

    @Test
    void mismatchedMuteReadbackAlsoRollsBackTheSuccessfulWrite() {
        var adapter = new FakeAdapter(true, false);
        adapter.mismatchReadAt = 2;
        var guard = new VoiceTransmissionGuard();
        try (var controller = new VoiceChatSafetyController(
                adapter, guard, readyEvents(), true, ignored -> { })) {
            var result = controller.beginAutomation();

            assertThat(result.failureCode()).isEqualTo("mute_readback_mismatch");
            assertThat(result.rollbackRestored()).isTrue();
            assertThat(adapter.muted).isFalse();
            assertThat(adapter.writeCount).isEqualTo(2);
            assertThat(guard.blocksTransmission()).isFalse();
        }
    }

    @Test
    void failedRollbackIsReportedAndCanBeRetriedThroughEnd() {
        var adapter = new FakeAdapter(true, false);
        adapter.failReadAt = 2;
        adapter.failWriteAt = 2;
        var guard = new VoiceTransmissionGuard();
        try (var controller = new VoiceChatSafetyController(
                adapter, guard, readyEvents(), true, ignored -> { })) {
            var begin = controller.beginAutomation();

            assertThat(begin.rollbackAttempted()).isTrue();
            assertThat(begin.rollbackRestored()).isFalse();
            assertThat(begin.rollbackFailureCode()).isEqualTo("restore_write_write_failed");
            assertThat(begin.ownsMute()).isTrue();
            assertThat(controller.snapshot().ownsMute()).isTrue();
            assertThat(controller.snapshot().recoveryRequired()).isTrue();
            assertThat(guard.blocksTransmission()).isFalse();

            adapter.failWriteAt = -1;
            var retry = controller.endAutomation();
            assertThat(retry.restored()).isTrue();
            assertThat(adapter.muted).isFalse();
            assertThat(controller.snapshot().recoveryRequired()).isFalse();
        }
    }

    @Test
    void snapshotIncludesLiveConnectedMutedAndReadFailureForReadyAdapter() {
        var adapter = new FakeAdapter(true, true);
        try (var controller = new VoiceChatSafetyController(
                adapter, new VoiceTransmissionGuard(), readyEvents(), true, ignored -> { })) {
            var live = controller.snapshot();

            assertThat(live.availability()).isEqualTo(VoiceChatAdapter.Availability.READY);
            assertThat(live.connected()).isTrue();
            assertThat(live.muted()).isTrue();
            assertThat(live.stateFailureCode()).isNull();

            adapter.failReadAt = 2;
            var failed = controller.snapshot();
            assertThat(failed.connected()).isNull();
            assertThat(failed.muted()).isNull();
            assertThat(failed.stateFailureCode()).isEqualTo("state_unknown");

            adapter.throwReadAt = 3;
            var threw = controller.snapshot();
            assertThat(threw.connected()).isNull();
            assertThat(threw.muted()).isNull();
            assertThat(threw.stateFailureCode()).startsWith("adapter_read_");
        }
    }

    @Test
    void beginAndEndConvertUnexpectedAdapterExceptionsToFailureData() {
        var throwingProbe = new FakeAdapter(true, false);
        throwingProbe.probeFailure = new IllegalStateException("boom");
        try (var controller = new VoiceChatSafetyController(
                throwingProbe, new VoiceTransmissionGuard(), readyEvents(), true, ignored -> { })) {
            final VoiceChatSafetyController.BeginResult[] result = new VoiceChatSafetyController.BeginResult[1];
            assertThatCode(() -> result[0] = controller.beginAutomation()).doesNotThrowAnyException();
            assertThat(result[0].permitted()).isFalse();
            assertThat(result[0].failureCode()).startsWith("adapter_probe_");
        }

        var throwingWrite = new FakeAdapter(true, false);
        throwingWrite.throwWriteAt = 1;
        try (var controller = new VoiceChatSafetyController(
                throwingWrite, new VoiceTransmissionGuard(), readyEvents(), true, ignored -> { })) {
            final VoiceChatSafetyController.BeginResult[] result = new VoiceChatSafetyController.BeginResult[1];
            assertThatCode(() -> result[0] = controller.beginAutomation()).doesNotThrowAnyException();
            assertThat(result[0].permitted()).isFalse();
            assertThat(result[0].failureCode()).startsWith("adapter_write_");
            assertThat(result[0].rollbackRestored()).isTrue();
            assertThat(throwingWrite.muted).isFalse();
        }

        var throwingRead = new FakeAdapter(true, false);
        var guard = new VoiceTransmissionGuard();
        try (var controller = new VoiceChatSafetyController(
                throwingRead, guard, readyEvents(), true, ignored -> { })) {
            assertThat(controller.beginAutomation().permitted()).isTrue();
            throwingRead.throwReadAt = 3;
            final VoiceChatSafetyController.EndResult[] result = new VoiceChatSafetyController.EndResult[1];
            assertThatCode(() -> result[0] = controller.endAutomation()).doesNotThrowAnyException();
            assertThat(result[0].failureCode()).startsWith("restore_read_adapter_read_");
            assertThat(guard.blocksTransmission()).isFalse();
        }
    }

    private static VoiceChatEventBridge readyEvents() {
        var events = new VoiceChatEventBridge();
        events.markGuardRegistered();
        return events;
    }

    private static final class FakeAdapter implements VoiceChatAdapter {
        private boolean connected;
        private boolean muted;
        private int writeCount;
        private int readCount;
        private String readFailure;
        private int failReadAt = -1;
        private int mismatchReadAt = -1;
        private int throwReadAt = -1;
        private int failWriteAt = -1;
        private int throwWriteAt = -1;
        private RuntimeException probeFailure;
        private java.util.function.Consumer<Boolean> muteObserver = ignored -> { };
        private java.util.function.Consumer<Boolean> writeObserver = ignored -> { };

        private FakeAdapter(boolean connected, boolean muted) {
            this.connected = connected;
            this.muted = muted;
        }

        @Override
        public Probe probe() {
            if (probeFailure != null) {
                throw probeFailure;
            }
            return new Probe(Availability.READY, "2.6.22+26.2", "test", null);
        }

        @Override
        public ReadResult readState() {
            readCount++;
            if (throwReadAt == readCount) {
                throw new IllegalStateException("unexpected read failure");
            }
            if (readFailure != null) {
                return ReadResult.failure(readFailure);
            }
            if (failReadAt == readCount) {
                return ReadResult.failure("state_unknown");
            }
            if (mismatchReadAt == readCount) {
                return ReadResult.success(new State(connected, !muted));
            }
            return ReadResult.success(new State(connected, muted));
        }

        @Override
        public WriteResult setMuted(boolean muted) {
            writeCount++;
            if (throwWriteAt == writeCount) {
                throw new IllegalStateException("unexpected write failure");
            }
            if (failWriteAt == writeCount) {
                return WriteResult.failure("write_failed");
            }
            this.muted = muted;
            writeObserver.accept(muted);
            muteObserver.accept(muted);
            return WriteResult.succeeded();
        }
    }
}
