package dev.aod.mcmcp.safety;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LocalArmingStateTest {
    @Test
    void readyLeaseReturnsAfterCompletionAndRemainsWorldScoped() {
        var state = new LocalArmingState();
        var session = UUID.randomUUID();
        state.arm(session, Set.of("stationary_break"));
        long armedEpoch = state.snapshot(session).controlEpoch();

        assertThat(state.allows(session, "stationary_break")).isTrue();
        assertThat(state.allows(session, "place_block")).isFalse();
        assertThat(state.snapshot(session).locked()).isFalse();
        assertThat(state.beginAction(session)).isTrue();
        assertThat(state.snapshot(session).mode()).isEqualTo(LocalArmingState.Mode.AGENT);
        assertThat(state.snapshot(session).controlEpoch()).isGreaterThan(armedEpoch);
        assertThat(state.beginAction(session)).isFalse();
        assertThat(state.completeAction(session)).isTrue();
        assertThat(state.snapshot(session).mode()).isEqualTo(LocalArmingState.Mode.READY);
        assertThat(state.beginAction(session)).isTrue();

        state.lock("local_ui_disabled");

        assertThat(state.allows(session, "stationary_break")).isFalse();

        state.arm(session, Set.of("stationary_break"));
        assertThat(state.allows(UUID.randomUUID(), "stationary_break")).isFalse();
        assertThat(state.snapshot(session).lastLockReason()).isEqualTo("world_session_changed");
    }

    @Test
    void readyWaitsUntilAnActionOrExplicitStop() {
        var state = new LocalArmingState();
        var session = UUID.randomUUID();
        state.arm(session, Set.of("movement"));
        long armedEpoch = state.snapshot(session).controlEpoch();

        assertThat(state.snapshot(session).mode()).isEqualTo(LocalArmingState.Mode.READY);
        assertThat(state.snapshot(session).controlEpoch()).isEqualTo(armedEpoch);

        state.lock("local_ui_disabled");

        assertThat(state.snapshot(session).mode()).isEqualTo(LocalArmingState.Mode.OFF);
        assertThat(state.snapshot(session).lastLockReason()).isEqualTo("local_ui_disabled");
    }

    @Test
    void sanitizesShortReasonContainingOnlyEdgeControlCharacters() {
        var state = new LocalArmingState();

        state.lock("\u0000a\u0000");

        assertThat(state.snapshot(null).lastLockReason()).isEqualTo("a");
    }
}
