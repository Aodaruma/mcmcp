package dev.aod.mcmcp.fixture;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Non-renewable monotonic elapsed-time deadline for one combined wheat fixture run. */
final class FixtureCombinedWheatLease {
    static final Duration MAX_DURATION = Duration.ofMinutes(15);

    private final LongSupplier monotonicNanos;
    private Long expiresAtNanos;

    FixtureCombinedWheatLease() {
        this(System::nanoTime);
    }

    FixtureCombinedWheatLease(LongSupplier monotonicNanos) {
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonicNanos");
    }

    void begin() {
        if (active()) {
            throw new IllegalStateException("combined wheat wall-clock lease is already active");
        }
        expiresAtNanos = monotonicNanos.getAsLong() + MAX_DURATION.toNanos();
    }

    boolean expired() {
        return active() && monotonicNanos.getAsLong() - expiresAtNanos >= 0L;
    }

    long remainingMillis() {
        if (!active()) {
            return 0L;
        }
        long remainingNanos = expiresAtNanos - monotonicNanos.getAsLong();
        return remainingNanos <= 0L
                ? 0L
                : Duration.ofNanos(remainingNanos).toMillis();
    }

    boolean active() {
        return expiresAtNanos != null;
    }

    void clear() {
        expiresAtNanos = null;
    }
}
