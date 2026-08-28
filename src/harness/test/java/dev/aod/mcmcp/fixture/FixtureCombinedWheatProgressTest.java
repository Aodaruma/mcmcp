package dev.aod.mcmcp.fixture;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class FixtureCombinedWheatProgressTest {
    @Test
    void requiresTheWheatGoalAndEveryPlotToBeRetilledAndReplanted() {
        assertThat(new FixtureCombinedWheatProgress(64, 9, 9, 9).complete()).isTrue();
        assertThat(new FixtureCombinedWheatProgress(63, 9, 9, 9).complete()).isFalse();
        assertThat(new FixtureCombinedWheatProgress(64, 8, 9, 9).complete()).isFalse();
        assertThat(new FixtureCombinedWheatProgress(64, 9, 8, 9).complete()).isFalse();
    }

    @Test
    void rejectsImpossibleCounts() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new FixtureCombinedWheatProgress(0, 10, 0, 9));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new FixtureCombinedWheatProgress(0, 0, -1, 9));
    }
}
