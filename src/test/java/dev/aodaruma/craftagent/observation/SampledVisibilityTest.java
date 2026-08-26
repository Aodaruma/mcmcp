package dev.aodaruma.craftagent.observation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class SampledVisibilityTest {
    private static final Vec3 START = new Vec3(-1.5D, 0.5D, 0.5D);
    private static final Vec3 END = new Vec3(2.0D - 1.0e-4D, 0.5D, 0.5D);
    private static final BlockPos WINDOW = new BlockPos(0, 0, 0);
    private static final BlockPos TARGET = new BlockPos(2, 0, 0);

    @Test
    void continuesPastGlassAndReachesTheTarget() {
        var clips = new AtomicInteger();
        var states = Map.of(
                WINDOW, Blocks.GLASS.defaultBlockState(),
                TARGET, Blocks.LAPIS_BLOCK.defaultBlockState());

        boolean visible = SampledVisibility.traceClearPath(
                START,
                END,
                TARGET,
                (from, to) -> {
                    if (clips.getAndIncrement() == 0) {
                        return hit(new Vec3(0.0D, 0.5D, 0.5D), WINDOW);
                    }
                    return hit(END, TARGET);
                },
                states::get);

        assertThat(visible).isTrue();
        assertThat(clips).hasValue(2);
    }

    @Test
    void opaqueAndPartialSolidBlocksRemainOccluders() {
        assertThat(SampledVisibility.isVisuallyTransparent(Blocks.STONE.defaultBlockState())).isFalse();
        assertThat(SampledVisibility.isVisuallyTransparent(Blocks.SMOOTH_STONE_SLAB.defaultBlockState())).isFalse();
        assertThat(SampledVisibility.isVisuallyTransparent(Blocks.OAK_STAIRS.defaultBlockState())).isFalse();
        assertThat(SampledVisibility.isVisuallyTransparent(Blocks.OAK_DOOR.defaultBlockState())).isFalse();

        for (BlockState blocker : new BlockState[] {
            Blocks.STONE.defaultBlockState(), Blocks.SMOOTH_STONE_SLAB.defaultBlockState()
        }) {
            boolean visible = SampledVisibility.traceClearPath(
                    START,
                    END,
                    TARGET,
                    (from, to) -> hit(new Vec3(0.0D, 0.5D, 0.5D), WINDOW),
                    ignored -> blocker);

            assertThat(visible).isFalse();
        }
    }

    @Test
    void opaqueBlockAfterGlassStillStopsTheRay() {
        var clips = new AtomicInteger();
        BlockPos wall = new BlockPos(1, 0, 0);
        var states = Map.of(
                WINDOW, Blocks.GLASS.defaultBlockState(),
                wall, Blocks.STONE.defaultBlockState());

        boolean visible = SampledVisibility.traceClearPath(
                START,
                END,
                TARGET,
                (from, to) -> clips.getAndIncrement() == 0
                        ? hit(new Vec3(0.0D, 0.5D, 0.5D), WINDOW)
                        : hit(new Vec3(1.25D, 0.5D, 0.5D), wall),
                states::get);

        assertThat(visible).isFalse();
        assertThat(clips).hasValue(2);
    }

    @Test
    void vanillaTransparentMaterialBoundaryIsExplicit() {
        assertThat(SampledVisibility.isVisuallyTransparent(Blocks.GLASS.defaultBlockState())).isTrue();
        assertThat(SampledVisibility.isVisuallyTransparent(Blocks.STAINED_GLASS.white().defaultBlockState())).isTrue();
        assertThat(SampledVisibility.isVisuallyTransparent(Blocks.TINTED_GLASS.defaultBlockState())).isTrue();
        assertThat(SampledVisibility.isVisuallyTransparent(Blocks.ICE.defaultBlockState())).isTrue();
        assertThat(SampledVisibility.isVisuallyTransparent(Blocks.GLASS_PANE.defaultBlockState())).isTrue();
        assertThat(SampledVisibility.isVisuallyTransparent(Blocks.STAINED_GLASS_PANE.white().defaultBlockState()))
                .isTrue();
    }

    @Test
    void repeatedTransparentHitsFailClosedAtTheTraversalLimit() {
        var clips = new AtomicInteger();

        boolean visible = SampledVisibility.traceClearPath(
                START,
                new Vec3(100.0D, 0.5D, 0.5D),
                null,
                (from, to) -> {
                    int x = clips.getAndIncrement() * 2;
                    return hit(new Vec3(x, 0.5D, 0.5D), new BlockPos(x, 0, 0));
                },
                ignored -> Blocks.GLASS.defaultBlockState());

        assertThat(visible).isFalse();
        assertThat(clips).hasValue(17);
    }

    private static BlockHitResult hit(Vec3 location, BlockPos position) {
        return new BlockHitResult(location, Direction.WEST, position, false);
    }
}
