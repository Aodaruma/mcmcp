package dev.aod.mcmcp.adminbridge;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Pure monotonic deadline used by the reversible random-tick lease. */
public final class MonotonicLease {
    private final LongSupplier nanos;
    private Long deadline;

    public MonotonicLease() {
        this(System::nanoTime);
    }

    MonotonicLease(LongSupplier nanos) {
        this.nanos = Objects.requireNonNull(nanos, "nanos");
    }

    public void begin(Duration duration) {
        if (active() || duration.isZero() || duration.isNegative()
                || duration.compareTo(Duration.ofMinutes(30)) > 0) {
            throw new IllegalStateException("lease_begin_invalid");
        }
        deadline = Math.addExact(nanos.getAsLong(), duration.toNanos());
    }

    public boolean expired() {
        return active() && nanos.getAsLong() - deadline >= 0L;
    }

    public long remainingSeconds() {
        if (!active()) {
            return 0L;
        }
        long remaining = deadline - nanos.getAsLong();
        return remaining <= 0L ? 0L : Math.ceilDiv(remaining, 1_000_000_000L);
    }

    public boolean active() {
        return deadline != null;
    }

    public void clear() {
        deadline = null;
    }
}
