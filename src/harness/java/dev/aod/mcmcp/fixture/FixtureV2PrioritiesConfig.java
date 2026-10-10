package dev.aod.mcmcp.fixture;

final class FixtureV2PrioritiesConfig {
    static boolean validRequest(String request) {
        return request.matches("[0-9]{1,8}:(components|cake|cancel|blueprint|inventory|water|water_air|boat|minecart|horse|mod_break|trade|trade_full|trade_change|elytra_step|mechanisms|dimension_end|dimension_overworld|ladder_down|ladder_long|ladder_up|ladder_long_up|ladder_gap|ladder_enclosed|approach_chest|approach_roof|approach_wall|approach_far|storage_plain|storage_upgraded|storage_tab|storage_food|storage_drift|storage_cursor_drift|waystones|report)");
    }
}
