package dev.aod.mcmcp.agent.safety;

import dev.aod.mcmcp.client.AgentInputState;
import dev.aod.mcmcp.runtime.ClientReconciliationSignals;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Same-thread bridge between collision resolution and tick-boundary movement evidence. */
public final class AgentMovementTrace {
    private static final double CONTACT_EPSILON_SQUARED = 1.0E-10D;
    private static final AgentMovementTrace GLOBAL = new AgentMovementTrace();

    private TickCapture activeTick;
    private volatile TickMovement latest;
    private long sequence;

    public static AgentMovementTrace global() {
        return GLOBAL;
    }

    public static boolean agentMovementActive() {
        return AgentInputState.global().goalMovementOutputActive();
    }

    public void beginTick(LocalPlayer player) {
        Objects.requireNonNull(player, "player");
        if (!agentMovementActive()) {
            clearActiveTick();
            return;
        }
        var proof = AgentInputState.global().goalMovementProofFor(player, player.level());
        if (proof.isEmpty()) {
            clearActiveTick();
            return;
        }
        var reconciliation = ClientReconciliationSignals.global()
                .currentSnapshot((ClientLevel) player.level());
        if (reconciliation.isEmpty()) {
            clearActiveTick();
            return;
        }
        beginTick(
                player,
                player.level(),
                player.tickCount,
                proof.orElseThrow().worldRevision(),
                reconciliation.orElseThrow().positionCorrectionRevision(),
                player.position());
    }

    public void recordCollision(
            LocalPlayer player,
            MoverType moverType,
            AABB start,
            Vec3 intendedDelta,
            Vec3 resolvedDelta) {
        Objects.requireNonNull(player, "player");
        if (moverType != MoverType.SELF) {
            return;
        }
        recordCollision(player, player.level(), start, intendedDelta, resolvedDelta);
    }

    public void endTick(LocalPlayer player) {
        Objects.requireNonNull(player, "player");
        long correctionRevision = ClientReconciliationSignals.global()
                .currentSnapshot((ClientLevel) player.level())
                .map(ClientReconciliationSignals.Snapshot::positionCorrectionRevision)
                .orElse(-1L);
        endTick(player, player.level(), player.position(), correctionRevision);
    }

    public Optional<TickMovement> latestFor(LocalPlayer player) {
        Objects.requireNonNull(player, "player");
        var correctionRevision = ClientReconciliationSignals.global()
                .currentSnapshot((ClientLevel) player.level())
                .map(ClientReconciliationSignals.Snapshot::positionCorrectionRevision);
        return correctionRevision.flatMap(revision -> latestFor(player, player.level())
                .filter(frame -> frame.positionCorrectionRevision() == revision));
    }

    Optional<TickMovement> latestFor(Object playerIdentity, Object levelIdentity) {
        var snapshot = latest;
        if (snapshot == null
                || snapshot.playerIdentity() != playerIdentity
                || snapshot.levelIdentity() != levelIdentity) {
            return Optional.empty();
        }
        return Optional.of(snapshot);
    }

    synchronized void beginTick(
            Object playerIdentity,
            Object levelIdentity,
            long tick,
            long worldRevision,
            Vec3 position) {
        beginTick(playerIdentity, levelIdentity, tick, worldRevision, 0L, position);
    }

    synchronized void beginTick(
            Object playerIdentity,
            Object levelIdentity,
            long tick,
            long worldRevision,
            long positionCorrectionRevision,
            Vec3 position) {
        Objects.requireNonNull(playerIdentity, "playerIdentity");
        Objects.requireNonNull(levelIdentity, "levelIdentity");
        Objects.requireNonNull(position, "position");
        if (tick < 0L || worldRevision < 0L || positionCorrectionRevision < 0L) {
            throw new IllegalArgumentException("movement tick and revision must be non-negative");
        }
        activeTick = new TickCapture(
                playerIdentity,
                levelIdentity,
                tick,
                worldRevision,
                positionCorrectionRevision,
                position,
                Thread.currentThread(),
                new ArrayList<>());
    }

    private synchronized void clearActiveTick() {
        activeTick = null;
    }

    synchronized void recordCollision(
            Object playerIdentity,
            Object levelIdentity,
            AABB start,
            Vec3 intendedDelta,
            Vec3 resolvedDelta) {
        var capture = activeTick;
        if (capture == null
                || capture.playerIdentity() != playerIdentity
                || capture.levelIdentity() != levelIdentity) {
            return;
        }
        capture.requireSameThread();
        capture.collisions().add(new CollisionResolution(
                Objects.requireNonNull(start, "start"),
                Objects.requireNonNull(intendedDelta, "intendedDelta"),
                Objects.requireNonNull(resolvedDelta, "resolvedDelta"),
                SweptAabbPath.segments(start, intendedDelta, resolvedDelta)));
    }

    synchronized void endTick(
            Object playerIdentity,
            Object levelIdentity,
            Vec3 position) {
        long correctionRevision = activeTick == null
                ? -1L : activeTick.positionCorrectionRevision();
        endTick(playerIdentity, levelIdentity, position, correctionRevision);
    }

    synchronized void endTick(
            Object playerIdentity,
            Object levelIdentity,
            Vec3 position,
            long positionCorrectionRevision) {
        var capture = activeTick;
        activeTick = null;
        if (capture == null
                || capture.playerIdentity() != playerIdentity
                || capture.levelIdentity() != levelIdentity
                || capture.positionCorrectionRevision() != positionCorrectionRevision) {
            return;
        }
        capture.requireSameThread();
        if (capture.collisions().isEmpty()) {
            return;
        }
        latest = new TickMovement(
                ++sequence,
                capture.playerIdentity(),
                capture.levelIdentity(),
                capture.tick(),
                capture.worldRevision(),
                capture.positionCorrectionRevision(),
                capture.startPosition(),
                Objects.requireNonNull(position, "position"),
                position.subtract(capture.startPosition()),
                List.copyOf(capture.collisions()));
    }

    private record TickCapture(
            Object playerIdentity,
            Object levelIdentity,
            long tick,
            long worldRevision,
            long positionCorrectionRevision,
            Vec3 startPosition,
            Thread thread,
            ArrayList<CollisionResolution> collisions) {
        private void requireSameThread() {
            if (Thread.currentThread() != thread) {
                throw new IllegalStateException(
                        "agent collision trace crossed the game-thread boundary");
            }
        }
    }

    public record CollisionResolution(
            AABB start,
            Vec3 intendedDelta,
            Vec3 resolvedDelta,
            List<SweptAabbPath.AxisSegment> segments) {
        public CollisionResolution {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(intendedDelta, "intendedDelta");
            Objects.requireNonNull(resolvedDelta, "resolvedDelta");
            segments = List.copyOf(Objects.requireNonNull(segments, "segments"));
        }

        public AABB resolvedEnd() {
            return start.move(resolvedDelta);
        }

        public boolean horizontallyClipped() {
            return differs(intendedDelta.x, resolvedDelta.x)
                    || differs(intendedDelta.z, resolvedDelta.z);
        }
    }

    public record TickMovement(
            long sequence,
            Object playerIdentity,
            Object levelIdentity,
            long tick,
            long worldRevision,
            long positionCorrectionRevision,
            Vec3 tickStartPosition,
            Vec3 tickEndPosition,
            Vec3 actualDelta,
            List<CollisionResolution> collisions) {
        public TickMovement {
            if (sequence < 1L || tick < 0L || worldRevision < 0L
                    || positionCorrectionRevision < 0L) {
                throw new IllegalArgumentException("movement sequence and tick must be positive");
            }
            Objects.requireNonNull(playerIdentity, "playerIdentity");
            Objects.requireNonNull(levelIdentity, "levelIdentity");
            Objects.requireNonNull(tickStartPosition, "tickStartPosition");
            Objects.requireNonNull(tickEndPosition, "tickEndPosition");
            Objects.requireNonNull(actualDelta, "actualDelta");
            collisions = List.copyOf(Objects.requireNonNull(collisions, "collisions"));
            if (collisions.isEmpty()) {
                throw new IllegalArgumentException("tick movement requires a collision resolution");
            }
        }

        /** CONTACT requires tick-boundary displacement aligned with this resolved movement. */
        public boolean contactEvidence(CollisionResolution collision) {
            Objects.requireNonNull(collision, "collision");
            double resolvedHorizontalSquared = collision.resolvedDelta().x * collision.resolvedDelta().x
                    + collision.resolvedDelta().z * collision.resolvedDelta().z;
            double actualHorizontalSquared = actualDelta.x * actualDelta.x
                    + actualDelta.z * actualDelta.z;
            if (resolvedHorizontalSquared <= CONTACT_EPSILON_SQUARED
                    || actualHorizontalSquared <= CONTACT_EPSILON_SQUARED) {
                return false;
            }
            return actualDelta.x * collision.resolvedDelta().x
                    + actualDelta.z * collision.resolvedDelta().z > 0.0D;
        }
    }

    private static boolean differs(double intended, double resolved) {
        return Math.abs(intended - resolved) > 1.0E-7D;
    }
}
