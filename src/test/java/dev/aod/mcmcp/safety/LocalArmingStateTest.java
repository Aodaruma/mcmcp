package dev.aod.mcmcp.safety;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LocalArmingStateTest {
    @Test
    void remainsArmedUntilExplicitlyLockedOrTheWorldSessionChanges() {
        var state = new LocalArmingState();
        var session = UUID.randomUUID();
        state.arm(session, Set.of("stationary_break"));

        assertThat(state.allows(session, "stationary_break")).isTrue();
        assertThat(state.allows(session, "place_block")).isFalse();
        assertThat(state.snapshot(session).locked()).isFalse();

        state.lock("local_ui_disabled");

        assertThat(state.allows(session, "stationary_break")).isFalse();

        state.arm(session, Set.of("stationary_break"));
        assertThat(state.allows(UUID.randomUUID(), "stationary_break")).isFalse();
        assertThat(state.snapshot(session).lastLockReason()).isEqualTo("world_session_changed");
    }

    @Test
    void sanitizesShortReasonContainingOnlyEdgeControlCharacters() {
        var state = new LocalArmingState();

        state.lock("\u0000a\u0000");

        assertThat(state.snapshot(null).lastLockReason()).isEqualTo("a");
    }
}
