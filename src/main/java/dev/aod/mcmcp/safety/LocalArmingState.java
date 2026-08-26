package dev.aod.mcmcp.safety;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Local-only, session-scoped one-action control lease. */
public final class LocalArmingState {
    public static final long READY_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(30);

    private Mode mode = Mode.OFF;
    private UUID worldSessionId;
    private Set<String> capabilities = Set.of();
    private long readyExpiresAtNanos;
    private String lastLockReason = "startup";

    public synchronized void arm(UUID sessionId, Set<String> capabilityProfile) {
        arm(sessionId, capabilityProfile, System.nanoTime());
    }

    public synchronized void armFor(
            UUID sessionId,
            Set<String> capabilityProfile,
            Duration readyTimeout) {
        Objects.requireNonNull(readyTimeout, "readyTimeout");
        long timeoutNanos = readyTimeout.toNanos();
        if (timeoutNanos <= 0L) {
            throw new IllegalArgumentException("readyTimeout must be positive");
        }
        arm(sessionId, capabilityProfile, System.nanoTime(), timeoutNanos);
    }

    synchronized void arm(UUID sessionId, Set<String> capabilityProfile, long nowNanos) {
        arm(sessionId, capabilityProfile, nowNanos, READY_TIMEOUT_NANOS);
    }

    private void arm(
            UUID sessionId,
            Set<String> capabilityProfile,
            long nowNanos,
            long timeoutNanos) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(capabilityProfile, "capabilityProfile");
        worldSessionId = sessionId;
        capabilities = Set.copyOf(new LinkedHashSet<>(capabilityProfile));
        mode = Mode.READY;
        // System.nanoTime() is defined only by differences; natural overflow is intentional.
        readyExpiresAtNanos = nowNanos + timeoutNanos;
        lastLockReason = null;
    }

    /** Consumes READY exactly once; the active action no longer inherits the READY timeout. */
    public synchronized boolean beginAction(UUID sessionId) {
        var current = snapshot(sessionId);
        if (current.mode() != Mode.READY) {
            return false;
        }
        mode = Mode.AGENT;
        readyExpiresAtNanos = 0L;
        return true;
    }

    public synchronized boolean beginRecovery(UUID sessionId) {
        var current = snapshot(sessionId);
        if (current.mode() != Mode.AGENT) {
            return false;
        }
        mode = Mode.RECOVERING;
        return true;
    }

    public synchronized void lock(String reason) {
        mode = Mode.OFF;
        worldSessionId = null;
        capabilities = Set.of();
        readyExpiresAtNanos = 0L;
        lastLockReason = sanitizeReason(reason);
    }

    public synchronized Snapshot snapshot(UUID currentSessionId) {
        return snapshot(currentSessionId, System.nanoTime());
    }

    public synchronized Snapshot snapshot(UUID currentSessionId, long nowNanos) {
        if (worldSessionId != null && !worldSessionId.equals(currentSessionId)) {
            lock("world_session_changed");
        }
        if (mode == Mode.READY && nowNanos - readyExpiresAtNanos >= 0L) {
            lock("ready_timeout");
        }
        return new Snapshot(mode, worldSessionId, capabilities, readyExpiresAtNanos, lastLockReason);
    }

    public synchronized boolean allows(UUID currentSessionId, String capability) {
        var snapshot = snapshot(currentSessionId);
        return snapshot.mode() != Mode.OFF
                && snapshot.capabilities().contains(capability);
    }

    private static String sanitizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "unspecified";
        }
        var normalized = reason.replaceAll("[\\p{Cntrl}]", " ").strip();
        return normalized.substring(0, Math.min(96, normalized.length()));
    }

    public record Snapshot(
            Mode mode,
            UUID worldSessionId,
            Set<String> capabilities,
            long readyExpiresAtNanos,
            String lastLockReason) {
        public Snapshot {
            Objects.requireNonNull(mode, "mode");
        }

        public boolean locked() {
            return mode == Mode.OFF;
        }

        public boolean inputIsolationActive() {
            return mode == Mode.AGENT || mode == Mode.RECOVERING;
        }

        public long readyRemainingSeconds(long nowNanos) {
            if (mode != Mode.READY) {
                return 0L;
            }
            long remaining = readyExpiresAtNanos - nowNanos;
            if (remaining <= 0L) {
                return 0L;
            }
            return Math.max(1L, (remaining + TimeUnit.SECONDS.toNanos(1) - 1L)
                    / TimeUnit.SECONDS.toNanos(1));
        }
    }

    public enum Mode {
        OFF,
        READY,
        AGENT,
        RECOVERING
    }
}
