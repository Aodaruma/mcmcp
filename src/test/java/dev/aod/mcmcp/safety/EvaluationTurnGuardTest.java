package dev.aod.mcmcp.safety;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluationTurnGuardTest {
    private final AtomicLong clock = new AtomicLong(1_000L);
    private final EvaluationTurnGuard guard = new EvaluationTurnGuard(clock::get);
    private final UUID world = UUID.randomUUID();
    private final EvaluationTurnGuard.RunnerIdentity runner =
            new EvaluationTurnGuard.RunnerIdentity(42L);

    @Test
    void leaseIsWorldScopedAndExposesOnlyAnImmutableActiveSnapshot() {
        UUID leaseId = UUID.randomUUID();
        var lease = guard.tryAcquire(
                world, leaseId, runner, Duration.ofSeconds(30)).orElseThrow();

        var snapshot = guard.snapshot(world);
        assertThat(snapshot.active()).isTrue();
        assertThat(snapshot.activeLease()).contains(lease);
        assertThat(snapshot.expiresAtNanos())
                .isEqualTo(1_000L + Duration.ofSeconds(30).toNanos());
        assertThat(snapshot.epoch()).isEqualTo(1L);
        assertThat(lease.leaseId()).isEqualTo(leaseId);
        assertThat(lease.runner()).isEqualTo(runner);
        assertThat(guard.tryAcquire(
                world, UUID.randomUUID(), runner, Duration.ofSeconds(30)))
                .isEmpty();
    }

    @Test
    void renewalUsesMonotonicElapsedTimeAndRejectsStaleOrDifferentLeases() {
        var lease = guard.tryAcquire(world, runner, Duration.ofSeconds(10)).orElseThrow();
        var different = new EvaluationTurnGuard.Lease(
                world, UUID.randomUUID(), runner);

        clock.addAndGet(Duration.ofSeconds(9).toNanos());
        assertThat(guard.renew(different, Duration.ofSeconds(10))).isFalse();
        assertThat(guard.renew(lease, Duration.ofSeconds(20))).isTrue();
        long renewedAt = clock.get();
        assertThat(guard.snapshot(world).expiresAtNanos())
                .isEqualTo(renewedAt + Duration.ofSeconds(20).toNanos());

        clock.addAndGet(Duration.ofSeconds(20).toNanos());
        assertThat(guard.snapshot(world).active()).isTrue();
        assertThat(guard.renew(lease, Duration.ofSeconds(20))).isFalse();
        assertThat(guard.leaseNeedingRevocation(world).orElseThrow().reason())
                .isEqualTo(EvaluationTurnGuard.InvalidationReason.LEASE_EXPIRED);
        assertThat(guard.revoke(lease, "lease_expired")).isTrue();
        assertThat(guard.snapshot(world).active()).isFalse();
    }

    @Test
    void normalReleaseIsIdempotentButCannotReleaseAnotherLease() {
        var lease = guard.tryAcquire(world, runner, Duration.ofMinutes(1)).orElseThrow();
        var terminal = guard.awaitTerminal(lease);
        var different = new EvaluationTurnGuard.Lease(
                world, UUID.randomUUID(), runner);

        assertThat(guard.release(different)).isFalse();
        assertThat(guard.release(lease, "turn_completed\nignored-control-line")).isTrue();
        assertThat(guard.release(lease)).isTrue();
        assertThat(guard.snapshot(world).active()).isFalse();
        assertThat(terminal.join().outcome())
                .isEqualTo(EvaluationTurnGuard.Outcome.RELEASED);
        assertThat(terminal.join().reason())
                .isEqualTo("turn_completed ignored-control-line");

        var next = guard.tryAcquire(world, runner, Duration.ofMinutes(1)).orElseThrow();
        assertThat(guard.release(lease)).isFalse();
        assertThat(guard.snapshot(world).activeLease()).contains(next);
    }

    @Test
    void aRetiredExternalLeaseIdCannotBeReusedByTheSameRunner() {
        UUID leaseId = UUID.randomUUID();
        var first = guard.tryAcquire(
                world, leaseId, runner, Duration.ofMinutes(1)).orElseThrow();
        assertThat(guard.release(first)).isTrue();

        assertThat(guard.tryAcquire(
                world, leaseId, runner, Duration.ofMinutes(1))).isEmpty();
    }

    @Test
    void revokeCompletesWaitersAndIsIdempotentForTheExactLease() {
        var lease = guard.tryAcquire(world, runner, Duration.ofMinutes(1)).orElseThrow();
        var terminal = guard.awaitTerminal(lease);

        assertThat(guard.revoke(lease, "local_escape")).isTrue();
        assertThat(guard.revoke(lease, "different_reason_is_not_rewritten")).isTrue();
        assertThat(terminal.join().outcome())
                .isEqualTo(EvaluationTurnGuard.Outcome.REVOKED);
        assertThat(terminal.join().reason()).isEqualTo("local_escape");
    }

    @Test
    void expiryAndWorldChangeStayLockedUntilRuntimeRevokesAfterInputRelease() {
        var expiring = guard.tryAcquire(world, runner, Duration.ofNanos(5)).orElseThrow();
        var expired = guard.awaitTerminal(expiring);
        clock.addAndGet(5L);

        assertThat(guard.snapshot(world).active()).isTrue();
        assertThat(expired).isNotCompleted();
        var expiry = guard.leaseNeedingRevocation(world).orElseThrow();
        assertThat(expiry.lease()).isEqualTo(expiring);
        assertThat(expiry.reason())
                .isEqualTo(EvaluationTurnGuard.InvalidationReason.LEASE_EXPIRED);
        assertThat(guard.revoke(expiring, "lease_expired")).isTrue();
        assertThat(expired.join().outcome())
                .isEqualTo(EvaluationTurnGuard.Outcome.REVOKED);

        var next = guard.tryAcquire(world, runner, Duration.ofMinutes(1)).orElseThrow();
        var changed = guard.awaitTerminal(next);
        UUID otherWorld = UUID.randomUUID();
        assertThat(guard.snapshot(otherWorld).active()).isTrue();
        assertThat(changed).isNotCompleted();
        assertThat(guard.leaseNeedingRevocation(otherWorld).orElseThrow().reason())
                .isEqualTo(EvaluationTurnGuard.InvalidationReason.WORLD_SESSION_CHANGED);
        assertThat(guard.revoke(next, "world_session_changed")).isTrue();
        assertThat(changed.join().outcome())
                .isEqualTo(EvaluationTurnGuard.Outcome.REVOKED);
    }

    @Test
    void staleAwaitFailsWithoutLeakingTheCurrentLeaseFuture() {
        var current = guard.tryAcquire(world, runner, Duration.ofMinutes(1)).orElseThrow();
        var stale = new EvaluationTurnGuard.Lease(
                world, UUID.randomUUID(), runner);

        assertThatThrownBy(() -> guard.awaitTerminal(stale).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
        assertThat(guard.snapshot(world).activeLease()).contains(current);
    }

    @Test
    void naturalNanoTimeWrapStillExpiresByElapsedTime() {
        clock.set(Long.MAX_VALUE - 2L);
        var lease = guard.tryAcquire(world, runner, Duration.ofNanos(5)).orElseThrow();
        clock.set(Long.MIN_VALUE + 2L);

        assertThat(guard.snapshot(world).active()).isTrue();
        assertThat(guard.leaseNeedingRevocation(world).orElseThrow().reason())
                .isEqualTo(EvaluationTurnGuard.InvalidationReason.LEASE_EXPIRED);
        assertThat(guard.revoke(lease, "lease_expired")).isTrue();
        assertThat(guard.awaitTerminal(lease).join().outcome())
                .isEqualTo(EvaluationTurnGuard.Outcome.REVOKED);
    }

    @Test
    void runnerIdentityCanSafelyCorrelateAProcessHandle() {
        var process = ProcessHandle.current();
        var identity = EvaluationTurnGuard.RunnerIdentity.capture(process);

        assertThat(identity.processId()).isEqualTo(process.pid());
        assertThat(identity.matches(process)).isTrue();
        assertThat(new EvaluationTurnGuard.RunnerIdentity(
                process.pid() + 1L, Optional.empty()).matches(process)).isFalse();
    }

    @Test
    void invalidDurationsAreRejectedBeforeStateChanges() {
        assertThatThrownBy(() -> guard.tryAcquire(
                world, runner, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(guard.snapshot(world).active()).isFalse();
    }
}
