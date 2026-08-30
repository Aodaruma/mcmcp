package dev.aod.mcmcp.routine;

import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SafePlacementSupportPolicyTest {
    @Test
    void acceptsOnlyAuditedInertSupportIds() {
        assertThat(List.of(
                "minecraft:cobblestone",
                "minecraft:dirt",
                "minecraft:glass",
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
                "minecraft:farmland",
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
                Blocks.GLASS,
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
                Blocks.FARMLAND,
                Blocks.WATER))
                .allSatisfy(block -> assertThat(SafePlacementSupportPolicy.allowsLiveState(
                        block.defaultBlockState(), false)).isFalse());
        assertThat(SafePlacementSupportPolicy.allowsLiveState(
                Blocks.STONE.defaultBlockState(), true)).isFalse();
        assertThat(SafePlacementSupportPolicy.allowsLiveState(
                Blocks.FARMLAND.defaultBlockState(), true)).isFalse();
    }

    @Test
    void farmlandIsAllowedOnlyForAnExactClosedCropPlacement() {
        var bounds = new ActionBounds(
                "minecraft:overworld",
                new BlockTarget("minecraft:overworld", 0, 63, 0),
                new BlockTarget("minecraft:overworld", 2, 66, 2),
                0, 30, false);
        var target = new BlockTarget("minecraft:overworld", 1, 65, 1);
        var air = new BlockStateFingerprint("minecraft:air", Map.of());
        var wheat = new PlaceBlockRequest(
                target, air, "minecraft:wheat_seeds",
                new BlockStateFingerprint("minecraft:wheat", Map.of("age", "0")), bounds);
        var stone = new PlaceBlockRequest(
                target, air, "minecraft:stone",
                new BlockStateFingerprint("minecraft:stone", Map.of()), bounds);

        assertThat(MinecraftSemanticActionPort.allowsPlacementSupport(
                Blocks.FARMLAND.defaultBlockState(), false, wheat)).isTrue();
        assertThat(MinecraftSemanticActionPort.allowsPlacementSupport(
                Blocks.FARMLAND.defaultBlockState(), false, stone)).isFalse();
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
