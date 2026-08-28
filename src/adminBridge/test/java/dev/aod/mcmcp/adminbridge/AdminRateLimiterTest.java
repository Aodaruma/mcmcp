package dev.aod.mcmcp.adminbridge;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class AdminRateLimiterTest {
    @Test
    void enforcesBurstAndRefillsFromMonotonicTime() {
        AtomicLong now = new AtomicLong();
        AdminRateLimiter limiter = new AdminRateLimiter(2, 2.0D, now::get);
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();

        now.addAndGet(Duration.ofMillis(500).toNanos());
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();
    }
}
