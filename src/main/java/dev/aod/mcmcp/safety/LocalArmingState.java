package dev.aod.mcmcp.safety;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Local-only, session-scoped control lease. */
public final class LocalArmingState {
    private Mode mode = Mode.OFF;
    private UUID worldSessionId;
    private Set<String> capabilities = Set.of();
    private long controlEpoch;
    private String lastLockReason = "startup";

    public synchronized void arm(UUID sessionId, Set<String> capabilityProfile) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(capabilityProfile, "capabilityProfile");
        worldSessionId = sessionId;
        capabilities = Set.copyOf(new LinkedHashSet<>(capabilityProfile));
        mode = Mode.READY;
        lastLockReason = null;
        controlEpoch++;
    }

    /** Temporarily moves a persistent READY lease into active execution. */
    public synchronized boolean beginAction(UUID sessionId) {
        var current = snapshot(sessionId);
        if (current.mode() != Mode.READY) {
            return false;
        }
        mode = Mode.AGENT;
        controlEpoch++;
        return true;
    }

    public synchronized boolean beginRecovery(UUID sessionId) {
        var current = snapshot(sessionId);
        if (current.mode() != Mode.AGENT) {
            return false;
        }
        mode = Mode.RECOVERING;
        controlEpoch++;
        return true;
    }

    /** Returns a completed action to READY without extending it across a world boundary. */
    public synchronized boolean completeAction(UUID sessionId) {
        var current = snapshot(sessionId);
        if (current.mode() != Mode.AGENT && current.mode() != Mode.RECOVERING) {
            return false;
        }
        mode = Mode.READY;
        controlEpoch++;
        return true;
    }

    public synchronized void lock(String reason) {
        mode = Mode.OFF;
        worldSessionId = null;
        capabilities = Set.of();
        lastLockReason = sanitizeReason(reason);
        controlEpoch++;
    }

    public synchronized Snapshot snapshot(UUID currentSessionId) {
        if (worldSessionId != null && !worldSessionId.equals(currentSessionId)) {
            lock("world_session_changed");
        }
        return new Snapshot(
                mode,
                worldSessionId,
                capabilities,
                lastLockReason,
                controlEpoch);
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
            String lastLockReason,
            long controlEpoch) {
        public Snapshot {
            Objects.requireNonNull(mode, "mode");
        }

        public boolean locked() {
            return mode == Mode.OFF;
        }

        public boolean inputIsolationActive() {
            return mode == Mode.AGENT || mode == Mode.RECOVERING;
        }
    }

    public enum Mode {
        OFF,
        READY,
        AGENT,
        RECOVERING
    }
}
