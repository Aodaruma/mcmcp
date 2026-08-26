package dev.aod.mcmcp.agent.action;

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
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Pure admission planner for map- and pose-dependent Action DSL primitive costs. */
public final class AgentPrimitivePlanner {
    private static final long TICK_MILLIS = 50L;
    private static final int MAX_ABSTRACT_POSES = 4_096;
    private static final double MAX_CELL_HORIZONTAL_ERROR = Math.sqrt(0.5D);
    private static final double NAVIGATION_VERTICAL_ERROR_ABOVE = 0.75D;
    private static final double FACE_COMPLETION_ERROR_DEGREES = 1.5D;
    private static final double NAVIGATION_TRAJECTORY_FACTOR = 1.5D;
    private static final double VERTICAL_ARC_ALLOWANCE = 1.5D;
    private static final int MAX_TOTAL_ROUTE_EXPANSIONS = 32_768;
    private static final int MAX_POSE_TRANSITIONS = 16_384;

    private AgentPrimitivePlanner() {
    }

    public static Analysis analyze(
            ActionDsl.Program program,
            KnownTraversabilitySnapshot map,
            DeterministicAStar pathfinder,
            Pose initialPose,
            Optional<ObservationFrame> latestFrame,
            float maxCameraDegreesPerTick) {
        return analyze(
                program, map, pathfinder, initialPose, latestFrame,
                maxCameraDegreesPerTick, () -> true);
    }

    public static Analysis analyze(
            ActionDsl.Program program,
            KnownTraversabilitySnapshot map,
            DeterministicAStar pathfinder,
            Pose initialPose,
            Optional<ObservationFrame> latestFrame,
            float maxCameraDegreesPerTick,
            BooleanSupplier canContinue) {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(map, "map");
        Objects.requireNonNull(pathfinder, "pathfinder");
        Objects.requireNonNull(initialPose, "initialPose");
        Objects.requireNonNull(latestFrame, "latestFrame");
        Objects.requireNonNull(canContinue, "canContinue");
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
        var routeCache = new LinkedHashMap<RouteKey, RoutePlan>();
        var work = new PlanningWork(canContinue);
        analyzeSequence(
                program.body(),
                List.of(initialPose),
                map,
                pathfinder,
                latestFrame,
                maxCameraDegreesPerTick,
                costs,
                routeDependencies,
                knownTargets,
                routeCache,
                work);
        return new Analysis(costs, routeDependencies, knownTargets);
    }

    public static RoutePlan requireRoute(
            KnownTraversabilitySnapshot map,
            DeterministicAStar pathfinder,
            NavCell start,
            ActionDsl.Position target) {
        Objects.requireNonNull(map, "map");
        Objects.requireNonNull(pathfinder, "pathfinder");
        Objects.requireNonNull(start, "start");
        NavCell destination = navCell(target);
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

    public static MinecraftActionPrimitiveExecutor.KnownFaceTarget requireKnownFaceTarget(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.Position target) {
        if (!knownTarget(map, latestFrame, target)) {
            throw new PlanningException(Code.TARGET_UNKNOWN, "Face target is not current known evidence");
        }
        return new MinecraftActionPrimitiveExecutor.KnownFaceTarget(
                map.worldSessionId(), map.worldRevision(), target);
    }

    public static boolean knownTarget(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.Position target) {
        Objects.requireNonNull(map, "map");
        Objects.requireNonNull(latestFrame, "latestFrame");
        Objects.requireNonNull(target, "target");
        if (!map.dimension().equals(target.dimension())) {
            return false;
        }
        if (map.containsCell(navCell(target))) {
            return true;
        }
        return latestFrame.stream()
                .filter(frame -> frame.dimension().value().equals(target.dimension()))
                .flatMap(frame -> frame.records().stream())
                .filter(record -> record.worldRevision() == map.worldRevision())
                .anyMatch(record -> matches(record, target));
    }

    private static List<Pose> analyzeSequence(
            List<ActionDsl.Node> nodes,
            List<Pose> input,
            KnownTraversabilitySnapshot map,
            DeterministicAStar pathfinder,
            Optional<ObservationFrame> latestFrame,
            float cameraLimit,
            Map<String, ActionDslCompiler.Cost> costs,
            Map<TraversabilityEdge.Key, TraversabilityEdge> routeDependencies,
            Set<ActionDsl.Position> knownTargets,
            Map<RouteKey, RoutePlan> routeCache,
            PlanningWork work) {
        List<Pose> states = input;
        for (ActionDsl.Node node : nodes) {
            work.check();
            states = analyzeNode(
                    node, states, map, pathfinder, latestFrame, cameraLimit,
                    costs, routeDependencies, knownTargets, routeCache, work);
        }
        return states;
    }

    private static List<Pose> analyzeNode(
            ActionDsl.Node node,
            List<Pose> input,
            KnownTraversabilitySnapshot map,
            DeterministicAStar pathfinder,
            Optional<ObservationFrame> latestFrame,
            float cameraLimit,
            Map<String, ActionDslCompiler.Cost> costs,
            Map<TraversabilityEdge.Key, TraversabilityEdge> routeDependencies,
            Set<ActionDsl.Position> knownTargets,
            Map<RouteKey, RoutePlan> routeCache,
            PlanningWork work) {
        if (node instanceof ActionDsl.WaitTicks) {
            return input;
        }
        if (node instanceof ActionDsl.NavigateToKnown navigate) {
            knownTargets.add(navigate.target());
            ActionDslCompiler.Cost worst = null;
            var output = new ArrayList<Pose>(input.size());
            for (Pose pose : input) {
                work.poseTransition();
                var routeKey = new RouteKey(pose.cell(), navCell(navigate.target()));
                RoutePlan route = routeCache.get(routeKey);
                if (route == null) {
                    var result = pathfinder.findRoute(
                            map,
                            routeKey.start(),
                            routeKey.target(),
                            work::canContinue,
                            work::routeExpansion);
                    route = requireRouteResult(result);
                    routeCache.put(routeKey, route);
                }
                addRouteDependencies(map, route, routeDependencies);
                worst = maximum(worst, navigationCost(route, pose));
                if (route.edges().isEmpty()) {
                    if (!zeroEdgeWithinTolerance(pose, navigate.target(), navigate.tolerance())) {
                        throw new PlanningException(
                                Code.NO_KNOWN_PATH,
                                "A zero-edge route cannot prove the requested tolerance");
                    }
                    output.add(pose);
                } else {
                    output.add(pose.at(navCell(navigate.target()), navigate.tolerance()));
                }
            }
            merge(costs, node.id(), Objects.requireNonNull(worst, "navigation cost"));
            return distinct(output);
        }
        if (node instanceof ActionDsl.FaceKnownPosition face) {
            requireKnownFaceTarget(map, latestFrame, face.target());
            knownTargets.add(face.target());
            ActionDslCompiler.Cost worst = null;
            var output = new ArrayList<Pose>(input.size());
            for (Pose pose : input) {
                work.poseTransition();
                Aim aim = aim(pose, face.target());
                AimError aimError = aimError(pose, face.target(), aim);
                worst = maximum(worst, faceCost(pose, face.target(), cameraLimit));
                output.add(pose.aimed(aim, aimError));
            }
            merge(costs, node.id(), Objects.requireNonNull(worst, "camera cost"));
            return distinct(output);
        }
        if (node instanceof ActionDsl.If conditional) {
            var output = new ArrayList<Pose>();
            output.addAll(analyzeSequence(
                    conditional.thenBranch(), input, map, pathfinder,
                    latestFrame, cameraLimit, costs, routeDependencies,
                    knownTargets, routeCache, work));
            output.addAll(analyzeSequence(
                    conditional.elseBranch(), input, map, pathfinder,
                    latestFrame, cameraLimit, costs, routeDependencies,
                    knownTargets, routeCache, work));
            return distinct(output);
        }
        var repeat = (ActionDsl.Repeat) node;
        List<Pose> output = input;
        for (int count = 0; count < repeat.count(); count++) {
            output = analyzeSequence(
                    repeat.body(), output, map, pathfinder,
                    latestFrame, cameraLimit, costs, routeDependencies,
                    knownTargets, routeCache, work);
        }
        return output;
    }

    private static List<Pose> distinct(List<Pose> poses) {
        var result = new LinkedHashSet<>(poses);
        if (result.size() > MAX_ABSTRACT_POSES) {
            throw new ActionDslException(
                    ActionDslException.Code.PROGRAM_BUDGET_UNPROVABLE,
                    "Action pose analysis exceeds " + MAX_ABSTRACT_POSES + " states");
        }
        return List.copyOf(result);
    }

    private static void addRouteDependencies(
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

    public static ActionDslCompiler.Cost faceCost(
            Pose pose, ActionDsl.Position target, float maxCameraDegreesPerTick) {
        Objects.requireNonNull(pose, "pose");
        if (!Float.isFinite(maxCameraDegreesPerTick) || maxCameraDegreesPerTick <= 0.0F) {
            throw new IllegalArgumentException("camera limit must be positive");
        }
        Aim aim = aim(pose, Objects.requireNonNull(target, "target"));
        AimError aimError = aimError(pose, target, aim);
        double camera = Math.min(360.0D,
                angularError(pose.yaw(), pose.pitch(), aim.yaw(), aim.pitch())
                        + pose.orientationErrorDegrees()
                        + aimError.totalDegrees());
        long ticks = Math.max(1L, (long) Math.ceil(camera / maxCameraDegreesPerTick));
        return new ActionDslCompiler.Cost(
                Math.multiplyExact(ticks, TICK_MILLIS),
                ticks,
                0.0D,
                camera,
                0,
                0,
                0);
    }

    /** Replaces the first center-to-center edge with the real pose-to-first-waypoint distance. */
    public static ActionDslCompiler.Cost navigationCost(RoutePlan route, Pose pose) {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(pose, "pose");
        if (!route.cells().getFirst().equals(pose.cell())) {
            throw new IllegalArgumentException("route does not start at the supplied pose cell");
        }
        double geometricDistance = 0.0D;
        if (!route.edges().isEmpty()) {
            NavCell waypoint = route.cells().get(1);
            double horizontal = Math.hypot(
                    pose.x() - (waypoint.x() + 0.5D),
                    pose.z() - (waypoint.z() + 0.5D))
                    + pose.horizontalPositionError();
            double vertical = Math.max(
                    Math.abs(pose.y() - pose.yErrorBelow() - waypoint.y()),
                    Math.abs(pose.y() + pose.yErrorAbove() - waypoint.y()));
            geometricDistance = route.distanceBlocks()
                    - route.edges().getFirst().key().length()
                    + Math.hypot(horizontal, vertical);
        }
        long verticalEdges = route.edges().stream()
                .filter(edge -> edge.key().from().y() != edge.key().to().y())
                .count();
        // This is an enforced per-occurrence trajectory allowance, not a chord estimate.
        double distance = geometricDistance * NAVIGATION_TRAJECTORY_FACTOR
                + verticalEdges * VERTICAL_ARC_ALLOWANCE;
        var routeCost = route.toDslPrimitiveCost();
        return new ActionDslCompiler.Cost(
                routeCost.durationMillis(),
                routeCost.ticks(),
                distance,
                routeCost.cameraDegrees(),
                routeCost.interactions(),
                routeCost.blocksBroken(),
                routeCost.blocksPlaced());
    }

    private static void merge(
            Map<String, ActionDslCompiler.Cost> costs,
            String nodeId,
            ActionDslCompiler.Cost cost) {
        costs.merge(nodeId, cost, AgentPrimitivePlanner::maximum);
    }

    private static ActionDslCompiler.Cost maximum(
            ActionDslCompiler.Cost left,
            ActionDslCompiler.Cost right) {
        if (left == null) return right;
        return new ActionDslCompiler.Cost(
                Math.max(left.durationMillis(), right.durationMillis()),
                Math.max(left.ticks(), right.ticks()),
                Math.max(left.distanceBlocks(), right.distanceBlocks()),
                Math.max(left.cameraDegrees(), right.cameraDegrees()),
                Math.max(left.interactions(), right.interactions()),
                Math.max(left.blocksBroken(), right.blocksBroken()),
                Math.max(left.blocksPlaced(), right.blocksPlaced()));
    }

    private static boolean matches(ObservationRecord record, ActionDsl.Position target) {
        if (record instanceof ObservationRecord.VisibleSurface surface) {
            var position = surface.position();
            return position.dimension().value().equals(target.dimension())
                    && position.x() == target.x()
                    && position.y() == target.y()
                    && position.z() == target.z();
        }
        if (record instanceof ObservationRecord.VisibleEntity entity) {
            var position = entity.position();
            return position.dimension().value().equals(target.dimension())
                    && floor(position.x()) == target.x()
                    && floor(position.y()) == target.y()
                    && floor(position.z()) == target.z();
        }
        return false;
    }

    private static Aim aim(Pose pose, ActionDsl.Position target) {
        double dx = target.x() + 0.5D - pose.x();
        double dy = target.y() + 0.5D - (pose.y() + pose.eyeHeight());
        double dz = target.z() + 0.5D - pose.z();
        double horizontal = Math.hypot(dx, dz);
        if (horizontal < 1.0e-9D && Math.abs(dy) < 1.0e-9D) {
            throw new PlanningException(Code.TARGET_UNKNOWN, "Face target coincides with the eye position");
        }
        return new Aim(
                (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D),
                (float) -Math.toDegrees(Math.atan2(dy, horizontal)));
    }

    private static AimError aimError(Pose pose, ActionDsl.Position target, Aim nominal) {
        double dx = target.x() + 0.5D - pose.x();
        double dy = target.y() + 0.5D - (pose.y() + pose.eyeHeight());
        double dz = target.z() + 0.5D - pose.z();
        double horizontal = Math.hypot(dx, dz);
        double horizontalError = pose.horizontalPositionError();
        double yawError = horizontal <= horizontalError
                ? 180.0D
                : Math.toDegrees(Math.asin(Math.min(1.0D, horizontalError / horizontal)));

        double dyMin = dy - pose.yErrorAbove();
        double dyMax = dy + pose.yErrorBelow();
        double horizontalMin = Math.max(0.0D, horizontal - horizontalError);
        double horizontalMax = horizontal + horizontalError;
        double pitchError;
        if (horizontalMin == 0.0D && dyMin <= 0.0D && dyMax >= 0.0D) {
            pitchError = Math.max(
                    Math.abs(-90.0D - nominal.pitch()),
                    Math.abs(90.0D - nominal.pitch()));
        } else {
            pitchError = 0.0D;
            for (double candidateDy : new double[] {dyMin, dyMax}) {
                for (double candidateHorizontal : new double[] {horizontalMin, horizontalMax}) {
                    double candidate = -Math.toDegrees(
                            Math.atan2(candidateDy, candidateHorizontal));
                    pitchError = Math.max(pitchError, Math.abs(candidate - nominal.pitch()));
                }
            }
        }
        return new AimError(yawError, pitchError);
    }

    private static boolean zeroEdgeWithinTolerance(
            Pose pose, ActionDsl.Position target, double tolerance) {
        double horizontal = Math.hypot(
                target.x() + 0.5D - pose.x(),
                target.z() + 0.5D - pose.z()) + pose.horizontalPositionError();
        double minimumY = pose.y() - pose.yErrorBelow();
        double maximumY = pose.y() + pose.yErrorAbove();
        return horizontal <= tolerance
                && minimumY >= target.y()
                && maximumY <= target.y() + NAVIGATION_VERTICAL_ERROR_ABOVE;
    }

    private static double angularError(
            float yaw, float pitch, float desiredYaw, float desiredPitch) {
        return Math.abs(Mth.wrapDegrees(desiredYaw - yaw))
                + Math.abs(Mth.clamp(desiredPitch, -90.0F, 90.0F) - pitch);
    }

    private static NavCell navCell(ActionDsl.Position position) {
        Objects.requireNonNull(position, "position");
        return new NavCell(position.dimension(), position.x(), position.y(), position.z());
    }

    private static int floor(double value) {
        double result = Math.floor(value);
        if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("position is outside the navigation coordinate range");
        }
        return (int) result;
    }

    private static RoutePlan requireRouteResult(DeterministicAStar.SearchResult result) {
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

    public record Pose(
            NavCell cell,
            double x,
            double y,
            double z,
            double eyeHeight,
            float yaw,
            float pitch,
            double horizontalPositionError,
            double yErrorBelow,
            double yErrorAbove,
            double orientationErrorDegrees) {
        public Pose(
                NavCell cell,
                double x,
                double y,
                double z,
                double eyeHeight,
                float yaw,
                float pitch) {
            this(cell, x, y, z, eyeHeight, yaw, pitch, 0.0D, 0.0D, 0.0D, 0.0D);
        }

        public Pose {
            Objects.requireNonNull(cell, "cell");
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Double.isFinite(eyeHeight) || eyeHeight <= 0.0D
                    || !Float.isFinite(yaw) || !Float.isFinite(pitch)
                    || !Double.isFinite(horizontalPositionError)
                    || !Double.isFinite(yErrorBelow) || !Double.isFinite(yErrorAbove)
                    || !Double.isFinite(orientationErrorDegrees)
                    || horizontalPositionError < 0.0D || yErrorBelow < 0.0D
                    || yErrorAbove < 0.0D || orientationErrorDegrees < 0.0D
                    || orientationErrorDegrees > 360.0D) {
                throw new IllegalArgumentException("pose must be finite");
            }
        }

        private Pose at(NavCell target, double tolerance) {
            return new Pose(
                    target,
                    target.x() + 0.5D,
                    target.y(),
                    target.z() + 0.5D,
                    eyeHeight,
                    yaw,
                    pitch,
                    Math.min(tolerance, MAX_CELL_HORIZONTAL_ERROR),
                    0.0D,
                    NAVIGATION_VERTICAL_ERROR_ABOVE,
                    orientationErrorDegrees);
        }

        private Pose aimed(Aim aim, AimError aimError) {
            return new Pose(
                    cell, x, y, z, eyeHeight, aim.yaw(), aim.pitch(),
                    horizontalPositionError, yErrorBelow, yErrorAbove,
                    Math.min(360.0D,
                            aimError.totalDegrees() + FACE_COMPLETION_ERROR_DEGREES));
        }
    }

    public record Analysis(
            Map<String, ActionDslCompiler.Cost> primitiveCosts,
            Map<TraversabilityEdge.Key, TraversabilityEdge> routeDependencies,
            Set<ActionDsl.Position> knownTargets) {
        public Analysis {
            primitiveCosts = Map.copyOf(Objects.requireNonNull(primitiveCosts, "primitiveCosts"));
            routeDependencies = Map.copyOf(
                    Objects.requireNonNull(routeDependencies, "routeDependencies"));
            knownTargets = Set.copyOf(Objects.requireNonNull(knownTargets, "knownTargets"));
        }

        public Optional<ActionDslCompiler.Cost> worstCase(ActionDsl.Node primitive) {
            return Optional.ofNullable(primitiveCosts.get(primitive.id()));
        }
    }

    public enum Code { TARGET_UNKNOWN, NO_KNOWN_PATH, TIMEOUT, PROGRAM_BUDGET_UNPROVABLE }

    public static final class PlanningException extends IllegalArgumentException {
        private final Code code;

        private PlanningException(Code code, String message) {
            super(message);
            this.code = Objects.requireNonNull(code, "code");
        }

        public Code code() {
            return code;
        }
    }

    private record Aim(float yaw, float pitch) {
    }

    private record RouteKey(NavCell start, NavCell target) {
        private RouteKey {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(target, "target");
        }
    }

    private static final class PlanningWork {
        private final BooleanSupplier canContinue;
        private int routeExpansions;
        private int poseTransitions;

        private PlanningWork(BooleanSupplier canContinue) {
            this.canContinue = canContinue;
        }

        private boolean canContinue() {
            return canContinue.getAsBoolean();
        }

        private void check() {
            if (!canContinue()) {
                throw new PlanningException(Code.TIMEOUT, "Agent planning deadline expired");
            }
        }

        private void routeExpansion() {
            check();
            if (++routeExpansions > MAX_TOTAL_ROUTE_EXPANSIONS) {
                throw workLimit("route expansions", MAX_TOTAL_ROUTE_EXPANSIONS);
            }
        }

        private void poseTransition() {
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

    private record AimError(double yawDegrees, double pitchDegrees) {
        private AimError {
            if (!Double.isFinite(yawDegrees) || !Double.isFinite(pitchDegrees)
                    || yawDegrees < 0.0D || pitchDegrees < 0.0D) {
                throw new IllegalArgumentException("aim error must be finite and non-negative");
            }
        }

        private double totalDegrees() {
            return Math.min(360.0D, yawDegrees + pitchDegrees);
        }
    }
}
