package dev.aod.mcmcp.mcp;

import java.util.Objects;
import java.util.function.LongSupplier;

/** Small synchronized token bucket; one instance protects the single loopback endpoint. */
final class TokenBucketRateLimiter {
    private final double capacity;
    private final double tokensPerNano;
    private final LongSupplier nanoTime;
    private double tokens;
    private long lastRefillNanos;

    TokenBucketRateLimiter(int burst, double permitsPerSecond) {
        this(burst, permitsPerSecond, System::nanoTime);
    }

    TokenBucketRateLimiter(int burst, double permitsPerSecond, LongSupplier nanoTime) {
        if (burst < 1 || !Double.isFinite(permitsPerSecond) || permitsPerSecond <= 0) {
            throw new IllegalArgumentException("Invalid token bucket configuration");
        }
        capacity = burst;
        tokensPerNano = permitsPerSecond / 1_000_000_000.0;
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        tokens = burst;
        lastRefillNanos = nanoTime.getAsLong();
    }

    synchronized boolean tryAcquire() {
        long now = nanoTime.getAsLong();
        long elapsed = Math.max(0L, now - lastRefillNanos);
        tokens = Math.min(capacity, tokens + elapsed * tokensPerNano);
        lastRefillNanos = now;
        if (tokens < 1.0) {
            return false;
        }
        tokens -= 1.0;
        return true;
    }
}
