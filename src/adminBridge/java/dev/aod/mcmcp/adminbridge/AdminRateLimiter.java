package dev.aod.mcmcp.adminbridge;

import java.util.Objects;
import java.util.function.LongSupplier;

/** Small monotonic token bucket protecting the privileged loopback endpoint. */
final class AdminRateLimiter {
    private final int capacity;
    private final double permitsPerNano;
    private final LongSupplier clock;
    private double tokens;
    private long last;

    AdminRateLimiter(int capacity, double permitsPerSecond) {
        this(capacity, permitsPerSecond, System::nanoTime);
    }

    AdminRateLimiter(int capacity, double permitsPerSecond, LongSupplier clock) {
        if (capacity < 1 || !Double.isFinite(permitsPerSecond) || permitsPerSecond <= 0.0D) {
            throw new IllegalArgumentException("rate_limit_invalid");
        }
        this.capacity = capacity;
        this.permitsPerNano = permitsPerSecond / 1_000_000_000.0D;
        this.clock = Objects.requireNonNull(clock, "clock");
        tokens = capacity;
        last = clock.getAsLong();
    }

    synchronized boolean tryAcquire() {
        long now = clock.getAsLong();
        long elapsed = Math.max(0L, now - last);
        last = now;
        tokens = Math.min(capacity, tokens + elapsed * permitsPerNano);
        if (tokens < 1.0D) {
            return false;
        }
        tokens -= 1.0D;
        return true;
    }
}
