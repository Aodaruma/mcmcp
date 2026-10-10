package dev.aod.mcmcp.agent.safety;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class FallDamageEstimateTest {
    @Test void uses26_2FloorBoundaryAndCurrentAttributes() {
        assertThat(FallDamageEstimate.predict(3, 3, 1, 0, 0)).isZero();
        assertThat(FallDamageEstimate.predict(3.9, 3, 1, 0, 0)).isZero();
        assertThat(FallDamageEstimate.predict(4, 3, 1, 0, 0)).isEqualTo(1);
        assertThat(FallDamageEstimate.predict(5, 3, 1, 0, 0)).isEqualTo(2);
        assertThat(FallDamageEstimate.predict(5, 5, 1, 0, 0)).isZero();
        assertThat(FallDamageEstimate.predict(5, 3, 0.5, 0, 0)).isEqualTo(1);
    }

    @Test void featherFallingProtectionAndResistanceUseEffectiveHealthPoints() {
        assertThat(FallDamageEstimate.predict(5, 3, 1, 12, 0)).isEqualTo((double) (2 * 0.52F));
        assertThat(FallDamageEstimate.predict(5, 3, 1, 28, 0)).isEqualTo((double) (2 * (1 - 20 / 25.0F)));
        assertThat(FallDamageEstimate.predict(5, 3, 1, 0, 1)).isEqualTo((double) 1.6F);
        assertThat(FallDamageEstimate.predict(5, 3, 1, 12, 5)).isZero();
    }

    @Test void unknownInputsNeverGrantDamagePermission() {
        assertThat(FallDamageEstimate.predict(Double.NaN, 3, 1, 0, 0)).isInfinite();
        assertThat(FallDamageEstimate.predict(5, Double.NaN, 1, 0, 0)).isInfinite();
        assertThat(FallDamageEstimate.predict(5, 3, -1, 0, 0)).isInfinite();
    }
}
