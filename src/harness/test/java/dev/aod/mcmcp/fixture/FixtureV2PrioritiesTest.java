package dev.aod.mcmcp.fixture;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class FixtureV2PrioritiesTest {
    @Test void onlyFixedCasesWithAnExplicitNewRequestNumberAreAccepted() {
        assertThat(FixtureV2PrioritiesConfig.validRequest("1:water")).isTrue();
        for (String kind : java.util.List.of("scaffold_column", "scaffold_top", "seed_piston", "seed_scaffold", "move_exact", "move_exact_slab", "move_exact_creative", "move_exact_spectator", "portal_end", "portal_nether", "move_gap", "move_gap_two", "move_gap_roof", "move_drop3", "move_drop4", "move_drop5", "move_drop_ff", "move_drop_protection", "move_drop_cumulative", "ladder_down", "ladder_long", "ladder_up", "ladder_long_up", "ladder_gap", "ladder_enclosed", "approach_chest", "approach_roof", "approach_wall", "approach_far", "storage_placed_inside", "storage_placed_top", "storage_placed_no_hand", "storage_placed", "storage_boundary", "storage_plain", "storage_upgraded", "storage_tab", "storage_food", "storage_drift", "storage_cursor_drift", "waystones"))
            assertThat(FixtureV2PrioritiesConfig.validRequest("2:" + kind)).isTrue();
        for(String value:java.util.List.of("water", "1:function x", "1:water\n2:boat", "1:../water", "123456789:boat"))
            assertThat(FixtureV2PrioritiesConfig.validRequest(value)).isFalse();
    }
}
