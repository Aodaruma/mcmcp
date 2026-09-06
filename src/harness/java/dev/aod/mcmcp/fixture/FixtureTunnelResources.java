package dev.aod.mcmcp.fixture;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;

/** Owns only a saved observation override and the finite tunnel's original forced-chunk flags. */
final class FixtureTunnelResources {
    private final FixtureRaysPerTickLease rays;
    private final Map<Chunk, Boolean> pendingRestore = new LinkedHashMap<>();
    // All ownership fields, including the restoration ledger, share this object's monitor.
    private Object server;
    private Object world;
    private ChunkAccess access;

    FixtureTunnelResources(FixtureRaysPerTickLease rays) { this.rays = Objects.requireNonNull(rays); }

    synchronized void begin(Object currentServer, Object currentWorld, ChunkAccess chunkAccess) {
        if (active()) throw new IllegalStateException("tunnel resources are already owned");
        Objects.requireNonNull(currentServer);
        Objects.requireNonNull(currentWorld);
        Objects.requireNonNull(chunkAccess);
        world = currentWorld;
        access = chunkAccess;
        server = currentServer;
        try {
            // Snapshot every flag before changing any flag; an existing forced chunk remains forced on exit.
            for (Chunk chunk : chunks()) pendingRestore.put(chunk, access.forced(chunk));
            rays.begin(server, world);
            for (Chunk chunk : chunks()) {
                access.setForced(chunk, true);
                if (!access.forced(chunk)) throw new IllegalStateException("tunnel chunk was not forced");
            }
        } catch (RuntimeException failure) {
            try { restore(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    synchronized void requireOwner(Object currentServer, Object currentWorld) {
        if (active() && (server != currentServer || world != currentWorld))
            throw new IllegalStateException("tunnel resources belong to another world");
    }

    synchronized void restore() {
        if (!active()) return;
        RuntimeException failure = null;
        var entries = pendingRestore.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            try {
                access.setForced(entry.getKey(), entry.getValue());
                if (access.forced(entry.getKey()) != entry.getValue())
                    throw new IllegalStateException("tunnel chunk flag restoration was not confirmed");
                entries.remove();
            } catch (RuntimeException exception) {
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            }
        }
        // One failed chunk must not leave the global observation override behind.
        try { rays.restoreOwned(); } catch (RuntimeException exception) {
            if (failure == null) failure = exception;
            else failure.addSuppressed(exception);
        }
        if (pendingRestore.isEmpty() && !rays.active()) {
            server = null;
            world = null;
            access = null;
        }
        if (failure != null) throw failure;
    }

    synchronized boolean active() { return server != null; }
    synchronized Object server() { return server; }
    synchronized int pendingChunks() { return pendingRestore.size(); }
    synchronized Integer originalRaysPerTick() { return rays.originalEffectiveRays(); }

    static List<Chunk> chunks() {
        var result = new ArrayList<Chunk>();
        for (int x = FixtureTunnelPlan.MIN.x() >> 4; x <= (FixtureTunnelPlan.MAX.x() >> 4); x++)
            for (int z = FixtureTunnelPlan.MIN.z() >> 4; z <= (FixtureTunnelPlan.MAX.z() >> 4); z++)
                result.add(new Chunk(x, z));
        return List.copyOf(result);
    }

    record Chunk(int x, int z) { }
    interface ChunkAccess {
        boolean forced(Chunk chunk);
        void setForced(Chunk chunk, boolean forced);
    }
}
