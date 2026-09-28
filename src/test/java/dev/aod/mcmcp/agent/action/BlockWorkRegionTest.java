package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.agent.navigation.NavCell;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlockWorkRegionTest {
    @Test
    void walksSignedInclusiveBoxInStableOrder() {
        var region = new BlockWorkRegion("minecraft:overworld", 10, 64, 20,
                -1, 1, -1);
        assertThat(region.size()).isEqualTo(8);
        assertThat(region.cells()).containsExactly(
                cell(10, 64, 20), cell(9, 64, 20),
                cell(10, 64, 19), cell(9, 64, 19),
                cell(10, 65, 20), cell(9, 65, 20),
                cell(10, 65, 19), cell(9, 65, 19));
        assertThat(region.contains(cell(9, 65, 19))).isTrue();
        assertThat(region.contains(cell(8, 65, 19))).isFalse();
        assertThat(region.contains(new NavCell("minecraft:the_nether", 10, 64, 20)))
                .isFalse();
    }

    @Test
    void singleCellAndLargestAllowedCorridorStayBounded() {
        assertThat(new BlockWorkRegion("minecraft:overworld", 1, 2, 3,
                0, 0, 0).cells()).containsExactly(cell(1, 2, 3));
        var corridor = new BlockWorkRegion("minecraft:overworld", 0, 64, 0,
                4095, 0, 0);
        assertThat(corridor.size()).isEqualTo(BlockWorkRegion.MAX_CELLS);
        assertThat(corridor.cells().getLast()).isEqualTo(cell(4095, 64, 0));
    }

    @Test
    void rejectsOversizeAndCoordinateOverflow() {
        assertThatThrownBy(() -> new BlockWorkRegion("minecraft:overworld", 0, 0, 0,
                4096, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BlockWorkRegion("minecraft:overworld", 0, 0, 0,
                64, 64, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BlockWorkRegion("minecraft:overworld",
                Integer.MAX_VALUE, 0, 0, 1, 0, 0))
                .isInstanceOf(ArithmeticException.class);
    }

    private static NavCell cell(int x, int y, int z) {
        return new NavCell("minecraft:overworld", x, y, z);
    }
}
