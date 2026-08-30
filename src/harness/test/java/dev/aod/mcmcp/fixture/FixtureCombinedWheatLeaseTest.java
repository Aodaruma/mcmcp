package dev.aod.mcmcp.fixture;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class FixtureCombinedWheatLeaseTest {
    @Test
    void coversTheFreshEvaluatorAndExpiresAtExactlyTwentyMinutes() {
        var now = new AtomicLong(1_000L);
        var lease = new FixtureCombinedWheatLease(now::get);

        lease.begin();

        assertThat(FixtureCombinedWheatLease.MAX_DURATION).isEqualTo(Duration.ofMinutes(20));
        assertThat(lease.remainingMillis()).isEqualTo(Duration.ofMinutes(20).toMillis());
        now.set(1_000L + Duration.ofMinutes(20).toNanos() - 1_000_000L);
        assertThat(lease.expired()).isFalse();
        assertThat(lease.remainingMillis()).isEqualTo(1L);
        now.set(1_000L + Duration.ofMinutes(20).toNanos());
        assertThat(lease.expired()).isTrue();
        assertThat(lease.remainingMillis()).isZero();

        lease.clear();
        assertThat(lease.active()).isFalse();

        var active = new FixtureCombinedWheatLease(() -> 0L);
        active.begin();
        assertThatIllegalStateException().isThrownBy(active::begin);
    }
}
