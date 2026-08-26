package dev.aod.mcmcp.mcp;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBucketRateLimiterTest {
    @Test
    void refillsAtConfiguredRateWithoutGoingAboveBurst() {
        AtomicLong clock = new AtomicLong(10);
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(2, 2.0, clock::get);

        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();

        clock.addAndGet(500_000_000L);
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();

        clock.addAndGet(10_000_000_000L);
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();
    }

    @Test
    void aBackwardsClockDoesNotMintTokens() {
        AtomicLong clock = new AtomicLong(1_000);
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1.0, clock::get);

        assertThat(limiter.tryAcquire()).isTrue();
        clock.set(0);
        assertThat(limiter.tryAcquire()).isFalse();
    }
}
