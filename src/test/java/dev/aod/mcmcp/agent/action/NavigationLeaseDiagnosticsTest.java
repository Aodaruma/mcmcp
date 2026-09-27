package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.client.AgentInputState;
import dev.aod.mcmcp.routine.MovementInputLease;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NavigationLeaseDiagnosticsTest {
    @Test
    void expiredDrivingAndSettlingReleaseBeforeReturningTimingWithoutRepublishing() throws Exception {
        for (boolean settling : new boolean[]{false, true}) {
            var fixture = new Fixture();
            fixture.tick(4, 650);
            var result = fixture.heartbeat(700, settling);
            assertThat(result.status()).isEqualTo(MinecraftActionPrimitiveExecutor.Status.FAILED);
            assertThat(result.reason()).isEqualTo(MinecraftActionPrimitiveExecutor.Reason.MOVEMENT_LEASE_EXPIRED);
            assertThat(result.diagnostics()).containsExactly(
                    "movement_lease_phase=" + (settling ? "settling" : "driving"),
                    "movement_lease_overdue_ms=200", "movement_lease_tick_gap=3", "movement_executor_ms=50");
            assertThat(fixture.lease.active()).isFalse();
            assertThat(fixture.control.keys).isEmpty();
            assertThat(fixture.control.applies).isEqualTo(2);
            assertThat(fixture.control.releases).isOne();
            fixture.executor.close();
            assertThat(fixture.control.releases).isOne();
        }
    }

    @Test
    void successfulHeartbeatRefreshesTheOriginalDeadlineAndTickWithoutDiagnostics() throws Exception {
        var fixture = new Fixture();
        fixture.tick(4, 350);
        assertThat(fixture.heartbeat(400, false)).isNull();
        assertThat(fixture.control.applies).isEqualTo(3);
        assertThat(fixture.lease.active()).isTrue();
        fixture.tick(5, 850);
        var result = fixture.heartbeat(900, true);
        assertThat(result.diagnostics()).containsExactly(
                "movement_lease_phase=settling", "movement_lease_overdue_ms=0",
                "movement_lease_tick_gap=1", "movement_executor_ms=50");
        assertThat(fixture.control.applies).isEqualTo(3);
        assertThat(fixture.control.releases).isOne();
    }

    @Test
    void expiryReleaseFailureKeepsTheSameLeaseReachableForCleanupRetry() throws Exception {
        var fixture = new Fixture();
        fixture.control.failReleaseOnce = true;
        fixture.tick(4, 650);
        assertThatThrownBy(() -> fixture.heartbeat(700, true))
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(fixture.executor.active()).isTrue();
        assertThat(fixture.control.applies).isEqualTo(2);
        fixture.executor.close();
        assertThat(fixture.executor.active()).isFalse();
        assertThat(fixture.control.releases).isEqualTo(2);
    }

    private static final class Fixture {
        final MinecraftActionPrimitiveExecutor executor = new MinecraftActionPrimitiveExecutor(4);
        final Control control = new Control();
        final long base = System.nanoTime();
        final MovementInputLease lease;

        Fixture() throws Exception {
            var ownerField = MinecraftActionPrimitiveExecutor.class.getDeclaredField("ownerId");
            ownerField.setAccessible(true);
            var owner = (UUID) ownerField.get(executor);
            lease = MovementInputLease.acquire(control, owner, base, Duration.ofMillis(500));
            lease.setDesired(owner, Set.of(MovementInputLease.MovementKey.FORWARD));
            assertThat(lease.heartbeat(owner, base, Duration.ofMillis(500))).isTrue();
            set("movement", lease);
            set("lastMovementHeartbeatTick", 1L);
        }

        void tick(long tick, long startedMillis) throws Exception {
            set("lastClientTick", tick);
            set("tickStartedNanos", base + startedMillis * 1_000_000L);
        }

        MinecraftActionPrimitiveExecutor.TickResult heartbeat(long millis, boolean settling) throws Exception {
            var method = MinecraftActionPrimitiveExecutor.class.getDeclaredMethod("heartbeatMovement", long.class, boolean.class);
            method.setAccessible(true);
            return (MinecraftActionPrimitiveExecutor.TickResult) method.invoke(executor, base + millis * 1_000_000L, settling);
        }

        void set(String name, Object value) throws Exception {
            var field = MinecraftActionPrimitiveExecutor.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(executor, value);
        }
    }

    private static final class Control implements MovementInputLease.MovementControl {
        Set<MovementInputLease.MovementKey> keys = Set.of();
        int applies;
        int releases;
        boolean failReleaseOnce;

        @Override public void apply(Set<MovementInputLease.MovementKey> desired) { keys = desired; applies++; }
        @Override public long watchdogTime(long now) { return AgentInputState.global().watchdogTime(now); }
        @Override public void release() {
            releases++;
            if (failReleaseOnce) { failReleaseOnce = false; throw new IllegalStateException("release pending"); }
            keys = Set.of();
        }
    }
}
