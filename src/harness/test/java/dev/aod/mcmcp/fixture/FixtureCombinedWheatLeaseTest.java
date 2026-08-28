package dev.aod.mcmcp.fixture;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class FixtureCombinedWheatLeaseTest {
    @Test
    void expiresAtExactlyFifteenMinutesOfMonotonicElapsedTime() {
        var now = new AtomicLong(1_000L);
        var lease = new FixtureCombinedWheatLease(now::get);

        lease.begin();

        assertThat(FixtureCombinedWheatLease.MAX_DURATION).isEqualTo(Duration.ofMinutes(15));
        assertThat(lease.remainingMillis()).isEqualTo(Duration.ofMinutes(15).toMillis());
        now.set(1_000L + Duration.ofMinutes(15).toNanos() - 1_000_000L);
        assertThat(lease.expired()).isFalse();
        assertThat(lease.remainingMillis()).isEqualTo(1L);
        now.set(1_000L + Duration.ofMinutes(15).toNanos());
        assertThat(lease.expired()).isTrue();
        assertThat(lease.remainingMillis()).isZero();

        lease.clear();
        assertThat(lease.active()).isFalse();
    }

    @Test
    void cannotSilentlyRenewAnActiveLease() {
        var lease = new FixtureCombinedWheatLease(() -> 0L);
        lease.begin();

        assertThatIllegalStateException().isThrownBy(lease::begin);
    }
}
