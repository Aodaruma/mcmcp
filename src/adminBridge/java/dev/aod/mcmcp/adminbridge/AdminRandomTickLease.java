package dev.aod.mcmcp.adminbridge;

import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.time.Duration;

/** Reversible, bounded owner of randomTickSpeed for an applied external fixture. */
final class AdminRandomTickLease {
    private final MonotonicLease deadline = new MonotonicLease();
    private final RandomTickLeaseJournal journal;
    private final WorldIdentityStore worldIdentities = new WorldIdentityStore();
    private IntegratedServer server;
    private Integer original;
    private Integer target;

    AdminRandomTickLease(Path journalPath) {
        journal = new RandomTickLeaseJournal(journalPath);
    }

    void recoverIfPresent(AdminBridgeSecurity.Context context) {
        if (active() || !journal.exists()) {
            return;
        }
        RandomTickLeaseJournal.Entry entry = journal.read();
        WorldIdentityStore.Identity identity = worldIdentity(context.server());
        if (!entry.worldPathSha256().equals(identity.worldPathSha256())
                || !entry.worldId().equals(identity.worldId())) {
            throw new RandomTickLeaseJournal.JournalException(
                    "random_tick_journal_world_mismatch");
        }
        context.server().getGameRules().set(
                GameRules.RANDOM_TICK_SPEED, entry.original(), context.server());
        requireSaved(context.server());
        journal.delete();
    }

    void begin(AdminBridgeSecurity.Context context, FixtureManifest.RandomTickLease request) {
        if (request == null) {
            return;
        }
        if (active()) {
            throw new IllegalStateException("random_tick_lease_active");
        }
        recoverIfPresent(context);
        server = context.server();
        original = server.getGameRules().get(GameRules.RANDOM_TICK_SPEED);
        target = request.target();
        try {
            WorldIdentityStore.Identity identity = worldIdentity(server);
            journal.write(new RandomTickLeaseJournal.Entry(
                    identity.worldPathSha256(), identity.worldId(), original, target));
            deadline.begin(Duration.ofSeconds(request.maximumSeconds()));
            server.getGameRules().set(GameRules.RANDOM_TICK_SPEED, target, server);
        } catch (RuntimeException failure) {
            try {
                server.getGameRules().set(GameRules.RANDOM_TICK_SPEED, original, server);
                if (journal.exists()) {
                    requireSaved(server);
                    journal.delete();
                }
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            } finally {
                clear();
            }
            throw failure;
        }
    }

    void onServerTick(IntegratedServer current) {
        if (!active() || current != server || !deadline.expired()) {
            return;
        }
        restore(current, true);
    }

    void onServerStopping(IntegratedServer current) {
        if (active() && current == server) {
            restore(current, false);
        }
    }

    void restore(IntegratedServer current) {
        restore(current, true);
    }

    private void restore(IntegratedServer current, boolean persistAndDeleteJournal) {
        if (!active()) {
            return;
        }
        if (current != server || !current.isSameThread()) {
            throw new IllegalStateException("random_tick_restore_context_invalid");
        }
        current.getGameRules().set(GameRules.RANDOM_TICK_SPEED, original, current);
        if (persistAndDeleteJournal) {
            requireSaved(current);
            journal.delete();
        }
        clear();
    }

    Snapshot snapshot() {
        return new Snapshot(active(), target, deadline.remainingSeconds());
    }

    private boolean active() {
        return server != null && original != null && deadline.active();
    }

    private void clear() {
        server = null;
        original = null;
        target = null;
        deadline.clear();
    }

    private WorldIdentityStore.Identity worldIdentity(IntegratedServer server) {
        return worldIdentities.loadOrCreate(server.getWorldPath(LevelResource.ROOT));
    }

    private static void requireSaved(IntegratedServer server) {
        if (!server.saveEverything(true, true, true)) {
            throw new RandomTickLeaseJournal.JournalException(
                    "random_tick_restore_save_failed");
        }
    }

    record Snapshot(boolean active, Integer target, long remainingSeconds) {
    }
}
