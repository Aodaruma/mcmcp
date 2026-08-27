package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.dsl.ActionDslCompiler;
import dev.aod.mcmcp.agent.navigation.DeterministicAStar;
import dev.aod.mcmcp.agent.navigation.KnownTraversabilityMap;
import dev.aod.mcmcp.agent.navigation.NavCell;
import dev.aod.mcmcp.agent.navigation.RoutePlan;
import dev.aod.mcmcp.agent.navigation.TraversabilityEdge;
import dev.aod.mcmcp.agent.observation.ObservationFrame;
import dev.aod.mcmcp.agent.observation.ObservationRecord;
import dev.aod.mcmcp.agent.observation.ObservationValues;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentPrimitivePlannerTest {
    private static final String DIMENSION = "minecraft:overworld";

    @Test
    void expiredWorkerDeadlineStopsPlanningAsTimeout() {
        UUID session = UUID.randomUUID();
        NavCell start = cell(0);
        var program = new ActionDsl.Program(
                1,
                Optional.empty(),
                Set.of(),
                List.of(new ActionDsl.WaitTicks("hold", 1)));

        assertThatThrownBy(() -> AgentPrimitivePlanner.analyze(
                        program,
                        map(session).snapshot().orElseThrow(),
                        new DeterministicAStar(),
                        new AgentPrimitivePlanner.Pose(start, 0.5, 64, 0.5, 1.62, 0, 0),
                        Optional.empty(),
                        4.5F,
                        () -> false))
                .isInstanceOf(AgentPrimitivePlanner.PlanningException.class)
                .extracting(failure -> ((AgentPrimitivePlanner.PlanningException) failure).code())
                .isEqualTo(AgentPrimitivePlanner.Code.TIMEOUT);
    }

    @Test
    void recordsEveryNavigationAndFaceTargetForCommitRevalidation() {
        UUID session = UUID.randomUUID();
        NavCell start = cell(0);
        NavCell navigationTarget = cell(1);
        NavCell faceTarget = cell(2);
        var map = map(session);
        map.observe(confirmed(session, start, navigationTarget));
        map.observe(confirmed(session, navigationTarget, faceTarget));
        var program = new ActionDsl.Program(
                1,
                Optional.empty(),
                Set.of(ActionDsl.Capability.MOVEMENT, ActionDsl.Capability.CAMERA),
                List.of(navigate("move", navigationTarget), face("look", faceTarget)));

        var analysis = AgentPrimitivePlanner.analyze(
                program,
                map.snapshot().orElseThrow(),
                new DeterministicAStar(),
                new AgentPrimitivePlanner.Pose(start, 0.5, 64, 0.5, 1.62, 0, 0),
                Optional.empty(),
                4.5F);

        assertThat(analysis.knownTargets()).containsExactlyInAnyOrder(
                position(navigationTarget), position(faceTarget));
    }

    @Test
    void admitsFourExplicitCardinalLooksUsingTheirCumulativeCameraCost() {
        UUID session = UUID.randomUUID();
        NavCell center = cell(0);
        var map = map(session);
        var targets = List.of(cell(1), new NavCell(DIMENSION, 0, 64, 1),
                cell(-1), new NavCell(DIMENSION, 0, 64, -1));
        targets.forEach(target -> map.observe(confirmed(session, center, target)));
        var nodes = List.<ActionDsl.Node>of(
                face("east", targets.get(0)),
                face("south", targets.get(1)),
                face("west", targets.get(2)),
                face("north", targets.get(3)));
        var program = new ActionDsl.Program(
                1, Optional.empty(), Set.of(ActionDsl.Capability.CAMERA), nodes);
        var request = new ActionDsl.Request(
                1,
                program,
                new ActionDsl.Budget(30_000, 600, 0, 360, 0, 0, 0));
        var snapshot = map.snapshot().orElseThrow();

        var analysis = AgentPrimitivePlanner.analyze(
                program,
                snapshot,
                new DeterministicAStar(),
                new AgentPrimitivePlanner.Pose(center, 0.5, 64, 0.5, 0.5, -90, 0),
                Optional.empty(),
                4.5F);
        var compiled = ActionDslCompiler.compile(
                request, analysis::worstCase, Set.of(ActionDsl.Capability.CAMERA));

        assertThat(compiled.worstCaseCost().cameraDegrees()).isEqualTo(274.5D);
        assertThat(compiled.worstCaseCost().ticks()).isEqualTo(64L);
    }

    @Test
    void navigationCostUsesThePriorPrimitiveDestination() {
        UUID session = UUID.randomUUID();
        var map = map(session);
        for (int x = -2; x < 2; x++) {
            NavCell left = cell(x);
            NavCell right = cell(x + 1);
            map.observe(confirmed(session, left, right));
            map.observe(confirmed(session, right, left));
        }
        var nodes = List.<ActionDsl.Node>of(
                navigate("left", cell(-2)),
                navigate("right", cell(2)));
        var program = new ActionDsl.Program(
                1, Optional.empty(), Set.of(ActionDsl.Capability.MOVEMENT), nodes);
        var request = new ActionDsl.Request(
                1,
                program,
                new ActionDsl.Budget(30_000, 600, 16, 0, 0, 0, 0));
        var snapshot = map.snapshot().orElseThrow();

        var analysis = AgentPrimitivePlanner.analyze(
                program,
                snapshot,
                new DeterministicAStar(),
                new AgentPrimitivePlanner.Pose(cell(0), 0.5, 64, 0.5, 1.62, 0, 0),
                Optional.empty(),
                4.5F);
        var compiled = ActionDslCompiler.compile(
                request, analysis::worstCase, Set.of(ActionDsl.Capability.MOVEMENT));

        assertThat(analysis.primitiveCosts().get("left").distanceBlocks()).isEqualTo(3.0D);
        assertThat(analysis.primitiveCosts().get("right").distanceBlocks())
                .isEqualTo(1.5D * (3.0D + Math.hypot(
                        1.0D + Math.sqrt(0.5D), 0.75D)));
        assertThat(compiled.worstCaseCost().distanceBlocks())
                .isEqualTo(1.5D * (5.0D + Math.hypot(
                        1.0D + Math.sqrt(0.5D), 0.75D)));
    }

    @Test
    void navigationCostIncludesTheRealOffsetToTheStartingCellCenter() {
        UUID session = UUID.randomUUID();
        var map = map(session);
        NavCell start = cell(0);
        NavCell target = cell(1);
        map.observe(confirmed(session, start, target));
        RoutePlan route = new DeterministicAStar()
                .findRoute(map.snapshot().orElseThrow(), start, target)
                .route().orElseThrow();

        var cost = AgentPrimitivePlanner.navigationCost(
                route,
                new AgentPrimitivePlanner.Pose(start, 0.01, 64, 0.5, 1.62, 0, 0));

        assertThat(cost.distanceBlocks()).isEqualTo(2.235D);
    }

    @Test
    void breakRequiresTheExactCurrentVisibleFaceAndAccountsForAimAndOneBreak() {
        UUID session = UUID.randomUUID();
        var map = map(session).snapshot().orElseThrow();
        var target = new ActionDsl.Position(DIMENSION, 3, 65, 0);
        var block = new ActionDsl.BreakKnownFace(
                "chop", target, ActionDsl.BlockFace.WEST,
                "minecraft:oak_log", "minecraft:iron_axe");
        var program = new ActionDsl.Program(
                1, Optional.empty(),
                Set.of(ActionDsl.Capability.CAMERA, ActionDsl.Capability.BLOCK_BREAK),
                List.of(block));
        var frame = frame(target, ObservationRecord.Face.WEST, "minecraft:oak_log", 0);

        var analysis = AgentPrimitivePlanner.analyze(
                program, map, new DeterministicAStar(),
                new AgentPrimitivePlanner.Pose(cell(0), 0.5, 64, 0.5, 1.62, 0, 0),
                Optional.of(frame), 4.5F);

        assertThat(analysis.knownSurfaces()).containsExactly(new AgentPrimitivePlanner.KnownSurface(
                target, ActionDsl.BlockFace.WEST, "minecraft:oak_log"));
        assertThat(analysis.primitiveCosts().get("chop").blocksBroken()).isOne();
        assertThat(analysis.primitiveCosts().get("chop").ticks())
                .isGreaterThan(AgentPrimitivePlanner.BREAK_TICK_UPPER_BOUND
                        + AgentPrimitivePlanner.BREAK_REOBSERVATION_TICKS);
        assertThatThrownBy(() -> AgentPrimitivePlanner.requireKnownBreakSurface(
                map,
                Optional.of(frame(target, ObservationRecord.Face.EAST, "minecraft:oak_log", 0)),
                block))
                .isInstanceOf(AgentPrimitivePlanner.PlanningException.class);
    }

    @Test
    void wheatMutationCostsUseTheSelectedSurfaceAngleAndPreferUpForTilling() {
        UUID session = UUID.randomUUID();
        var map = map(session).snapshot().orElseThrow();
        var target = new ActionDsl.Position(DIMENSION, 3, 64, 0);
        var till = new ActionDsl.TillKnownBlock(
                "till", target, "minecraft:dirt", "minecraft:iron_hoe");
        var program = new ActionDsl.Program(
                1, Optional.empty(),
                Set.of(ActionDsl.Capability.CAMERA, ActionDsl.Capability.BLOCK_INTERACT),
                List.of(till));

        var analysis = AgentPrimitivePlanner.analyze(
                program, map, new DeterministicAStar(),
                new AgentPrimitivePlanner.Pose(cell(0), 0.5, 64, 0.5, 1.62, 0, 0),
                Optional.of(frame(List.of(
                        surface(target, ObservationRecord.Face.NORTH, "minecraft:dirt", null, 0),
                        surface(target, ObservationRecord.Face.UP, "minecraft:dirt", null, 0)))),
                4.5F);

        assertThat(analysis.knownSurfaces()).containsExactly(
                new AgentPrimitivePlanner.KnownSurface(
                        target, ActionDsl.BlockFace.UP, "minecraft:dirt"));
        assertThat(analysis.primitiveCosts().get("till").interactions()).isOne();
        assertThat(analysis.primitiveCosts().get("till").blocksBroken()).isZero();
        assertThat(analysis.mutationAims().get("till").face())
                .isEqualTo(ActionDsl.BlockFace.UP);
        double camera = analysis.primitiveCosts().get("till").cameraDegrees();
        assertThat(camera).isLessThan(360.0D);
        assertThat(analysis.primitiveCosts().get("till").ticks())
                .isEqualTo(AgentPrimitivePlanner.BLOCK_MUTATION_TICK_UPPER_BOUND
                        + (long) Math.ceil(camera / 4.5D));
        assertThatThrownBy(() -> AgentPrimitivePlanner.analyze(
                program, map, new DeterministicAStar(),
                new AgentPrimitivePlanner.Pose(cell(0), 0.5, 64, 0.5, 1.62, 0, 0),
                Optional.of(frame(target, ObservationRecord.Face.UP, "minecraft:stone", 0)),
                4.5F)).isInstanceOf(AgentPrimitivePlanner.PlanningException.class);
        assertThatThrownBy(() -> AgentPrimitivePlanner.analyze(
                program, map, new DeterministicAStar(),
                new AgentPrimitivePlanner.Pose(cell(0), 0.5, 64, 0.5, 1.62, 0, 0),
                Optional.of(frame(target, ObservationRecord.Face.DOWN, "minecraft:dirt", 0)),
                4.5F)).isInstanceOf(AgentPrimitivePlanner.PlanningException.class);
    }

    @Test
    void openFenceGateBindsOneVisibleOakGateRayWitness() {
        UUID session = UUID.randomUUID();
        var map = map(session).snapshot().orElseThrow();
        var target = new ActionDsl.Position(DIMENSION, 3, 64, 0);
        var program = new ActionDsl.Program(
                1, Optional.empty(),
                Set.of(ActionDsl.Capability.CAMERA, ActionDsl.Capability.BLOCK_INTERACT),
                List.of(new ActionDsl.OpenKnownFenceGate("open_gate", target)));

        var analysis = AgentPrimitivePlanner.analyze(
                program, map, new DeterministicAStar(),
                new AgentPrimitivePlanner.Pose(cell(0), 0.5, 64, 0.5, 1.62, 0, 0),
                Optional.of(frame(
                        target, ObservationRecord.Face.SOUTH, "minecraft:oak_fence_gate", 0)),
                4.5F);

        assertThat(analysis.primitiveCosts().get("open_gate").interactions()).isOne();
        assertThat(analysis.mutationAims().get("open_gate"))
                .extracting(
                        AgentPrimitivePlanner.MutationAim::block,
                        AgentPrimitivePlanner.MutationAim::face,
                        AgentPrimitivePlanner.MutationAim::point)
                .containsExactly(
                        target,
                        ActionDsl.BlockFace.SOUTH,
                        new Vec3(3.5D, 64.5D, 1.0D));
        assertThatThrownBy(() -> AgentPrimitivePlanner.analyze(
                program, map, new DeterministicAStar(),
                new AgentPrimitivePlanner.Pose(cell(0), 0.5, 64, 0.5, 1.62, 0, 0),
                Optional.of(frame(
                        target, ObservationRecord.Face.SOUTH, "minecraft:spruce_fence_gate", 0)),
                4.5F)).isInstanceOf(AgentPrimitivePlanner.PlanningException.class);
    }

    @Test
    void plantBindsSupportUpWhileHarvestRequiresMatureCropEvidence() {
        UUID session = UUID.randomUUID();
        var map = map(session).snapshot().orElseThrow();
        var support = new ActionDsl.Position(DIMENSION, 3, 64, 0);
        var crop = new ActionDsl.Position(DIMENSION, 3, 65, 0);
        var plant = new ActionDsl.PlantKnownWheat(
                "plant", crop, support, "minecraft:wheat_seeds");
        var harvest = new ActionDsl.HarvestKnownWheat("harvest", crop);
        var program = new ActionDsl.Program(
                1, Optional.empty(), Set.of(
                        ActionDsl.Capability.CAMERA,
                        ActionDsl.Capability.BLOCK_PLACE,
                        ActionDsl.Capability.BLOCK_BREAK),
                List.of(plant, harvest));
        var pose = new AgentPrimitivePlanner.Pose(
                cell(0), 0.5, 64, 0.5, 1.62, 0, 0);

        var analysis = AgentPrimitivePlanner.analyze(
                program, map, new DeterministicAStar(), pose,
                Optional.of(frame(List.of(
                        surface(support, ObservationRecord.Face.NORTH,
                                "minecraft:farmland", null, 0),
                        surface(support, ObservationRecord.Face.UP,
                                "minecraft:farmland", null, 0),
                        surface(crop, ObservationRecord.Face.WEST,
                                "minecraft:wheat", true, 0)))),
                4.5F);

        assertThat(analysis.mutationAims().get("plant"))
                .extracting(
                        AgentPrimitivePlanner.MutationAim::block,
                        AgentPrimitivePlanner.MutationAim::face,
                        AgentPrimitivePlanner.MutationAim::point)
                .containsExactly(
                        support,
                        ActionDsl.BlockFace.UP,
                        new Vec3(3.5D, 64.9375D, 0.5D));
        assertThat(analysis.knownSurfaces()).contains(
                new AgentPrimitivePlanner.KnownSurface(
                        crop, ActionDsl.BlockFace.WEST, "minecraft:wheat", true));
        assertThatThrownBy(() -> AgentPrimitivePlanner.analyze(
                new ActionDsl.Program(
                        1, Optional.empty(), program.capabilities(), List.of(harvest)),
                map, new DeterministicAStar(), pose,
                Optional.of(frame(List.of(surface(
                        crop, ObservationRecord.Face.WEST, "minecraft:wheat", false, 0)))),
                4.5F)).isInstanceOf(AgentPrimitivePlanner.PlanningException.class);
    }

    @Test
    void mutationRejectsAStaleEyeOriginAndAnOutOfReachRayHit() {
        UUID session = UUID.randomUUID();
        var map = map(session).snapshot().orElseThrow();
        var target = new ActionDsl.Position(DIMENSION, 3, 64, 0);
        var program = new ActionDsl.Program(
                1, Optional.empty(),
                Set.of(ActionDsl.Capability.CAMERA, ActionDsl.Capability.BLOCK_INTERACT),
                List.of(new ActionDsl.TillKnownBlock(
                        "till", target, "minecraft:dirt", "minecraft:iron_hoe")));

        assertThatThrownBy(() -> AgentPrimitivePlanner.analyze(
                program, map, new DeterministicAStar(),
                new AgentPrimitivePlanner.Pose(cell(1), 1.5D, 64, 0.5D, 1.62D, 0, 0),
                Optional.of(frame(target, ObservationRecord.Face.UP, "minecraft:dirt", 0)),
                4.5F))
                .isInstanceOf(AgentPrimitivePlanner.PlanningException.class)
                .extracting(failure -> ((AgentPrimitivePlanner.PlanningException) failure).code())
                .isEqualTo(AgentPrimitivePlanner.Code.TARGET_UNKNOWN);

        var distant = new ActionDsl.Position(DIMENSION, 6, 64, 0);
        var distantProgram = new ActionDsl.Program(
                1, Optional.empty(), program.capabilities(),
                List.of(new ActionDsl.TillKnownBlock(
                        "far", distant, "minecraft:dirt", "minecraft:iron_hoe")));
        assertThatThrownBy(() -> AgentPrimitivePlanner.analyze(
                distantProgram, map, new DeterministicAStar(),
                new AgentPrimitivePlanner.Pose(cell(0), 0.5D, 64, 0.5D, 1.62D, 0, 0),
                Optional.of(frame(distant, ObservationRecord.Face.UP, "minecraft:dirt", 0)),
                4.5F))
                .isInstanceOf(AgentPrimitivePlanner.PlanningException.class)
                .extracting(failure -> ((AgentPrimitivePlanner.PlanningException) failure).code())
                .isEqualTo(AgentPrimitivePlanner.Code.TARGET_UNKNOWN);
    }

    @Test
    void replanCostUsesOnlyTheRemainingDistanceToTheFirstWaypoint() {
        UUID session = UUID.randomUUID();
        var map = map(session);
        NavCell start = cell(0);
        NavCell target = cell(1);
        map.observe(confirmed(session, start, target));
        RoutePlan route = new DeterministicAStar()
                .findRoute(map.snapshot().orElseThrow(), start, target)
                .route().orElseThrow();

        var cost = AgentPrimitivePlanner.navigationCost(
                route,
                new AgentPrimitivePlanner.Pose(start, 0.6D, 64, 0.5D, 1.62D, 0, 0));

        assertThat(cost.distanceBlocks()).isEqualTo(1.35D);
    }

    @Test
    void navigationReplanDoesNotChargeReservedProbeTimeTwice() {
        UUID session = UUID.randomUUID();
        var map = map(session);
        NavCell start = cell(0);
        NavCell target = cell(1);
        map.observe(probeAllowed(session, start, target));
        RoutePlan route = new DeterministicAStar()
                .findRoute(map.snapshot().orElseThrow(), start, target)
                .route().orElseThrow();
        var planned = AgentPrimitivePlanner.navigationCost(
                route,
                new AgentPrimitivePlanner.Pose(start, 0.5D, 64, 0.5D, 1.62D, 0, 0));

        var retry = AgentPrimitivePlanner.navigationReplanCost(route, planned);

        assertThat(retry.ticks()).isEqualTo(
                planned.ticks() - RoutePlan.EXTRA_TICKS_PER_PROBE);
        assertThat(retry.durationMillis()).isEqualTo(
                planned.durationMillis() - RoutePlan.EXTRA_TICKS_PER_PROBE * 50L);
        assertThat(retry.distanceBlocks()).isEqualTo(planned.distanceBlocks());
    }

    private static ActionDsl.FaceKnownPosition face(String id, NavCell target) {
        return new ActionDsl.FaceKnownPosition(id, position(target));
    }

    private static ActionDsl.NavigateToKnown navigate(String id, NavCell target) {
        return new ActionDsl.NavigateToKnown(id, position(target), 0.75D);
    }

    private static ActionDsl.Position position(NavCell cell) {
        return new ActionDsl.Position(cell.dimension(), cell.x(), cell.y(), cell.z());
    }

    private static KnownTraversabilityMap map(UUID session) {
        var map = new KnownTraversabilityMap();
        map.startSession(session, DIMENSION, 0);
        return map;
    }

    private static NavCell cell(int x) {
        return new NavCell(DIMENSION, x, 64, 0);
    }

    private static TraversabilityEdge confirmed(UUID session, NavCell from, NavCell to) {
        return new TraversabilityEdge(
                session,
                new TraversabilityEdge.Key(from, to),
                TraversabilityEdge.Status.CONFIRMED,
                TraversabilityEdge.TargetSupport.CONFIRMED,
                TraversabilityEdge.Clearance.CONFIRMED,
                TraversabilityEdge.Transition.CONFIRMED,
                TraversabilityEdge.Fluid.NONE,
                TraversabilityEdge.Hazard.NONE,
                TraversabilityEdge.Provenance.LOCAL_VOLUME,
                from,
                1,
                0);
    }

    private static TraversabilityEdge probeAllowed(UUID session, NavCell from, NavCell to) {
        return new TraversabilityEdge(
                session,
                new TraversabilityEdge.Key(from, to),
                TraversabilityEdge.Status.PROBE_ALLOWED,
                TraversabilityEdge.TargetSupport.CONFIRMED,
                TraversabilityEdge.Clearance.CONFIRMED,
                TraversabilityEdge.Transition.PARTIAL,
                TraversabilityEdge.Fluid.NONE,
                TraversabilityEdge.Hazard.NONE,
                TraversabilityEdge.Provenance.LOCAL_VOLUME,
                from,
                1,
                0);
    }

    private static ObservationFrame frame(
            ActionDsl.Position target,
            ObservationRecord.Face face,
            String block,
            long revision) {
        var dimension = new ObservationValues.ResourceId(target.dimension());
        return frame(List.of(surface(target, face, block, null, revision)));
    }

    private static ObservationRecord.VisibleSurface surface(
            ActionDsl.Position target,
            ObservationRecord.Face face,
            String block,
            Boolean cropMature,
            long revision) {
        var dimension = new ObservationValues.ResourceId(target.dimension());
        var eye = new ObservationValues.WorldPosition(dimension, 0.5, 65.62, 0.5);
        Vec3 hit = rayHit(target, face, block);
        return new ObservationRecord.VisibleSurface(
                new ObservationValues.BlockPosition(
                        dimension, target.x(), target.y(), target.z()),
                face,
                new ObservationValues.ResourceId(block),
                ObservationRecord.ShapeClass.OPAQUE,
                cropMature,
                new ObservationValues.WorldPosition(
                        dimension, hit.x, hit.y, hit.z),
                eye,
                1,
                revision);
    }

    private static Vec3 rayHit(
            ActionDsl.Position target, ObservationRecord.Face face, String block) {
        double x = target.x() + 0.5D;
        double y = target.y() + 0.5D;
        double z = target.z() + 0.5D;
        if ("minecraft:farmland".equals(block) && face == ObservationRecord.Face.UP) {
            y = target.y() + 0.9375D;
        } else {
            switch (face) {
                case DOWN -> y = target.y();
                case UP -> y = target.y() + 1.0D;
                case NORTH -> z = target.z();
                case SOUTH -> z = target.z() + 1.0D;
                case WEST -> x = target.x();
                case EAST -> x = target.x() + 1.0D;
            }
        }
        return new Vec3(x, y, z);
    }

    private static ObservationFrame frame(
            List<ObservationRecord.VisibleSurface> surfaces) {
        var dimension = new ObservationValues.ResourceId(DIMENSION);
        return new ObservationFrame(
                "obs-0000000000000001", dimension, 1, 16, false,
                new java.util.ArrayList<ObservationRecord>(surfaces));
    }
}
