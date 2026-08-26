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
        assertThat(FixturePhase3AutorunConfig.parse("all_satisfied", "true")).get()
                .extracting(FixturePhase3AutorunConfig::mode)
                .isEqualTo(FixturePhase3AutorunConfig.Mode.ALL_SATISFIED);
        assertThat(FixturePhase3AutorunConfig.parse("mutations", "true")).get()
                .extracting(FixturePhase3AutorunConfig::mode)
                .isEqualTo(FixturePhase3AutorunConfig.Mode.MUTATIONS);
        assertThat(FixturePhase3AutorunConfig.parse("waterlogged", "true")).get()
                .extracting(FixturePhase3AutorunConfig::mode)
                .isEqualTo(FixturePhase3AutorunConfig.Mode.WATERLOGGED);
        assertThat(FixturePhase3AutorunConfig.parse("directional_stairs", "true")).get()
                .extracting(FixturePhase3AutorunConfig::mode)
                .isEqualTo(FixturePhase3AutorunConfig.Mode.DIRECTIONAL_STAIRS);
        assertThat(FixturePhase3AutorunConfig.parse("hopper", "true")).get()
                .extracting(FixturePhase3AutorunConfig::mode)
                .isEqualTo(FixturePhase3AutorunConfig.Mode.HOPPER);
        assertThat(FixturePhase3AutorunConfig.parse("shortage", "true")).get()
                .extracting(FixturePhase3AutorunConfig::mode)
                .isEqualTo(FixturePhase3AutorunConfig.Mode.SHORTAGE);
        assertThat(FixturePhase3AutorunConfig.parse("divergence", "true")).get()
                .extracting(FixturePhase3AutorunConfig::mode)
                .isEqualTo(FixturePhase3AutorunConfig.Mode.DIVERGENCE);
        assertThat(FixturePhase3AutorunConfig.parse("hidden", "true")).get()
                .extracting(FixturePhase3AutorunConfig::mode)
                .isEqualTo(FixturePhase3AutorunConfig.Mode.HIDDEN);
        assertThat(FixturePhase3AutorunConfig.parse("build_runner", "true")).get()
                .extracting(FixturePhase3AutorunConfig::mode)
                .isEqualTo(FixturePhase3AutorunConfig.Mode.BUILD_RUNNER);
        assertThat(FixturePhase3AutorunConfig.parse("creative_capture", "true"))
                .contains(new FixturePhase3AutorunConfig(
                        FixturePhase3AutorunConfig.Mode.CREATIVE_CAPTURE, true));
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
        assertThat(FixturePhase3AutorunConfig.Mode.ALL_SATISFIED.selectedSlot()).isZero();
        assertThat(FixturePhase3AutorunConfig.Mode.MUTATIONS.selectedSlot()).isZero();
        assertThat(FixturePhase3AutorunConfig.Mode.WATERLOGGED.selectedSlot()).isEqualTo(7);
        assertThat(FixturePhase3AutorunConfig.Mode.DIRECTIONAL_STAIRS.selectedSlot()).isEqualTo(7);
        assertThat(FixturePhase3AutorunConfig.Mode.HOPPER.selectedSlot()).isEqualTo(7);
        assertThat(FixturePhase3AutorunConfig.Mode.SHORTAGE.selectedSlot()).isEqualTo(1);
        assertThat(FixturePhase3AutorunConfig.Mode.DIVERGENCE.selectedSlot()).isZero();
        assertThat(FixturePhase3AutorunConfig.Mode.HIDDEN.selectedSlot()).isZero();
        assertThat(FixturePhase3AutorunConfig.Mode.BUILD_RUNNER.selectedSlot()).isEqualTo(1);
        assertThat(FixturePhase3AutorunConfig.Mode.CREATIVE_CAPTURE.selectedSlot()).isZero();
        assertThat(FixturePhase3AutorunConfig.Mode.values())
                .filteredOn(FixturePhase3AutorunConfig.Mode::phase4)
                .containsExactly(
                        FixturePhase3AutorunConfig.Mode.ALL_SATISFIED,
                        FixturePhase3AutorunConfig.Mode.MUTATIONS,
                        FixturePhase3AutorunConfig.Mode.WATERLOGGED,
                        FixturePhase3AutorunConfig.Mode.DIRECTIONAL_STAIRS,
                        FixturePhase3AutorunConfig.Mode.HOPPER,
                        FixturePhase3AutorunConfig.Mode.SHORTAGE,
                        FixturePhase3AutorunConfig.Mode.DIVERGENCE,
                        FixturePhase3AutorunConfig.Mode.HIDDEN,
                        FixturePhase3AutorunConfig.Mode.BUILD_RUNNER);
        assertThat(FixturePhase3AutorunConfig.Mode.CREATIVE_CAPTURE.creativeCapture()).isTrue();
        assertThat(FixturePhase3AutorunConfig.Mode.values())
                .filteredOn(FixturePhase3AutorunConfig.Mode::phase4)
                .allSatisfy(mode -> assertThat(FixturePhase3AutorunConfig.parse(
                        mode.name().toLowerCase(java.util.Locale.ROOT), "true")).get()
                        .extracting(FixturePhase3AutorunConfig::autoArm)
                        .isEqualTo(true));
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
