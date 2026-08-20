package dev.aodaruma.craftagent.routine;

import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SafePlacementSupportPolicyTest {
    @Test
    void acceptsOnlyTheSixAuditedCanonicalVanillaSupportIds() {
        assertThat(List.of(
                "minecraft:cobblestone",
                "minecraft:dirt",
                "minecraft:grass_block",
                "minecraft:obsidian",
                "minecraft:smooth_stone",
                "minecraft:stone"))
                .allSatisfy(id -> assertThat(
                        SafePlacementSupportPolicy.allowsRegisteredBlockId(id)).isTrue());

        assertThat(List.of(
                "minecraft:chest",
                "minecraft:lever",
                "minecraft:oak_trapdoor",
                "minecraft:barrel",
                "minecraft:hopper",
                "example:stone",
                "stone"))
                .allSatisfy(id -> assertThat(
                        SafePlacementSupportPolicy.allowsRegisteredBlockId(id)).isFalse());
    }

    @Test
    void liveBoundaryRejectsInteractiveContainersFluidsAndUnexpectedEntities() {
        assertThat(List.of(
                Blocks.COBBLESTONE,
                Blocks.DIRT,
                Blocks.GRASS_BLOCK,
                Blocks.OBSIDIAN,
                Blocks.SMOOTH_STONE,
                Blocks.STONE))
                .allSatisfy(block -> assertThat(SafePlacementSupportPolicy.allowsLiveState(
                        block.defaultBlockState(), false)).isTrue());

        assertThat(List.of(
                Blocks.CHEST,
                Blocks.LEVER,
                Blocks.OAK_TRAPDOOR,
                Blocks.BARREL,
                Blocks.HOPPER,
                Blocks.WATER))
                .allSatisfy(block -> assertThat(SafePlacementSupportPolicy.allowsLiveState(
                        block.defaultBlockState(), false)).isFalse());
        assertThat(SafePlacementSupportPolicy.allowsLiveState(
                Blocks.STONE.defaultBlockState(), true)).isFalse();
    }

    @Test
    void unsafeSupportDispatchesZeroPacketsOrInteractions() {
        for (var support : List.of(
                Blocks.CHEST,
                Blocks.LEVER,
                Blocks.OAK_TRAPDOOR,
                Blocks.BARREL,
                Blocks.HOPPER)) {
            var dispatches = new AtomicInteger();
            assertThatThrownBy(() -> SafePlacementSupportPolicy.dispatchUseIfAllowed(
                    support.defaultBlockState(), support.defaultBlockState().hasBlockEntity(),
                    dispatches::incrementAndGet))
                    .isInstanceOf(
                            SafePlacementSupportPolicy.UnsafePlacementSupportException.class)
                    .hasMessage(SafePlacementSupportPolicy.REJECTION_MESSAGE);
            assertThat(dispatches).hasValue(0);
        }

        var dispatches = new AtomicInteger();
        assertThat(SafePlacementSupportPolicy.dispatchUseIfAllowed(
                Blocks.SMOOTH_STONE.defaultBlockState(), false,
                dispatches::incrementAndGet)).isEqualTo(1);
        assertThat(dispatches).hasValue(1);
    }
}
