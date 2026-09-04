package dev.aod.mcmcp.routine;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedInputLeaseTest {
    @Test
    void rejectsEmptyInputsAndLongWatchdogHorizons() {
        var control = new FakeControl();

        assertThatThrownBy(() -> BoundedInputLease.acquire(
                control, Set.of(), 0L, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BoundedInputLease.acquire(
                control, Set.of(BoundedInputLease.Input.USE), 0L, Duration.ofSeconds(3)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(control.publications).isZero();
    }

    @Test
    void heartbeatRenewsImmutableInputSetAndExpiryReleasesEverything() {
        var control = new FakeControl();
        var inputs = Set.of(
                BoundedInputLease.Input.FORWARD,
                BoundedInputLease.Input.SNEAK,
                BoundedInputLease.Input.USE);
        var lease = BoundedInputLease.acquire(control, inputs, 100L, Duration.ofNanos(50));

        assertThat(control.lastInputs).containsExactlyInAnyOrderElementsOf(inputs);
        assertThat(control.lastDeadline).isEqualTo(150L);
        assertThat(lease.heartbeat(120L, Duration.ofNanos(50))).isTrue();
        assertThat(control.lastDeadline).isEqualTo(170L);
        assertThat(control.publications).isEqualTo(2);

        assertThat(lease.heartbeat(170L, Duration.ofNanos(50))).isFalse();
        assertThat(control.releases).isOne();
        assertThat(control.lastInputs).containsExactlyInAnyOrderElementsOf(inputs);
        assertThatThrownBy(() -> lease.heartbeat(171L, Duration.ofNanos(50)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void publicationFailureClosesAndReleasesTheOwnedSet() {
        var control = new FakeControl();
        var lease = BoundedInputLease.acquire(
                control, Set.of(BoundedInputLease.Input.ATTACK),
                0L, Duration.ofSeconds(1));
        control.failPublish = true;

        assertThatThrownBy(() -> lease.heartbeat(1L, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("publish failed");
        assertThat(lease.active()).isFalse();
        assertThat(control.releases).isOne();
    }

    @Test
    void failedReleaseCanBeRetriedIdempotently() {
        var control = new FakeControl();
        var lease = BoundedInputLease.acquire(
                control, Set.of(BoundedInputLease.Input.USE),
                0L, Duration.ofSeconds(1));
        control.failRelease = true;

        assertThatThrownBy(lease::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("release failed");

        control.failRelease = false;
        lease.close();
        lease.close();
        assertThat(control.releases).isEqualTo(2);
    }

    private static final class FakeControl implements BoundedInputLease.Control {
        private int publications;
        private int releases;
        private boolean failPublish;
        private boolean failRelease;
        private Set<BoundedInputLease.Input> lastInputs = Set.of();
        private long lastDeadline;

        @Override
        public void publish(Set<BoundedInputLease.Input> inputs, long validUntilNanos) {
            if (failPublish) throw new IllegalStateException("publish failed");
            publications++;
            lastInputs = Set.copyOf(inputs);
            lastDeadline = validUntilNanos;
        }

        @Override
        public void release(Set<BoundedInputLease.Input> inputs) {
            releases++;
            lastInputs = Set.copyOf(inputs);
            if (failRelease) throw new IllegalStateException("release failed");
        }
    }
}
