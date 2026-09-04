package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.safety.LocalArmingState;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AutomationUiSnapshotTest {
    @Test
    void exposesAllControlModes() {
        var control = new LocalArmingState();
        var session = UUID.randomUUID();

        assertThat(AutomationUiSnapshot.resolve(
                true, control.snapshot(session), null).state())
                .isEqualTo(AutomationUiSnapshot.State.OFF);
        assertThat(AutomationUiSnapshot.resolve(
                true, control.snapshot(session), true, null).state())
                .isEqualTo(AutomationUiSnapshot.State.OFF);

        control.arm(session, Set.of("movement"));
        var ready = AutomationUiSnapshot.resolve(
                true, control.snapshot(session), null);
        assertThat(ready.state()).isEqualTo(AutomationUiSnapshot.State.READY);
        assertThat(AutomationUiSnapshot.resolve(
                true, control.snapshot(session), true, null).state())
                .isEqualTo(AutomationUiSnapshot.State.EVALUATING);
        var pending = AutomationUiSnapshot.resolve(
                true, control.snapshot(session), true, true, "minecraft:zombie", null);
        assertThat(pending.state()).isEqualTo(AutomationUiSnapshot.State.CONSENT_PENDING);
        assertThat(pending.detail()).isEqualTo("minecraft:zombie");

        assertThat(control.beginAction(session)).isTrue();
        assertThat(AutomationUiSnapshot.resolve(
                true, control.snapshot(session), true, null).state())
                .isEqualTo(AutomationUiSnapshot.State.AGENT);
        assertThat(control.beginRecovery(session)).isTrue();
        assertThat(AutomationUiSnapshot.resolve(
                true, control.snapshot(session), true, null).state())
                .isEqualTo(AutomationUiSnapshot.State.RECOVERING);
    }

    @Test
    void faultPresentationOverridesControlMode() {
        var control = new LocalArmingState();

        var snapshot = AutomationUiSnapshot.resolve(
                false, control.snapshot(null), "endpoint_bind_failed");

        assertThat(snapshot.state()).isEqualTo(AutomationUiSnapshot.State.FAULT);
        assertThat(snapshot.detail()).isEqualTo("endpoint_bind_failed");
    }
}
