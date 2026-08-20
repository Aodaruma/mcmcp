package dev.aodaruma.craftagent.voice;

import java.util.Objects;

/**
 * Small thread-safe bridge from the Voice Chat plugin callback threads to the
 * client runtime. Exactly one controller may be attached.
 */
public final class VoiceChatEventBridge {
    public static final VoiceChatEventBridge GLOBAL = new VoiceChatEventBridge();

    private Listener listener;
    private boolean guardRegistered;
    private Boolean lastConnected;
    private Boolean lastMuted;

    public synchronized Registration attach(Listener next) {
        Objects.requireNonNull(next, "next");
        if (listener != null && listener != next) {
            throw new IllegalStateException("a Voice Chat listener is already attached");
        }
        listener = next;
        return () -> detach(next);
    }

    public synchronized boolean guardRegistered() {
        return guardRegistered;
    }

    void markGuardRegistered() {
        synchronized (this) {
            guardRegistered = true;
        }
    }

    void connectionChanged(boolean connected) {
        final Listener target;
        synchronized (this) {
            lastConnected = connected;
            target = listener;
        }
        notifyConnection(target, connected);
    }

    void muteChanged(boolean muted) {
        final Listener target;
        synchronized (this) {
            lastMuted = muted;
            target = listener;
        }
        notifyMute(target, muted);
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(guardRegistered, lastConnected, lastMuted);
    }

    private synchronized void detach(Listener expected) {
        if (listener == expected) {
            listener = null;
        }
    }

    private static void notifyConnection(Listener target, boolean connected) {
        if (target == null) {
            return;
        }
        try {
            target.onConnectionChanged(connected);
        } catch (RuntimeException | LinkageError ignored) {
            // Voice Chat's callback thread must never be destabilized by CraftAgent.
        }
    }

    private static void notifyMute(Listener target, boolean muted) {
        if (target == null) {
            return;
        }
        try {
            target.onMuteChanged(muted);
        } catch (RuntimeException | LinkageError ignored) {
            // Voice Chat's callback thread must never be destabilized by CraftAgent.
        }
    }

    public interface Listener {
        void onConnectionChanged(boolean connected);

        void onMuteChanged(boolean muted);
    }

    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    public record Snapshot(boolean guardRegistered, Boolean connected, Boolean muted) {
    }
}
