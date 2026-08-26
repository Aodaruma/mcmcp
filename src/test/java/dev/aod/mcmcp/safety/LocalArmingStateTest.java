package dev.aod.mcmcp.safety;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LocalArmingStateTest {
    @Test
    void readyLeaseIsOneActionAndWorldScoped() {
        var state = new LocalArmingState();
        var session = UUID.randomUUID();
        state.arm(session, Set.of("stationary_break"));

        assertThat(state.allows(session, "stationary_break")).isTrue();
        assertThat(state.allows(session, "place_block")).isFalse();
        assertThat(state.snapshot(session).locked()).isFalse();
        assertThat(state.beginAction(session)).isTrue();
        assertThat(state.snapshot(session).mode()).isEqualTo(LocalArmingState.Mode.AGENT);
        assertThat(state.beginAction(session)).isFalse();

        state.lock("local_ui_disabled");

        assertThat(state.allows(session, "stationary_break")).isFalse();

        state.arm(session, Set.of("stationary_break"));
        assertThat(state.allows(UUID.randomUUID(), "stationary_break")).isFalse();
        assertThat(state.snapshot(session).lastLockReason()).isEqualTo("world_session_changed");
    }

    @Test
    void readyLeaseExpiresWithoutConsumingAnAction() {
        var state = new LocalArmingState();
        var session = UUID.randomUUID();
        state.arm(session, Set.of("movement"), 100L);

        assertThat(state.snapshot(session, 100L + LocalArmingState.READY_TIMEOUT_NANOS - 1L).mode())
                .isEqualTo(LocalArmingState.Mode.READY);
        assertThat(state.snapshot(session, 100L + LocalArmingState.READY_TIMEOUT_NANOS).mode())
                .isEqualTo(LocalArmingState.Mode.OFF);
        assertThat(state.snapshot(session).lastLockReason()).isEqualTo("ready_timeout");
    }

    @Test
    void readyLeaseRemainsCorrectAcrossNanoTimeWrap() {
        var state = new LocalArmingState();
        var session = UUID.randomUUID();
        long start = Long.MAX_VALUE - LocalArmingState.READY_TIMEOUT_NANOS / 2L;
        state.arm(session, Set.of("movement"), start);

        assertThat(state.snapshot(session, start + LocalArmingState.READY_TIMEOUT_NANOS - 1L).mode())
                .isEqualTo(LocalArmingState.Mode.READY);
        assertThat(state.snapshot(session, start + LocalArmingState.READY_TIMEOUT_NANOS).mode())
                .isEqualTo(LocalArmingState.Mode.OFF);
    }

    @Test
    void sanitizesShortReasonContainingOnlyEdgeControlCharacters() {
        var state = new LocalArmingState();

        state.lock("\u0000a\u0000");

        assertThat(state.snapshot(null).lastLockReason()).isEqualTo("a");
    }

    @Test
    void supportsABoundedConfiguredReadyTimeout() {
        var state = new LocalArmingState();
        var session = UUID.randomUUID();

        state.armFor(session, Set.of("movement"), Duration.ofSeconds(12));

        assertThat(state.snapshot(session).readyRemainingSeconds(System.nanoTime()))
                .isBetween(1L, 12L);
    }
}
