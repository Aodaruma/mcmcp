package dev.aodaruma.craftagent.safety;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Local-only, session-scoped arming state. MCP can observe but never relax it. */
public final class LocalArmingState {
    public static final Duration DEFAULT_ARM_DURATION = Duration.ofMinutes(15);

    private UUID worldSessionId;
    private long expiresAtNanos;
    private Set<String> capabilities = Set.of();
    private String lastLockReason = "startup";

    public synchronized void arm(UUID sessionId, Set<String> capabilityProfile, Duration duration, long nowNanos) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(capabilityProfile, "capabilityProfile");
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("arming duration must be positive");
        }
        worldSessionId = sessionId;
        capabilities = Set.copyOf(new LinkedHashSet<>(capabilityProfile));
        expiresAtNanos = Math.addExact(nowNanos, duration.toNanos());
        lastLockReason = null;
    }

    public synchronized void lock(String reason) {
        worldSessionId = null;
        expiresAtNanos = 0;
        capabilities = Set.of();
        lastLockReason = sanitizeReason(reason);
    }

    public synchronized Snapshot snapshot(UUID currentSessionId, long nowNanos) {
        if (worldSessionId != null && (nowNanos >= expiresAtNanos || !worldSessionId.equals(currentSessionId))) {
            lock(nowNanos >= expiresAtNanos ? "expired" : "world_session_changed");
        }
        var unlocked = worldSessionId != null;
        var remaining = unlocked ? Math.max(0L, expiresAtNanos - nowNanos) : 0L;
        return new Snapshot(!unlocked, worldSessionId, remaining, capabilities, lastLockReason);
    }

    public synchronized boolean allows(UUID currentSessionId, String capability, long hardDeadlineNanos, long nowNanos) {
        var snapshot = snapshot(currentSessionId, nowNanos);
        return !snapshot.locked()
                && snapshot.capabilities().contains(capability)
                && hardDeadlineNanos <= expiresAtNanos;
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
            long remainingNanos,
            Set<String> capabilities,
            String lastLockReason) {
    }
}
