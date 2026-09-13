package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.runtime.ContainerSyncSignals.StackFingerprint;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.client.multiplayer.ClientLevel;

/** One outstanding owned inventory SWAP, confirmed from both inbound slot payloads. */
public final class InventorySwapSignals {
    private static final InventorySwapSignals GLOBAL = new InventorySwapSignals();
    private final Map<ClientLevel, Ticket> pending = new WeakHashMap<>();
    public static InventorySwapSignals global() { return GLOBAL; }

    public synchronized Ticket begin(ClientLevel level, UUID session, int source, int destination,
            StackFingerprint sourceBefore, StackFingerprint destinationBefore) {
        Objects.requireNonNull(level, "level");
        if (pending.containsKey(level)) throw new IllegalStateException("inventory swap already pending");
        var ticket = new Ticket(session, source, destination, sourceBefore, destinationBefore);
        pending.put(level, ticket);
        return ticket;
    }

    public synchronized void onSlot(ClientLevel level, int slot, StackFingerprint payload) {
        var ticket = pending.get(level);
        if (ticket != null) ticket.accept(slot, payload);
    }

    public synchronized void close(ClientLevel level, Ticket ticket) {
        if (pending.remove(level, ticket)) ticket.close();
    }
    public synchronized void closeLevel(ClientLevel level) {
        var ticket = pending.remove(level);
        if (ticket != null) ticket.close();
    }

    public enum Result { WAITING, CONFIRMED, MISMATCH }

    public static final class Ticket {
        private final UUID session;
        private final int source;
        private final int destination;
        private final StackFingerprint sourceBefore;
        private final StackFingerprint destinationBefore;
        private StackFingerprint sourceAfter;
        private StackFingerprint destinationAfter;
        private boolean closed;

        Ticket(UUID session, int source, int destination,
                StackFingerprint sourceBefore, StackFingerprint destinationBefore) {
            this.session = Objects.requireNonNull(session, "session");
            if (source < 9 || source >= 36 || destination < 0 || destination >= 9) {
                throw new IllegalArgumentException("swap slot outside main inventory/hotbar");
            }
            this.source = source;
            this.destination = destination;
            this.sourceBefore = Objects.requireNonNull(sourceBefore, "sourceBefore");
            this.destinationBefore = Objects.requireNonNull(destinationBefore, "destinationBefore");
            if (sourceBefore.empty()) throw new IllegalArgumentException("swap source is empty");
        }

        synchronized void accept(int slot, StackFingerprint payload) {
            Objects.requireNonNull(payload, "payload");
            if (closed) return;
            if (slot == source) sourceAfter = payload;
            if (slot == destination) destinationAfter = payload;
        }

        public synchronized Result result(UUID currentSession) {
            if (closed || !session.equals(currentSession)) return Result.MISMATCH;
            if (sourceAfter == null || destinationAfter == null) return Result.WAITING;
            return sourceBefore.equals(destinationAfter) && destinationBefore.equals(sourceAfter)
                    ? Result.CONFIRMED : Result.MISMATCH;
        }

        synchronized void close() { closed = true; }
    }
}
