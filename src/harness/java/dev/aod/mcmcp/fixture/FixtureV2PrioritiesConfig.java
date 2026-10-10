package dev.aod.mcmcp.fixture;

final class FixtureV2PrioritiesConfig {
    static boolean validRequest(String request) {
        return request.matches("[0-9]{1,8}:(move_exact|move_exact_slab|move_exact_creative|move_exact_spectator|portal_end|portal_nether|move_gap|move_gap_two|move_gap_roof|move_drop3|move_drop4|move_drop5|move_drop_ff|move_drop_protection|move_drop_cumulative|components|cake|cancel|blueprint|inventory|water|water_air|boat|minecart|horse|mod_break|trade|trade_full|trade_change|elytra_step|mechanisms|dimension_end|dimension_overworld|ladder_down|ladder_long|ladder_up|ladder_long_up|ladder_gap|ladder_enclosed|approach_chest|approach_roof|approach_wall|approach_far|storage_placed_inside|storage_placed_top|storage_placed_no_hand|storage_placed|storage_boundary|storage_plain|storage_upgraded|storage_tab|storage_food|storage_drift|storage_cursor_drift|waystones|signs_front|signs_back|signs_occluded|signs_large|report)");
    }
}
