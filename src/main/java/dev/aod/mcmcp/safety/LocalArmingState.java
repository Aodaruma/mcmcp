package dev.aod.mcmcp.safety;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Local-only, session-scoped arming state with no time-based expiry. */
public final class LocalArmingState {
    private UUID worldSessionId;
    private Set<String> capabilities = Set.of();
    private String lastLockReason = "startup";

    public synchronized void arm(UUID sessionId, Set<String> capabilityProfile) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(capabilityProfile, "capabilityProfile");
        worldSessionId = sessionId;
        capabilities = Set.copyOf(new LinkedHashSet<>(capabilityProfile));
        lastLockReason = null;
    }

    public synchronized void lock(String reason) {
        worldSessionId = null;
        capabilities = Set.of();
        lastLockReason = sanitizeReason(reason);
    }

    public synchronized Snapshot snapshot(UUID currentSessionId) {
        if (worldSessionId != null && !worldSessionId.equals(currentSessionId)) {
            lock("world_session_changed");
        }
        var unlocked = worldSessionId != null;
        return new Snapshot(!unlocked, worldSessionId, capabilities, lastLockReason);
    }

    public synchronized boolean allows(UUID currentSessionId, String capability) {
        var snapshot = snapshot(currentSessionId);
        return !snapshot.locked()
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
            boolean locked,
            UUID worldSessionId,
            Set<String> capabilities,
            String lastLockReason) {
    }
}
