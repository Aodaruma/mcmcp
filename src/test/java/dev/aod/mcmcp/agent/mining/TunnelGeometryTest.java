package dev.aod.mcmcp.agent.mining;

import dev.aod.mcmcp.agent.dsl.ActionDsl;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class TunnelGeometryTest {
    private static final ActionDsl.Position ENTRANCE = new ActionDsl.Position("minecraft:overworld", 15, 64, -3);

    @Test void oneToTenChunksAreDistanceNotWorldChunkAlignment() {
        for (int length : new int[] { 1, 16, 160 }) {
            var plan = TunnelGeometry.plan(ENTRANCE, ActionDsl.BlockFace.WEST, length, false, 0, 0);
            assertThat(plan.startFeet()).isEqualTo(cell(14, -3));
            assertThat(plan.route()).hasSize(length);
            assertThat(plan.route().getLast()).isEqualTo(cell(14 + length, -3));
            assertThat(plan.maxBreaks()).isEqualTo(2 * length);
            assertThat(plan.travelBlocks()).isEqualTo(length);
        }
    }

    @Test void allHorizontalFacesAdvanceInwardAndKeepFloorAndCeilingOutsideScope() {
        for (var face : List.of(ActionDsl.BlockFace.NORTH, ActionDsl.BlockFace.SOUTH,
                ActionDsl.BlockFace.WEST, ActionDsl.BlockFace.EAST)) {
            var plan = TunnelGeometry.plan(ENTRANCE, face, 160, false, 0, 0);
            assertThat(plan.route().getFirst().position()).isEqualTo(ENTRANCE);
            assertThat(plan.containsBlock(plan.startFeet())).isFalse();
            for (var feet : plan.route()) {
                assertThat(plan.containsBlock(feet)).isTrue();
                assertThat(plan.containsBlock(feet.head())).isTrue();
                assertThat(plan.containsBlock(feet.offset(0, -1, 0))).isFalse();
                assertThat(plan.containsBlock(feet.offset(0, 2, 0))).isFalse();
            }
        }
    }

    @Test void branchesReturnToMainInDeclaredLeftThenRightOrder() {
        var entrance = new ActionDsl.Position("minecraft:overworld", 1, 64, 0);
        var plan = TunnelGeometry.plan(entrance, ActionDsl.BlockFace.WEST, 4, true, 2, 3);
        assertThat(plan.route()).containsExactly(cell(1, 0), cell(2, 0), cell(3, 0),
                cell(3, -1), cell(3, -2), cell(3, -1), cell(3, 0),
                cell(3, 1), cell(3, 2), cell(3, 1), cell(3, 0), cell(4, 0));
        assertThat(plan.excavationCells()).hasSize(8);
        assertThat(plan.travelBlocks()).isEqualTo(12);
        assertThat(plan.maxBreaks()).isEqualTo(16);
    }

    @Test void largestBranchGeometryStaysFiniteAndDisjoint() {
        var plan = TunnelGeometry.plan(ENTRANCE, ActionDsl.BlockFace.NORTH, 160, true, 7, 3);
        assertThat(plan.excavationCells()).hasSize(902);
        assertThat(plan.route()).hasSize(1644);
        assertThat(plan.maxBreaks()).isEqualTo(1804);
        assertThat(plan.route()).allSatisfy(cell -> assertThat(cell.y()).isEqualTo(64));
        assertThatThrownBy(() -> plan.route().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> plan.excavationCells().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void invalidGeometryIsRejectedBeforeAnAdapterCanReadWorldData() {
        for (int length : new int[] { 0, 161, Integer.MAX_VALUE }) {
            assertThatIllegalArgumentException().isThrownBy(() ->
                    TunnelGeometry.plan(ENTRANCE, ActionDsl.BlockFace.WEST, length, false, 0, 0));
        }
        assertThatIllegalArgumentException().isThrownBy(() ->
                TunnelGeometry.plan(ENTRANCE, ActionDsl.BlockFace.UP, 1, false, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() ->
                TunnelGeometry.plan(ENTRANCE, ActionDsl.BlockFace.WEST, 16, false, 1, 3));
        assertThatIllegalArgumentException().isThrownBy(() ->
                TunnelGeometry.plan(ENTRANCE, ActionDsl.BlockFace.WEST, 16, true, 8, 3));
        assertThatIllegalArgumentException().isThrownBy(() ->
                TunnelGeometry.plan(ENTRANCE, ActionDsl.BlockFace.WEST, 16, true, 2, 2));
        assertThatIllegalArgumentException().isThrownBy(() -> TunnelGeometry.plan(
                new ActionDsl.Position("minecraft:overworld", 30_000_000, 64, 0),
                ActionDsl.BlockFace.WEST, 16, false, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> TunnelGeometry.plan(
                new ActionDsl.Position("minecraft:overworld", 0, 2048, 0),
                ActionDsl.BlockFace.WEST, 1, false, 0, 0));
    }

    @Test void manuallyConstructedPlanCannotSkipCellsOrEnlargeItsFootprint() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new TunnelGeometry.Plan(cell(0, 0), List.of(cell(2, 0)), Set.of(cell(2, 0))));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new TunnelGeometry.Plan(cell(0, 0), List.of(cell(1, 0)), Set.of(cell(1, 0), cell(2, 0))));
        assertThatIllegalArgumentException().isThrownBy(() -> new TunnelGeometry.Plan(cell(0, 0),
                List.of(cell(1, 0), cell(0, 0)), Set.of(cell(1, 0), cell(0, 0))));
    }

    private static TunnelGeometry.Cell cell(int x, int z) {
        return new TunnelGeometry.Cell("minecraft:overworld", x, 64, z);
    }
}
