package de.maxhenkel.voicechat.voice.client;

/** Exact-signature test double; no production source depends on this class. */
public final class ClientPlayerStateManager {
    private boolean disconnected = true;
    private boolean muted;

    public boolean isDisconnected() {
        return disconnected;
    }

    public boolean isMuted() {
        return muted;
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
    }
}
