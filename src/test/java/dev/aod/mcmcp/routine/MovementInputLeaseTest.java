package dev.aod.mcmcp.routine;

import dev.aod.mcmcp.client.AgentInputState;
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
    void climbableVerticalInputCanChangeDirectionAndStillReleaseTheLease() {
        var control = new FakeControl();
        var owner = UUID.randomUUID();
        var lease = MovementInputLease.acquire(control, owner, 0, Duration.ofSeconds(1));

        lease.setDesired(owner, Set.of(MovementInputLease.MovementKey.JUMP));
        assertThat(lease.heartbeat(owner, 1, Duration.ofSeconds(1))).isTrue();
        assertThat(control.lastApplied).containsExactly(MovementInputLease.MovementKey.JUMP);

        lease.setDesired(owner, Set.of(MovementInputLease.MovementKey.CROUCH));
        assertThat(lease.heartbeat(owner, 2, Duration.ofSeconds(1))).isTrue();
        assertThat(control.lastApplied).containsExactly(MovementInputLease.MovementKey.CROUCH);
        assertThat(lease.active()).isTrue();
        assertThat(control.releases).isZero();

        lease.close(owner);
        assertThat(control.lastApplied).isEmpty();
        assertThat(control.releases).isOne();
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

    @Test
    void pauseClockPreventsWatchdogExpiryUntilSimulationResumes() {
        var state = new AgentInputState();
        var control = new FakeControl() {
            @Override
            public long watchdogTime(long nowNanos) {
                return state.watchdogTime(nowNanos);
            }
        };
        var owner = UUID.randomUUID();
        var lease = MovementInputLease.acquire(control, owner, 100, Duration.ofNanos(50));
        lease.setDesired(owner, Set.of(MovementInputLease.MovementKey.FORWARD));

        state.setPaused(true, 120);
        assertThat(lease.maintain(owner, 10_000)).isTrue();

        state.setPaused(false, 10_100);
        assertThat(lease.heartbeat(owner, 10_110, Duration.ofNanos(50))).isTrue();
        assertThat(lease.deadlineNanos()).isEqualTo(180);
    }

    private static class FakeControl implements MovementInputLease.MovementControl {
        private Set<MovementInputLease.MovementKey> lastApplied = Set.of();
        private int releases;
        private boolean failApply;
        private boolean failReleaseOnce;
        private int applies;

        @Override
        public void apply(Set<MovementInputLease.MovementKey> keys) {
            applies++;
            if (failApply) {
                throw new IllegalStateException("apply failed");
            }
            lastApplied = Set.copyOf(keys);
        }

        @Override
        public void release() {
            releases++;
            if (failReleaseOnce) {
                failReleaseOnce = false;
                throw new IllegalStateException("release pending");
            }
            lastApplied = Set.of();
        }
    }

    @Test
    void validationNeverRenewsOrPublishesAndClosesAtTheOriginalDeadline() {
        var control = new FakeControl();
        var owner = UUID.randomUUID();
        var lease = MovementInputLease.acquire(control, owner, 100, Duration.ofNanos(50));
        lease.setDesired(owner, Set.of(MovementInputLease.MovementKey.CROUCH));
        assertThat(lease.heartbeat(owner, 100, Duration.ofNanos(50))).isTrue();
        assertThat(lease.validate(owner, 149)).isTrue();
        assertThat(lease.deadlineNanos()).isEqualTo(150);
        assertThat(control.applies).isEqualTo(2);
        assertThat(control.lastApplied).containsExactly(MovementInputLease.MovementKey.CROUCH);
        assertThat(lease.validate(owner, 150)).isFalse();
        assertThat(control.applies).isEqualTo(2);
        assertThat(control.lastApplied).isEmpty();
        assertThat(lease.active()).isFalse();
        assertThat(lease.validate(owner, 151)).isFalse();
        lease.close();
        assertThat(control.releases).isOne();
    }

    @Test
    void validationUsesThePauseAdjustedClockAndEnforcesOwnership() {
        var state = new AgentInputState();
        var control = new FakeControl() {
            @Override public long watchdogTime(long now) { return state.watchdogTime(now); }
        };
        var owner = UUID.randomUUID();
        var lease = MovementInputLease.acquire(control, owner, 100, Duration.ofNanos(50));
        assertThatThrownBy(() -> lease.validate(UUID.randomUUID(), 200)).isInstanceOf(SecurityException.class);
        state.setPaused(true, 120);
        assertThat(lease.validate(owner, 10_000)).isTrue();
        state.setPaused(false, 10_100);
        assertThat(lease.validate(owner, 10_129)).isTrue();
        assertThat(lease.validate(owner, 10_130)).isFalse();
    }

    @Test
    void validationHandlesNanoTimeWrapAndAllowsFailedReleaseCleanupRetry() {
        var control = new FakeControl();
        var owner = UUID.randomUUID();
        long start = Long.MAX_VALUE - 20;
        var lease = MovementInputLease.acquire(control, owner, start, Duration.ofNanos(50));
        assertThat(lease.validate(owner, start + 49)).isTrue();
        control.failReleaseOnce = true;
        assertThatThrownBy(() -> lease.validate(owner, start + 50)).isInstanceOf(IllegalStateException.class);
        assertThat(lease.active()).isFalse();
        assertThat(control.applies).isOne();
        lease.close();
        assertThat(control.releases).isEqualTo(2);
        assertThat(control.lastApplied).isEmpty();
    }
}
