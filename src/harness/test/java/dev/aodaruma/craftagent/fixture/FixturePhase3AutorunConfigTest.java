package dev.aodaruma.craftagent.fixture;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class FixturePhase3AutorunConfigTest {
    @Test
    void absentModeLeavesAutorunDisabledEvenWhenAutoArmWasSet() {
        assertThat(FixturePhase3AutorunConfig.parse(null, "true")).isEmpty();
        assertThat(FixturePhase3AutorunConfig.parse("  ", "true")).isEmpty();
    }

    @Test
    void parsesEverySupportedModeAndNormalizesWhitespaceAndCase() {
        assertThat(FixturePhase3AutorunConfig.parse(" NaViGaTe ", "true"))
                .contains(new FixturePhase3AutorunConfig(FixturePhase3AutorunConfig.Mode.NAVIGATE, true));
        assertThat(FixturePhase3AutorunConfig.parse("break", "true")).get()
                .extracting(FixturePhase3AutorunConfig::mode)
                .isEqualTo(FixturePhase3AutorunConfig.Mode.BREAK);
        assertThat(FixturePhase3AutorunConfig.parse("place", "true")).get()
                .extracting(FixturePhase3AutorunConfig::mode)
                .isEqualTo(FixturePhase3AutorunConfig.Mode.PLACE);
        assertThat(FixturePhase3AutorunConfig.parse("lever", "true")).get()
                .extracting(FixturePhase3AutorunConfig::mode)
                .isEqualTo(FixturePhase3AutorunConfig.Mode.LEVER);
        assertThat(FixturePhase3AutorunConfig.parse("cow", "true")).get()
                .extracting(FixturePhase3AutorunConfig::mode)
                .isEqualTo(FixturePhase3AutorunConfig.Mode.COW);
    }

    @Test
    void resetCanNeverRequestAutoArming() {
        assertThat(FixturePhase3AutorunConfig.parse("reset", "true"))
                .contains(new FixturePhase3AutorunConfig(FixturePhase3AutorunConfig.Mode.RESET, false));
    }

    @Test
    void everyModeDeclaresItsClientSelectedSlot() {
        assertThat(FixturePhase3AutorunConfig.Mode.NAVIGATE.selectedSlot()).isZero();
        assertThat(FixturePhase3AutorunConfig.Mode.BREAK.selectedSlot()).isZero();
        assertThat(FixturePhase3AutorunConfig.Mode.PLACE.selectedSlot()).isEqualTo(1);
        assertThat(FixturePhase3AutorunConfig.Mode.LEVER.selectedSlot()).isEqualTo(6);
        assertThat(FixturePhase3AutorunConfig.Mode.COW.selectedSlot()).isZero();
        assertThat(FixturePhase3AutorunConfig.Mode.RESET.selectedSlot()).isZero();
    }

    @Test
    void autoArmDefaultsToFalseAndAcceptsExplicitFalse() {
        assertThat(FixturePhase3AutorunConfig.parse("navigate", null)).get()
                .extracting(FixturePhase3AutorunConfig::autoArm)
                .isEqualTo(false);
        assertThat(FixturePhase3AutorunConfig.parse("navigate", " FALSE ")).get()
                .extracting(FixturePhase3AutorunConfig::autoArm)
                .isEqualTo(false);
    }

    @Test
    void rejectsUnknownModesAndNonBooleanAutoArmValues() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> FixturePhase3AutorunConfig.parse("teleport", "true"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> FixturePhase3AutorunConfig.parse("navigate", "yes"));
    }
}
