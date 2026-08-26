package dev.aod.mcmcp.runtime;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Session-fenced evidence for server-to-client reconciliation which has no block prediction ACK.
 *
 * <p>The client packet mixin only records into an already-bound level. An action binds its current
 * world session and captures a {@link Snapshot} before dispatch, so login/previous-session packets
 * can never be reused as evidence.</p>
 */
public final class ClientReconciliationSignals {
    private static final ClientReconciliationSignals GLOBAL = new ClientReconciliationSignals();

    private final Object gate = new Object();
    private final Map<ClientLevel, SessionChannel> channels = new WeakHashMap<>();

    public static ClientReconciliationSignals global() {
        return GLOBAL;
    }

    /** Binds (or rebinds) this level to the exact runtime world session and returns its watermarks. */
    public Snapshot bindAndSnapshot(ClientLevel level, UUID worldSessionId) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(worldSessionId, "worldSessionId");
        synchronized (gate) {
            return channels.computeIfAbsent(level, ignored -> new SessionChannel())
                    .bindAndSnapshot(worldSessionId);
        }
    }

    /** Read-only current boundary; unlike bindAndSnapshot this never creates or rebinds state. */
    public Optional<Snapshot> currentSnapshot(ClientLevel level) {
        Objects.requireNonNull(level, "level");
        synchronized (gate) {
            var channel = channels.get(level);
            return channel == null || !channel.bound()
                    ? Optional.empty()
                    : Optional.of(channel.snapshot());
        }
    }

    public void onPositionCorrection(ClientLevel level, int teleportId, Vec3 appliedPosition) {
        Objects.requireNonNull(appliedPosition, "appliedPosition");
        record(level, channel -> channel.positionCorrection(
                new PositionCorrection(teleportId, appliedPosition)));
    }

    public void onServerRotation(ClientLevel level, float yaw, float pitch) {
        record(level, channel -> channel.serverRotation(new ServerRotation(yaw, pitch)));
    }

    public void onLocalPlayerMotion(ClientLevel level, Vec3 movement, String source) {
        Objects.requireNonNull(movement, "movement");
        Objects.requireNonNull(source, "source");
        record(level, channel -> channel.localMotion(new LocalMotion(movement, sanitize(source))));
    }

    public void onInventorySync(
            ClientLevel level,
            String packetKind,
            int packetSlot,
            boolean selectedSlotRelevant,
            String selectedItemId,
            int selectedCount) {
        Objects.requireNonNull(packetKind, "packetKind");
        Objects.requireNonNull(selectedItemId, "selectedItemId");
        if (selectedCount < 0) {
            throw new IllegalArgumentException("selectedCount must be non-negative");
        }
        record(level, channel -> channel.inventorySync(new InventorySync(
                sanitize(packetKind), packetSlot, selectedSlotRelevant,
                selectedItemId, selectedCount)));
    }

    /** Records a processed mutation whose exact scope is unavailable. */
    public void onWorldMutation(ClientLevel level) {
        record(level, SessionChannel::worldMutation);
    }

    public void onBlockMutation(ClientLevel level, BlockPos position) {
        Objects.requireNonNull(position, "position");
        record(level, channel -> channel.worldMutation(
                WorldMutation.Kind.BLOCK, position.getX(), position.getY(), position.getZ()));
    }

    public void onChunkMutation(ClientLevel level, int chunkX, int chunkZ) {
        record(level, channel -> channel.worldMutation(
                WorldMutation.Kind.CHUNK, chunkX, 0, chunkZ));
    }

    /** Explicit lifecycle fence for level replacement/disconnect. */
    public void closeLevel(ClientLevel level) {
        if (level == null) {
            return;
        }
        synchronized (gate) {
            channels.remove(level);
        }
    }

    private void record(ClientLevel level, java.util.function.Consumer<SessionChannel> recorder) {
        Objects.requireNonNull(level, "level");
        synchronized (gate) {
            var channel = channels.get(level);
            // Packets before the first action/session binding are deliberately not evidence.
            if (channel != null && channel.bound()) {
                recorder.accept(channel);
            }
        }
    }

    private static String sanitize(String value) {
        var normalized = value.replaceAll("[\\p{Cntrl}]", " ").strip();
        return normalized.substring(0, Math.min(normalized.length(), 64));
    }

    public record Snapshot(
            UUID worldSessionId,
            long positionCorrectionRevision,
            long rotationRevision,
            long motionRevision,
            long inventoryRevision,
            long selectedSlotInventoryRevision,
            long worldRevision,
            List<WorldMutation> worldMutations,
            PositionCorrection lastPositionCorrection,
            ServerRotation lastServerRotation,
            LocalMotion lastLocalMotion,
            InventorySync lastInventorySync) {
        public Snapshot {
            Objects.requireNonNull(worldSessionId, "worldSessionId");
            worldMutations = List.copyOf(worldMutations);
        }

        public boolean sameSession(Snapshot other) {
            return other != null && worldSessionId.equals(other.worldSessionId);
        }
    }

    public record PositionCorrection(int teleportId, Vec3 appliedPosition) {
        public PositionCorrection {
            Objects.requireNonNull(appliedPosition, "appliedPosition");
        }
    }

    public record ServerRotation(float yaw, float pitch) {
    }

    public record LocalMotion(Vec3 movement, String source) {
        public LocalMotion {
            Objects.requireNonNull(movement, "movement");
            Objects.requireNonNull(source, "source");
        }
    }

    public record InventorySync(
            String packetKind,
            int packetSlot,
            boolean selectedSlotRelevant,
            String selectedItemId,
            int selectedCount) {
        public InventorySync {
            Objects.requireNonNull(packetKind, "packetKind");
            Objects.requireNonNull(selectedItemId, "selectedItemId");
            if (selectedCount < 0) {
                throw new IllegalArgumentException("selectedCount must be non-negative");
            }
        }
    }

    public record WorldMutation(long revision, Kind kind, int x, int y, int z) {
        public WorldMutation {
            if (revision < 1L) {
                throw new IllegalArgumentException("mutation revision must be positive");
            }
            Objects.requireNonNull(kind, "kind");
        }

        public enum Kind { BLOCK, CHUNK, ALL }
    }

    /** Package-private deterministic core used by unit tests. */
    static final class SessionChannel {
        private UUID worldSessionId;
        private long positionCorrectionRevision;
        private long rotationRevision;
        private long motionRevision;
        private long inventoryRevision;
        private long selectedSlotInventoryRevision;
        private long worldRevision;
        private final ArrayDeque<WorldMutation> worldMutations = new ArrayDeque<>(256);
        private PositionCorrection lastPositionCorrection;
        private ServerRotation lastServerRotation;
        private LocalMotion lastLocalMotion;
        private InventorySync lastInventorySync;

        boolean bound() {
            return worldSessionId != null;
        }

        Snapshot bindAndSnapshot(UUID requestedSession) {
            Objects.requireNonNull(requestedSession, "requestedSession");
            if (!requestedSession.equals(worldSessionId)) {
                worldSessionId = requestedSession;
                positionCorrectionRevision = 0;
                rotationRevision = 0;
                motionRevision = 0;
                inventoryRevision = 0;
                selectedSlotInventoryRevision = 0;
                worldRevision = 0;
                worldMutations.clear();
                lastPositionCorrection = null;
                lastServerRotation = null;
                lastLocalMotion = null;
                lastInventorySync = null;
            }
            return snapshot();
        }

        void positionCorrection(PositionCorrection evidence) {
            lastPositionCorrection = Objects.requireNonNull(evidence, "evidence");
            positionCorrectionRevision++;
        }

        void serverRotation(ServerRotation evidence) {
            lastServerRotation = Objects.requireNonNull(evidence, "evidence");
            rotationRevision++;
        }

        void localMotion(LocalMotion evidence) {
            lastLocalMotion = Objects.requireNonNull(evidence, "evidence");
            motionRevision++;
        }

        void inventorySync(InventorySync evidence) {
            lastInventorySync = Objects.requireNonNull(evidence, "evidence");
            inventoryRevision++;
            if (evidence.selectedSlotRelevant()) {
                selectedSlotInventoryRevision++;
            }
        }

        void worldMutation() {
            worldMutation(WorldMutation.Kind.ALL, 0, 0, 0);
        }

        void worldMutation(WorldMutation.Kind kind, int x, int y, int z) {
            worldRevision++;
            if (worldMutations.size() == 256) {
                worldMutations.removeFirst();
            }
            worldMutations.addLast(new WorldMutation(worldRevision, kind, x, y, z));
        }

        Snapshot snapshot() {
            if (worldSessionId == null) {
                throw new IllegalStateException("reconciliation channel is not session-bound");
            }
            return new Snapshot(worldSessionId, positionCorrectionRevision, rotationRevision,
                    motionRevision, inventoryRevision, selectedSlotInventoryRevision, worldRevision,
                    List.copyOf(worldMutations),
                    lastPositionCorrection, lastServerRotation, lastLocalMotion, lastInventorySync);
        }
    }
}
