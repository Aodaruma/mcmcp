package dev.aod.mcmcp.routine;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class FloorExtensionCorridorTest {
    @Test void admitsPassiveFixturesWithoutAdmittingCollisionFluidDamageOrTriggers() {
        for (var block : List.of(Blocks.AIR, Blocks.TORCH, Blocks.WALL_TORCH, Blocks.SOUL_TORCH,
                Blocks.REDSTONE_TORCH, Blocks.REDSTONE_WIRE, Blocks.RAIL, Blocks.POWERED_RAIL, Blocks.SHORT_GRASS)) {
            assertThat(MinecraftFloorExtensionAttempt.corridorPassable(block.defaultBlockState(), EmptyBlockGetter.INSTANCE, BlockPos.ZERO))
                    .as(block.toString()).isTrue();
        }
        for (var block : List.of(Blocks.STONE, Blocks.WATER, Blocks.LAVA, Blocks.FIRE, Blocks.SOUL_FIRE,
                Blocks.WITHER_ROSE, Blocks.SWEET_BERRY_BUSH, Blocks.COBWEB, Blocks.POWDER_SNOW,
                Blocks.NETHER_PORTAL, Blocks.STONE_PRESSURE_PLATE, Blocks.TRIPWIRE, Blocks.CAMPFIRE)) {
            assertThat(MinecraftFloorExtensionAttempt.corridorPassable(block.defaultBlockState(), EmptyBlockGetter.INSTANCE, BlockPos.ZERO))
                    .as(block.toString()).isFalse();
        }
    }
}
