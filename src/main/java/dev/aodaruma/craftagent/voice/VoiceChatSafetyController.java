package dev.aodaruma.craftagent.voice;

import java.util.Objects;
import java.util.function.Consumer;

/** Owns one automation session's mute, readback, conflict, and restore protocol. */
public final class VoiceChatSafetyController implements VoiceChatEventBridge.Listener, AutoCloseable {
    private final VoiceChatAdapter adapter;
    private final VoiceTransmissionGuard transmissionGuard;
    private final VoiceChatEventBridge events;
    private final boolean allowWhenNotInstalled;
    private final Consumer<String> stopRequester;
    private final VoiceChatEventBridge.Registration eventRegistration;

    private boolean active;
    private boolean preparing;
    private boolean ownsMute;
    private boolean previousMuted;
    private boolean externalMuteChange;
    private boolean recoveryRequired;
    private int internalMutationDepth;
    private boolean stopRequested;

    public VoiceChatSafetyController(
            VoiceChatAdapter adapter,
            VoiceTransmissionGuard transmissionGuard,
            VoiceChatEventBridge events,
            boolean allowWhenNotInstalled,
            Consumer<String> stopRequester) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.transmissionGuard = Objects.requireNonNull(transmissionGuard, "transmissionGuard");
        this.events = Objects.requireNonNull(events, "events");
        this.allowWhenNotInstalled = allowWhenNotInstalled;
        this.stopRequester = Objects.requireNonNull(stopRequester, "stopRequester");
        eventRegistration = events.attach(this);
    }

    /** Must be invoked on the Minecraft client thread. */
    public synchronized BeginResult beginAutomation() {
        if (active || preparing) {
            return BeginResult.failure("voice_session_already_active", true);
        }
        if (ownsMute || recoveryRequired) {
            return BeginResult.failure("voice_mute_recovery_required", true);
        }

        resetSessionState();
        preparing = true;
        boolean voiceChatPresent = true;
        try {
            var probe = safeProbe();
            voiceChatPresent = probe.availability() != VoiceChatAdapter.Availability.NOT_INSTALLED;
            if (!voiceChatPresent) {
                preparing = false;
                if (!allowWhenNotInstalled) {
                    return BeginResult.failure("voicechat_required", false);
                }
                active = true;
                return BeginResult.success(false, false, probe.adapterVersion());
            }
            if (!probe.ready()) {
                return failPreparation(
                        probe.failureCode() == null ? "voicechat_not_ready" : probe.failureCode(),
                        true);
            }
            if (!events.guardRegistered()) {
                return failPreparation("voice_transmission_guard_unavailable", true);
            }

            // The public event guard is raised before inspecting or changing mute so
            // there is no microphone frame gap during the internal adapter calls.
            transmissionGuard.block();
            var before = safeReadState();
            if (!before.success()) {
                return failPreparation(before.failureCode(), true);
            }
            if (!before.state().connected()) {
                return failPreparation("voicechat_disconnected", true);
            }
            previousMuted = before.state().muted();
            if (!previousMuted) {
                // Treat every attempted write as potentially side-effecting. A
                // reflective target can mutate the config and still fail while
                // dispatching a later event, so rollback is also required when
                // the adapter reports a write failure.
                ownsMute = true;
                var write = mutateMuted(true);
                if (!write.success()) {
                    return failPreparation(write.failureCode(), true);
                }
                // Do not grant the routine until the persisted state can be read
                // back from the same adapter boundary.
                var readback = safeReadState();
                if (!readback.success()) {
                    return failPreparation("mute_readback_" + readback.failureCode(), true);
                }
                if (!readback.state().connected() || !readback.state().muted()) {
                    return failPreparation("mute_readback_mismatch", true);
                }
            }

            preparing = false;
            active = true;
            return BeginResult.success(true, ownsMute, probe.adapterVersion());
        } catch (RuntimeException | LinkageError failure) {
            return failPreparation(exceptionCode("begin_exception", failure), voiceChatPresent);
        }
    }

    /**
     * Must run after all game inputs have been released and before disconnect.
     * The transmission guard remains raised until the restore attempt finishes.
     */
    public synchronized EndResult endAutomation() {
        if (!active && !preparing && !ownsMute) {
            transmissionGuard.unblock();
            return EndResult.noSession();
        }
        active = false;
        preparing = false;
        try {
            if (!ownsMute) {
                return EndResult.success(false, false);
            }
            if (externalMuteChange) {
                ownsMute = false;
                recoveryRequired = false;
                return EndResult.failure(false, "mute_changed_externally");
            }

            var current = safeReadState();
            if (!current.success()) {
                return EndResult.failure(false, "restore_read_" + current.failureCode());
            }
            if (!current.state().connected()) {
                return EndResult.failure(false, "voicechat_disconnected");
            }
            if (!current.state().muted()) {
                externalMuteChange = true;
                ownsMute = false;
                recoveryRequired = false;
                return EndResult.failure(false, "mute_changed_externally");
            }

            var restore = restoreOwnedMute();
            if (!restore.restored()) {
                return EndResult.failure(restore.attempted(), restore.failureCode());
            }
            return EndResult.success(true, true);
        } catch (RuntimeException | LinkageError failure) {
            recoveryRequired = ownsMute;
            return EndResult.failure(false, exceptionCode("end_exception", failure));
        } finally {
            transmissionGuard.unblock();
            if (ownsMute) {
                active = false;
                preparing = false;
                recoveryRequired = true;
                internalMutationDepth = 0;
                stopRequested = false;
            } else {
                resetSessionState();
            }
        }
    }

    @Override
    public void onConnectionChanged(boolean connected) {
        if (!connected) {
            requestStopIfRunning("voicechat_disconnected");
        }
    }

    @Override
    public void onMuteChanged(boolean muted) {
        boolean requestStop = false;
        synchronized (this) {
            if (internalMutationDepth > 0 || (!active && !preparing)) {
                return;
            }
            externalMuteChange = true;
            if (!muted) {
                requestStop = markStopRequestedIfRunningLocked();
            }
        }
        if (requestStop) {
            notifyStopRequester("voicechat_unmuted_externally");
        }
    }

    public synchronized Snapshot snapshot() {
        var probe = safeProbe();
        Boolean connected = null;
        Boolean muted = null;
        String stateFailureCode = null;
        if (probe.ready()) {
            var state = safeReadState();
            if (state.success()) {
                connected = state.state().connected();
                muted = state.state().muted();
            } else {
                stateFailureCode = state.failureCode();
            }
        } else if (probe.availability() != VoiceChatAdapter.Availability.NOT_INSTALLED) {
            stateFailureCode = probe.failureCode();
        }
        return new Snapshot(
                active,
                transmissionGuard.blocksTransmission(),
                ownsMute,
                externalMuteChange,
                probe.availability(),
                probe.detectedModVersion(),
                probe.adapterVersion(),
                stopRequested,
                connected,
                muted,
                stateFailureCode,
                recoveryRequired);
    }

    @Override
    public void close() {
        endAutomation();
        eventRegistration.close();
    }

    private VoiceChatAdapter.WriteResult mutateMuted(boolean muted) {
        internalMutationDepth++;
        try {
            return safeWriteMuted(muted);
        } finally {
            internalMutationDepth--;
        }
    }

    private BeginResult failPreparation(String failureCode, boolean voiceChatPresent) {
        RestoreAttempt rollback = RestoreAttempt.notNeeded();
        if (ownsMute) {
            rollback = restoreOwnedMute();
            recoveryRequired = !rollback.restored();
        }
        preparing = false;
        active = false;
        transmissionGuard.unblock();
        return BeginResult.failure(failureCode, voiceChatPresent, rollback, ownsMute);
    }

    private void requestStopIfRunning(String reason) {
        final boolean requestStop;
        synchronized (this) {
            requestStop = markStopRequestedIfRunningLocked();
        }
        if (requestStop) {
            notifyStopRequester(reason);
        }
    }

    private boolean markStopRequestedIfRunningLocked() {
        if ((!active && !preparing) || stopRequested) {
            return false;
        }
        stopRequested = true;
        return true;
    }

    private void notifyStopRequester(String reason) {
        try {
            stopRequester.accept(reason);
        } catch (RuntimeException | LinkageError ignored) {
            // The event callback must remain isolated; the guard stays raised.
        }
    }

    private RestoreAttempt restoreOwnedMute() {
        if (!ownsMute) {
            return RestoreAttempt.notNeeded();
        }
        if (externalMuteChange) {
            return RestoreAttempt.failure(false, "mute_changed_externally");
        }
        var write = mutateMuted(previousMuted);
        if (!write.success()) {
            return RestoreAttempt.failure(true, "restore_write_" + write.failureCode());
        }
        var readback = safeReadState();
        if (!readback.success()) {
            return RestoreAttempt.failure(true, "restore_readback_" + readback.failureCode());
        }
        if (readback.state().muted() != previousMuted) {
            return RestoreAttempt.failure(true, "restore_readback_mismatch");
        }
        ownsMute = false;
        recoveryRequired = false;
        return RestoreAttempt.success();
    }

    private VoiceChatAdapter.Probe safeProbe() {
        try {
            var probe = adapter.probe();
            if (probe == null) {
                return unavailableProbe("adapter_probe_null");
            }
            return probe;
        } catch (RuntimeException | LinkageError failure) {
            return unavailableProbe(exceptionCode("adapter_probe", failure));
        }
    }

    private VoiceChatAdapter.ReadResult safeReadState() {
        try {
            var result = adapter.readState();
            if (result == null) {
                return VoiceChatAdapter.ReadResult.failure("adapter_read_null");
            }
            if (result.success() && result.state() == null) {
                return VoiceChatAdapter.ReadResult.failure("adapter_read_missing_state");
            }
            if (!result.success() && result.failureCode() == null) {
                return VoiceChatAdapter.ReadResult.failure("adapter_read_failed");
            }
            return result;
        } catch (RuntimeException | LinkageError failure) {
            return VoiceChatAdapter.ReadResult.failure(exceptionCode("adapter_read", failure));
        }
    }

    private VoiceChatAdapter.WriteResult safeWriteMuted(boolean muted) {
        try {
            var result = adapter.setMuted(muted);
            if (result == null) {
                return VoiceChatAdapter.WriteResult.failure("adapter_write_null");
            }
            if (!result.success() && result.failureCode() == null) {
                return VoiceChatAdapter.WriteResult.failure("adapter_write_failed");
            }
            return result;
        } catch (RuntimeException | LinkageError failure) {
            return VoiceChatAdapter.WriteResult.failure(exceptionCode("adapter_write", failure));
        }
    }

    private static VoiceChatAdapter.Probe unavailableProbe(String failureCode) {
        return new VoiceChatAdapter.Probe(
                VoiceChatAdapter.Availability.UNAVAILABLE,
                null,
                "unknown",
                normalize(failureCode));
    }

    private void resetSessionState() {
        active = false;
        preparing = false;
        ownsMute = false;
        previousMuted = false;
        externalMuteChange = false;
        recoveryRequired = false;
        internalMutationDepth = 0;
        stopRequested = false;
    }

    public record BeginResult(
            boolean permitted,
            boolean voiceChatPresent,
            boolean ownsMute,
            String adapterVersion,
            String failureCode,
            boolean rollbackAttempted,
            boolean rollbackRestored,
            String rollbackFailureCode) {
        static BeginResult success(boolean present, boolean ownsMute, String adapterVersion) {
            return new BeginResult(true, present, ownsMute, adapterVersion, null,
                    false, false, null);
        }

        static BeginResult failure(String failureCode, boolean present) {
            return failure(failureCode, present, RestoreAttempt.notNeeded(), false);
        }

        static BeginResult failure(
                String failureCode,
                boolean present,
                RestoreAttempt rollback,
                boolean ownsMute) {
            return new BeginResult(
                    false,
                    present,
                    ownsMute,
                    null,
                    normalize(failureCode),
                    rollback.attempted(),
                    rollback.restored(),
                    rollback.failureCode());
        }
    }

    public record EndResult(
            boolean sessionExisted,
            boolean restoreAttempted,
            boolean restored,
            String failureCode) {
        static EndResult noSession() {
            return new EndResult(false, false, false, null);
        }

        static EndResult success(boolean attempted, boolean restored) {
            return new EndResult(true, attempted, restored, null);
        }

        static EndResult failure(boolean attempted, String failureCode) {
            return new EndResult(true, attempted, false, normalize(failureCode));
        }
    }

    public record Snapshot(
            boolean active,
            boolean transmissionBlocked,
            boolean ownsMute,
            boolean externalMuteChange,
            VoiceChatAdapter.Availability availability,
            String detectedModVersion,
            String adapterVersion,
            boolean stopRequested,
            Boolean connected,
            Boolean muted,
            String stateFailureCode,
            boolean recoveryRequired) {
    }

    private record RestoreAttempt(boolean attempted, boolean restored, String failureCode) {
        private static RestoreAttempt notNeeded() {
            return new RestoreAttempt(false, false, null);
        }

        private static RestoreAttempt success() {
            return new RestoreAttempt(true, true, null);
        }

        private static RestoreAttempt failure(boolean attempted, String failureCode) {
            return new RestoreAttempt(attempted, false, normalize(failureCode));
        }
    }

    private static String exceptionCode(String prefix, Throwable failure) {
        return normalize(prefix + "_" + failure.getClass().getSimpleName());
    }

    private static String normalize(String code) {
        if (code == null || code.isBlank()) {
            return "voicechat_failure";
        }
        return code.replaceAll("[^A-Za-z0-9_]+", "_");
    }
}
