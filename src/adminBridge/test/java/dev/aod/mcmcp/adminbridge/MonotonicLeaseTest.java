package dev.aod.mcmcp.adminbridge;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MonotonicLeaseTest {
    @Test
    void expiresOnMonotonicTimeAndCanBeCleared() {
        AtomicLong now = new AtomicLong(10L);
        MonotonicLease lease = new MonotonicLease(now::get);
        lease.begin(Duration.ofSeconds(3));

        assertThat(lease.active()).isTrue();
        assertThat(lease.remainingSeconds()).isEqualTo(3L);
        now.addAndGet(Duration.ofMillis(2_001).toNanos());
        assertThat(lease.remainingSeconds()).isEqualTo(1L);
        assertThat(lease.expired()).isFalse();
        now.addAndGet(Duration.ofMillis(999).toNanos());
        assertThat(lease.expired()).isTrue();

        lease.clear();
        assertThat(lease.active()).isFalse();
    }

    @Test
    void refusesRenewalAndDurationsBeyondTheHardLimit() {
        MonotonicLease lease = new MonotonicLease(() -> 0L);
        lease.begin(Duration.ofMinutes(20));
        assertThatThrownBy(() -> lease.begin(Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class);

        MonotonicLease tooLong = new MonotonicLease(() -> 0L);
        assertThatThrownBy(() -> tooLong.begin(Duration.ofMinutes(31)))
                .isInstanceOf(IllegalStateException.class);
    }
}
