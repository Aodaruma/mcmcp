package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.agent.action.AgentConstructionPlanner.PlacementApproachRequirement;
import dev.aod.mcmcp.agent.action.AgentPlannerGeometry.Aim;
import dev.aod.mcmcp.agent.action.AgentPlannerGeometry.AimError;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.Analysis;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.ApproachPlan;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.Code;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.KnownSurface;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.MutationAim;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.MutationBatchPlan;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.PickupPlan;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.PlanningException;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.Pose;
import dev.aod.mcmcp.agent.action.AgentSurfaceEvidence.MutationSurface;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.dsl.ActionDslCompiler;
import dev.aod.mcmcp.agent.dsl.ActionDslException;
import dev.aod.mcmcp.agent.navigation.DeterministicAStar;
import dev.aod.mcmcp.agent.navigation.KnownTraversabilitySnapshot;
import dev.aod.mcmcp.agent.navigation.NavCell;
import dev.aod.mcmcp.agent.navigation.RoutePlan;
import dev.aod.mcmcp.agent.navigation.TraversabilityEdge;
import dev.aod.mcmcp.agent.observation.ObservationFrame;
import dev.aod.mcmcp.agent.observation.ObservationRecord;
import dev.aod.mcmcp.agent.observation.PlacementStateResolver;
import dev.aod.mcmcp.routine.KnownBrewingRequest;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.ToLongFunction;

import static dev.aod.mcmcp.agent.action.AgentNavigationPlanner.APPROACH_TOLERANCE;
import static dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.BREWING_TICK_UPPER_BOUND;

/** DSLの記述順に分岐・反復と姿勢を解析し、全体の有限作業量を管理する。 */
final class AgentProgramPlanner {
    private AgentProgramPlanner() {
    }

    private static final int MAX_ABSTRACT_POSES = 4_096;

    private static final int MAX_TOTAL_ROUTE_EXPANSIONS = 32_768;

    private static final int MAX_POSE_TRANSITIONS = 16_384;

    private static final double MAX_FISHING_AIM_BLOCKS = 8.0D;

    /** Runtime admission variant with session-scoped observed placement-state memory. */
    static Analysis analyze(
            ActionDsl.Program program,
            KnownTraversabilitySnapshot map,
            DeterministicAStar pathfinder,
            Pose initialPose,
            Optional<ObservationFrame> latestFrame,
            float maxCameraDegreesPerTick,
            long visualBarrierWorldRevision,
            ToLongFunction<ActionDsl.Position> surfaceRevisionBarrier,
            BooleanSupplier canContinue,
            PlacementStateResolver placementStates) {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(map, "map");
        Objects.requireNonNull(pathfinder, "pathfinder");
        Objects.requireNonNull(initialPose, "initialPose");
        Objects.requireNonNull(latestFrame, "latestFrame");
        Objects.requireNonNull(surfaceRevisionBarrier, "surfaceRevisionBarrier");
        Objects.requireNonNull(canContinue, "canContinue");
        Objects.requireNonNull(placementStates, "placementStates");
        AgentSurfaceEvidence.requireVisualBarrierWorldRevision(
                map, map.worldRevision(), visualBarrierWorldRevision);
        if (!map.dimension().equals(initialPose.cell().dimension())) {
            throw new PlanningException(Code.NO_KNOWN_PATH, "Player is outside the current map boundary");
        }
        if (!Float.isFinite(maxCameraDegreesPerTick)
                || maxCameraDegreesPerTick <= 0.0F
                || maxCameraDegreesPerTick
                        > MinecraftActionPrimitiveExecutor.MAX_ALLOWED_CAMERA_DEGREES_PER_TICK) {
            throw new IllegalArgumentException("camera limit is outside the executor range");
        }

        var costs = new LinkedHashMap<String, ActionDslCompiler.Cost>();
        var routeDependencies = new LinkedHashMap<TraversabilityEdge.Key, TraversabilityEdge>();
        var knownTargets = new LinkedHashSet<ActionDsl.Position>();
        var knownFacingSurfaces = new LinkedHashSet<KnownSurface>();
        var knownSurfaces = new LinkedHashSet<KnownSurface>();
        var mutationAims = new LinkedHashMap<String, MutationAim>();
        var mutationBatchPlans = new LinkedHashMap<String, MutationBatchPlan>();
        var routeCache = new LinkedHashMap<RouteKey, RoutePlan>();
        var work = new PlanningWork(canContinue);
        Set<String> waitsBackedByPriorPlant = waitsBackedByPriorPlant(program.body());
        analyzeSequence(
                program.body(),
                List.of(initialPose),
                map,
                pathfinder,
                latestFrame,
                visualBarrierWorldRevision,
                surfaceRevisionBarrier,
                maxCameraDegreesPerTick,
                costs,
                routeDependencies,
                knownTargets,
                knownFacingSurfaces,
                knownSurfaces,
                mutationAims,
                mutationBatchPlans,
                routeCache,
                waitsBackedByPriorPlant,
                placementStates,
                work);
        return new Analysis(
                costs, routeDependencies, knownTargets, knownFacingSurfaces, knownSurfaces,
                mutationAims, mutationBatchPlans);
    }

    /**
     * Proves only the static control dependency needed to admit a closed
     * plant -> wait program before the crop exists. Runtime JIT admission still
     * requires newly visible wheat when the wait occurrence actually begins.
     */
    private static Set<String> waitsBackedByPriorPlant(List<ActionDsl.Node> nodes) {
        var waits = new LinkedHashSet<String>();
        guaranteedWheatAfter(nodes, Set.of(), waits);
        return Set.copyOf(waits);
    }

    private static Set<ActionDsl.Position> guaranteedWheatAfter(
            List<ActionDsl.Node> nodes,
            Set<ActionDsl.Position> input,
            Set<String> waitsBackedByPriorPlant) {
        var present = new LinkedHashSet<>(input);
        for (ActionDsl.Node node : nodes) {
            if (node instanceof ActionDsl.PlantKnownWheat plant) {
                present.add(plant.target());
            } else if (node instanceof ActionDsl.PlantKnownWheatBatch batch) {
                batch.targets().stream().map(ActionDsl.PlantPlot::target).forEach(present::add);
            } else if (node instanceof ActionDsl.HarvestKnownWheat harvest) {
                present.remove(harvest.target());
            } else if (node instanceof ActionDsl.HarvestKnownWheatBatch batch) {
                present.removeAll(batch.targets());
            } else if (node instanceof ActionDsl.WaitUntil wait) {
                if (wait.condition() instanceof ActionDsl.CropMatureCondition crop
                        && present.contains(crop.target())) {
                    waitsBackedByPriorPlant.add(wait.id());
                }
            } else if (node instanceof ActionDsl.If conditional) {
                Set<ActionDsl.Position> thenPresent = guaranteedWheatAfter(
                        conditional.thenBranch(), present, waitsBackedByPriorPlant);
                Set<ActionDsl.Position> elsePresent = guaranteedWheatAfter(
                        conditional.elseBranch(), present, waitsBackedByPriorPlant);
                present = new LinkedHashSet<>(thenPresent);
                present.retainAll(elsePresent);
            } else if (node instanceof ActionDsl.Repeat repeat) {
                // Eligibility inside a repeat is based on its first occurrence only;
                // later iterations must not retroactively authorize that same node ID.
                present = new LinkedHashSet<>(guaranteedWheatAfter(
                        repeat.body(), present, waitsBackedByPriorPlant));
                for (int count = 1; count < repeat.count(); count++) {
                    present = new LinkedHashSet<>(guaranteedWheatAfter(
                            repeat.body(), present, new LinkedHashSet<>()));
                }
            }
        }
        return Set.copyOf(present);
    }

    private static List<Pose> analyzeSequence(
            List<ActionDsl.Node> nodes,
            List<Pose> input,
            KnownTraversabilitySnapshot map,
            DeterministicAStar pathfinder,
            Optional<ObservationFrame> latestFrame,
            long visualBarrierWorldRevision,
            ToLongFunction<ActionDsl.Position> surfaceRevisionBarrier,
            float cameraLimit,
            Map<String, ActionDslCompiler.Cost> costs,
            Map<TraversabilityEdge.Key, TraversabilityEdge> routeDependencies,
            Set<ActionDsl.Position> knownTargets,
            Set<KnownSurface> knownFacingSurfaces,
            Set<KnownSurface> knownSurfaces,
            Map<String, MutationAim> mutationAims,
            Map<String, MutationBatchPlan> mutationBatchPlans,
            Map<RouteKey, RoutePlan> routeCache,
            Set<String> waitsBackedByPriorPlant,
            PlacementStateResolver placementStates,
            PlanningWork work) {
        List<Pose> states = input;
        for (ActionDsl.Node node : nodes) {
            work.check();
            states = analyzeNode(
                    node, states, map, pathfinder, latestFrame,
                    visualBarrierWorldRevision, surfaceRevisionBarrier, cameraLimit,
                    costs, routeDependencies, knownTargets, knownFacingSurfaces, knownSurfaces,
                    mutationAims, mutationBatchPlans, routeCache,
                    waitsBackedByPriorPlant, placementStates, work);
        }
        return states;
    }

    private static List<Pose> analyzeNode(
            ActionDsl.Node node,
            List<Pose> input,
            KnownTraversabilitySnapshot map,
            DeterministicAStar pathfinder,
            Optional<ObservationFrame> latestFrame,
            long visualBarrierWorldRevision,
            ToLongFunction<ActionDsl.Position> surfaceRevisionBarrier,
            float cameraLimit,
            Map<String, ActionDslCompiler.Cost> costs,
            Map<TraversabilityEdge.Key, TraversabilityEdge> routeDependencies,
            Set<ActionDsl.Position> knownTargets,
            Set<KnownSurface> knownFacingSurfaces,
            Set<KnownSurface> knownSurfaces,
            Map<String, MutationAim> mutationAims,
            Map<String, MutationBatchPlan> mutationBatchPlans,
            Map<RouteKey, RoutePlan> routeCache,
            Set<String> waitsBackedByPriorPlant,
            PlacementStateResolver placementStates,
            PlanningWork work) {
        if (node instanceof ActionDsl.WaitTicks) {
            return input;
        }
        if (node instanceof ActionDsl.WaitUntil wait) {
            if (wait.condition() instanceof ActionDsl.CropMatureCondition crop
                    && !waitsBackedByPriorPlant.contains(wait.id())) {
                long surfaceBarrier = AgentSurfaceEvidence.surfaceBarrierWorldRevision(
                        map, surfaceRevisionBarrier, crop.target());
                KnownSurface surface = AgentSurfaceEvidence.requireKnownWheatWaitSurface(
                        map, latestFrame, input,
                        crop.target(), surfaceBarrier);
                knownSurfaces.add(surface);
            }
            AgentPlannerCosts.merge(costs, node.id(), ActionDslCompiler.intrinsicWaitCost(wait.maxTicks()));
            return input;
        }
        if (node instanceof ActionDsl.NavigateToKnown navigate) {
            knownTargets.add(navigate.target());
            ActionDslCompiler.Cost worst = null;
            var output = new ArrayList<Pose>(input.size());
            for (Pose pose : input) {
                work.poseTransition();
                var routeKey = new RouteKey(pose.cell(), AgentPlannerGeometry.navCell(navigate.target()));
                RoutePlan route = routeCache.get(routeKey);
                if (route == null) {
                    var result = pathfinder.findRoute(
                            map,
                            routeKey.start(),
                            routeKey.target(),
                            work::canContinue,
                            work::routeExpansion);
                    route = AgentNavigationPlanner.requireRouteResult(result);
                    routeCache.put(routeKey, route);
                }
                AgentNavigationPlanner.addRouteDependencies(map, route, routeDependencies);
                worst = AgentPlannerCosts.maximum(worst, AgentPlannerCosts.navigationCost(route, pose));
                output.add(pose.at(AgentPlannerGeometry.navCell(navigate.target()), navigate.tolerance()));
            }
            AgentPlannerCosts.merge(costs, node.id(), Objects.requireNonNull(worst, "navigation cost"));
            return distinct(output);
        }
        if (node instanceof ActionDsl.ApproachKnownSurface approach) {
            long surfaceBarrier = AgentSurfaceEvidence.surfaceBarrierWorldRevision(
                    map, surfaceRevisionBarrier, approach.target());
            KnownSurface surface = AgentSurfaceEvidence.requireKnownSurface(
                    map, latestFrame, approach.target(), approach.expectedBlock(), surfaceBarrier);
            knownSurfaces.add(surface);
            ActionDslCompiler.Cost worst = null;
            var output = new ArrayList<Pose>(input.size());
            for (Pose pose : input) {
                work.poseTransition();
                ApproachPlan plan = AgentNavigationPlanner.requireApproachPlan(
                        map,
                        pathfinder,
                        pose,
                        approach.target(),
                        approach.expectedBlock(),
                        latestFrame,
                        surfaceBarrier,
                        work);
                AgentNavigationPlanner.addRouteDependencies(map, plan.route(), routeDependencies);
                worst = AgentPlannerCosts.maximum(worst, AgentPlannerCosts.navigationCost(plan.route(), pose));
                output.add(pose.at(plan.anchor(), APPROACH_TOLERANCE));
            }
            AgentPlannerCosts.merge(costs, node.id(), Objects.requireNonNull(worst, "approach cost"));
            return distinct(output);
        }
        if (node instanceof ActionDsl.ApproachKnownPlacement approach) {
            List<PlacementApproachRequirement> requirements =
                    AgentConstructionPlanner.requirePlacementApproachRequirements(
                            map,
                            latestFrame,
                            approach,
                            surfaceRevisionBarrier,
                            placementStates);
            for (PlacementApproachRequirement requirement : requirements) {
                knownSurfaces.add(new KnownSurface(
                        requirement.support().position(),
                        requirement.support().face(),
                        requirement.support().expectedState().orElseThrow().block()));
            }
            ActionDslCompiler.Cost worst = null;
            var output = new ArrayList<Pose>(input.size());
            for (Pose pose : input) {
                work.poseTransition();
                ApproachPlan plan = AgentConstructionPlanner.requireKnownPlacementApproachPlan(
                        map, pathfinder, pose, requirements, work);
                AgentNavigationPlanner.addRouteDependencies(map, plan.route(), routeDependencies);
                worst = AgentPlannerCosts.maximum(worst, AgentPlannerCosts.navigationCost(plan.route(), pose));
                output.add(pose.at(plan.anchor(), APPROACH_TOLERANCE));
            }
            AgentPlannerCosts.merge(costs, node.id(), Objects.requireNonNull(worst, "placement approach cost"));
            return distinct(output);
        }
        if (node instanceof ActionDsl.FaceKnownPosition face) {
            AgentSurfaceEvidence.requireKnownFaceTarget(map, latestFrame, face.target());
            knownTargets.add(face.target());
            ActionDslCompiler.Cost worst = null;
            var output = new ArrayList<Pose>(input.size());
            for (Pose pose : input) {
                work.poseTransition();
                Aim aim = AgentPlannerGeometry.aim(pose, face.target());
                AimError aimError = AgentPlannerGeometry.aimError(pose, face.target(), aim);
                worst = AgentPlannerCosts.maximum(worst, AgentPlannerCosts.faceCost(pose, face.target(), cameraLimit));
                output.add(pose.aimed(aim, aimError));
            }
            AgentPlannerCosts.merge(costs, node.id(), Objects.requireNonNull(worst, "camera cost"));
            return distinct(output);
        }
        if (node instanceof ActionDsl.FaceKnownBlockFace face) {
            KnownSurface required = new KnownSurface(
                    face.target(), face.face(), face.expectedBlock());
            if (!AgentSurfaceEvidence.knownFacingSurface(map, latestFrame, required)) {
                throw new PlanningException(
                        Code.TARGET_UNKNOWN,
                        "Face block target requires delivered matching surface evidence");
            }
            knownFacingSurfaces.add(required);
            Vec3 point = MinecraftActionPrimitiveExecutor.blockFaceAimPoint(
                    face.target(), face.face());
            ActionDslCompiler.Cost worst = null;
            var output = new ArrayList<Pose>(input.size());
            for (Pose pose : input) {
                work.poseTransition();
                Aim aim = AgentPlannerGeometry.aim(pose, point);
                AimError aimError = AgentPlannerGeometry.aimError(pose, point, aim);
                worst = AgentPlannerCosts.maximum(worst, AgentPlannerCosts.faceCost(pose, point, cameraLimit));
                output.add(pose.aimed(aim, aimError));
            }
            AgentPlannerCosts.merge(costs, node.id(), Objects.requireNonNull(worst, "camera cost"));
            return distinct(output);
        }
        if (node instanceof ActionDsl.BreakKnownFace block) {
            long surfaceBarrier = AgentSurfaceEvidence.surfaceBarrierWorldRevision(
                    map, surfaceRevisionBarrier, block.target());
            var required = AgentSurfaceEvidence.requireKnownBreakSurface(
                    map, latestFrame, block, surfaceBarrier);
            var surface = AgentSurfaceEvidence.knownSurfaceRecord(
                    map, latestFrame, required, surfaceBarrier).orElseThrow();
            knownSurfaces.add(required);
            Vec3 point = AgentPlannerGeometry.rayHit(surface);
            MutationAim candidate = new MutationAim(block.target(), block.face(), point);
            MutationAim previous = mutationAims.putIfAbsent(node.id(), candidate);
            if (previous != null && !previous.equals(candidate)) {
                throw new PlanningException(
                        Code.PROGRAM_BUDGET_UNPROVABLE,
                        "Break node resolves to more than one aim witness");
            }
            ActionDslCompiler.Cost worst = null;
            var output = new ArrayList<Pose>(input.size());
            for (Pose pose : input) {
                work.poseTransition();
                AgentPlannerGeometry.requireBreakPose(pose, surface, point);
                Aim aim = AgentPlannerGeometry.aim(pose, point);
                AimError aimError = AgentPlannerGeometry.aimError(pose, point, aim);
                worst = AgentPlannerCosts.maximum(worst, AgentPlannerCosts.breakCost(pose, block, point, cameraLimit));
                output.add(pose.aimed(aim, aimError));
            }
            AgentPlannerCosts.merge(costs, node.id(), Objects.requireNonNull(worst, "break cost"));
            return distinct(output);
        }
        if (node instanceof ActionDsl.CastKnownFishingRod cast) {
            MutationSurface surface = AgentSurfaceEvidence.requireMutationSurface(
                    map,
                    latestFrame,
                    input,
                    cast.target(),
                    AgentSurfaceEvidence.surfaceBarrierWorldRevision(map, surfaceRevisionBarrier, cast.target()),
                    "minecraft:water",
                    value -> value.face().name().equals(cast.face().name())
                            && AgentSurfaceEvidence.exactObservedState(value, cast.expectedState()),
                    "Fishing cast requires a current exact source-water face",
                    MAX_FISHING_AIM_BLOCKS);
            return AgentMutationPlanner.analyzeMutation(
                    node, input, cameraLimit, costs, knownSurfaces, mutationAims, work,
                    surface, 2, 0, 0);
        }
        if (node instanceof ActionDsl.ReelKnownFishingSession) {
            AgentPlannerCosts.merge(costs, node.id(), ActionDslCompiler.intrinsicKnownFishingReelCost());
            return input;
        }
        if (node instanceof ActionDsl.BreakKnownBlock block) {
            long surfaceBarrier = AgentSurfaceEvidence.surfaceBarrierWorldRevision(
                    map, surfaceRevisionBarrier, block.target());
            var required = AgentSurfaceEvidence.requireKnownBreakSurface(
                    map, latestFrame, block, surfaceBarrier);
            var surface = AgentSurfaceEvidence.knownSurfaceRecord(
                    map, latestFrame, required, surfaceBarrier).orElseThrow();
            knownSurfaces.add(required);
            Vec3 point = AgentPlannerGeometry.rayHit(surface);
            MutationAim candidate = new MutationAim(block.target(), block.face(), point);
            MutationAim previous = mutationAims.putIfAbsent(node.id(), candidate);
            if (previous != null && !previous.equals(candidate)) {
                throw new PlanningException(
                        Code.PROGRAM_BUDGET_UNPROVABLE,
                        "Break node resolves to more than one aim witness");
            }
            ActionDslCompiler.Cost worst = null;
            var output = new ArrayList<Pose>(input.size());
            for (Pose pose : input) {
                work.poseTransition();
                AgentPlannerGeometry.requireBreakPose(pose, surface, point);
                Aim aim = AgentPlannerGeometry.aim(pose, point);
                AimError aimError = AgentPlannerGeometry.aimError(pose, point, aim);
                worst = AgentPlannerCosts.maximum(worst, AgentPlannerCosts.breakCost(pose, block, point, cameraLimit));
                output.add(pose.aimed(aim, aimError));
            }
            AgentPlannerCosts.merge(costs, node.id(), Objects.requireNonNull(worst, "break cost"));
            return distinct(output);
        }
        if (node instanceof ActionDsl.OperateKnownCobblestoneGenerator operation) {
            var block = new ActionDsl.BreakKnownBlock(
                    operation.id(), operation.target(), operation.face(),
                    operation.expectedState(), operation.toolItem(),
                    operation.expectedDrop(), operation.minimumInventoryCount());
            long surfaceBarrier = AgentSurfaceEvidence.surfaceBarrierWorldRevision(
                    map, surfaceRevisionBarrier, operation.target());
            var required = AgentSurfaceEvidence.requireKnownBreakSurface(
                    map, latestFrame, block, surfaceBarrier);
            var surface = AgentSurfaceEvidence.knownSurfaceRecord(
                    map, latestFrame, required, surfaceBarrier).orElseThrow();
            knownSurfaces.add(required);
            Vec3 point = AgentPlannerGeometry.rayHit(surface);
            for (Pose pose : input) {
                work.poseTransition();
                AgentPlannerGeometry.requireBreakPose(pose, surface, point);
            }
            AgentPlannerCosts.merge(costs, node.id(),
                    ActionDslCompiler.intrinsicCobblestoneGeneratorCost(operation));
            // This operation is deliberately pre-aimed and stationary. It never turns the
            // camera itself; the exact face is revalidated immediately before every attack.
            return input;
        }
        if (node instanceof ActionDsl.TillKnownBlock till) {
            MutationSurface surface = AgentSurfaceEvidence.requireMutationSurface(
                    map, latestFrame, input, till.target(),
                    AgentSurfaceEvidence.surfaceBarrierWorldRevision(map, surfaceRevisionBarrier, till.target()),
                    till.expectedBlock(),
                    value -> value.face() != ObservationRecord.Face.DOWN,
                    "Till target requires a current non-DOWN visible surface");
            return AgentMutationPlanner.analyzeMutation(
                    node, input, cameraLimit, costs, knownSurfaces, mutationAims, work,
                    surface, 1, 0, 0);
        }
        if (node instanceof ActionDsl.TillKnownBatch
                || node instanceof ActionDsl.PlantKnownWheatBatch
                || node instanceof ActionDsl.HarvestKnownWheatBatch) {
            return AgentMutationPlanner.analyzeMutationBatch(
                    node, input, map, latestFrame, surfaceRevisionBarrier,
                    cameraLimit, costs, knownSurfaces, mutationBatchPlans, work);
        }
        if (node instanceof ActionDsl.PlantKnownWheat plant) {
            if (!AgentPlannerGeometry.directlyAbove(plant.target(), plant.support())) {
                throw new PlanningException(
                        Code.TARGET_UNKNOWN, "Plant target must be directly above its support");
            }
            MutationSurface surface = AgentSurfaceEvidence.requireMutationSurface(
                    map, latestFrame, input, plant.support(),
                    AgentSurfaceEvidence.surfaceBarrierWorldRevision(map, surfaceRevisionBarrier, plant.support()),
                    "minecraft:farmland",
                    value -> value.face() == ObservationRecord.Face.UP,
                    "Plant support requires its current UP face");
            return AgentMutationPlanner.analyzeMutation(
                    node, input, cameraLimit, costs, knownSurfaces, mutationAims, work,
                    surface, 0, 0, 1);
        }
        if (node instanceof ActionDsl.HarvestKnownWheat harvest) {
            MutationSurface surface = AgentSurfaceEvidence.requireMutationSurface(
                    map, latestFrame, input, harvest.target(),
                    AgentSurfaceEvidence.surfaceBarrierWorldRevision(map, surfaceRevisionBarrier, harvest.target()),
                    "minecraft:wheat",
                    value -> Boolean.TRUE.equals(value.cropMature()),
                    "Harvest target requires current crop_mature=true evidence");
            return AgentMutationPlanner.analyzeMutation(
                    node, input, cameraLimit, costs, knownSurfaces, mutationAims, work,
                    surface, 0, 1, 0);
        }
        if (node instanceof ActionDsl.ApplyKnownBlockPlan plan) {
            return AgentConstructionPlanner.analyzeBlockPlan(
                    plan, input, map, latestFrame,
                    surfaceRevisionBarrier, costs, knownSurfaces, placementStates, work);
        }
        if (node instanceof ActionDsl.ClearKnownBlockPlan plan) {
            return AgentConstructionPlanner.analyzeClearPlan(
                    plan, input, map, latestFrame,
                    surfaceRevisionBarrier, costs, knownSurfaces, work);
        }
        if (node instanceof ActionDsl.PillarUpKnown pillar) {
            return AgentConstructionPlanner.analyzePillar(
                    pillar, input, map, latestFrame,
                    surfaceRevisionBarrier, costs, knownSurfaces, placementStates, work);
        }
        if (node instanceof ActionDsl.ApplyKnownRedstoneSpec redstone) {
            return AgentConstructionPlanner.analyzeRedstone(
                    redstone, input, map, latestFrame,
                    surfaceRevisionBarrier, costs, knownSurfaces, mutationAims);
        }
        if (node instanceof ActionDsl.OpenKnownFenceGate gate) {
            MutationSurface surface = AgentSurfaceEvidence.requireMutationSurface(
                    map, latestFrame, input, gate.target(),
                    AgentSurfaceEvidence.surfaceBarrierWorldRevision(map, surfaceRevisionBarrier, gate.target()),
                    "minecraft:oak_fence_gate",
                    value -> true,
                    "Fence gate target requires a current visible oak fence gate surface");
            return AgentMutationPlanner.analyzeMutation(
                    node, input, cameraLimit, costs, knownSurfaces, mutationAims, work,
                    surface, 1, 0, 0);
        }
        if (node instanceof ActionDsl.OpenKnownPassage passage) {
            MutationSurface surface = AgentSurfaceEvidence.requireMutationSurface(
                    map, latestFrame, input, passage.target(),
                    AgentSurfaceEvidence.surfaceBarrierWorldRevision(map, surfaceRevisionBarrier, passage.target()),
                    passage.expectedBlock(),
                    value -> true,
                    "Passage target requires a current matching visible wooden surface");
            return AgentMutationPlanner.analyzeMutation(
                    node, input, cameraLimit, costs, knownSurfaces, mutationAims, work,
                    surface, 1, 0, 0);
        }
        if (node instanceof ActionDsl.InspectKnownContainer inspect) {
            AgentSurfaceEvidence.requireRoutingLabel(
                    map, latestFrame, inspect.routingLabel(), inspect.target(),
                    inspect.expectedBlock(), visualBarrierWorldRevision);
            MutationSurface surface = AgentSurfaceEvidence.requireInventorySurface(
                    map, latestFrame, input, inspect.target(),
                    AgentSurfaceEvidence.surfaceBarrierWorldRevision(map, surfaceRevisionBarrier, inspect.target()),
                    visualBarrierWorldRevision,
                    inspect.expectedBlock(),
                    "Container target requires a current matching visible surface");
            return AgentMutationPlanner.analyzeContainer(
                    node, input, cameraLimit, costs, knownSurfaces, mutationAims,
                    work, surface, 1);
        }
        if (node instanceof ActionDsl.TakeKnownContainerStack take) {
            AgentSurfaceEvidence.requireRoutingLabel(
                    map, latestFrame, take.routingLabel(), take.target(),
                    take.expectedBlock(), visualBarrierWorldRevision);
            MutationSurface surface = AgentSurfaceEvidence.requireInventorySurface(
                    map, latestFrame, input, take.target(),
                    AgentSurfaceEvidence.surfaceBarrierWorldRevision(map, surfaceRevisionBarrier, take.target()),
                    visualBarrierWorldRevision,
                    take.expectedBlock(),
                    "Container target requires a current matching visible surface");
            return AgentMutationPlanner.analyzeOwnedMenu(
                    node, input, cameraLimit, costs, knownSurfaces, mutationAims,
                    work, surface, ActionDslCompiler.knownContainerTransferInteractions(take.maxStacks()),
                    ActionDslCompiler.knownContainerTransferTicks(take.maxStacks()),
                    "container", false, Double.POSITIVE_INFINITY);
        }
        if (node instanceof ActionDsl.StoreKnownContainerStack store) {
            AgentSurfaceEvidence.requireRoutingLabel(
                    map, latestFrame, store.routingLabel(), store.target(),
                    store.expectedBlock(), visualBarrierWorldRevision);
            MutationSurface surface = AgentSurfaceEvidence.requireInventorySurface(
                    map, latestFrame, input, store.target(),
                    AgentSurfaceEvidence.surfaceBarrierWorldRevision(map, surfaceRevisionBarrier, store.target()),
                    visualBarrierWorldRevision,
                    store.expectedBlock(),
                    "Container target requires a current matching visible surface");
            return AgentMutationPlanner.analyzeOwnedMenu(
                    node, input, cameraLimit, costs, knownSurfaces, mutationAims,
                    work, surface, ActionDslCompiler.knownContainerTransferInteractions(store.maxStacks()),
                    ActionDslCompiler.knownContainerTransferTicks(store.maxStacks()),
                    "container", false, Double.POSITIVE_INFINITY);
        }
        if (node instanceof ActionDsl.CraftKnownRecipe craft) {
            MutationSurface surface = AgentSurfaceEvidence.requireInventorySurface(
                    map, latestFrame, input, craft.target(),
                    AgentSurfaceEvidence.surfaceBarrierWorldRevision(map, surfaceRevisionBarrier, craft.target()),
                    visualBarrierWorldRevision,
                    craft.expectedState().block(),
                    "Crafting target requires a current visible crafting table surface");
            return AgentMutationPlanner.analyzeContainer(
                    node, input, cameraLimit, costs, knownSurfaces, mutationAims, work, surface,
                    ActionDslCompiler.knownCraftInteractions(craft.maxCrafts()));
        }
        if (node instanceof ActionDsl.SmeltKnownRecipe smelt) {
            MutationSurface surface = AgentSurfaceEvidence.requireMutationSurface(
                    map, latestFrame, input, smelt.target(),
                    AgentSurfaceEvidence.surfaceBarrierWorldRevision(map, surfaceRevisionBarrier, smelt.target()),
                    smelt.expectedState().block(),
                    value -> true,
                    "Smelting target requires a current matching visible surface");
            return AgentMutationPlanner.analyzeOwnedMenu(
                    node, input, cameraLimit, costs, knownSurfaces, mutationAims,
                    work, surface,
                    ActionDslCompiler.KNOWN_SMELTING_INTERACTIONS,
                    ActionDslCompiler.knownSmeltingTicks(smelt.maxSmelts()),
                    "smelting",
                    true,
                    KnownBrewingRequest.MAX_ONE_WAY_CAMERA_DEGREES);
        }
        if (node instanceof ActionDsl.BrewKnownPotionBatch brew) {
            MutationSurface surface = AgentSurfaceEvidence.requireMutationSurface(
                    map, latestFrame, input, brew.target(),
                    AgentSurfaceEvidence.surfaceBarrierWorldRevision(map, surfaceRevisionBarrier, brew.target()),
                    brew.expectedBlock(),
                    value -> true,
                    "Brewing target requires a current matching visible surface");
            return AgentMutationPlanner.analyzeOwnedMenu(
                    node, input, cameraLimit, costs, knownSurfaces, mutationAims,
                    work, surface,
                    ActionDslCompiler.KNOWN_BREWING_INTERACTIONS,
                    BREWING_TICK_UPPER_BOUND,
                    "brewing",
                    true,
                    KnownBrewingRequest.MAX_ONE_WAY_CAMERA_DEGREES);
        }
        if (node instanceof ActionDsl.RemoveVisibleFrameItem || node instanceof ActionDsl.InsertVisibleFrameItem) {
            ActionDslCompiler.Cost worst = null;
            var output = new ArrayList<Pose>(input.size());
            for (Pose pose : input) {
                work.poseTransition();
                var frameAim = AgentSurfaceEvidence.requireFrameItemAim(
                        map, pose, latestFrame, node, visualBarrierWorldRevision);
                var camera = AgentPlannerCosts.faceCost(pose, frameAim.aimPoint(), cameraLimit);
                if (camera.cameraDegrees() > 360 || camera.ticks() > ActionDslCompiler.FRAME_ITEM_TICKS - 100) {
                    throw new ActionDslException(ActionDslException.Code.PROGRAM_BUDGET_UNPROVABLE,
                            "Frame camera cannot fit its bounded attempt");
                }
                var cost = new ActionDslCompiler.Cost(
                        ActionDslCompiler.FRAME_ITEM_DURATION_MILLIS, ActionDslCompiler.FRAME_ITEM_TICKS,
                        0, camera.cameraDegrees(), 1, 0, 0);
                worst = AgentPlannerCosts.maximum(worst, cost);
                var heading = AgentPlannerGeometry.aim(pose, frameAim.aimPoint());
                output.add(pose.aimed(heading, AgentPlannerGeometry.aimError(pose, frameAim.aimPoint(), heading)));
            }
            AgentPlannerCosts.merge(costs, node.id(), Objects.requireNonNull(worst));
            return distinct(output);
        }
        if (node instanceof ActionDsl.CollectVisibleItem collect) {
            ObservationRecord.VisibleEntity entity = AgentPickupPlanner.requireVisibleItem(
                    map, latestFrame, collect, visualBarrierWorldRevision);
            ActionDslCompiler.Cost worst = null;
            var output = new ArrayList<Pose>(input.size());
            for (Pose pose : input) {
                work.poseTransition();
                PickupPlan pickup = AgentPickupPlanner.requirePickupPlan(
                        map, pathfinder, pose.cell(), entity, work);
                AgentNavigationPlanner.addRouteDependencies(map, pickup.route(), routeDependencies);
                worst = AgentPlannerCosts.maximum(worst, AgentPlannerCosts.pickupCost(pickup.route(), pose));
                output.add(pose.at(pickup.pickupCell(), 0.25D));
            }
            AgentPlannerCosts.merge(costs, node.id(), Objects.requireNonNull(worst, "pickup cost"));
            return distinct(output);
        }
        if (node instanceof ActionDsl.CollectVisibleItemBatch batch) {
            return AgentPickupPlanner.analyzeCollectBatch(
                    batch, input, map, pathfinder, latestFrame,
                    visualBarrierWorldRevision, costs, routeDependencies, work);
        }
        if (node instanceof ActionDsl.If conditional) {
            var output = new ArrayList<Pose>();
            output.addAll(analyzeSequence(
                    conditional.thenBranch(), input, map, pathfinder,
                    latestFrame, visualBarrierWorldRevision,
                    surfaceRevisionBarrier, cameraLimit,
                    costs, routeDependencies,
                    knownTargets, knownFacingSurfaces, knownSurfaces, mutationAims,
                    mutationBatchPlans, routeCache,
                    waitsBackedByPriorPlant, placementStates, work));
            output.addAll(analyzeSequence(
                    conditional.elseBranch(), input, map, pathfinder,
                    latestFrame, visualBarrierWorldRevision,
                    surfaceRevisionBarrier, cameraLimit,
                    costs, routeDependencies,
                    knownTargets, knownFacingSurfaces, knownSurfaces, mutationAims,
                    mutationBatchPlans, routeCache,
                    waitsBackedByPriorPlant, placementStates, work));
            return distinct(output);
        }
        var repeat = (ActionDsl.Repeat) node;
        List<Pose> output = input;
        for (int count = 0; count < repeat.count(); count++) {
            output = analyzeSequence(
                    repeat.body(), output, map, pathfinder,
                    latestFrame, visualBarrierWorldRevision,
                    surfaceRevisionBarrier, cameraLimit,
                    costs, routeDependencies,
                    knownTargets, knownFacingSurfaces, knownSurfaces, mutationAims,
                    mutationBatchPlans, routeCache,
                    waitsBackedByPriorPlant, placementStates, work);
        }
        return output;
    }

    static List<Pose> distinct(List<Pose> poses) {
        var result = new LinkedHashSet<>(poses);
        if (result.size() > MAX_ABSTRACT_POSES) {
            throw new ActionDslException(
                    ActionDslException.Code.PROGRAM_BUDGET_UNPROVABLE,
                    "Action pose analysis exceeds " + MAX_ABSTRACT_POSES + " states");
        }
        return List.copyOf(result);
    }

    private record RouteKey(NavCell start, NavCell target) {
        private RouteKey {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(target, "target");
        }
    }

    /** 全nodeと探索で共有し、委譲や分岐のたびに補充しない有限の作業量。 */
    static final class PlanningWork {
        private final BooleanSupplier canContinue;
        private int routeExpansions;
        private int poseTransitions;

        PlanningWork(BooleanSupplier canContinue) {
            this.canContinue = canContinue;
        }

        boolean canContinue() {
            return canContinue.getAsBoolean();
        }

        void check() {
            if (!canContinue()) {
                throw new PlanningException(Code.TIMEOUT, "Agent planning deadline expired");
            }
        }

        void routeExpansion() {
            check();
            if (++routeExpansions > MAX_TOTAL_ROUTE_EXPANSIONS) {
                throw workLimit("route expansions", MAX_TOTAL_ROUTE_EXPANSIONS);
            }
        }

        void poseTransition() {
            check();
            if (++poseTransitions > MAX_POSE_TRANSITIONS) {
                throw workLimit("pose transitions", MAX_POSE_TRANSITIONS);
            }
        }

        private static PlanningException workLimit(String work, int limit) {
            return new PlanningException(
                    Code.PROGRAM_BUDGET_UNPROVABLE,
                    "Agent planning exceeds " + limit + " aggregate " + work);
        }
    }
}
