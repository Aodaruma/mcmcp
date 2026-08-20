package dev.aodaruma.craftagent.routine;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MovementInputLeaseTest {
    @Test
    void onlyOwnerCanDriveOrCloseLease() {
        var control = new FakeControl();
        var owner = UUID.randomUUID();
        var lease = MovementInputLease.acquire(control, owner, 0, Duration.ofSeconds(1));

        assertThatThrownBy(() -> lease.setDesired(UUID.randomUUID(), Set.of(
                MovementInputLease.MovementKey.FORWARD)))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> lease.close(UUID.randomUUID()))
                .isInstanceOf(SecurityException.class);
        assertThat(lease.active()).isTrue();
        assertThat(control.releases).isZero();

        lease.close(owner);
        assertThat(control.releases).isOne();
    }

    @Test
    void heartbeatAppliesOnlyDeclaredNonOpposedKeys() {
        var control = new FakeControl();
        var owner = UUID.randomUUID();
        var lease = MovementInputLease.acquire(control, owner, 100, Duration.ofNanos(50));
        lease.setDesired(owner, Set.of(
                MovementInputLease.MovementKey.FORWARD,
                MovementInputLease.MovementKey.LEFT,
                MovementInputLease.MovementKey.JUMP));

        assertThat(lease.heartbeat(owner, 120, Duration.ofNanos(50))).isTrue();
        assertThat(control.lastApplied).containsExactlyInAnyOrder(
                MovementInputLease.MovementKey.FORWARD,
                MovementInputLease.MovementKey.LEFT,
                MovementInputLease.MovementKey.JUMP);
        assertThatThrownBy(() -> lease.setDesired(owner, Set.of(
                MovementInputLease.MovementKey.LEFT,
                MovementInputLease.MovementKey.RIGHT)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyHeartbeatPausesAndLaterResumesTheSameLease() {
        var control = new FakeControl();
        var owner = UUID.randomUUID();
        var lease = MovementInputLease.acquire(control, owner, 0, Duration.ofSeconds(1));

        lease.setDesired(owner, Set.of(MovementInputLease.MovementKey.FORWARD));
        assertThat(lease.heartbeat(owner, 1, Duration.ofSeconds(1))).isTrue();
        assertThat(control.lastApplied).containsExactly(MovementInputLease.MovementKey.FORWARD);

        lease.setDesired(owner, Set.of());
        assertThat(lease.heartbeat(owner, 2, Duration.ofSeconds(1))).isTrue();
        assertThat(control.lastApplied).isEmpty();
        assertThat(lease.active()).isTrue();
        assertThat(control.releases).isZero();

        lease.setDesired(owner, Set.of(
                MovementInputLease.MovementKey.FORWARD,
                MovementInputLease.MovementKey.RIGHT));
        assertThat(lease.heartbeat(owner, 3, Duration.ofSeconds(1))).isTrue();
        assertThat(control.lastApplied).containsExactlyInAnyOrder(
                MovementInputLease.MovementKey.FORWARD,
                MovementInputLease.MovementKey.RIGHT);
        assertThat(control.releases).isZero();
    }

    @Test
    void expiryAndExceptionalApplyReleaseAllOwnedKeys() {
        var control = new FakeControl();
        var owner = UUID.randomUUID();
        var lease = MovementInputLease.acquire(control, owner, 100, Duration.ofNanos(20));
        lease.setDesired(owner, Set.of(MovementInputLease.MovementKey.FORWARD));

        assertThat(lease.maintain(owner, 120)).isFalse();
        assertThat(lease.active()).isFalse();
        assertThat(control.releases).isOne();

        var failing = new FakeControl();
        var second = MovementInputLease.acquire(failing, owner, 0, Duration.ofSeconds(1));
        failing.failApply = true;
        assertThatThrownBy(() -> second.heartbeat(owner, 1, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("apply failed");
        assertThat(failing.releases).isOne();
    }

    @Test
    void rejectsHorizonBeyondTwoSeconds() {
        assertThatThrownBy(() -> MovementInputLease.acquire(
                new FakeControl(), UUID.randomUUID(), 0, Duration.ofSeconds(3)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static final class FakeControl implements MovementInputLease.MovementControl {
        private Set<MovementInputLease.MovementKey> lastApplied = Set.of();
        private int releases;
        private boolean failApply;

        @Override
        public void apply(Set<MovementInputLease.MovementKey> keys) {
            if (failApply) {
                throw new IllegalStateException("apply failed");
            }
            lastApplied = Set.copyOf(keys);
        }

        @Override
        public void release() {
            releases++;
            lastApplied = Set.of();
        }
    }
}
