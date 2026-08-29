package dev.aod.mcmcp.safety;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/**
 * Minecraft-independent, world-session-scoped input guard for one evaluator turn.
 *
 * <p>The lease deadline is measured only with a monotonic clock. Every state-changing call
 * requires the complete opaque {@link Lease}; a delayed release from an older runner therefore
 * cannot release a newer turn. Normal release and revocation are idempotent for the most recently
 * terminated lease. External lease IDs are never reused during the process lifetime; after the
 * fixed retention cap, acquisition fails closed instead of evicting stale-release protection.</p>
 */
public final class EvaluationTurnGuard {
    private static final long MAX_DURATION_NANOS = Long.MAX_VALUE / 2L;
    private static final int MAX_PROCESS_LIFETIME_LEASE_IDS = 4_096;

    private final LongSupplier nanoTime;
    private final Set<UUID> usedLeaseIds = new HashSet<>();
    private ActiveLease active;
    private Terminal lastTerminal;
    private long epoch;

    public EvaluationTurnGuard() {
        this(System::nanoTime);
    }

    /** Public for deterministic tests and non-Minecraft runtime adapters. */
    public EvaluationTurnGuard(LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    /** Generates an opaque lease ID and acquires the guard if no lease is active. */
    public Optional<Lease> tryAcquire(
            UUID worldSessionId,
            RunnerIdentity runner,
            Duration maximumDuration) {
        return tryAcquire(
                worldSessionId, UUID.randomUUID(), runner, maximumDuration);
    }

    /**
     * Acquires a caller-correlated lease. A current lease is never replaced by this call, even
     * after its deadline: the runtime must first stop/release any owned inputs and then explicitly
     * revoke that lease.
     */
    public Optional<Lease> tryAcquire(
            UUID worldSessionId,
            UUID leaseId,
            RunnerIdentity runner,
            Duration maximumDuration) {
        Objects.requireNonNull(worldSessionId, "worldSessionId");
        Objects.requireNonNull(leaseId, "leaseId");
        Objects.requireNonNull(runner, "runner");
        long durationNanos = validatedDuration(maximumDuration);
        long now = nanoTime.getAsLong();
        Optional<Lease> acquired;
        synchronized (this) {
            if (active != null
                    || usedLeaseIds.contains(leaseId)
                    || usedLeaseIds.size() >= MAX_PROCESS_LIFETIME_LEASE_IDS) {
                acquired = Optional.empty();
            } else {
                var lease = new Lease(worldSessionId, leaseId, runner);
                active = new ActiveLease(
                        lease, now, durationNanos, new CompletableFuture<>());
                usedLeaseIds.add(leaseId);
                epoch++;
                acquired = Optional.of(lease);
            }
        }
        return acquired;
    }

    /**
     * Refreshes the monotonic deadline for the exact active lease. Expired, stale, and different
     * leases are rejected and can never be resurrected.
     */
    public boolean renew(Lease lease, Duration maximumDuration) {
        Objects.requireNonNull(lease, "lease");
        long durationNanos = validatedDuration(maximumDuration);
        long now = nanoTime.getAsLong();
        boolean renewed;
        synchronized (this) {
            if (active == null
                    || !active.lease.equals(lease)
                    || elapsed(now, active.startedNanos) >= active.durationNanos) {
                renewed = false;
            } else {
                active.startedNanos = now;
                active.durationNanos = durationNanos;
                epoch++;
                renewed = true;
            }
        }
        return renewed;
    }

    /** Completes the exact lease normally. Repeating the same release is harmless. */
    public boolean release(Lease lease) {
        return release(lease, "released");
    }

    /** Completes the exact lease normally with a bounded local reason. */
    public boolean release(Lease lease, String reason) {
        return terminateExact(lease, Outcome.RELEASED, reason);
    }

    /** Revokes the exact lease. Repeating the same revocation is harmless. */
    public boolean revoke(Lease lease, String reason) {
        return terminateExact(lease, Outcome.REVOKED, reason);
    }

    /**
     * Returns a future completed by an explicit release or revocation after input cleanup.
     * A stale/different lease receives an already-failed future and cannot observe another lease.
     */
    public CompletableFuture<Terminal> awaitTerminal(Lease lease) {
        Objects.requireNonNull(lease, "lease");
        CompletableFuture<Terminal> result;
        synchronized (this) {
            if (active != null && active.lease.equals(lease)) {
                result = active.terminal.copy();
            } else if (lastTerminal != null && lastTerminal.lease().equals(lease)) {
                result = CompletableFuture.completedFuture(lastTerminal);
            } else {
                result = CompletableFuture.failedFuture(new IllegalArgumentException(
                        "evaluation turn lease is stale or belongs to another runner"));
            }
        }
        return result;
    }

    /**
     * Returns the current active state. An invalid lease intentionally remains active in this
     * view until the runtime has stopped gameplay work, released inputs, and explicitly revoked
     * it. This prevents a deadline check from unlocking physical input too early.
     */
    public Snapshot snapshot(UUID currentWorldSessionId) {
        long now = nanoTime.getAsLong();
        Snapshot snapshot;
        synchronized (this) {
            snapshot = active == null
                    ? new Snapshot(null, 0L, epoch, lastTerminal, null)
                    : new Snapshot(
                            active.lease,
                            active.startedNanos + active.durationNanos,
                            epoch,
                            lastTerminal,
                            invalidation(currentWorldSessionId, now));
        }
        return snapshot;
    }

    /**
     * Reports an invalid lease without terminating it. The caller must run its priority stop and
     * input-release path before calling {@link #revoke(Lease, String)} with the reported lease.
     */
    public Optional<Invalidation> leaseNeedingRevocation(UUID currentWorldSessionId) {
        return snapshot(currentWorldSessionId).pendingInvalidation();
    }

    private boolean terminateExact(Lease lease, Outcome outcome, String reason) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(outcome, "outcome");
        long now = nanoTime.getAsLong();
        Completion terminal = null;
        boolean accepted;
        synchronized (this) {
            if (active != null && active.lease.equals(lease)) {
                terminal = terminateActive(outcome, reason, now);
                accepted = true;
            } else {
                accepted = active == null
                        && lastTerminal != null
                        && lastTerminal.lease().equals(lease);
            }
        }
        complete(terminal);
        return accepted;
    }

    private Invalidation invalidation(UUID currentWorldSessionId, long now) {
        if (active == null) {
            return null;
        }
        if (!active.lease.worldSessionId().equals(currentWorldSessionId)) {
            return new Invalidation(active.lease, InvalidationReason.WORLD_SESSION_CHANGED);
        }
        if (elapsed(now, active.startedNanos) >= active.durationNanos) {
            return new Invalidation(active.lease, InvalidationReason.LEASE_EXPIRED);
        }
        return null;
    }

    /** Caller must hold this object's monitor. */
    private Completion terminateActive(Outcome outcome, String reason, long now) {
        var terminated = active;
        var terminal = new Terminal(
                terminated.lease, outcome, sanitizeReason(reason), now);
        active = null;
        lastTerminal = terminal;
        epoch++;
        return new Completion(terminated.terminal, terminal);
    }

    private static void complete(Completion completion) {
        if (completion != null) {
            completion.future.complete(completion.terminal);
        }
    }

    private static long validatedDuration(Duration duration) {
        Objects.requireNonNull(duration, "maximumDuration");
        long nanos;
        try {
            nanos = duration.toNanos();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(
                    "evaluation turn duration is out of range", failure);
        }
        if (nanos <= 0L || nanos > MAX_DURATION_NANOS) {
            throw new IllegalArgumentException(
                    "evaluation turn duration must be positive and monotonic-safe");
        }
        return nanos;
    }

    private static long elapsed(long now, long start) {
        // System.nanoTime is meaningful only by subtraction; natural wrap is intentional.
        return now - start;
    }

    private static String sanitizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "unspecified";
        }
        var normalized = reason.replaceAll("[\\p{Cntrl}]", " ").strip();
        return normalized.substring(0, Math.min(96, normalized.length()));
    }

    /** Complete opaque token required for every state-changing operation. */
    public record Lease(
            UUID worldSessionId,
            UUID leaseId,
            RunnerIdentity runner) {
        public Lease {
            Objects.requireNonNull(worldSessionId, "worldSessionId");
            Objects.requireNonNull(leaseId, "leaseId");
            Objects.requireNonNull(runner, "runner");
        }
    }

    /** PID plus optional start instant prevents accidental PID-reuse matches when available. */
    public record RunnerIdentity(
            long processId,
            Optional<Instant> startInstant) {
        public RunnerIdentity {
            if (processId <= 0L) {
                throw new IllegalArgumentException("processId must be positive");
            }
            startInstant = Objects.requireNonNull(startInstant, "startInstant");
        }

        public RunnerIdentity(long processId) {
            this(processId, Optional.empty());
        }

        public static RunnerIdentity capture(ProcessHandle process) {
            Objects.requireNonNull(process, "process");
            return new RunnerIdentity(process.pid(), process.info().startInstant());
        }

        public boolean matches(ProcessHandle process) {
            Objects.requireNonNull(process, "process");
            if (process.pid() != processId) {
                return false;
            }
            return startInstant.isEmpty()
                    || process.info().startInstant().equals(startInstant);
        }
    }

    /** Immutable read-only state. Active-only fields are null/zero when no lease is held. */
    public record Snapshot(
            Lease lease,
            long expiresAtNanos,
            long epoch,
            Terminal lastTerminal,
            Invalidation invalidation) {
        public Snapshot {
            if (lease == null && expiresAtNanos != 0L) {
                throw new IllegalArgumentException(
                        "an inactive snapshot cannot have an expiry");
            }
            if (invalidation != null
                    && (lease == null || !invalidation.lease().equals(lease))) {
                throw new IllegalArgumentException(
                        "pending invalidation must belong to the active lease");
            }
        }

        public boolean active() {
            return lease != null;
        }

        public Optional<Lease> activeLease() {
            return Optional.ofNullable(lease);
        }

        public Optional<Terminal> previousTerminal() {
            return Optional.ofNullable(lastTerminal);
        }

        public Optional<Invalidation> pendingInvalidation() {
            return Optional.ofNullable(invalidation);
        }
    }

    public record Invalidation(
            Lease lease,
            InvalidationReason reason) {
        public Invalidation {
            Objects.requireNonNull(lease, "lease");
            Objects.requireNonNull(reason, "reason");
        }
    }

    public record Terminal(
            Lease lease,
            Outcome outcome,
            String reason,
            long terminalNanos) {
        public Terminal {
            Objects.requireNonNull(lease, "lease");
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(reason, "reason");
        }
    }

    public enum Outcome {
        RELEASED,
        REVOKED
    }

    public enum InvalidationReason {
        LEASE_EXPIRED,
        WORLD_SESSION_CHANGED
    }

    private static final class ActiveLease {
        private final Lease lease;
        private long startedNanos;
        private long durationNanos;
        private final CompletableFuture<Terminal> terminal;

        private ActiveLease(
                Lease lease,
                long startedNanos,
                long durationNanos,
                CompletableFuture<Terminal> terminal) {
            this.lease = lease;
            this.startedNanos = startedNanos;
            this.durationNanos = durationNanos;
            this.terminal = terminal;
        }
    }

    private record Completion(
            CompletableFuture<Terminal> future,
            Terminal terminal) { }
}
