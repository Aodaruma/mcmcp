package dev.aod.mcmcp.mcp;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Per-call generation, deadline and cancellation signal shared with the runtime queue. */
public final class RuntimeCallContext {
    private final String requestId;
    private final long deadlineNanos;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    private RuntimeCallContext(String requestId, long deadlineNanos) {
        this.requestId = requestId;
        this.deadlineNanos = deadlineNanos;
    }

    public static RuntimeCallContext withTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        long now = System.nanoTime();
        long timeoutNanos;
        try {
            timeoutNanos = timeout.toNanos();
        }
        catch (ArithmeticException ignored) {
            timeoutNanos = Long.MAX_VALUE;
        }
        long deadline = deadlineAfter(now, timeoutNanos);
        return new RuntimeCallContext(UUID.randomUUID().toString(), deadline);
    }

    public String requestId() {
        return requestId;
    }

    public long deadlineNanos() {
        return deadlineNanos;
    }

    public long remainingNanos() {
        return remainingNanos(deadlineNanos, System.nanoTime());
    }

    public boolean isExpired() {
        return remainingNanos() == 0L;
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /** The runtime must check this immediately before queued work or a side effect. */
    public boolean canBeginWork() {
        return !isCancelled() && !isExpired();
    }

    public void cancel() {
        cancelled.set(true);
    }

    public static long deadlineAfter(long nowNanos, long timeoutNanos) {
        if (timeoutNanos <= 0L) {
            throw new IllegalArgumentException("timeoutNanos must be positive");
        }
        return nowNanos + timeoutNanos;
    }

    public static boolean deadlineReached(long deadlineNanos, long nowNanos) {
        return nowNanos - deadlineNanos >= 0L;
    }

    static long remainingNanos(long deadlineNanos, long nowNanos) {
        long remaining = deadlineNanos - nowNanos;
        return remaining > 0L ? remaining : 0L;
    }
}
