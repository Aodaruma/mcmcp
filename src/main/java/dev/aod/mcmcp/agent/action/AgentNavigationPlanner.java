package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.ApproachPlan;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.Code;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.PlanningException;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.Pose;
import dev.aod.mcmcp.agent.action.AgentProgramPlanner.PlanningWork;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.navigation.DeterministicAStar;
import dev.aod.mcmcp.agent.navigation.KnownTraversabilitySnapshot;
import dev.aod.mcmcp.agent.navigation.NavCell;
import dev.aod.mcmcp.agent.navigation.RoutePlan;
import dev.aod.mcmcp.agent.navigation.TraversabilityEdge;
import dev.aod.mcmcp.agent.observation.ObservationFrame;
import dev.aod.mcmcp.agent.observation.ObservationRecord;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static dev.aod.mcmcp.agent.action.AgentPlannerGeometry.MAX_BREAK_REACH_BLOCKS;
import static dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.NAVIGATION_VERTICAL_ERROR_ABOVE;

/** 既知の経路と接近セルを選び、経路が依存する通行証拠を収集する。 */
final class AgentNavigationPlanner {
    private AgentNavigationPlanner() {
    }

    private static final double APPROACH_REACH_BLOCKS = 4.25D;

    private static final double APPROACH_EYE_HEIGHT = 1.62D;

    static final double APPROACH_TOLERANCE = 0.25D;

    static RoutePlan requireRoute(
            KnownTraversabilitySnapshot map,
            DeterministicAStar pathfinder,
            NavCell start,
            ActionDsl.Position target) {
        Objects.requireNonNull(map, "map");
        Objects.requireNonNull(pathfinder, "pathfinder");
        Objects.requireNonNull(start, "start");
        NavCell destination = AgentPlannerGeometry.navCell(target);
        var result = pathfinder.findRoute(map, start, destination);
        if (result.route().isPresent()) {
            return result.route().orElseThrow();
        }
        var reason = result.failure().orElseThrow();
        throw new PlanningException(
                reason == DeterministicAStar.FailureReason.TARGET_UNKNOWN
                        ? Code.TARGET_UNKNOWN : Code.NO_KNOWN_PATH,
                "No policy-approved route is available: " + reason.name().toLowerCase());
    }

    static void addRouteDependencies(
            KnownTraversabilitySnapshot map,
            RoutePlan route,
            Map<TraversabilityEdge.Key, TraversabilityEdge> dependencies) {
        for (var edge : route.edges()) {
            dependencies.putIfAbsent(edge.key(), edge);
            var from = edge.key().from();
            var to = edge.key().to();
            if (!from.horizontallyDiagonalTo(to)) continue;
            for (var side : List.of(
                    new NavCell(from.dimension(), to.x(), from.y(), from.z()),
                    new NavCell(from.dimension(), from.x(), from.y(), to.z()))) {
                var key = new TraversabilityEdge.Key(from, side);
                dependencies.putIfAbsent(
                        key,
                        map.edge(key).orElseThrow(() -> new PlanningException(
                                Code.NO_KNOWN_PATH,
                                "A diagonal route lost its corner-clear evidence")));
            }
        }
    }

    /**
     * Legacy geometry-only helper retained for API compatibility.
     * Product Action admission uses the delivery-backed {@link Pose} overload below.
     */
    static ApproachPlan requireApproachPlan(
            KnownTraversabilitySnapshot map,
            DeterministicAStar pathfinder,
            NavCell start,
            ActionDsl.Position target) {
        return requireApproachPlan(
                map, pathfinder, start, target, null, APPROACH_EYE_HEIGHT, null);
    }

    /**
     * Selects an approach cell which remains within mutation reach of at least one current,
     * delivery-backed ray witness even at the admitted navigation settlement error. Reach is
     * safe for both the current eye height and standing height because navigation input cleanup
     * may release a crouched pose before arrival.
     */
    static ApproachPlan requireApproachPlan(
            KnownTraversabilitySnapshot map,
            DeterministicAStar pathfinder,
            Pose startPose,
            ActionDsl.Position target,
            String expectedBlock,
            Optional<ObservationFrame> latestFrame,
            long surfaceBarrierWorldRevision) {
        return requireApproachPlan(
                map,
                pathfinder,
                startPose,
                target,
                expectedBlock,
                latestFrame,
                surfaceBarrierWorldRevision,
                null);
    }

    static ApproachPlan requireApproachPlan(
            KnownTraversabilitySnapshot map,
            DeterministicAStar pathfinder,
            Pose startPose,
            ActionDsl.Position target,
            String expectedBlock,
            Optional<ObservationFrame> latestFrame,
            long surfaceBarrierWorldRevision,
            PlanningWork work) {
        Objects.requireNonNull(startPose, "startPose");
        Objects.requireNonNull(expectedBlock, "expectedBlock");
        Objects.requireNonNull(latestFrame, "latestFrame");
        AgentSurfaceEvidence.requireSurfaceBarrierWorldRevision(map, surfaceBarrierWorldRevision);
        List<Vec3> rayWitnesses = latestFrame.stream()
                .filter(frame -> frame.dimension().value().equals(map.dimension()))
                .flatMap(frame -> frame.records().stream())
                .filter(ObservationRecord.VisibleSurface.class::isInstance)
                .map(ObservationRecord.VisibleSurface.class::cast)
                .filter(surface -> surface.worldRevision() >= surfaceBarrierWorldRevision
                        && surface.worldRevision() <= map.worldRevision())
                .filter(surface -> AgentSurfaceEvidence.matches(surface, target, expectedBlock))
                .filter(surface -> surface.rayHit() != null)
                .map(AgentPlannerGeometry::rayHit)
                .toList();
        if (rayWitnesses.isEmpty()) {
            throw new PlanningException(
                    Code.TARGET_UNKNOWN,
                    "Approach target requires a current delivered ray witness");
        }
        return requireApproachPlan(
                map,
                pathfinder,
                startPose.cell(),
                target,
                rayWitnesses,
                startPose.eyeHeight(),
                work);
    }

    private static ApproachPlan requireApproachPlan(
            KnownTraversabilitySnapshot map,
            DeterministicAStar pathfinder,
            NavCell start,
            ActionDsl.Position target,
            List<Vec3> rayWitnesses,
            double eyeHeight,
            PlanningWork work) {
        Objects.requireNonNull(map, "map");
        Objects.requireNonNull(pathfinder, "pathfinder");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(target, "target");
        if (!map.dimension().equals(target.dimension())
                || !map.dimension().equals(start.dimension())) {
            throw new PlanningException(
                    Code.NO_KNOWN_PATH, "Approach target is outside the current map boundary");
        }

        var cells = new java.util.TreeSet<NavCell>();
        cells.add(start);
        for (TraversabilityEdge edge : map.edges().values()) {
            if (edge.traversable()) {
                cells.add(edge.key().from());
                cells.add(edge.key().to());
            }
        }

        ApproachPlan best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        double bestReach = Double.POSITIVE_INFINITY;
        for (NavCell candidate : cells) {
            double reach = rayWitnesses == null
                    ? approachReachSquared(candidate, target)
                    : rayWitnesses.stream()
                            .mapToDouble(witness ->
                                    approachRayReachSquared(candidate, witness, eyeHeight))
                            .min()
                            .orElseThrow();
            double reachLimit = rayWitnesses == null
                    ? APPROACH_REACH_BLOCKS : MAX_BREAK_REACH_BLOCKS;
            if (reach > reachLimit * reachLimit) {
                continue;
            }
            DeterministicAStar.SearchResult result = work == null
                    ? pathfinder.findRoute(map, start, candidate)
                    : pathfinder.findRoute(
                            map, start, candidate, work::canContinue, work::routeExpansion);
            if (result.route().isEmpty()) {
                continue;
            }
            RoutePlan route = result.route().orElseThrow();
            boolean better = route.distanceBlocks() < bestDistance - 1.0e-9D
                    || Math.abs(route.distanceBlocks() - bestDistance) <= 1.0e-9D
                            && (reach < bestReach - 1.0e-9D
                                    || Math.abs(reach - bestReach) <= 1.0e-9D
                                            && (best == null
                                                    || candidate.compareTo(best.anchor()) < 0));
            if (better) {
                best = new ApproachPlan(route, candidate);
                bestDistance = route.distanceBlocks();
                bestReach = reach;
            }
        }
        if (best == null) {
            throw new PlanningException(
                    Code.NO_KNOWN_PATH,
                    "No policy-approved interaction-range approach cell is available");
        }
        return best;
    }

    private static double approachReachSquared(
            NavCell cell, ActionDsl.Position target) {
        double eyeX = cell.x() + 0.5D;
        double eyeY = cell.y() + APPROACH_EYE_HEIGHT;
        double eyeZ = cell.z() + 0.5D;
        double closestX = Mth.clamp(eyeX, target.x(), target.x() + 1.0D);
        double closestY = Mth.clamp(eyeY, target.y(), target.y() + 1.0D);
        double closestZ = Mth.clamp(eyeZ, target.z(), target.z() + 1.0D);
        return AgentPlannerGeometry.square(eyeX - closestX)
                + AgentPlannerGeometry.square(eyeY - closestY)
                + AgentPlannerGeometry.square(eyeZ - closestZ);
    }

    static double approachRayReachSquared(
            NavCell cell, Vec3 witness, double eyeHeight) {
        return Math.max(
                approachRayReachSquaredAtEyeHeight(cell, witness, eyeHeight),
                approachRayReachSquaredAtEyeHeight(
                        cell, witness, APPROACH_EYE_HEIGHT));
    }

    private static double approachRayReachSquaredAtEyeHeight(
            NavCell cell, Vec3 witness, double eyeHeight) {
        double eyeX = cell.x() + 0.5D;
        double eyeY = cell.y() + eyeHeight;
        double eyeZ = cell.z() + 0.5D;
        double horizontal = Math.hypot(witness.x - eyeX, witness.z - eyeZ)
                + APPROACH_TOLERANCE;
        double vertical = Math.max(
                Math.abs(witness.y - eyeY),
                Math.abs(witness.y - (eyeY + NAVIGATION_VERTICAL_ERROR_ABOVE)));
        return AgentPlannerGeometry.square(horizontal) + AgentPlannerGeometry.square(vertical);
    }

    static RoutePlan requireRouteResult(DeterministicAStar.SearchResult result) {
        if (result.route().isPresent()) {
            return result.route().orElseThrow();
        }
        var reason = result.failure().orElseThrow();
        if (reason == DeterministicAStar.FailureReason.SEARCH_CANCELLED) {
            throw new PlanningException(Code.TIMEOUT, "Agent planning deadline expired");
        }
        throw new PlanningException(
                reason == DeterministicAStar.FailureReason.TARGET_UNKNOWN
                        ? Code.TARGET_UNKNOWN : Code.NO_KNOWN_PATH,
                "No policy-approved route is available: " + reason.name().toLowerCase());
    }
}
