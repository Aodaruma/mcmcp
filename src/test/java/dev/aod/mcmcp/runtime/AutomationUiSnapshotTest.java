package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.safety.LocalArmingState;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AutomationUiSnapshotTest {
    @Test
    void exposesAllControlModesAndReadyCountdown() {
        var control = new LocalArmingState();
        var session = UUID.randomUUID();

        assertThat(AutomationUiSnapshot.resolve(
                true, control.snapshot(session), System.nanoTime(), null).state())
                .isEqualTo(AutomationUiSnapshot.State.OFF);

        control.arm(session, Set.of("movement"));
        long nowNanos = System.nanoTime();
        var ready = AutomationUiSnapshot.resolve(
                true, control.snapshot(session, nowNanos), nowNanos, null);
        assertThat(ready.state()).isEqualTo(AutomationUiSnapshot.State.READY);
        assertThat(ready.readySeconds()).isBetween(1L, 30L);

        assertThat(control.beginAction(session)).isTrue();
        assertThat(AutomationUiSnapshot.resolve(
                true, control.snapshot(session), System.nanoTime(), null).state())
                .isEqualTo(AutomationUiSnapshot.State.AGENT);
        assertThat(control.beginRecovery(session)).isTrue();
        assertThat(AutomationUiSnapshot.resolve(
                true, control.snapshot(session), System.nanoTime(), null).state())
                .isEqualTo(AutomationUiSnapshot.State.RECOVERING);
    }

    @Test
    void faultPresentationOverridesControlMode() {
        var control = new LocalArmingState();

        var snapshot = AutomationUiSnapshot.resolve(
                false, control.snapshot(null), System.nanoTime(), "endpoint_bind_failed");

        assertThat(snapshot.state()).isEqualTo(AutomationUiSnapshot.State.FAULT);
        assertThat(snapshot.detail()).isEqualTo("endpoint_bind_failed");
    }
}
