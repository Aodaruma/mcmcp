package dev.aod.mcmcp.fixture;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static dev.aod.mcmcp.fixture.FixtureTunnelPlan.Material.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class FixtureTunnelPlanTest {
    private static final List<FixturePhase5Mode> MODES = List.of(
            FixturePhase5Mode.TUNNEL_STRAIGHT16, FixturePhase5Mode.TUNNEL_STRAIGHT160,
            FixturePhase5Mode.TUNNEL_BRANCHES, FixturePhase5Mode.TUNNEL_HAZARD);

    @Test
    void allModesHaveClosedIndependentPlansAndAdjacentRoutes() {
        for (var mode : MODES) {
            var plan = FixtureTunnelPlan.forMode(mode);
            assertThat(plan.baseline()).hasSize(22_168);
            assertThat(plan.baseline().keySet()).allMatch(FixtureTunnelPlan::contains);
            assertThat(plan.baseline()).containsKeys(FixtureTunnelPlan.MIN, FixtureTunnelPlan.MAX);
            assertThat(plan.excavation()).allMatch(cell -> plan.baseline().get(cell) == STONE);
            assertThat(plan.baseline().get(FixtureTunnelPlan.START)).isEqualTo(AIR);
            assertThat(plan.baseline().get(FixtureTunnelPlan.START.above())).isEqualTo(AIR);
            assertThat(plan.baseline().get(FixtureTunnelPlan.START.offset(0, -1, 0))).isEqualTo(SEA_LANTERN);
            int feet = mode == FixturePhase5Mode.TUNNEL_STRAIGHT160 ? 160
                    : mode == FixturePhase5Mode.TUNNEL_BRANCHES ? 40 : 16;
            assertThat(plan.feet()).hasSize(feet).doesNotHaveDuplicates();
            assertThat(plan.excavation()).hasSize(feet * 2);
            assertThat(plan.route()).hasSize(mode == FixturePhase5Mode.TUNNEL_BRANCHES ? 64 : feet);
            var previous = FixtureTunnelPlan.START;
            for (var cell : plan.route()) {
                assertThat(Math.abs(previous.x() - cell.x()) + Math.abs(previous.z() - cell.z())).isEqualTo(1);
                assertThat(cell.y()).isEqualTo(200);
                assertThat(plan.feet()).contains(cell);
                previous = cell;
            }
            assertThat(previous).isEqualTo(FixtureTunnelPlan.START.offset(plan.length(), 0, 0));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> FixtureTunnelPlan.forMode(FixturePhase5Mode.CRAFT));
    }

    @Test
    void successRequiresAllExcavationAndNoOutsideChangesAndFinalPose() {
        for (var mode : MODES.stream().filter(mode -> mode != FixturePhase5Mode.TUNNEL_HAZARD).toList()) {
            var plan = FixtureTunnelPlan.forMode(mode);
            var world = new HashMap<>(plan.baseline());
            var initial = FixtureTunnelPlan.audit(plan, world::get, 257.5, 200, 256.5);
            assertThat(initial.baselineMatches()).isTrue();
            assertThat(initial.pass()).isFalse();
            plan.excavation().forEach(cell -> world.put(cell, AIR));
            var done = FixtureTunnelPlan.audit(plan, world::get, 257.5 + plan.length(), 200, 256.5);
            assertThat(done.pass()).isTrue();
            assertThat(done.outsideChanged()).isZero();
            assertThat(done.completedCells()).isEqualTo(plan.feet().size());
            assertThat(FixtureTunnelPlan.audit(plan, world::get, 257.5, 200, 256.5).pass()).isFalse();
            world.put(FixtureTunnelPlan.MIN, AIR);
            var escaped = FixtureTunnelPlan.audit(plan, world::get, 257.5 + plan.length(), 200, 256.5);
            assertThat(escaped.outsideChanged()).isEqualTo(1);
            assertThat(escaped.pass()).isFalse();
        }
    }

    @Test
    void missingHalfAndUnexpectedMaterialsCannotClaimCompletion() {
        var plan = FixtureTunnelPlan.forMode(FixturePhase5Mode.TUNNEL_STRAIGHT16);
        var world = new HashMap<>(plan.baseline());
        plan.excavation().forEach(cell -> world.put(cell, AIR));
        world.put(FixtureTunnelPlan.ENTRANCE, STONE);
        var partial = FixtureTunnelPlan.audit(plan, world::get, 273.5, 200, 256.5);
        assertThat(partial.partialCells()).isEqualTo(1);
        assertThat(partial.prefixCells()).isZero();
        assertThat(partial.pass()).isFalse();
        world.put(FixtureTunnelPlan.ENTRANCE, OTHER);
        assertThat(FixtureTunnelPlan.audit(plan, world::get, 273.5, 200, 256.5).invalidInsideStates()).isEqualTo(1);
        assertThat(FixtureTunnelPlan.audit(plan, world::get, Double.NaN, 200, 256.5).poseMatch()).isFalse();
    }

    @Test
    void floorGapRequiresExactStoppedPrefixAndSafePose() {
        var plan = FixtureTunnelPlan.forMode(FixturePhase5Mode.TUNNEL_HAZARD);
        for (int y = 197; y <= 199; y++)
            assertThat(plan.baseline().get(new FixtureTunnelPlan.Cell(261, y, 256))).isEqualTo(AIR);
        assertThat(plan.baseline().get(new FixtureTunnelPlan.Cell(261, 196, 256))).isEqualTo(BEDROCK);
        var world = new HashMap<>(plan.baseline());
        plan.feet().subList(0, 4).forEach(cell -> { world.put(cell, AIR); world.put(cell.above(), AIR); });
        var stopped = FixtureTunnelPlan.audit(plan, world::get, 260.5, 200, 256.5);
        assertThat(stopped.hazardPrefix()).isTrue();
        assertThat(stopped.pass()).isTrue();
        assertThat(stopped.completedCells()).isEqualTo(4);
        // The fourth column is excavated (eight breaks) but its floor gap forbids move four.
        assertThat(plan.feet().subList(0, stopped.completedCells()).size() * 2).isEqualTo(8);
        assertThat(plan.route().get(2)).isEqualTo(new FixtureTunnelPlan.Cell(260, 200, 256));
        assertThat(plan.route().get(3)).isEqualTo(FixtureTunnelPlan.GAP);
        assertThat(plan.baseline().get(plan.route().get(2).offset(0, -1, 0))).isEqualTo(SEA_LANTERN);
        assertThat(FixtureTunnelPlan.audit(plan, world::get, 261.5, 197, 256.5).pass()).isFalse();
        world.put(plan.feet().get(4).above(), AIR);
        assertThat(FixtureTunnelPlan.audit(plan, world::get, 260.5, 200, 256.5).pass()).isFalse();
    }
}
