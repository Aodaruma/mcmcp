package dev.aod.mcmcp.safety;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Local-UI-issued, single-consume authority to start one finite kill-zone operation.
 *
 * <p>The physical grant is not a reusable per-attack bearer lease. A future
 * {@code operate_kill_zone} consumer must consume it once at Action start, copy the approved count,
 * interval, duration, and spatial policy into Action-owned state, and reserve each subsequent
 * dispatch against that finite state. This permits newly spawned mobs without one click per mob
 * while preventing the grant ref from being replayed across Actions.</p>
 */
public final class ScopedEntityAttackConsentStore {
    public static final long PENDING_TTL_TICKS = 1_200L;
    public static final long GRANTED_TTL_TICKS = 2_400L;
    public static final long MAX_OPERATION_DURATION_TICKS = 36_000L;
    public static final int MAX_ATTACKS = 2_048;
    public static final int MAX_ENTITY_TYPES = 16;
    public static final long MIN_MINIMUM_INTERVAL_TICKS = 10L;
    public static final long MAX_MINIMUM_INTERVAL_TICKS = 1_200L;
    public static final double MIN_STATION_TO_KILL_ZONE_GAP = 0.5D;

    private final SecureRandom random;
    private Pending pending;
    private Granted granted;
    private long lastTick = Long.MIN_VALUE;
    private boolean tickFaulted;

    public ScopedEntityAttackConsentStore() {
        this(new SecureRandom());
    }

    ScopedEntityAttackConsentStore(SecureRandom random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    /** Registers exactly one user-visible policy request; another live policy cannot replace it. */
    public synchronized RequestResult request(
            UUID worldSessionId,
            String policyBindingHash,
            Scope scope,
            long clientTick) {
        Objects.requireNonNull(worldSessionId, "worldSessionId");
        requireHash(policyBindingHash, "policyBindingHash");
        Objects.requireNonNull(scope, "scope");
        if (!advance(clientTick)) {
            return RequestResult.TICK_REJECTED;
        }
        if (granted != null) {
            return granted.matches(worldSessionId, policyBindingHash, scope)
                    ? RequestResult.ALREADY_GRANTED : RequestResult.BUSY;
        }
        if (pending != null) {
            return pending.matches(worldSessionId, policyBindingHash, scope)
                    ? RequestResult.ALREADY_PENDING : RequestResult.BUSY;
        }
        pending = new Pending(
                worldSessionId,
                policyBindingHash,
                scope,
                clientTick,
                deadline(clientTick, PENDING_TTL_TICKS));
        return RequestResult.REGISTERED;
    }

    /** Mints start authority only from the dedicated local physical-click handler. */
    public synchronized boolean grantFromPhysicalUiClick(
            LocalUiGrantCapability click,
            UUID worldSessionId,
            long clientTick) {
        Objects.requireNonNull(click, "click");
        Objects.requireNonNull(worldSessionId, "worldSessionId");
        if (!click.consumeOnce() || !advance(clientTick)) {
            return false;
        }
        if (pending == null || !pending.worldSessionId().equals(worldSessionId)) {
            return false;
        }
        byte[] bytes = new byte[18];
        random.nextBytes(bytes);
        granted = new Granted(
                Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
                pending.worldSessionId(),
                pending.policyBindingHash(),
                pending.scope(),
                clientTick,
                deadline(clientTick, GRANTED_TTL_TICKS));
        pending = null;
        return true;
    }

    /**
     * Atomically consumes the exact grant once to start one finite Action.
     * The future consumer must derive the station bounds and attack profile from trusted live state
     * before registration, then JIT enforce the approved policy and all hard safety gates during the
     * Action. A false result never grants partial authority.
     */
    public synchronized boolean consumeExactForActionStart(
            String consentRef,
            UUID worldSessionId,
            String policyBindingHash,
            Scope scope,
            long clientTick) {
        Objects.requireNonNull(consentRef, "consentRef");
        Objects.requireNonNull(worldSessionId, "worldSessionId");
        requireHash(policyBindingHash, "policyBindingHash");
        Objects.requireNonNull(scope, "scope");
        if (!advance(clientTick)
                || granted == null
                || clientTick < granted.grantedAtTick()
                || !granted.reference().equals(consentRef)
                || !granted.matches(worldSessionId, policyBindingHash, scope)) {
            return false;
        }
        granted = null;
        return true;
    }

    public synchronized Snapshot snapshot(UUID worldSessionId, long clientTick) {
        if (!advance(clientTick) || worldSessionId == null) {
            return Snapshot.none();
        }
        if (granted != null && granted.worldSessionId().equals(worldSessionId)) {
            return new Snapshot(
                    State.GRANTED,
                    granted.policyBindingHash(),
                    granted.scope(),
                    granted.reference(),
                    granted.expiresAtTick());
        }
        if (pending != null && pending.worldSessionId().equals(worldSessionId)) {
            return new Snapshot(
                    State.PENDING,
                    pending.policyBindingHash(),
                    pending.scope(),
                    null,
                    null);
        }
        return Snapshot.none();
    }

    /** World, OFF, emergency-stop, fault, and shutdown boundaries revoke all start authority. */
    public synchronized void clear() {
        pending = null;
        granted = null;
        lastTick = Long.MIN_VALUE;
        tickFaulted = false;
    }

    private boolean advance(long clientTick) {
        requireTick(clientTick);
        if (tickFaulted) {
            return false;
        }
        if (lastTick != Long.MIN_VALUE && clientTick < lastTick) {
            pending = null;
            granted = null;
            tickFaulted = true;
            return false;
        }
        lastTick = clientTick;
        if (pending != null && clientTick >= pending.expiresAtTick()) {
            pending = null;
        }
        if (granted != null && clientTick >= granted.expiresAtTick()) {
            granted = null;
        }
        return true;
    }

    private static long deadline(long clientTick, long ttl) {
        return clientTick > Long.MAX_VALUE - ttl ? Long.MAX_VALUE : clientTick + ttl;
    }

    private static void requireTick(long clientTick) {
        if (clientTick < 0L) {
            throw new IllegalArgumentException("clientTick must be non-negative");
        }
    }

    private static void requireHash(String value, String field) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be SHA-256");
        }
    }

    private static void requireResourceLocation(String value, String field) {
        if (value == null || value.length() > 128
                || !value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException(field + " must be a registered resource location");
        }
    }

    public enum RequestResult {
        REGISTERED,
        ALREADY_PENDING,
        ALREADY_GRANTED,
        BUSY,
        TICK_REJECTED
    }

    public enum State {
        NONE,
        PENDING,
        GRANTED
    }

    /** Single-use capability object minted only through the safety-package UI bridge. */
    public static final class LocalUiGrantCapability {
        private boolean consumed;

        LocalUiGrantCapability() {
        }

        private boolean consumeOnce() {
            if (consumed) {
                return false;
            }
            consumed = true;
            return true;
        }
    }

    public record Bounds(
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ) {
        public Bounds {
            if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(minZ)
                    || !Double.isFinite(maxX) || !Double.isFinite(maxY)
                    || !Double.isFinite(maxZ)
                    || minX > maxX || minY > maxY || minZ > maxZ
                    || maxX - minX > 32.0D || maxY - minY > 16.0D
                    || maxZ - minZ > 32.0D) {
                throw new IllegalArgumentException(
                        "consent bounds must be finite, ordered, and local");
            }
        }

        private boolean interiorOverlaps(Bounds other) {
            return minX < other.maxX && maxX > other.minX
                    && minY < other.maxY && maxY > other.minY
                    && minZ < other.maxZ && maxZ > other.minZ;
        }

        private double separationFrom(Bounds other) {
            double xGap = Math.max(0.0D, Math.max(other.minX - maxX, minX - other.maxX));
            double yGap = Math.max(0.0D, Math.max(other.minY - maxY, minY - other.maxY));
            double zGap = Math.max(0.0D, Math.max(other.minZ - maxZ, minZ - other.maxZ));
            return Math.sqrt(xGap * xGap + yGap * yGap + zGap * zGap);
        }
    }

    /**
     * Approved finite-operation policy. The future runtime must derive playerStationBounds from the
     * grant-time player AABB/support and derive attackSideEffectProfile plus
     * attackProfileFingerprint from the exact held stack through a declared adapter. The fingerprint
     * excludes durability damage so Mending and equivalent same-profile stack replacement remain
     * usable; item disappearance, breakage, or attack-profile drift terminates the Action.
     */
    public record Scope(
            String dimension,
            Bounds playerStationBounds,
            Bounds targetKillZoneBounds,
            List<String> entityTypeAllowlist,
            String mainHandItem,
            String attackProfileFingerprint,
            AttackSideEffectProfile attackSideEffectProfile,
            int maxAttacks,
            long minimumIntervalTicks,
            long maxOperationDurationTicks) {
        public Scope {
            requireResourceLocation(dimension, "dimension");
            Objects.requireNonNull(playerStationBounds, "playerStationBounds");
            Objects.requireNonNull(targetKillZoneBounds, "targetKillZoneBounds");
            if (playerStationBounds.maxX() - playerStationBounds.minX() > 1.5D
                    || playerStationBounds.maxY() - playerStationBounds.minY() > 2.75D
                    || playerStationBounds.maxZ() - playerStationBounds.minZ() > 1.5D
                    || playerStationBounds.interiorOverlaps(targetKillZoneBounds)
                    || playerStationBounds.separationFrom(targetKillZoneBounds)
                            < MIN_STATION_TO_KILL_ZONE_GAP) {
                throw new IllegalArgumentException(
                        "player station must be tight and separated from the kill zone");
            }
            Objects.requireNonNull(entityTypeAllowlist, "entityTypeAllowlist");
            if (entityTypeAllowlist.isEmpty()
                    || entityTypeAllowlist.size() > MAX_ENTITY_TYPES) {
                throw new IllegalArgumentException("entity type allowlist must be small and non-empty");
            }
            var normalizedTypes = new ArrayList<>(entityTypeAllowlist);
            normalizedTypes.forEach(type -> requireResourceLocation(type, "entityTypeAllowlist"));
            if (normalizedTypes.contains("minecraft:player")) {
                throw new IllegalArgumentException("players can never be attack-consent targets");
            }
            if (new HashSet<>(normalizedTypes).size() != normalizedTypes.size()) {
                throw new IllegalArgumentException("entity type allowlist cannot contain duplicates");
            }
            normalizedTypes.sort(String::compareTo);
            entityTypeAllowlist = List.copyOf(normalizedTypes);
            requireResourceLocation(mainHandItem, "mainHandItem");
            requireHash(attackProfileFingerprint, "attackProfileFingerprint");
            Objects.requireNonNull(attackSideEffectProfile, "attackSideEffectProfile");
            if (maxAttacks < 1 || maxAttacks > MAX_ATTACKS
                    || minimumIntervalTicks < MIN_MINIMUM_INTERVAL_TICKS
                    || minimumIntervalTicks > MAX_MINIMUM_INTERVAL_TICKS
                    || maxOperationDurationTicks < 1L
                    || maxOperationDurationTicks > MAX_OPERATION_DURATION_TICKS) {
                throw new IllegalArgumentException("attack count, interval, or duration is out of range");
            }
        }
    }

    public enum AttackSideEffectProfile {
        VANILLA_SINGLE_TARGET,
        VANILLA_SWEEP,
        ADAPTER_SINGLE_TARGET,
        ADAPTER_BOUNDED_AOE
    }

    public record Snapshot(
            State state,
            String policyBindingHash,
            Scope scope,
            String consentRef,
            Long validBeforeClientTick) {
        public Snapshot {
            Objects.requireNonNull(state, "state");
            boolean none = state == State.NONE;
            boolean grantedState = state == State.GRANTED;
            if (none != (policyBindingHash == null)
                    || none != (scope == null)
                    || grantedState != (consentRef != null)
                    || grantedState != (validBeforeClientTick != null)
                    || (grantedState && validBeforeClientTick <= 0L)) {
                throw new IllegalArgumentException("inconsistent consent snapshot");
            }
            if (!none) {
                requireHash(policyBindingHash, "policyBindingHash");
            }
        }

        public static Snapshot none() {
            return new Snapshot(State.NONE, null, null, null, null);
        }
    }

    private record Pending(
            UUID worldSessionId,
            String policyBindingHash,
            Scope scope,
            long requestedAtTick,
            long expiresAtTick) {
        private boolean matches(UUID session, String hash, Scope candidate) {
            return worldSessionId.equals(session)
                    && policyBindingHash.equals(hash)
                    && scope.equals(candidate);
        }
    }

    private record Granted(
            String reference,
            UUID worldSessionId,
            String policyBindingHash,
            Scope scope,
            long grantedAtTick,
            long expiresAtTick) {
        private boolean matches(UUID session, String hash, Scope candidate) {
            return worldSessionId.equals(session)
                    && policyBindingHash.equals(hash)
                    && scope.equals(candidate);
        }
    }
}
