package dev.aod.mcmcp.routine;

import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SafeConstructionBlockPolicyTest {
    @Test
    void acceptsClosedFullBlockFamiliesAndRejectsDynamicOrNeighbourSensitiveBlocks() {
        assertThat(List.of(
                Blocks.STONE,
                Blocks.COBBLESTONE,
                Blocks.OAK_PLANKS,
                Blocks.GLASS,
                Blocks.OAK_LOG,
                Blocks.STRIPPED_OAK_LOG,
                Blocks.CRIMSON_STEM))
                .allSatisfy(block -> assertThat(
                        SafeConstructionBlockPolicy.allowsLiveState(
                                block.defaultBlockState(), false)).isTrue());

        assertThat(List.of(
                Blocks.CHEST,
                Blocks.SAND,
                Blocks.WATER,
                Blocks.REDSTONE_BLOCK,
                Blocks.PISTON,
                Blocks.TNT,
                Blocks.OAK_DOOR,
                Blocks.OAK_STAIRS,
                Blocks.STONE_SLAB,
                Blocks.SCAFFOLDING))
                .allSatisfy(block -> assertThat(
                        SafeConstructionBlockPolicy.allowsLiveState(
                                block.defaultBlockState(), false)).isFalse());
        assertThat(SafeConstructionBlockPolicy.allowsLiveState(
                Blocks.STONE.defaultBlockState(), true)).isFalse();
    }

    @Test
    void requiresCompleteStrictStateAndMatchingBlockItem() {
        var oakLog = new BlockStateFingerprint(
                "minecraft:oak_log", Map.of("axis", "x"));
        SafeConstructionBlockPolicy.requireExpectedStateAndItem(
                oakLog, "minecraft:oak_log");

        assertThatThrownBy(() -> SafeConstructionBlockPolicy.requireExpectedStateAndItem(
                new BlockStateFingerprint("minecraft:oak_log", Map.of()),
                "minecraft:oak_log"))
                .isInstanceOf(
                        SafeConstructionBlockPolicy.UnsafeConstructionBlockException.class);
        assertThatThrownBy(() -> SafeConstructionBlockPolicy.requireExpectedStateAndItem(
                oakLog, "minecraft:stone"))
                .isInstanceOf(
                        SafeConstructionBlockPolicy.UnsafeConstructionBlockException.class);
        assertThatThrownBy(() -> SafeConstructionBlockPolicy.requireExpectedStateAndItem(
                new BlockStateFingerprint("minecraft:redstone_block", Map.of()),
                "minecraft:redstone_block"))
                .isInstanceOf(
                        SafeConstructionBlockPolicy.UnsafeConstructionBlockException.class);
    }
}
