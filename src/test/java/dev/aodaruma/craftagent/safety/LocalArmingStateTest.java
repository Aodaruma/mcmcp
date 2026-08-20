package dev.aodaruma.craftagent.safety;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LocalArmingStateTest {
    @Test
    void bindsArmingToSessionCapabilityAndExpiry() {
        var state = new LocalArmingState();
        var session = UUID.randomUUID();
        state.arm(session, Set.of("stationary_break"), Duration.ofSeconds(10), 1_000);

        assertThat(state.allows(session, "stationary_break", 5_000, 2_000)).isTrue();
        assertThat(state.allows(session, "place_block", 5_000, 2_000)).isFalse();
        assertThat(state.allows(UUID.randomUUID(), "stationary_break", 5_000, 2_000)).isFalse();
        assertThat(state.snapshot(session, Duration.ofSeconds(10).toNanos() + 1_000).locked()).isTrue();
    }

    @Test
    void sanitizesShortReasonContainingOnlyEdgeControlCharacters() {
        var state = new LocalArmingState();

        state.lock("\u0000a\u0000");

        assertThat(state.snapshot(null, 0).lastLockReason()).isEqualTo("a");
    }
}
