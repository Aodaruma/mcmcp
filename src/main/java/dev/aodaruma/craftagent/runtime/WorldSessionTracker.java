package dev.aodaruma.craftagent.runtime;

import java.util.Objects;
import java.util.UUID;

/** Client-thread-owned world lifecycle and monotonic command fence. */
public final class WorldSessionTracker {
    public enum Readiness {
        LOADING,
        TITLE,
        WORLD_READY,
        STOPPING
    }

    private long generation;
    private long clientTick;
    private UUID worldSessionId;
    private String dimension;
    private Readiness readiness = Readiness.LOADING;

    public void resourcesReady() {
        if (readiness == Readiness.LOADING) {
            readiness = Readiness.TITLE;
        }
    }

    /** Invalidates every queued command before any new player/world becomes ready. */
    public void invalidate() {
        generation++;
        worldSessionId = null;
        dimension = null;
        clientTick = 0;
        if (readiness != Readiness.STOPPING) {
            readiness = Readiness.TITLE;
        }
    }

    /** Begins a new server/world join while keeping readiness latched by a later tick. */
    public UUID beginConnection() {
        generation++;
        worldSessionId = UUID.randomUUID();
        dimension = null;
        clientTick = 0;
        if (readiness != Readiness.STOPPING) {
            readiness = Readiness.TITLE;
        }
        return worldSessionId;
    }

    /** Fences work while a client level is being replaced, without losing join-scoped memory. */
    public void suspendWorld() {
        generation++;
        dimension = null;
        if (readiness != Readiness.STOPPING) {
            readiness = Readiness.TITLE;
        }
    }

    /** Latches a ready world only from a later client tick, never directly from a lifecycle event. */
    public boolean latchReady(String currentDimension) {
        Objects.requireNonNull(currentDimension, "currentDimension");
        if (readiness == Readiness.STOPPING) {
            return false;
        }
        if (worldSessionId == null) {
            generation++;
            worldSessionId = UUID.randomUUID();
            dimension = currentDimension;
            clientTick = 0;
            readiness = Readiness.WORLD_READY;
            return true;
        }
        if (!currentDimension.equals(dimension)) {
            // Dimension transitions fence queued commands but remain in the same join session.
            generation++;
            dimension = currentDimension;
            readiness = Readiness.WORLD_READY;
            return true;
        }
        readiness = Readiness.WORLD_READY;
        return false;
    }

    public long tick() {
        if (readiness == Readiness.WORLD_READY) {
            clientTick++;
        }
        return clientTick;
    }

    public void stopping() {
        generation++;
        readiness = Readiness.STOPPING;
    }

    public Snapshot snapshot() {
        return new Snapshot(readiness, generation, clientTick, worldSessionId, dimension);
    }

    public record Snapshot(
            Readiness readiness,
            long generation,
            long clientTick,
            UUID worldSessionId,
            String dimension) {
        public boolean worldReady() {
            return readiness == Readiness.WORLD_READY && worldSessionId != null;
        }
    }
}
