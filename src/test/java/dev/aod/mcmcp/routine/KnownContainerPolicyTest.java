package dev.aod.mcmcp.routine;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class KnownContainerPolicyTest {
    @Test
    void exactAllowlistTracksEveryMinecraft262CopperChestWithoutOtherContainers() {
        Set<String> registeredCopperChests = Blocks.COPPER_CHEST.asList().stream()
                .map(block -> BuiltInRegistries.BLOCK.getKey(block).toString())
                .collect(Collectors.toUnmodifiableSet());

        assertThat(KnownContainerPolicy.copperChestBlockIds())
                .containsExactlyInAnyOrderElementsOf(registeredCopperChests)
                .hasSize(8);
        assertThat(KnownContainerPolicy.blockIds())
                .containsExactlyInAnyOrderElementsOf(Set.of(
                        "minecraft:chest",
                        "minecraft:barrel",
                        "minecraft:copper_chest",
                        "minecraft:exposed_copper_chest",
                        "minecraft:weathered_copper_chest",
                        "minecraft:oxidized_copper_chest",
                        "minecraft:waxed_copper_chest",
                        "minecraft:waxed_exposed_copper_chest",
                        "minecraft:waxed_weathered_copper_chest",
                        "minecraft:waxed_oxidized_copper_chest"));
        assertThat(Set.of(
                "minecraft:trapped_chest",
                "minecraft:ender_chest",
                "minecraft:white_shulker_box",
                "example:modded_chest"))
                .allMatch(blockId -> !KnownContainerPolicy.allows(blockId));
    }
}
