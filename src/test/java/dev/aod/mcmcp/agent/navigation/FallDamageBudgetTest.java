package dev.aod.mcmcp.agent.navigation;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class FallDamageBudgetTest {
    @Test void oneMoveCannotSpendOnePointThreeTimesWithBudgetTwo() {
        var budget = new FallDamageBudget(2);
        assertThat(budget.begin(1)).isTrue();
        assertThat(budget.record(1, true)).isTrue(); budget.finish();
        assertThat(budget.begin(1)).isTrue();
        assertThat(budget.record(1, true)).isTrue(); budget.finish();
        assertThat(budget.begin(1)).isFalse();
        assertThat(budget.observed()).isEqualTo(2);
        assertThat(budget.remaining()).isZero();
    }
    @Test void conservativelyKeepsPredictionAndRejectsUnexpectedDamage() {
        var budget = new FallDamageBudget(0.6);
        assertThat(budget.begin(0.52)).isTrue();
        assertThat(budget.record(0.5, true)).isTrue(); budget.finish();
        assertThat(budget.spent()).isEqualTo(0.52);
        assertThat(budget.begin(0.08)).isTrue();
        assertThat(budget.record(0.2, true)).isFalse();
        assertThat(new FallDamageBudget(10).record(1, true)).isFalse();
        var other = new FallDamageBudget(10);
        assertThat(other.begin(1)).isTrue();
        assertThat(other.record(1, false)).isFalse();
    }
}
