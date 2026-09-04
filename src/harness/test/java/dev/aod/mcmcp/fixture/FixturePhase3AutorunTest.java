package dev.aod.mcmcp.fixture;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FixturePhase3AutorunTest {
    @Test
    void routesDirectionalStairsMatrixToItsSetupOnlyPhase4Scenario() {
        assertThat(FixturePhase3Autorun.ScenarioRouting.phase4(
                FixturePhase3AutorunConfig.Mode.DIRECTIONAL_STAIRS_MATRIX))
                .isEqualTo(FixturePhase4Scenario.Mode.DIRECTIONAL_STAIRS_MATRIX);
    }
}
