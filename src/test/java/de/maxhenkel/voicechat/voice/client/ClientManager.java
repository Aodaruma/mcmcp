package de.maxhenkel.voicechat.voice.client;

/** Exact-name test double for the version-pinned optional runtime surface. */
public final class ClientManager {
    private static ClientPlayerStateManager manager = new ClientPlayerStateManager();

    private ClientManager() {
    }

    public static ClientPlayerStateManager getPlayerStateManager() {
        return manager;
    }

    public static void reset() {
        manager = new ClientPlayerStateManager();
    }
}
