package dev.aod.mcmcp.fixture;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class FixturePhase5AutorunConfigTest {
    @Test
    void parsesExactlyTheClosedPhaseFiveModeSet() {
        assertThat(Arrays.stream(FixturePhase5Mode.values())
                .map(FixturePhase5Mode::wireName))
                .containsExactly(
                        "recipes", "craft", "smelt", "warehouse_smelt", "label_transfer", "brew", "redstone", "transfer", "crop",
                        "combined_wheat", "tree", "sleep", "survey", "generalization",
                        "bounded_input_hold",
                        "cobblestone_generator",
                        "fishing",
                        "kill_zone",
                        "iron_farm", "reset");

        for (FixturePhase5Mode mode : FixturePhase5Mode.values()) {
            assertThat(FixturePhase5AutorunConfig.parse(
                    "  " + mode.wireName().toUpperCase(java.util.Locale.ROOT) + "  "))
                    .contains(new FixturePhase5AutorunConfig(mode));
        }

        assertThatIllegalArgumentException()
                .isThrownBy(() -> FixturePhase5AutorunConfig.parse("shortage"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> FixturePhase5AutorunConfig.parse("hidden"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> FixturePhase5AutorunConfig.parse("divergence"));
        assertThat(FixturePhase5AutorunConfig.parse(null)).isEmpty();
        assertThat(FixturePhase5AutorunConfig.parse("  ")).isEmpty();
    }
}
