package dev.aod.mcmcp.runtime;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PlayerExperienceTest {
    @Test void currentPointsMatchVanillaLevelBoundariesAndProgress() {
        long total = 0;
        for (int level = 0; level <= 1000; level++) {
            int needed = level >= 30 ? 112 + (level - 30) * 9 : level >= 15 ? 37 + (level - 15) * 5 : 7 + level * 2;
            var xp = PlayerExperience.payload(level, 0.5F, needed);
            assertThat(xp.get("points")).as("level %s", level).isEqualTo(total + needed / 2);
            assertThat(xp.get("points_to_next_level")).isEqualTo((long) needed - needed / 2);
            total += needed;
        }
        assertThat(PlayerExperience.payload(30, 0, 112).get("points")).isEqualTo(1395L);
        assertThat(PlayerExperience.payload(29, 0, 107).get("points")).isEqualTo(1288L);
    }

    @Test void invalidOrInexactDataDoesNotBecomeAZeroBalance() {
        assertThat(PlayerExperience.payload(-1, 0, 7)).isNull();
        assertThat(PlayerExperience.payload(0, Float.NaN, 7)).isNull();
        assertThat(PlayerExperience.payload(0, 1.1F, 7)).isNull();
        assertThat(PlayerExperience.payload(30, 0, -1)).isNull();
        assertThat(PlayerExperience.payload(Integer.MAX_VALUE, 0, 7)).containsEntry("points", null);
    }
}
