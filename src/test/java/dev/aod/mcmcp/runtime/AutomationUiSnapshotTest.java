package dev.aod.mcmcp.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AutomationUiSnapshotTest {
    @Test
    void lockTakesPrecedenceOverPendingActivity() {
        var snapshot = AutomationUiSnapshot.resolve(
                true, true, true, "local_ui_disabled");

        assertThat(snapshot.state()).isEqualTo(AutomationUiSnapshot.State.DISABLED);
        assertThat(snapshot.worldReady()).isTrue();
        assertThat(snapshot.lockReason()).isEqualTo("local_ui_disabled");
    }

    @Test
    void distinguishesArmedWorkFromArmedWaiting() {
        assertThat(AutomationUiSnapshot.resolve(true, false, true, null).state())
                .isEqualTo(AutomationUiSnapshot.State.ACTIVE);
        assertThat(AutomationUiSnapshot.resolve(true, false, false, null).state())
                .isEqualTo(AutomationUiSnapshot.State.IDLE);
    }
}
