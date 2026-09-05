package dev.aod.mcmcp.runtime;

import net.minecraft.client.multiplayer.ClientLevel;
import dev.aod.mcmcp.runtime.ContainerSyncSignals.StackFingerprint;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.WeakHashMap;

/** The nine hotbar slots only, copied from inbound packet payloads after session binding. */
public final class HotbarPayloadSyncSignals {
    private static final HotbarPayloadSyncSignals GLOBAL = new HotbarPayloadSyncSignals();
    private final Map<ClientLevel, SessionChannel> channels = new WeakHashMap<>();
    public static HotbarPayloadSyncSignals global() { return GLOBAL; }

    public synchronized Snapshot bindAndSnapshot(ClientLevel level, UUID session) {
        Objects.requireNonNull(level, "level");
        return channels.computeIfAbsent(level, ignored -> new SessionChannel()).bind(session);
    }

    public synchronized void onSlot(ClientLevel level, int inventorySlot,
                                     StackFingerprint payload, long tick) {
        var channel = channels.get(level);
        if (channel != null) channel.record(inventorySlot, payload, tick);
    }

    public synchronized void closeLevel(ClientLevel level) { channels.remove(level); }

    public record SlotEvidence(StackFingerprint stack, long receivedTick, long revision) { }
    public record Snapshot(UUID worldSessionId, long revision, Map<Integer, SlotEvidence> slots) {
        public Snapshot { slots = Map.copyOf(slots); }
    }

    static final class SessionChannel {
        private UUID session;
        private long revision;
        private final Map<Integer, SlotEvidence> slots = new LinkedHashMap<>();

        Snapshot bind(UUID nextSession) {
            Objects.requireNonNull(nextSession, "session");
            if (!nextSession.equals(session)) {
                session = nextSession;
                revision = 0;
                slots.clear();
            }
            return snapshot();
        }

        void record(int slot, StackFingerprint payload, long tick) {
            Objects.requireNonNull(payload, "payload");
            if (session == null || slot < 0 || slot >= 9) return;
            if (tick < 0) throw new IllegalArgumentException("negative packet tick");
            revision = Math.incrementExact(revision);
            slots.put(slot, new SlotEvidence(payload, tick, revision));
        }

        Snapshot snapshot() { return new Snapshot(session, revision, slots); }
    }
}
