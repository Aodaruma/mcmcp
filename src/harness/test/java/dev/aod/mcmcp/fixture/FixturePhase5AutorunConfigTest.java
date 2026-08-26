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
                        "recipes", "craft", "transfer", "crop",
                        "tree", "sleep", "survey", "iron_farm", "reset");

        for (FixturePhase5Mode mode : FixturePhase5Mode.values()) {
            assertThat(FixturePhase5AutorunConfig.parse(
                    "  " + mode.wireName().toUpperCase(java.util.Locale.ROOT) + "  ", "false"))
                    .contains(new FixturePhase5AutorunConfig(mode, false));
        }

        assertThatIllegalArgumentException()
                .isThrownBy(() -> FixturePhase5AutorunConfig.parse("shortage", "false"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> FixturePhase5AutorunConfig.parse("hidden", "false"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> FixturePhase5AutorunConfig.parse("divergence", "false"));
    }

    @Test
    void onlyRoutineModesMayAutoArm() {
        assertThat(Arrays.stream(FixturePhase5Mode.values())
                .filter(FixturePhase5Mode::routine))
                .containsExactly(
                        FixturePhase5Mode.CRAFT,
                        FixturePhase5Mode.TRANSFER,
                        FixturePhase5Mode.CROP,
                        FixturePhase5Mode.TREE,
                        FixturePhase5Mode.SLEEP,
                        FixturePhase5Mode.SURVEY,
                        FixturePhase5Mode.IRON_FARM);

        for (FixturePhase5Mode mode : FixturePhase5Mode.values()) {
            assertThat(FixturePhase5AutorunConfig.parse(mode.wireName(), "true")).get()
                    .extracting(FixturePhase5AutorunConfig::autoArm)
                    .isEqualTo(mode.routine());
        }
    }

    @Test
    void absentModeDisablesAutorunAndBooleanParsingIsStrict() {
        assertThat(FixturePhase5AutorunConfig.parse(null, "true")).isEmpty();
        assertThat(FixturePhase5AutorunConfig.parse("  ", "true")).isEmpty();
        assertThat(FixturePhase5AutorunConfig.parse("craft", null)).get()
                .extracting(FixturePhase5AutorunConfig::autoArm)
                .isEqualTo(false);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> FixturePhase5AutorunConfig.parse("craft", "yes"));
    }
}
