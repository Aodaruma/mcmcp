package dev.aodaruma.craftagent.routine;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttackInputLeaseTest {
    @Test
    void rejectsUnboundedOrEmptyHorizons() {
        var control = new FakeControl();

        assertThatThrownBy(() -> AttackInputLease.acquire(control, 0, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AttackInputLease.acquire(control, 0, Duration.ofSeconds(3)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(control.presses).isZero();
    }

    @Test
    void expiryReleasesAndCannotBeResurrected() {
        var control = new FakeControl();
        var lease = AttackInputLease.acquire(control, 100, Duration.ofNanos(20));

        assertThat(lease.maintain(119)).isTrue();
        assertThat(lease.maintain(120)).isFalse();
        assertThat(lease.active()).isFalse();
        assertThat(control.presses).isEqualTo(2);
        assertThat(control.releases).isOne();
        assertThatThrownBy(() -> lease.renew(121, Duration.ofNanos(20)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void renewalKeepsOnlyATwoSecondFutureHorizon() {
        var control = new FakeControl();
        var lease = AttackInputLease.acquire(control, 100, Duration.ofSeconds(1));

        assertThat(lease.renew(200, AttackInputLease.MAX_HORIZON)).isTrue();
        assertThat(lease.deadlineNanos()).isEqualTo(200 + Duration.ofSeconds(2).toNanos());

        lease.close();
        assertThat(control.releases).isOne();
    }

    @Test
    void heartbeatRenewsAndReassertsTheKey() {
        var control = new FakeControl();
        var lease = AttackInputLease.acquire(control, 100, Duration.ofNanos(50));

        assertThat(lease.heartbeat(120, Duration.ofNanos(50))).isTrue();
        assertThat(lease.deadlineNanos()).isEqualTo(170);
        assertThat(control.presses).isEqualTo(2);

        lease.close();
    }

    @Test
    void tryWithResourcesReleasesOnExceptionalExit() {
        var control = new FakeControl();

        assertThatThrownBy(() -> {
            try (var ignored = AttackInputLease.acquire(control, 0, Duration.ofSeconds(1))) {
                throw new IllegalStateException("routine failed");
            }
        }).isInstanceOf(IllegalStateException.class).hasMessage("routine failed");

        assertThat(control.releases).isOne();
    }

    @Test
    void pressFailureAttemptsReleaseAndKeepsReleaseFailureAsSuppressed() {
        var control = new AttackInputLease.AttackControl() {
            @Override
            public void press() {
                throw new IllegalStateException("press failed");
            }

            @Override
            public void release() {
                throw new IllegalArgumentException("release failed");
            }
        };

        assertThatThrownBy(() -> AttackInputLease.acquire(control, 0, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(failure -> assertThat(failure.getSuppressed())
                        .singleElement()
                        .isInstanceOf(IllegalArgumentException.class));
    }

    @Test
    void maintenanceFailureClosesTheLease() {
        var control = new FakeControl();
        var lease = AttackInputLease.acquire(control, 0, Duration.ofSeconds(1));
        control.failPress = true;

        assertThatThrownBy(() -> lease.maintain(1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("press failed");
        assertThat(lease.active()).isFalse();
        assertThat(control.releases).isOne();
    }

    @Test
    void failedReleaseCanBeRetriedIdempotently() {
        var control = new FakeControl();
        var lease = AttackInputLease.acquire(control, 0, Duration.ofSeconds(1));
        control.failRelease = true;

        assertThatThrownBy(lease::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("release failed");

        control.failRelease = false;
        lease.close();
        lease.close();
        assertThat(control.releases).isEqualTo(2);
    }

    private static final class FakeControl implements AttackInputLease.AttackControl {
        private int presses;
        private int releases;
        private boolean failPress;
        private boolean failRelease;

        @Override
        public void press() {
            if (failPress) {
                throw new IllegalStateException("press failed");
            }
            presses++;
        }

        @Override
        public void release() {
            releases++;
            if (failRelease) {
                throw new IllegalStateException("release failed");
            }
        }
    }
}
