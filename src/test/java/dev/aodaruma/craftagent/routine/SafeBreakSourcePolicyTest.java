package dev.aodaruma.craftagent.routine;

import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SafeBreakSourcePolicyTest {
    @Test
    void acceptsOnlyTheFiveAuditedCanonicalVanillaIds() {
        assertThat(List.of(
                "minecraft:cobblestone",
                "minecraft:dirt",
                "minecraft:grass_block",
                "minecraft:obsidian",
                "minecraft:stone"))
                .allSatisfy(id -> assertThat(
                        SafeBreakSourcePolicy.allowsRegisteredBlockId(id)).isTrue());

        assertThat(List.of(
                "minecraft:tnt",
                "minecraft:infested_stone",
                "minecraft:chest",
                "minecraft:ice",
                "minecraft:netherrack",
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

        assertThatThrownBy(() -> SafeBreakSourcePolicy.requireLiveState(
                Blocks.CHEST.defaultBlockState(), true))
                .isInstanceOf(SafeBreakSourcePolicy.UnsafeBreakSourceException.class)
                .hasMessage(SafeBreakSourcePolicy.REJECTION_MESSAGE);
    }
}
