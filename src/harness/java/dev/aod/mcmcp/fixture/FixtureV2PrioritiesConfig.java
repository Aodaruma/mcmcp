package dev.aod.mcmcp.fixture;

final class FixtureV2PrioritiesConfig {
    static boolean validRequest(String request) {
        if (request.matches("[0-9]{1,8}:ladder_side")) return true;
        if (request.matches("[0-9]{1,8}:recovery_equipment(_full|_bow|_shield|_water|_flight|_cancel|_locked)?")) return true;
        if (request.matches("[0-9]{1,8}:(recovery_bucket|recovery_web|recovery_boat|recovery_slime|recovery_hay|recovery_bed|recovery_water|recovery_elytra|recovery_elytra_pool|recovery_elytra_edge|recovery_mob|recovery_neutral|recovery_returning_mob|recovery_trader_near|recovery_trader_push|recovery_floor_offset|recovery_floor_push|recovery_floor_clear|recovery_torch_edge|recovery_torch_chain|recovery_torch_graze|recovery_bench_row|recovery_bench_first|recovery_bench_existing|recovery_bench_existing_hidden|recovery_bench_existing_mismatch|recovery_place_mob|recovery_build_flight|flight_open|flight_wall|flight_boost|flight_boost_wall)")) return true;
        return request.matches("[0-9]{1,8}:(floor_refill|floor_east|floor_west|floor_north|floor_south|floor_offset|floor_shortage|floor_obstructed|scaffold_step|scaffold_stair|scaffold_corner|scaffold_detour|scaffold_detour_roof|scaffold_detour_start|scaffold_column|scaffold_long|scaffold_top|seed_piston|seed_scaffold|move_exact|move_exact_slab|move_exact_creative|move_exact_spectator|portal_end|portal_nether|move_gap|move_gap_two|move_gap_roof|move_drop3|move_drop4|move_drop5|move_drop_ff|move_drop_protection|move_drop_cumulative|components|cake|cancel|blueprint|inventory|water|water_air|boat|minecart|horse|mod_break|trade|trade_full|trade_change|elytra_step|mechanisms|dimension_end|dimension_overworld|ladder_down|ladder_long|ladder_up|ladder_long_up|ladder_gap|ladder_enclosed|approach_chest|approach_roof|approach_wall|approach_far|storage_placed_inside|storage_placed_top|storage_placed_no_hand|storage_placed|storage_boundary|storage_craft|storage_plain|storage_upgraded|storage_tab|storage_food|storage_drift|storage_cursor_drift|waystones|signs_front|signs_back|signs_occluded|signs_large|report)");
    }

    static java.util.List<String> ladderSideCommands() {
        return java.util.List.of("gamemode creative @s", "ride @s dismount", "clear @s", "effect clear @s",
                "fill 199 201 197 205 216 203 minecraft:air",
                "fill 200 200 198 204 200 202 minecraft:stone",
                "fill 203 201 200 203 214 200 minecraft:stone",
                "fill 202 201 200 202 214 200 minecraft:ladder[facing=west]",
                "setblock 201 212 200 minecraft:stone",
                "setblock 202 207 199 minecraft:stone", "setblock 202 207 201 minecraft:stone",
                "tp @s 202.5 201 199.5 -90 0", "time set noon", "weather clear",
                "gamemode survival @s", "effect give @s minecraft:saturation 120 0 true");
    }
}
