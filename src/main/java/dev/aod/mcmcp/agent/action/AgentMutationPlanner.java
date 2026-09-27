package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.agent.action.AgentPlannerGeometry.Aim;
import dev.aod.mcmcp.agent.action.AgentPlannerGeometry.AimError;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.Code;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.KnownSurface;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.MutationAim;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.MutationBatchPlan;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.MutationBatchStep;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.PlanningException;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.Pose;
import dev.aod.mcmcp.agent.action.AgentProgramPlanner.PlanningWork;
import dev.aod.mcmcp.agent.action.AgentSurfaceEvidence.MutationSurface;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.dsl.ActionDslCompiler;
import dev.aod.mcmcp.agent.navigation.KnownTraversabilitySnapshot;
import dev.aod.mcmcp.agent.observation.ObservationFrame;
import dev.aod.mcmcp.agent.observation.ObservationRecord;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.ToLongFunction;

import static dev.aod.mcmcp.agent.action.AgentPlannerCosts.TICK_MILLIS;
import static dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.CONTAINER_TICK_UPPER_BOUND;
import static dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.MUTATION_BATCH_REPROOF_TICKS;

/** mutation・所有menuの照準と費用、および記述順の農作業batchを計画する。 */
final class AgentMutationPlanner {
    private AgentMutationPlanner() {
    }

    private static final double FARMLAND_SETTLING_BLOCKS = 1.0D / 16.0D;

    static List<Pose> analyzeMutationBatch(
            ActionDsl.Node batch,
            List<Pose> input,
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ToLongFunction<ActionDsl.Position> surfaceRevisionBarrier,
            float cameraLimit,
            Map<String, ActionDslCompiler.Cost> costs,
            Set<KnownSurface> knownSurfaces,
            Map<String, MutationBatchPlan> mutationBatchPlans,
            PlanningWork work) {
        List<ActionDsl.Node> children = mutationBatchChildren(batch);
        ActionDslCompiler.Cost worst = null;
        MutationBatchPlan sharedPlan = null;
        var output = new ArrayList<Pose>(input.size());
        for (Pose initial : input) {
            work.poseTransition();
            var candidates = new ArrayList<MutationBatchTarget>(children.size());
            for (int targetIndex = 0; targetIndex < children.size(); targetIndex++) {
                ActionDsl.Node child = children.get(targetIndex);
                try {
                    MutationSurface surface = requireMutationSurfaceForNode(
                            child, map, latestFrame, List.of(initial), surfaceRevisionBarrier);
                    candidates.add(new MutationBatchTarget(child, surface));
                    knownSurfaces.add(surface.surface());
                } catch (PlanningException failure) {
                    if (failure.code() != Code.TARGET_UNKNOWN) {
                        throw failure;
                    }
                    throw new PlanningException(
                            failure.code(),
                            "Mutation batch target[" + targetIndex + "] lacks required current evidence: "
                                    + failure.getMessage());
                }
            }
            BatchPath ordered = listedMutationBatch(initial, candidates, cameraLimit);
            var planned = new ArrayList<MutationBatchStep>(candidates.size());
            Pose plannedPose = initial;
            for (MutationBatchTarget candidate : ordered.targets()) {
                MutationSurface surface = candidate.surface();
                Vec3 point = surface.point();
                MutationAim aim = new MutationAim(
                        surface.surface().position(), surface.surface().face(), point);
                ActionDslCompiler.Cost plannedCost = mutationTargetCost(
                        plannedPose, candidate, cameraLimit);
                planned.add(new MutationBatchStep(candidate.node(), aim, plannedCost));
                Aim nextAim = AgentPlannerGeometry.aim(plannedPose, point);
                Pose nextPose = plannedPose.aimed(
                        nextAim, AgentPlannerGeometry.aimError(plannedPose, point, nextAim));
                if (candidate.node() instanceof ActionDsl.TillKnownBlock till
                        && mayStandOnTarget(plannedPose, till.target())) {
                    nextPose = nextPose.withAdditionalYErrorBelow(FARMLAND_SETTLING_BLOCKS);
                }
                plannedPose = nextPose;
            }
            MutationBatchPlan plan = new MutationBatchPlan(planned);
            if (sharedPlan != null && !sameMutationOrder(sharedPlan, plan)) {
                throw new PlanningException(
                        Code.PROGRAM_BUDGET_UNPROVABLE,
                        "Mutation batch order depends on an unresolved abstract pose");
            }
            sharedPlan = plan;
            worst = AgentPlannerCosts.maximum(worst, ordered.cost());
            output.add(ordered.pose());
        }
        MutationBatchPlan plan = Objects.requireNonNull(sharedPlan, "mutation batch plan");
        MutationBatchPlan previous = mutationBatchPlans.putIfAbsent(batch.id(), plan);
        if (previous != null && !previous.equals(plan)) {
            throw new PlanningException(
                    Code.PROGRAM_BUDGET_UNPROVABLE,
                    "Mutation batch node resolves to more than one plan");
        }
        AgentPlannerCosts.merge(costs, batch.id(), Objects.requireNonNull(worst, "mutation batch cost"));
        return AgentProgramPlanner.distinct(output);
    }

    /** Preserves the submitted target order while proving one bounded joint camera path. */
    private static BatchPath listedMutationBatch(
            Pose initial, List<MutationBatchTarget> input, float cameraLimit) {
        Pose pose = initial;
        ActionDslCompiler.Cost cost = new ActionDslCompiler.Cost(0, 0, 0, 0, 0, 0, 0);
        for (MutationBatchTarget candidate : input) {
            cost = AgentPlannerCosts.addCosts(cost, mutationTargetCost(pose, candidate, cameraLimit));
            Aim nextAim = AgentPlannerGeometry.aim(pose, candidate.surface().point());
            Pose nextPose = pose.aimed(
                    nextAim, AgentPlannerGeometry.aimError(pose, candidate.surface().point(), nextAim));
            if (candidate.node() instanceof ActionDsl.TillKnownBlock till
                    && mayStandOnTarget(pose, till.target())) {
                nextPose = nextPose.withAdditionalYErrorBelow(FARMLAND_SETTLING_BLOCKS);
            }
            pose = nextPose;
        }
        return new BatchPath(List.copyOf(input), pose, cost);
    }

    private static ActionDslCompiler.Cost mutationTargetCost(
            Pose pose, MutationBatchTarget candidate, float cameraLimit) {
        return mutationNodeCost(
                pose, candidate.node(), candidate.surface().point(), cameraLimit);
    }

    private static ActionDslCompiler.Cost mutationNodeCost(
            Pose pose, ActionDsl.Node node, Vec3 point, float cameraLimit) {
        long interactions = node instanceof ActionDsl.TillKnownBlock ? 1 : 0;
        long breaks = node instanceof ActionDsl.HarvestKnownWheat ? 1 : 0;
        long placements = node instanceof ActionDsl.PlantKnownWheat ? 1 : 0;
        ActionDslCompiler.Cost mutation = AgentPlannerCosts.mutationCost(
                pose, point, cameraLimit,
                interactions, breaks, placements);
        return new ActionDslCompiler.Cost(
                Math.addExact(
                        mutation.durationMillis(),
                        Math.multiplyExact(MUTATION_BATCH_REPROOF_TICKS, TICK_MILLIS)),
                Math.addExact(mutation.ticks(), MUTATION_BATCH_REPROOF_TICKS),
                mutation.distanceBlocks(),
                mutation.cameraDegrees(),
                mutation.interactions(),
                mutation.blocksBroken(),
                mutation.blocksPlaced());
    }

    /**
     * Reprices the fixed, unstarted suffix from the endpoint of the freshly reproved current aim.
     * The current reproof wait is already present in consumed progress; each future step retains
     * its full 40-tick reproof reserve.
     */
    static ActionDslCompiler.Cost recostMutationBatchRemainder(
            MutationBatchPlan plan,
            int currentIndex,
            Pose currentPose,
            MutationAim freshCurrentAim,
            ActionDslCompiler.Cost freshCurrentCost,
            float cameraLimit) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(currentPose, "currentPose");
        Objects.requireNonNull(freshCurrentAim, "freshCurrentAim");
        Objects.requireNonNull(freshCurrentCost, "freshCurrentCost");
        if (currentIndex < 0 || currentIndex >= plan.steps().size()) {
            throw new IllegalArgumentException("currentIndex is outside the mutation batch");
        }
        ActionDsl.Node current = plan.steps().get(currentIndex).primitive();
        Aim currentAim = AgentPlannerGeometry.aim(currentPose, freshCurrentAim.point());
        Pose suffixPose = currentPose.aimed(
                currentAim, AgentPlannerGeometry.aimError(currentPose, freshCurrentAim.point(), currentAim));
        if (current instanceof ActionDsl.TillKnownBlock till
                && mayStandOnTarget(currentPose, till.target())) {
            suffixPose = suffixPose.withAdditionalYErrorBelow(FARMLAND_SETTLING_BLOCKS);
        }

        ActionDslCompiler.Cost required = freshCurrentCost;
        for (int index = currentIndex + 1; index < plan.steps().size(); index++) {
            MutationBatchStep step = plan.steps().get(index);
            required = AgentPlannerCosts.addCosts(
                    required,
                    mutationNodeCost(suffixPose, step.primitive(), step.aim().point(), cameraLimit));
            Aim nextAim = AgentPlannerGeometry.aim(suffixPose, step.aim().point());
            Pose nextPose = suffixPose.aimed(
                    nextAim, AgentPlannerGeometry.aimError(suffixPose, step.aim().point(), nextAim));
            if (step.primitive() instanceof ActionDsl.TillKnownBlock till
                    && mayStandOnTarget(suffixPose, till.target())) {
                nextPose = nextPose.withAdditionalYErrorBelow(FARMLAND_SETTLING_BLOCKS);
            }
            suffixPose = nextPose;
        }
        return required;
    }

    private static boolean mayStandOnTarget(Pose pose, ActionDsl.Position target) {
        if (!pose.cell().dimension().equals(target.dimension())) return false;
        return intervalsOverlap(
                        pose.x() - pose.horizontalPositionError(),
                        pose.x() + pose.horizontalPositionError(),
                        target.x(), target.x() + 1.0D)
                && intervalsOverlap(
                        pose.z() - pose.horizontalPositionError(),
                        pose.z() + pose.horizontalPositionError(),
                        target.z(), target.z() + 1.0D)
                && intervalsOverlap(
                        pose.y() - pose.yErrorBelow(),
                        pose.y() + pose.yErrorAbove(),
                        target.y() + 1.0D, target.y() + 2.0D);
    }

    private static boolean intervalsOverlap(
            double leftMinimum, double leftMaximum, double rightMinimum, double rightMaximum) {
        return leftMaximum >= rightMinimum - 1.0e-9D
                && leftMinimum < rightMaximum - 1.0e-9D;
    }

    private static MutationSurface requireMutationSurfaceForNode(
            ActionDsl.Node node,
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            List<Pose> poses,
            ToLongFunction<ActionDsl.Position> surfaceRevisionBarrier) {
        if (node instanceof ActionDsl.TillKnownBlock till) {
            return AgentSurfaceEvidence.requireMutationSurface(
                    map, latestFrame, poses, till.target(),
                    AgentSurfaceEvidence.surfaceBarrierWorldRevision(map, surfaceRevisionBarrier, till.target()),
                    till.expectedBlock(), value -> value.face() != ObservationRecord.Face.DOWN,
                    "Till batch target requires a current non-DOWN visible surface");
        }
        if (node instanceof ActionDsl.PlantKnownWheat plant) {
            return AgentSurfaceEvidence.requireMutationSurface(
                    map, latestFrame, poses, plant.support(),
                    AgentSurfaceEvidence.surfaceBarrierWorldRevision(map, surfaceRevisionBarrier, plant.support()),
                    "minecraft:farmland", value -> value.face() == ObservationRecord.Face.UP,
                    "Plant batch support requires its current UP face");
        }
        var harvest = (ActionDsl.HarvestKnownWheat) node;
        return AgentSurfaceEvidence.requireMutationSurface(
                map, latestFrame, poses, harvest.target(),
                AgentSurfaceEvidence.surfaceBarrierWorldRevision(map, surfaceRevisionBarrier, harvest.target()),
                "minecraft:wheat", value -> Boolean.TRUE.equals(value.cropMature()),
                "Harvest batch target requires current crop_mature=true evidence");
    }

    static List<ActionDsl.Node> mutationBatchChildren(ActionDsl.Node batch) {
        if (batch instanceof ActionDsl.TillKnownBatch till) {
            return java.util.stream.IntStream.range(0, till.targets().size())
                    .mapToObj(index -> (ActionDsl.Node) new ActionDsl.TillKnownBlock(
                            batchChildId(till.id(), index), till.targets().get(index),
                            till.expectedBlock(), till.hoeItem()))
                    .toList();
        }
        if (batch instanceof ActionDsl.PlantKnownWheatBatch plant) {
            return java.util.stream.IntStream.range(0, plant.targets().size())
                    .mapToObj(index -> {
                        ActionDsl.PlantPlot plot = plant.targets().get(index);
                        return (ActionDsl.Node) new ActionDsl.PlantKnownWheat(
                                batchChildId(plant.id(), index), plot.target(), plot.support(),
                                plant.seedItem());
                    })
                    .toList();
        }
        if (batch instanceof ActionDsl.HarvestKnownWheatBatch harvest) {
            return java.util.stream.IntStream.range(0, harvest.targets().size())
                    .mapToObj(index -> (ActionDsl.Node) new ActionDsl.HarvestKnownWheat(
                            batchChildId(harvest.id(), index), harvest.targets().get(index)))
                    .toList();
        }
        throw new IllegalArgumentException("node is not a mutation batch");
    }

    private static String batchChildId(String batchId, int index) {
        String suffix = "_" + index;
        return batchId.substring(0, Math.min(batchId.length(), 32 - suffix.length())) + suffix;
    }

    private static ActionDsl.Position mutationPosition(ActionDsl.Node node) {
        return switch (node) {
            case ActionDsl.TillKnownBlock till -> till.target();
            case ActionDsl.PlantKnownWheat plant -> plant.target();
            case ActionDsl.HarvestKnownWheat harvest -> harvest.target();
            default -> throw new IllegalArgumentException("node is not a batch mutation child");
        };
    }

    private static boolean sameMutationOrder(MutationBatchPlan left, MutationBatchPlan right) {
        return left.steps().stream().map(step -> mutationPosition(step.primitive())).toList()
                .equals(right.steps().stream()
                        .map(step -> mutationPosition(step.primitive())).toList());
    }

    static List<Pose> analyzeMutation(
            ActionDsl.Node node,
            List<Pose> input,
            float cameraLimit,
            Map<String, ActionDslCompiler.Cost> costs,
            Set<KnownSurface> knownSurfaces,
            Map<String, MutationAim> mutationAims,
            PlanningWork work,
            MutationSurface mutationSurface,
            long interactions,
            long blocksBroken,
            long blocksPlaced) {
        KnownSurface surface = mutationSurface.surface();
        knownSurfaces.add(surface);
        ActionDslCompiler.Cost worst = null;
        var output = new ArrayList<Pose>(input.size());
        Vec3 point = mutationSurface.point();
        MutationAim candidate = new MutationAim(surface.position(), surface.face(), point);
        MutationAim previous = mutationAims.putIfAbsent(node.id(), candidate);
        if (previous != null && !previous.equals(candidate)) {
            throw new PlanningException(
                    Code.PROGRAM_BUDGET_UNPROVABLE,
                    "Mutation node resolves to more than one aim witness");
        }
        for (Pose pose : input) {
            work.poseTransition();
            Aim aim = AgentPlannerGeometry.aim(pose, point);
            AimError error = AgentPlannerGeometry.aimError(pose, point, aim);
            worst = AgentPlannerCosts.maximum(worst, AgentPlannerCosts.mutationCost(
                    pose, point, cameraLimit, interactions, blocksBroken, blocksPlaced));
            output.add(pose.aimed(aim, error));
        }
        AgentPlannerCosts.merge(costs, node.id(), Objects.requireNonNull(worst, "mutation cost"));
        return AgentProgramPlanner.distinct(output);
    }

    static List<Pose> analyzeContainer(
            ActionDsl.Node node,
            List<Pose> input,
            float cameraLimit,
            Map<String, ActionDslCompiler.Cost> costs,
            Set<KnownSurface> knownSurfaces,
            Map<String, MutationAim> mutationAims,
            PlanningWork work,
            MutationSurface containerSurface,
            long interactions) {
        return analyzeOwnedMenu(
                node, input, cameraLimit, costs, knownSurfaces, mutationAims, work,
                containerSurface, interactions,
                CONTAINER_TICK_UPPER_BOUND, "container", false, Double.POSITIVE_INFINITY);
    }

    static List<Pose> analyzeOwnedMenu(
            ActionDsl.Node node,
            List<Pose> input,
            float cameraLimit,
            Map<String, ActionDslCompiler.Cost> costs,
            Set<KnownSurface> knownSurfaces,
            Map<String, MutationAim> mutationAims,
            PlanningWork work,
            MutationSurface menuSurface,
            long interactions,
            long tickUpperBound,
            String costLabel,
            boolean restoreAdmittedPose,
            double maxOneWayCameraDegrees) {
        KnownSurface surface = menuSurface.surface();
        Vec3 point = menuSurface.point();
        knownSurfaces.add(surface);
        MutationAim candidate = new MutationAim(surface.position(), surface.face(), point);
        MutationAim previous = mutationAims.putIfAbsent(node.id(), candidate);
        if (previous != null && !previous.equals(candidate)) {
            throw new PlanningException(
                    Code.PROGRAM_BUDGET_UNPROVABLE,
                    "Owned menu node resolves to more than one aim witness");
        }
        ActionDslCompiler.Cost worst = null;
        var output = new ArrayList<Pose>(input.size());
        for (Pose pose : input) {
            work.poseTransition();
            ActionDslCompiler.Cost aim = AgentPlannerCosts.mutationCost(
                    pose, point, cameraLimit, interactions, 0, 0);
            if (aim.cameraDegrees() > maxOneWayCameraDegrees) {
                throw new PlanningException(
                        Code.PROGRAM_BUDGET_UNPROVABLE,
                        costLabel + " target exceeds the 270-degree one-way camera limit; "
                                + "put face_known_position immediately before this node");
            }
            var bounded = new ActionDslCompiler.Cost(
                    Math.multiplyExact(tickUpperBound, TICK_MILLIS),
                    tickUpperBound,
                    0.0D,
                    restoreAdmittedPose
                            ? aim.cameraDegrees() * 2.0D : aim.cameraDegrees(),
                    interactions,
                    0,
                    0);
            worst = AgentPlannerCosts.maximum(worst, bounded);
            Aim target = AgentPlannerGeometry.aim(pose, point);
            output.add(restoreAdmittedPose
                    ? pose : pose.aimed(target, AgentPlannerGeometry.aimError(pose, point, target)));
        }
        AgentPlannerCosts.merge(costs, node.id(), Objects.requireNonNull(worst, costLabel + " cost"));
        return AgentProgramPlanner.distinct(output);
    }

    private record MutationBatchTarget(ActionDsl.Node node, MutationSurface surface) {
        private MutationBatchTarget {
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(surface, "surface");
        }
    }

    private record BatchPath(
            List<MutationBatchTarget> targets,
            Pose pose,
            ActionDslCompiler.Cost cost) {
        private BatchPath {
            targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
            Objects.requireNonNull(pose, "pose");
            Objects.requireNonNull(cost, "cost");
        }
    }
}
