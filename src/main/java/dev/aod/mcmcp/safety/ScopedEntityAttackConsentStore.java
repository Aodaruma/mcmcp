package dev.aod.mcmcp.safety;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * Local-UI-issued, single-use consent for one canonically bound semantic entity attack.
 *
 * <p>The MCP side may register one bounded request, but only a non-replayable
 * {@link LocalUiGrantCapability} minted by the in-game status-button adapter can turn it into
 * authority.
 * Chat, books, signs, server packets, Action fields, and ordinary runtime code cannot mint that
 * token. This consent can waive only the named target's hostile-presence gate; damage, contact,
 * projectile, fire, fall, health, world, and reconciliation gates remain hard safety gates.</p>
 */
public final class ScopedEntityAttackConsentStore {
    public static final long PENDING_TTL_TICKS = 1_200L;
    public static final long GRANTED_TTL_TICKS = 200L;

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

    /** Registers exactly one user-visible request; a different live request cannot replace it. */
    public synchronized RequestResult request(
            UUID worldSessionId,
            String actionBindingHash,
            Scope scope,
            long clientTick) {
        Objects.requireNonNull(worldSessionId, "worldSessionId");
        requireHash(actionBindingHash);
        Objects.requireNonNull(scope, "scope");
        if (!advance(clientTick)) {
            return RequestResult.TICK_REJECTED;
        }
        if (granted != null) {
            return granted.matches(worldSessionId, actionBindingHash, scope)
                    ? RequestResult.ALREADY_GRANTED : RequestResult.BUSY;
        }
        if (pending != null) {
            return pending.matches(worldSessionId, actionBindingHash, scope)
                    ? RequestResult.ALREADY_PENDING : RequestResult.BUSY;
        }
        pending = new Pending(
                worldSessionId,
                actionBindingHash,
                scope,
                clientTick,
                deadline(clientTick, PENDING_TTL_TICKS));
        return RequestResult.REGISTERED;
    }

    /** Mints authority only from a token created by the local physical-click handler. */
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
                pending.actionBindingHash(),
                pending.scope(),
                clientTick,
                deadline(clientTick, GRANTED_TTL_TICKS));
        pending = null;
        return true;
    }

    /** Atomically verifies and consumes the exact grant at the final client-thread commit fence. */
    public synchronized boolean consumeExact(
            String consentRef,
            UUID worldSessionId,
            String actionBindingHash,
            Scope scope,
            long clientTick) {
        Objects.requireNonNull(consentRef, "consentRef");
        Objects.requireNonNull(worldSessionId, "worldSessionId");
        requireHash(actionBindingHash);
        Objects.requireNonNull(scope, "scope");
        if (!advance(clientTick)
                || granted == null
                || clientTick < granted.grantedAtTick()
                || !granted.reference().equals(consentRef)
                || !granted.matches(worldSessionId, actionBindingHash, scope)) {
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
                    granted.actionBindingHash(),
                    granted.scope(),
                    granted.reference(),
                    granted.expiresAtTick());
        }
        if (pending != null && pending.worldSessionId().equals(worldSessionId)) {
            return new Snapshot(
                    State.PENDING,
                    pending.actionBindingHash(),
                    pending.scope(),
                    null,
                    pending.expiresAtTick());
        }
        return Snapshot.none();
    }

    /** World, OFF, emergency-stop, and shutdown boundaries revoke every outstanding request. */
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

    private static void requireHash(String value) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("action binding hash must be SHA-256");
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

    /**
     * Single-use authority object for the future physical-local-UI adapter.
     *
     * <p>Construction is package-private, so MCP, Action, runtime, chat, and server-facing code
     * cannot mint it. The eventual status-button adapter must live behind a tiny safety-package
     * bridge rather than widening this constructor or adding a boolean grant parameter.</p>
     */
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
    }

    public record Scope(
            String dimension,
            Bounds bounds,
            String entityRef,
            String entityType) {
        public Scope {
            Objects.requireNonNull(bounds, "bounds");
            if (dimension == null || dimension.isBlank() || dimension.length() > 128
                    || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                    || entityRef == null || !entityRef.matches("[A-Za-z0-9_-]{24}")
                    || entityType == null
                    || !entityType.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                throw new IllegalArgumentException("invalid entity attack consent scope");
            }
        }
    }

    public record Snapshot(
            State state,
            String actionBindingHash,
            Scope scope,
            String consentRef,
            long validBeforeClientTick) {
        public Snapshot {
            Objects.requireNonNull(state, "state");
            boolean none = state == State.NONE;
            if (none != (actionBindingHash == null)
                    || none != (scope == null)
                    || (state == State.GRANTED) != (consentRef != null)
                    || (none ? validBeforeClientTick != 0L : validBeforeClientTick <= 0L)) {
                throw new IllegalArgumentException("inconsistent consent snapshot");
            }
            if (!none) {
                requireHash(actionBindingHash);
            }
        }

        public static Snapshot none() {
            return new Snapshot(State.NONE, null, null, null, 0L);
        }
    }

    private record Pending(
            UUID worldSessionId,
            String actionBindingHash,
            Scope scope,
            long requestedAtTick,
            long expiresAtTick) {
        private boolean matches(UUID session, String hash, Scope candidate) {
            return worldSessionId.equals(session)
                    && actionBindingHash.equals(hash)
                    && scope.equals(candidate);
        }
    }

    private record Granted(
            String reference,
            UUID worldSessionId,
            String actionBindingHash,
            Scope scope,
            long grantedAtTick,
            long expiresAtTick) {
        private boolean matches(UUID session, String hash, Scope candidate) {
            return worldSessionId.equals(session)
                    && actionBindingHash.equals(hash)
                    && scope.equals(candidate);
        }
    }
}
