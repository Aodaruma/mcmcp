package dev.aodaruma.craftagent.routine;

import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SafeBreakSourcePolicyTest {
    @Test
    void acceptsAuditedConstructionAndAllPhaseFiveCropAndLogIds() {
        assertThat(List.of(
                "minecraft:cobblestone",
                "minecraft:dirt",
                "minecraft:grass_block",
                "minecraft:obsidian",
                "minecraft:stone"))
                .allSatisfy(id -> assertThat(
                        SafeBreakSourcePolicy.allowsRegisteredBlockId(id)).isTrue());
        PhaseFiveWorldSpec.CROPS.values().forEach(adapter -> assertThat(
                SafeBreakSourcePolicy.allowsRegisteredBlockId(adapter.blockId())).isTrue());
        PhaseFiveWorldSpec.TREES.keySet().forEach(id -> assertThat(
                SafeBreakSourcePolicy.allowsRegisteredBlockId(id)).isTrue());

        assertThat(List.of(
                "minecraft:tnt",
                "minecraft:infested_stone",
                "minecraft:chest",
                "minecraft:ice",
                "minecraft:netherrack",
                "minecraft:stripped_oak_log",
                "minecraft:sugar_cane",
                "example:stone",
                "stone"))
                .allSatisfy(id -> assertThat(
                        SafeBreakSourcePolicy.allowsRegisteredBlockId(id)).isFalse());
    }

    @Test
    void packetBoundaryRejectsHooksBlockEntitiesFluidsAndUnexpectedLiveEntities() {
        assertThat(List.of(
                Blocks.COBBLESTONE,
                Blocks.DIRT,
                Blocks.GRASS_BLOCK,
                Blocks.WHEAT,
                Blocks.BEETROOTS,
                Blocks.OAK_LOG,
                Blocks.MANGROVE_LOG,
                Blocks.OBSIDIAN,
                Blocks.STONE))
                .allSatisfy(block -> assertThat(SafeBreakSourcePolicy.allowsLiveState(
                        block.defaultBlockState(), false)).isTrue());

        assertThat(List.of(
                Blocks.TNT,
                Blocks.INFESTED_STONE,
                Blocks.CHEST,
                Blocks.ICE,
                Blocks.WATER))
                .allSatisfy(block -> assertThat(SafeBreakSourcePolicy.allowsLiveState(
                        block.defaultBlockState(), false)).isFalse());
        assertThat(SafeBreakSourcePolicy.allowsLiveState(
                Blocks.STONE.defaultBlockState(), true)).isFalse();
        assertThat(SafeBreakSourcePolicy.allowsLiveState(
                Blocks.WHEAT.defaultBlockState(), true)).isFalse();

        assertThatThrownBy(() -> SafeBreakSourcePolicy.requireLiveState(
                Blocks.CHEST.defaultBlockState(), true))
                .isInstanceOf(SafeBreakSourcePolicy.UnsafeBreakSourceException.class)
                .hasMessage(SafeBreakSourcePolicy.REJECTION_MESSAGE);
    }
}
