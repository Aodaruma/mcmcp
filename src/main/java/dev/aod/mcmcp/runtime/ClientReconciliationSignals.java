package dev.aod.mcmcp.runtime;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
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
    static final int MAX_AUTHORITATIVE_BLOCK_STATES = 256;
    static final int MAX_SURFACE_MUTATION_REVISIONS = 256;
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

    /** Rendered entity content changes advance the audit clock without invalidating block rays. */
    public void onEntityDisplayMutation(ClientLevel level) {
        record(level, SessionChannel::entityDisplayMutation);
    }

    public void onBlockMutation(ClientLevel level, BlockPos position) {
        Objects.requireNonNull(position, "position");
        record(level, channel -> channel.unknownBlockMutation(position));
    }

    /**
     * Records one server-verified block state and its client state immediately before vanilla
     * applied that verification. The prior authoritative state remains the primary navigation
     * boundary because the client state may already contain a local prediction.
     */
    public void onBlockMutation(
            ClientLevel level,
            BlockPos position,
            BlockState clientBefore,
            BlockState serverVerifiedAfter) {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(clientBefore, "clientBefore");
        Objects.requireNonNull(serverVerifiedAfter, "serverVerifiedAfter");
        record(level, channel -> channel.blockMutation(
                position,
                navigationClass(clientBefore),
                navigationClass(serverVerifiedAfter)));
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
            long visualRevision,
            long visualBarrierWorldRevision,
            Map<BlockPos, Long> surfaceMutationRevisions,
            long surfaceMutationEvictionFloor,
            List<WorldMutation> worldMutations,
            PositionCorrection lastPositionCorrection,
            ServerRotation lastServerRotation,
            LocalMotion lastLocalMotion,
            InventorySync lastInventorySync) {
        public Snapshot {
            Objects.requireNonNull(worldSessionId, "worldSessionId");
            surfaceMutationRevisions = Map.copyOf(surfaceMutationRevisions);
            worldMutations = List.copyOf(worldMutations);
        }

        public boolean sameSession(Snapshot other) {
            return other != null && worldSessionId.equals(other.worldSessionId);
        }

        public long surfaceBarrierWorldRevision(int x, int y, int z) {
            long positionRevision = surfaceMutationRevisions.getOrDefault(
                    new BlockPos(x, y, z), 0L);
            return Math.max(
                    visualBarrierWorldRevision,
                    Math.max(surfaceMutationEvictionFloor, positionRevision));
        }

        /**
         * Exact-target freshness fence for an already explicit crop wait. When the
         * target remains in the bounded revision map, unrelated evictions cannot
         * invalidate that stronger coordinate-specific witness. Absence falls back
         * to the conservative global eviction floor.
         */
        public long waitTargetSurfaceBarrierWorldRevision(int x, int y, int z) {
            Long positionRevision = surfaceMutationRevisions.get(new BlockPos(x, y, z));
            long mutationBoundary = positionRevision != null
                    ? positionRevision : surfaceMutationEvictionFloor;
            return Math.max(visualBarrierWorldRevision, mutationBoundary);
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

    public record WorldMutation(
            long revision,
            Kind kind,
            int x,
            int y,
            int z,
            NavigationImpact navigationImpact) {
        public WorldMutation(long revision, Kind kind, int x, int y, int z) {
            this(revision, kind, x, y, z, NavigationImpact.LOCAL);
        }

        public WorldMutation {
            if (revision < 1L) {
                throw new IllegalArgumentException("mutation revision must be positive");
            }
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(navigationImpact, "navigationImpact");
            if (kind != Kind.BLOCK && kind != Kind.ENTITY_DISPLAY
                    && navigationImpact != NavigationImpact.LOCAL) {
                throw new IllegalArgumentException(
                        "chunk/all mutations must remain navigation-relevant");
            }
            if (kind == Kind.ENTITY_DISPLAY && navigationImpact != NavigationImpact.NONE) {
                throw new IllegalArgumentException("entity display mutations do not change block navigation");
            }
        }

        public enum Kind { BLOCK, CHUNK, ALL, ENTITY_DISPLAY }
    }

    public enum NavigationImpact { LOCAL, NONE }

    enum NavigationClass { AIR, WHEAT, FARMLAND, OTHER }

    /** Package-private deterministic core used by unit tests. */
    static final class SessionChannel {
        private UUID worldSessionId;
        private long positionCorrectionRevision;
        private long rotationRevision;
        private long motionRevision;
        private long inventoryRevision;
        private long selectedSlotInventoryRevision;
        private long worldRevision;
        private long visualRevision;
        private long visualBarrierWorldRevision;
        private long surfaceMutationEvictionFloor;
        private final ArrayDeque<WorldMutation> worldMutations = new ArrayDeque<>(256);
        private final LinkedHashMap<BlockPos, NavigationClass> authoritativeBlockStates =
                new LinkedHashMap<>(MAX_AUTHORITATIVE_BLOCK_STATES, 0.75F, true);
        private final LinkedHashMap<BlockPos, Long> surfaceMutationRevisions =
                new LinkedHashMap<>(MAX_SURFACE_MUTATION_REVISIONS, 0.75F, true);
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
                visualRevision = 0;
                visualBarrierWorldRevision = 0;
                surfaceMutationEvictionFloor = 0;
                worldMutations.clear();
                authoritativeBlockStates.clear();
                surfaceMutationRevisions.clear();
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

        void entityDisplayMutation() {
            worldMutation(WorldMutation.Kind.ENTITY_DISPLAY, 0, 0, 0, NavigationImpact.NONE);
        }

        void worldMutation(WorldMutation.Kind kind, int x, int y, int z) {
            if (kind == WorldMutation.Kind.ALL) {
                authoritativeBlockStates.clear();
            } else if (kind == WorldMutation.Kind.CHUNK) {
                authoritativeBlockStates.keySet().removeIf(position ->
                        (position.getX() >> 4) == x && (position.getZ() >> 4) == z);
            }
            worldMutation(kind, x, y, z, NavigationImpact.LOCAL);
        }

        void unknownBlockMutation(BlockPos position) {
            Objects.requireNonNull(position, "position");
            BlockPos immutable = position.immutable();
            authoritativeBlockStates.remove(immutable);
            worldMutation(
                    WorldMutation.Kind.BLOCK,
                    immutable.getX(), immutable.getY(), immutable.getZ(),
                    NavigationImpact.LOCAL);
        }

        void blockMutation(
                BlockPos position,
                NavigationClass clientBefore,
                NavigationClass serverVerifiedAfter) {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(clientBefore, "clientBefore");
            Objects.requireNonNull(serverVerifiedAfter, "serverVerifiedAfter");
            BlockPos immutable = position.immutable();
            NavigationClass authoritativeBefore = authoritativeBlockStates.get(immutable);
            NavigationImpact impact = navigationImpact(
                    authoritativeBefore, clientBefore, serverVerifiedAfter);
            authoritativeBlockStates.put(immutable, serverVerifiedAfter);
            while (authoritativeBlockStates.size() > MAX_AUTHORITATIVE_BLOCK_STATES) {
                authoritativeBlockStates.remove(
                        authoritativeBlockStates.keySet().iterator().next());
            }
            worldMutation(
                    WorldMutation.Kind.BLOCK,
                    immutable.getX(), immutable.getY(), immutable.getZ(),
                    impact);
        }

        void worldMutation(
                WorldMutation.Kind kind,
                int x,
                int y,
                int z,
                NavigationImpact navigationImpact) {
            worldRevision++;
            if (navigationImpact == NavigationImpact.LOCAL) {
                visualRevision++;
                visualBarrierWorldRevision = worldRevision;
            }
            if (kind == WorldMutation.Kind.BLOCK) {
                surfaceMutationRevisions.put(new BlockPos(x, y, z), worldRevision);
                while (surfaceMutationRevisions.size() > MAX_SURFACE_MUTATION_REVISIONS) {
                    var iterator = surfaceMutationRevisions.entrySet().iterator();
                    var evicted = iterator.next();
                    surfaceMutationEvictionFloor = Math.max(
                            surfaceMutationEvictionFloor, evicted.getValue());
                    iterator.remove();
                }
            }
            if (worldMutations.size() == 256) {
                worldMutations.removeFirst();
            }
            worldMutations.addLast(new WorldMutation(
                    worldRevision, kind, x, y, z, navigationImpact));
        }

        Snapshot snapshot() {
            if (worldSessionId == null) {
                throw new IllegalStateException("reconciliation channel is not session-bound");
            }
            return new Snapshot(worldSessionId, positionCorrectionRevision, rotationRevision,
                    motionRevision, inventoryRevision, selectedSlotInventoryRevision, worldRevision,
                    visualRevision, visualBarrierWorldRevision,
                    Map.copyOf(surfaceMutationRevisions), surfaceMutationEvictionFloor,
                    List.copyOf(worldMutations),
                    lastPositionCorrection, lastServerRotation, lastLocalMotion, lastInventorySync);
        }
    }

    static NavigationClass navigationClass(BlockState state) {
        Objects.requireNonNull(state, "state");
        if (state.isAir()) return NavigationClass.AIR;
        if (state.is(Blocks.WHEAT)) return NavigationClass.WHEAT;
        if (state.is(Blocks.FARMLAND)) return NavigationClass.FARMLAND;
        return NavigationClass.OTHER;
    }

    static NavigationImpact navigationImpact(
            NavigationClass authoritativeBefore,
            NavigationClass clientBefore,
            NavigationClass serverVerifiedAfter) {
        Objects.requireNonNull(clientBefore, "clientBefore");
        Objects.requireNonNull(serverVerifiedAfter, "serverVerifiedAfter");
        if (authoritativeBefore != null) {
            if (authoritativeBefore == NavigationClass.FARMLAND
                    && serverVerifiedAfter == NavigationClass.FARMLAND) {
                return NavigationImpact.NONE;
            }
            if (wheatOrAir(authoritativeBefore) && wheatOrAir(serverVerifiedAfter)) {
                return NavigationImpact.NONE;
            }
            return NavigationImpact.LOCAL;
        }
        // A newly verified wheat state is navigation-neutral only when the client also had an
        // empty-collision wheat/air state. Unknown initial farmland remains relevant so tilling
        // cannot be mistaken for a moisture-only update after local prediction.
        return serverVerifiedAfter == NavigationClass.WHEAT && wheatOrAir(clientBefore)
                ? NavigationImpact.NONE
                : NavigationImpact.LOCAL;
    }

    private static boolean wheatOrAir(NavigationClass value) {
        return value == NavigationClass.WHEAT || value == NavigationClass.AIR;
    }
}
