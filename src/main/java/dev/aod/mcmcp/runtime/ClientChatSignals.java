package dev.aod.mcmcp.runtime;

import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.MessageSignature;

/** Receives only messages accepted into the native chat display queue. */
public final class ClientChatSignals {
    private static Listener listener;
    private ClientChatSignals() { }

    static void listen(Listener value) { listener = value; }

    public static void added(GuiMessage message) {
        if (listener != null) listener.added(message);
    }

    public static void deleted(MessageSignature signature) {
        if (listener != null) listener.deleted(signature);
    }

    public static void cleared() {
        if (listener != null) listener.cleared();
    }

    interface Listener {
        void added(GuiMessage message);
        void deleted(MessageSignature signature);
        void cleared();
    }
}
