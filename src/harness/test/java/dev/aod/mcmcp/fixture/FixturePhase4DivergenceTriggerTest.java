package dev.aod.mcmcp.fixture;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FixturePhase4DivergenceTriggerTest {
    @Test
    void firesOnlyAfterThreeConsecutiveOwnedBreakTicksAndOnlyOnce() {
        var trigger = new FixturePhase4DivergenceTrigger();

        assertThat(trigger.observe(true)).isFalse();
        assertThat(trigger.observe(true)).isFalse();
        assertThat(trigger.observe(true)).isTrue();
        assertThat(trigger.observe(true)).isFalse();
        assertThat(trigger.observe(false)).isFalse();
    }

    @Test
    void aLostOwnedBreakSignalResetsTheConsecutiveTickWindow() {
        var trigger = new FixturePhase4DivergenceTrigger();

        assertThat(trigger.observe(true)).isFalse();
        assertThat(trigger.observe(true)).isFalse();
        assertThat(trigger.observe(false)).isFalse();
        assertThat(trigger.observe(true)).isFalse();
        assertThat(trigger.observe(true)).isFalse();
        assertThat(trigger.observe(true)).isTrue();
    }
}
