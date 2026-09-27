package dev.aod.mcmcp.agent.mining;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class SafeMiningBlocksTest {
    @Test void admitsExactNaturalRockAndOreStatesOnly() {
        assertThat(SafeMiningBlocks.allowsState("minecraft:diamond_ore", Map.of())).isTrue();
        assertThat(SafeMiningBlocks.allowsState("minecraft:granite", Map.of())).isTrue();
        assertThat(SafeMiningBlocks.allowsState("minecraft:deepslate", Map.of("axis", "y"))).isTrue();
        assertThat(SafeMiningBlocks.allowsState("minecraft:deepslate", Map.of())).isFalse();
        assertThat(SafeMiningBlocks.allowsState("minecraft:stone", Map.of("hidden", "true"))).isFalse();
        for (String block : new String[] { "gravel", "sand", "water", "lava", "chest", "spawner",
                "redstone_ore", "infested_stone", "obsidian", "oak_log" }) {
            assertThat(SafeMiningBlocks.allowsState("minecraft:" + block, Map.of())).isFalse();
        }
    }

    @Test void requiresPickaxesThatCanHarvestTheWholeAdmittedOreSubset() {
        assertThat(SafeMiningBlocks.allowsTool("minecraft:iron_pickaxe")).isTrue();
        assertThat(SafeMiningBlocks.allowsTool("minecraft:diamond_pickaxe")).isTrue();
        assertThat(SafeMiningBlocks.allowsTool("minecraft:netherite_pickaxe")).isTrue();
        assertThat(SafeMiningBlocks.allowsTool("minecraft:stone_pickaxe")).isFalse();
        assertThat(SafeMiningBlocks.allowsTool("minecraft:iron_axe")).isFalse();
    }
}
