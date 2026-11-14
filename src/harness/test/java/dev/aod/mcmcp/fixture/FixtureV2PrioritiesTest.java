package dev.aod.mcmcp.fixture;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class FixtureV2PrioritiesTest {
    @Test void onlyFixedCasesWithAnExplicitNewRequestNumberAreAccepted() {
        for (var suffix : java.util.List.of("", "_full", "_bow", "_shield", "_water", "_flight", "_cancel", "_locked"))
            assertThat(FixtureV2PrioritiesConfig.validRequest("8:recovery_equipment" + suffix)).isTrue();
        assertThat(FixtureV2PrioritiesConfig.validRequest("8:recovery_equipment_command")).isFalse();
        for (String kind : java.util.List.of("recovery_bucket", "recovery_web", "recovery_boat", "recovery_slime", "recovery_hay",
                "recovery_bed", "recovery_water", "recovery_elytra", "recovery_elytra_pool", "recovery_elytra_edge", "recovery_mob", "recovery_neutral", "recovery_returning_mob",
                "recovery_trader_near", "recovery_trader_push", "recovery_floor_offset", "recovery_floor_push", "recovery_floor_clear", "recovery_torch_edge", "recovery_torch_chain", "recovery_torch_graze", "recovery_bench_row", "recovery_bench_first", "recovery_bench_existing", "recovery_bench_existing_hidden", "recovery_bench_existing_mismatch", "recovery_place_mob", "recovery_build_flight", "flight_open", "flight_wall", "flight_boost", "flight_boost_wall"))
            assertThat(FixtureV2PrioritiesConfig.validRequest("7:" + kind)).isTrue();
        assertThat(FixtureV2PrioritiesConfig.validRequest("7:recovery_command")).isFalse();
        assertThat(FixtureV2PrioritiesConfig.validRequest("1:water")).isTrue();
        assertThat(FixtureV2PrioritiesConfig.validRequest("1:scaffold_detour")).isTrue();
        assertThat(FixtureV2PrioritiesConfig.validRequest("1:scaffold_detour_roof")).isTrue();
        assertThat(FixtureV2PrioritiesConfig.validRequest("2:scaffold_detour_start")).isTrue();
        assertThat(FixtureV2PrioritiesConfig.validRequest("3:scaffold_stair")).isTrue();
        assertThat(FixtureV2PrioritiesConfig.validRequest("4:scaffold_corner")).isTrue();
        assertThat(FixtureV2PrioritiesConfig.validRequest("5:scaffold_step")).isTrue();
        for (String kind : java.util.List.of("floor_east", "floor_west", "floor_north", "floor_south",
                "floor_offset", "floor_shortage", "floor_obstructed", "floor_refill"))
            assertThat(FixtureV2PrioritiesConfig.validRequest("6:" + kind)).isTrue();
        for (String kind : java.util.List.of("scaffold_column", "scaffold_long", "scaffold_top", "seed_piston", "seed_scaffold", "move_exact", "move_exact_slab", "move_exact_creative", "move_exact_spectator", "portal_end", "portal_nether", "move_gap", "move_gap_two", "move_gap_roof", "move_drop3", "move_drop4", "move_drop5", "move_drop_ff", "move_drop_protection", "move_drop_cumulative", "ladder_down", "ladder_long", "ladder_up", "ladder_long_up", "ladder_gap", "ladder_enclosed", "approach_chest", "approach_roof", "approach_wall", "approach_far", "storage_placed_inside", "storage_placed_top", "storage_placed_no_hand", "storage_placed", "storage_boundary", "storage_craft", "storage_plain", "storage_upgraded", "storage_tab", "storage_food", "storage_drift", "storage_cursor_drift", "waystones"))
            assertThat(FixtureV2PrioritiesConfig.validRequest("2:" + kind)).isTrue();
        for(String value:java.util.List.of("water", "1:function x", "1:water\n2:boat", "1:../water", "123456789:boat"))
            assertThat(FixtureV2PrioritiesConfig.validRequest(value)).isFalse();
    }
}
