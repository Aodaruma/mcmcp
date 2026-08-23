package dev.aodaruma.craftagent.observation;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreativeRegionCaptureTest {
    @Test
    void regionUsesOperationalArtifactCapsAndDeterministicChunkMajorTraversal() {
        var region = CreativeRegionCapture.parseRegion(Map.of(
                "dimension", "minecraft:overworld",
                "min", Map.of("x", 0, "y", 0, "z", 0),
                "max", Map.of("x", 127, "y", 255, "z", 127)));

        assertThat(region.volume()).isEqualTo(CreativeRegionCapture.MAX_VOLUME);
        assertThat(region.chunks()).hasSize(CreativeRegionCapture.MAX_CHUNKS);
        assertThat(region.positionAt(0)).isEqualTo(new BlockPos(0, 0, 0));
        assertThat(region.positionAt(region.volume() - 1)).isEqualTo(new BlockPos(127, 255, 127));
        assertThat(region.positionInChunkAt(region.chunks().getFirst(), 0))
                .isEqualTo(new BlockPos(0, 0, 0));
        assertThat(region.positionInChunkAt(region.chunks().get(1), 0))
                .isEqualTo(new BlockPos(16, 0, 0));

        assertThatThrownBy(() -> CreativeRegionCapture.parseRegion(Map.of(
                "dimension", "minecraft:overworld",
                "min", Map.of("x", 0, "y", 64, "z", 0),
                "max", Map.of("x", 256, "y", 64, "z", 0))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("axis");
        assertThatThrownBy(() -> CreativeRegionCapture.parseRegion(Map.of(
                "dimension", "minecraft:overworld",
                "min", Map.of("x", 0, "y", 64, "z", 0),
                "max", Map.of("x", 143, "y", 64, "z", 127))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64 chunk");
        assertThatThrownBy(() -> CreativeRegionCapture.parseRegion(Map.of(
                "dimension", "minecraft:overworld",
                "min", Map.of("x", 1, "y", 64, "z", 0),
                "max", Map.of("x", 0, "y", 64, "z", 0))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not exceed");
    }

    @Test
    void creativeGateRequiresEveryLocalAuthoritativeSignal() {
        assertThat(CreativeRegionCapture.clientAccessAllowed(
                true, true, GameType.CREATIVE, true, true, true, true)).isTrue();
        assertThat(CreativeRegionCapture.clientAccessAllowed(
                false, true, GameType.CREATIVE, true, true, true, true)).isFalse();
        assertThat(CreativeRegionCapture.clientAccessAllowed(
                true, false, GameType.CREATIVE, true, true, true, true)).isFalse();
        assertThat(CreativeRegionCapture.clientAccessAllowed(
                true, true, GameType.SURVIVAL, true, true, true, true)).isFalse();
        assertThat(CreativeRegionCapture.clientAccessAllowed(
                true, true, GameType.CREATIVE, false, true, true, true)).isFalse();
        assertThat(CreativeRegionCapture.clientAccessAllowed(
                true, true, GameType.CREATIVE, true, false, true, true)).isFalse();
        assertThat(CreativeRegionCapture.clientAccessAllowed(
                true, true, GameType.CREATIVE, true, true, false, true)).isFalse();
        assertThat(CreativeRegionCapture.clientAccessAllowed(
                true, true, GameType.CREATIVE, true, true, true, false)).isFalse();
    }

    @Test
    void blueprintHashIsRelativeAndBindsEveryFullStateProperty() {
        var stone = MinecraftObservationService.blockStateView(Blocks.STONE.defaultBlockState());
        var dirt = MinecraftObservationService.blockStateView(Blocks.DIRT.defaultBlockState());
        var stairs = new BlockStateView("minecraft:oak_stairs", Map.of(
                "waterlogged", "false",
                "half", "bottom",
                "facing", "north"));
        var first = List.of(
                new CreativeRegionCapture.Cell("c000", new BlockPlan.Offset(0, 0, 0), stone),
                new CreativeRegionCapture.Cell("c001", new BlockPlan.Offset(1, 0, 0), dirt));
        var sameAtAnotherAnchor = List.of(
                new CreativeRegionCapture.Cell("other", new BlockPlan.Offset(0, 0, 0), stone),
                new CreativeRegionCapture.Cell("ids_do_not_bind", new BlockPlan.Offset(1, 0, 0), dirt));
        var changed = List.of(
                new CreativeRegionCapture.Cell("c000", new BlockPlan.Offset(0, 0, 0), stone),
                new CreativeRegionCapture.Cell("c001", new BlockPlan.Offset(1, 0, 0), stone));
        var propertyState = List.of(
                new CreativeRegionCapture.Cell("ignored", new BlockPlan.Offset(0, 0, 0), stairs));

        assertThat(CreativeRegionCapture.blueprintHash(first))
                .matches("sha256:[0-9a-f]{64}")
                .isEqualTo(CreativeRegionCapture.blueprintHash(sameAtAnotherAnchor))
                .isEqualTo(CreativeRegionCapture.blueprintHash(first.reversed()))
                .isNotEqualTo(CreativeRegionCapture.blueprintHash(changed));
        assertThat(CreativeRegionCapture.blueprintHash(propertyState))
                .isEqualTo("sha256:47c255611df4db3340b37ea01f8505b2c0e1077f42d35397e9698bcd30e18f3b");
    }

    @Test
    void pairedBlockMaterialIsCountedOnlyFromThePrimaryHalf() {
        var lower = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        var upper = lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);

        assertThat(CreativeRegionCapture.isSecondaryMultiCellState(lower)).isFalse();
        assertThat(CreativeRegionCapture.isSecondaryMultiCellState(upper)).isTrue();
    }

    @Test
    void materialEstimateAccountsForCommonMultiItemBlockStates() {
        var doubleSlab = Blocks.STONE_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE);
        var candles = Blocks.CANDLE.defaultBlockState()
                .setValue(BlockStateProperties.CANDLES, 4);

        assertThat(CreativeRegionCapture.materialUnits(Blocks.STONE.defaultBlockState())).isEqualTo(1);
        assertThat(CreativeRegionCapture.materialUnits(doubleSlab)).isEqualTo(2);
        assertThat(CreativeRegionCapture.materialUnits(candles)).isEqualTo(4);
    }

}
