package dev.aod.mcmcp.fixture;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class FixtureV2PrioritiesTest {
    @Test void ladderSideIsAClosedFixedCaseWithBoundedLocalCommands() {
        assertThat(FixtureV2PrioritiesConfig.validRequest("1:ladder_side")).isTrue();
        for (String request : java.util.List.of("ladder_side", "1:ladder_side_command", "1:ladder_side 1 2 3",
                "1:ladder_side\n2:report", "123456789:ladder_side"))
            assertThat(FixtureV2PrioritiesConfig.validRequest(request)).isFalse();
        var commands = FixtureV2PrioritiesConfig.ladderSideCommands();
        assertThat(commands).hasSize(16).containsSubsequence(
                "fill 199 201 197 205 216 203 minecraft:air",
                "fill 200 200 198 204 200 202 minecraft:stone",
                "fill 203 201 200 203 214 200 minecraft:stone",
                "fill 202 201 200 202 214 200 minecraft:ladder[facing=west]",
                "setblock 201 212 200 minecraft:stone",
                "setblock 202 207 199 minecraft:stone", "setblock 202 207 201 minecraft:stone",
                "tp @s 202.5 201 199.5 -90 0");
        for (String command : commands) {
            if (!command.startsWith("fill ")) continue;
            var parts = command.split(" ");
            int minX = Integer.parseInt(parts[1]), minY = Integer.parseInt(parts[2]), minZ = Integer.parseInt(parts[3]);
            int maxX = Integer.parseInt(parts[4]), maxY = Integer.parseInt(parts[5]), maxZ = Integer.parseInt(parts[6]);
            assertThat((maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1)).isLessThanOrEqualTo(784);
            assertThat(minX >> 4).isEqualTo(12); assertThat(maxX >> 4).isEqualTo(12);
            assertThat(minZ >> 4).isEqualTo(12); assertThat(maxZ >> 4).isEqualTo(12);
        }
        assertThat(commands).noneMatch(command -> command.contains("300") || command.startsWith("execute "));
    }

    @Test void ladderSidePreloadAndCommandsRemainBehindOwnerAndInputGuards() throws Exception {
        var source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/harness/java/dev/aod/mcmcp/fixture/FixtureV2Priorities.java"));
        int owner = source.indexOf("if (!decision.allowed()) throw");
        int input = source.indexOf("if (!inputIdle()) throw", owner);
        int preload = source.indexOf("player.level().getChunk(x, z)", input);
        int reauthorize = source.indexOf("FixtureSecurity.reauthorize(decision.context())", preload);
        int command = source.indexOf("performPrefixedCommand(source, command)", reauthorize);
        assertThat(owner).isGreaterThanOrEqualTo(0);
        assertThat(input).isGreaterThan(owner); assertThat(preload).isGreaterThan(input);
        assertThat(reauthorize).isGreaterThan(preload); assertThat(command).isGreaterThan(reauthorize);
        assertThat(source).contains("int minX = ladderSide ? 199", "int maxX = ladderSide ? 205",
                "int minZ = ladderSide ? 197", "int maxZ = ladderSide ? 203");
    }

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
