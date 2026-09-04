package dev.aod.mcmcp.fixture;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class FixturePhase3AutorunConfigTest {
    @Test
    void parsesTheClosedModeSet() {
        assertThat(FixturePhase3AutorunConfig.parse(null)).isEmpty();
        assertThat(FixturePhase3AutorunConfig.parse("  ")).isEmpty();
        for (var mode : FixturePhase3AutorunConfig.Mode.values()) {
            assertThat(FixturePhase3AutorunConfig.parse(
                    "  " + mode.name().toLowerCase(java.util.Locale.ROOT) + "  "))
                    .contains(new FixturePhase3AutorunConfig(mode));
        }
        assertThatIllegalArgumentException()
                .isThrownBy(() -> FixturePhase3AutorunConfig.parse("teleport"));
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
        assertThat(FixturePhase3AutorunConfig.Mode.DIRECTIONAL_STAIRS_MATRIX.selectedSlot())
                .isEqualTo(7);
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
                        FixturePhase3AutorunConfig.Mode.DIRECTIONAL_STAIRS_MATRIX,
                        FixturePhase3AutorunConfig.Mode.HOPPER,
                        FixturePhase3AutorunConfig.Mode.SHORTAGE,
                        FixturePhase3AutorunConfig.Mode.DIVERGENCE,
                        FixturePhase3AutorunConfig.Mode.HIDDEN,
                        FixturePhase3AutorunConfig.Mode.BUILD_RUNNER);
        assertThat(FixturePhase3AutorunConfig.Mode.CREATIVE_CAPTURE.creativeCapture()).isTrue();
    }
}
