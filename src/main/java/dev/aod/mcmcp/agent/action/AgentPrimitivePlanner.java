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
import dev.aod.mcmcp.agent.observation.ObservationValues;
import net.minecraft.util.Mth;
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

/** Pure admission planner for map- and pose-dependent Action DSL primitive costs. */
public final class AgentPrimitivePlanner {
    private static final long TICK_MILLIS = 50L;
    private static final int MAX_ABSTRACT_POSES = 4_096;
    private static final double MAX_CELL_HORIZONTAL_ERROR = Math.sqrt(0.5D);
    private static final double NAVIGATION_VERTICAL_ERROR_ABOVE = 1.0D;
    private static final double FACE_COMPLETION_ERROR_DEGREES = 1.5D;
    private static final double NAVIGATION_TRAJECTORY_FACTOR = 1.5D;
    private static final double VERTICAL_ARC_ALLOWANCE = 1.5D;
    private static final int MAX_TOTAL_ROUTE_EXPANSIONS = 32_768;
    private static final int MAX_POSE_TRANSITIONS = 16_384;
    private static final double MAX_BREAK_REACH_BLOCKS = 4.5D;
    private static final double MAX_BREAK_EYE_ORIGIN_DRIFT = 0.125D;
    public static final long BREAK_TICK_UPPER_BOUND = 60L;
    public static final long BREAK_REOBSERVATION_TICKS = 40L;
    public static final long BLOCK_MUTATION_TICK_UPPER_BOUND = 100L;

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
        var knownSurfaces = new LinkedHashSet<KnownSurface>();
        var mutationAims = new LinkedHashMap<String, MutationAim>();
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
                knownSurfaces,
                mutationAims,
                routeCache,
                work);
        return new Analysis(costs, routeDependencies, knownTargets, knownSurfaces, mutationAims);
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

    public static KnownSurface requireKnownBreakSurface(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.BreakKnownFace target) {
        var required = new KnownSurface(
                target.target(), target.face(), target.expectedBlock());
        if (knownSurfaceRecord(map, latestFrame, required).isEmpty()) {
            throw new PlanningException(
                    Code.TARGET_UNKNOWN,
                    "Break target face is not current matching visible-surface evidence");
        }
        return required;
    }

    public static boolean knownSurface(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            KnownSurface required) {
        return knownSurfaceRecord(map, latestFrame, required).isPresent();
    }

    public static KnownSurface requireKnownSurface(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.Position position,
            String block) {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(block, "block");
        return latestFrame.stream()
                .filter(frame -> frame.dimension().value().equals(map.dimension()))
                .flatMap(frame -> frame.records().stream())
                .filter(ObservationRecord.VisibleSurface.class::isInstance)
                .map(ObservationRecord.VisibleSurface.class::cast)
                .filter(surface -> surface.worldRevision() == map.worldRevision())
                .filter(surface -> matches(surface, position, block))
                .map(surface -> new KnownSurface(
                        position,
                        ActionDsl.BlockFace.valueOf(surface.face().name()),
                        block))
                .findFirst()
                .orElseThrow(() -> new PlanningException(
                        Code.TARGET_UNKNOWN,
                        "Mutation target is not current matching visible-surface evidence"));
    }

    private static MutationSurface requireMutationSurface(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            List<Pose> poses,
            ActionDsl.Position position,
            String block,
            java.util.function.Predicate<ObservationRecord.VisibleSurface> allowed,
            String failure) {
        return latestFrame.stream()
                .filter(frame -> frame.dimension().value().equals(map.dimension()))
                .flatMap(frame -> frame.records().stream())
                .filter(ObservationRecord.VisibleSurface.class::isInstance)
                .map(ObservationRecord.VisibleSurface.class::cast)
                .filter(surface -> surface.worldRevision() == map.worldRevision())
                .filter(surface -> matches(surface, position, block))
                .filter(allowed)
                .filter(surface -> surface.rayHit() != null
                        && poses.stream().allMatch(pose -> mutationSurfaceValid(pose, surface)))
                .sorted(java.util.Comparator
                        .comparingInt((ObservationRecord.VisibleSurface surface) ->
                                surface.face() == ObservationRecord.Face.UP ? 0 : 1)
                        .thenComparingDouble(surface -> distanceSquared(
                                poses.getFirst(), surface.rayHit())))
                .map(surface -> new MutationSurface(
                        new KnownSurface(
                                position,
                                ActionDsl.BlockFace.valueOf(surface.face().name()),
                                block,
                                Boolean.TRUE.equals(surface.cropMature()) ? true : null),
                        rayHit(surface)))
                .findFirst()
                .orElseThrow(() -> new PlanningException(Code.TARGET_UNKNOWN, failure));
    }

    private static Optional<ObservationRecord.VisibleSurface> knownSurfaceRecord(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            KnownSurface required) {
        Objects.requireNonNull(map, "map");
        Objects.requireNonNull(latestFrame, "latestFrame");
        Objects.requireNonNull(required, "required");
        if (!map.dimension().equals(required.position().dimension())) return Optional.empty();
        return latestFrame.stream()
                .filter(frame -> frame.dimension().value().equals(map.dimension()))
                .flatMap(frame -> frame.records().stream())
                .filter(ObservationRecord.VisibleSurface.class::isInstance)
                .map(ObservationRecord.VisibleSurface.class::cast)
                .filter(surface -> surface.worldRevision() == map.worldRevision())
                .filter(surface -> matches(surface, required))
                .findFirst();
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
            Set<KnownSurface> knownSurfaces,
            Map<String, MutationAim> mutationAims,
            Map<RouteKey, RoutePlan> routeCache,
            PlanningWork work) {
        List<Pose> states = input;
        for (ActionDsl.Node node : nodes) {
            work.check();
            states = analyzeNode(
                    node, states, map, pathfinder, latestFrame, cameraLimit,
                    costs, routeDependencies, knownTargets, knownSurfaces,
                    mutationAims, routeCache, work);
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
            Set<KnownSurface> knownSurfaces,
            Map<String, MutationAim> mutationAims,
            Map<RouteKey, RoutePlan> routeCache,
            PlanningWork work) {
        if (node instanceof ActionDsl.WaitTicks || node instanceof ActionDsl.WaitUntil) {
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
                output.add(pose.at(navCell(navigate.target()), navigate.tolerance()));
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
        if (node instanceof ActionDsl.BreakKnownFace block) {
            var required = requireKnownBreakSurface(map, latestFrame, block);
            var surface = knownSurfaceRecord(map, latestFrame, required).orElseThrow();
            knownSurfaces.add(required);
            ActionDslCompiler.Cost worst = null;
            var output = new ArrayList<Pose>(input.size());
            for (Pose pose : input) {
                work.poseTransition();
                requireBreakPose(pose, surface, block);
                Vec3 point = MinecraftActionPrimitiveExecutor.blockFaceAimPoint(
                        block.target(), block.face());
                Aim aim = aim(pose, point);
                AimError aimError = aimError(pose, point, aim);
                worst = maximum(worst, breakCost(pose, block, cameraLimit));
                output.add(pose.aimed(aim, aimError));
            }
            merge(costs, node.id(), Objects.requireNonNull(worst, "break cost"));
            return distinct(output);
        }
        if (node instanceof ActionDsl.TillKnownBlock till) {
            MutationSurface surface = requireMutationSurface(
                    map, latestFrame, input, till.target(), till.expectedBlock(),
                    value -> value.face() != ObservationRecord.Face.DOWN,
                    "Till target requires a current non-DOWN visible surface");
            return analyzeMutation(
                    node, input, cameraLimit, costs, knownSurfaces, mutationAims, work,
                    surface, 1, 0, 0);
        }
        if (node instanceof ActionDsl.PlantKnownWheat plant) {
            if (!directlyAbove(plant.target(), plant.support())) {
                throw new PlanningException(
                        Code.TARGET_UNKNOWN, "Plant target must be directly above its support");
            }
            MutationSurface surface = requireMutationSurface(
                    map, latestFrame, input, plant.support(), "minecraft:farmland",
                    value -> value.face() == ObservationRecord.Face.UP,
                    "Plant support requires its current UP face");
            return analyzeMutation(
                    node, input, cameraLimit, costs, knownSurfaces, mutationAims, work,
                    surface, 0, 0, 1);
        }
        if (node instanceof ActionDsl.HarvestKnownWheat harvest) {
            MutationSurface surface = requireMutationSurface(
                    map, latestFrame, input, harvest.target(), "minecraft:wheat",
                    value -> Boolean.TRUE.equals(value.cropMature()),
                    "Harvest target requires current crop_mature=true evidence");
            return analyzeMutation(
                    node, input, cameraLimit, costs, knownSurfaces, mutationAims, work,
                    surface, 0, 1, 0);
        }
        if (node instanceof ActionDsl.OpenKnownFenceGate gate) {
            MutationSurface surface = requireMutationSurface(
                    map, latestFrame, input, gate.target(), "minecraft:oak_fence_gate",
                    value -> true,
                    "Fence gate target requires a current visible oak fence gate surface");
            return analyzeMutation(
                    node, input, cameraLimit, costs, knownSurfaces, mutationAims, work,
                    surface, 1, 0, 0);
        }
        if (node instanceof ActionDsl.If conditional) {
            var output = new ArrayList<Pose>();
            output.addAll(analyzeSequence(
                    conditional.thenBranch(), input, map, pathfinder,
                    latestFrame, cameraLimit, costs, routeDependencies,
                    knownTargets, knownSurfaces, mutationAims, routeCache, work));
            output.addAll(analyzeSequence(
                    conditional.elseBranch(), input, map, pathfinder,
                    latestFrame, cameraLimit, costs, routeDependencies,
                    knownTargets, knownSurfaces, mutationAims, routeCache, work));
            return distinct(output);
        }
        var repeat = (ActionDsl.Repeat) node;
        List<Pose> output = input;
        for (int count = 0; count < repeat.count(); count++) {
            output = analyzeSequence(
                    repeat.body(), output, map, pathfinder,
                    latestFrame, cameraLimit, costs, routeDependencies,
                    knownTargets, knownSurfaces, mutationAims, routeCache, work);
        }
        return output;
    }

    private static List<Pose> analyzeMutation(
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
            Aim aim = aim(pose, point);
            AimError error = aimError(pose, point, aim);
            worst = maximum(worst, mutationCost(
                    pose, point, cameraLimit, interactions, blocksBroken, blocksPlaced));
            output.add(pose.aimed(aim, error));
        }
        merge(costs, node.id(), Objects.requireNonNull(worst, "mutation cost"));
        return distinct(output);
    }

    private static boolean directlyAbove(
            ActionDsl.Position target, ActionDsl.Position support) {
        return target.dimension().equals(support.dimension())
                && target.x() == support.x()
                && target.y() == support.y() + 1
                && target.z() == support.z();
    }

    private static boolean mutationSurfaceValid(
            Pose pose, ObservationRecord.VisibleSurface surface) {
        var eye = surface.eyeOrigin();
        if (!eye.dimension().value().equals(pose.cell().dimension())) {
            return false;
        }
        double poseEyeY = pose.y() + pose.eyeHeight();
        if (Math.hypot(eye.x() - pose.x(), eye.z() - pose.z())
                        > pose.horizontalPositionError() + MAX_BREAK_EYE_ORIGIN_DRIFT
                || Math.abs(eye.y() - poseEyeY)
                        > Math.max(pose.yErrorBelow(), pose.yErrorAbove())
                                + MAX_BREAK_EYE_ORIGIN_DRIFT) {
            return false;
        }
        var hit = surface.rayHit();
        double distance = Math.sqrt(
                square(hit.x() - pose.x())
                        + square(hit.y() - poseEyeY)
                        + square(hit.z() - pose.z()));
        double poseError = Math.hypot(
                pose.horizontalPositionError(),
                Math.max(pose.yErrorBelow(), pose.yErrorAbove()));
        return distance + poseError <= MAX_BREAK_REACH_BLOCKS;
    }

    private static double distanceSquared(
            Pose pose, ObservationValues.WorldPosition point) {
        return square(point.x() - pose.x())
                + square(point.y() - (pose.y() + pose.eyeHeight()))
                + square(point.z() - pose.z());
    }

    private static Vec3 rayHit(ObservationRecord.VisibleSurface surface) {
        var hit = Objects.requireNonNull(surface.rayHit(), "rayHit");
        return new Vec3(hit.x(), hit.y(), hit.z());
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

    public static ActionDslCompiler.Cost breakCost(
            Pose pose, ActionDsl.BreakKnownFace target, float maxCameraDegreesPerTick) {
        Objects.requireNonNull(target, "target");
        Vec3 point = MinecraftActionPrimitiveExecutor.blockFaceAimPoint(
                target.target(), target.face());
        var aim = aim(pose, point);
        var error = aimError(pose, point, aim);
        double camera = Math.min(360.0D,
                angularError(pose.yaw(), pose.pitch(), aim.yaw(), aim.pitch())
                        + pose.orientationErrorDegrees() + error.totalDegrees());
        long aimTicks = Math.max(
                1L, (long) Math.ceil(camera / maxCameraDegreesPerTick));
        long boundedTicks = Math.addExact(
                aimTicks,
                Math.addExact(BREAK_REOBSERVATION_TICKS, BREAK_TICK_UPPER_BOUND));
        return new ActionDslCompiler.Cost(
                Math.multiplyExact(boundedTicks, TICK_MILLIS),
                boundedTicks,
                0.0D,
                camera,
                0,
                1,
                0);
    }

    public static ActionDslCompiler.Cost mutationCost(
            Pose pose,
            Vec3 aimPoint,
            float maxCameraDegreesPerTick,
            long interactions,
            long blocksBroken,
            long blocksPlaced) {
        Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(aimPoint, "aimPoint");
        if (!Float.isFinite(maxCameraDegreesPerTick) || maxCameraDegreesPerTick <= 0.0F) {
            throw new IllegalArgumentException("camera limit must be positive");
        }
        Aim aim = aim(pose, aimPoint);
        AimError error = aimError(pose, aimPoint, aim);
        double camera = Math.min(360.0D,
                angularError(pose.yaw(), pose.pitch(), aim.yaw(), aim.pitch())
                        + pose.orientationErrorDegrees() + error.totalDegrees());
        long aimTicks = Math.max(1L, (long) Math.ceil(camera / maxCameraDegreesPerTick));
        long ticks = Math.addExact(aimTicks, BLOCK_MUTATION_TICK_UPPER_BOUND);
        return new ActionDslCompiler.Cost(
                Math.multiplyExact(ticks, TICK_MILLIS), ticks, 0.0D, camera,
                interactions, blocksBroken, blocksPlaced);
    }

    /** Replaces the first center-to-center edge with the real pose-to-first-waypoint distance. */
    public static ActionDslCompiler.Cost navigationCost(RoutePlan route, Pose pose) {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(pose, "pose");
        if (!route.cells().getFirst().equals(pose.cell())) {
            throw new IllegalArgumentException("route does not start at the supplied pose cell");
        }
        double geometricDistance;
        if (route.edges().isEmpty()) {
            NavCell waypoint = route.cells().getFirst();
            geometricDistance = Math.hypot(
                    pose.x() - (waypoint.x() + 0.5D),
                    pose.z() - (waypoint.z() + 0.5D))
                    + pose.horizontalPositionError();
        } else {
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

    /** Removes probe time already reserved by the admitted occurrence before a retry. */
    public static ActionDslCompiler.Cost navigationReplanCost(
            RoutePlan route, ActionDslCompiler.Cost planned) {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(planned, "planned");
        long reservedTicks = Math.multiplyExact(
                (long) route.probeEdgeCount(), RoutePlan.EXTRA_TICKS_PER_PROBE);
        long reservedMillis = Math.multiplyExact(reservedTicks, TICK_MILLIS);
        return new ActionDslCompiler.Cost(
                Math.subtractExact(planned.durationMillis(), reservedMillis),
                Math.subtractExact(planned.ticks(), reservedTicks),
                planned.distanceBlocks(),
                planned.cameraDegrees(),
                planned.interactions(),
                planned.blocksBroken(),
                planned.blocksPlaced());
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

    private static boolean matches(
            ObservationRecord.VisibleSurface surface, KnownSurface required) {
        var position = surface.position();
        var target = required.position();
        return position.dimension().value().equals(target.dimension())
                && position.x() == target.x()
                && position.y() == target.y()
                && position.z() == target.z()
                && surface.face().name().equals(required.face().name())
                && surface.block().value().equals(required.block())
                && (required.cropMature() == null
                        || required.cropMature().equals(surface.cropMature()));
    }

    private static boolean matches(
            ObservationRecord.VisibleSurface surface,
            ActionDsl.Position target,
            String block) {
        var position = surface.position();
        return position.dimension().value().equals(target.dimension())
                && position.x() == target.x()
                && position.y() == target.y()
                && position.z() == target.z()
                && surface.block().value().equals(block);
    }

    private static void requireBreakPose(
            Pose pose,
            ObservationRecord.VisibleSurface surface,
            ActionDsl.BreakKnownFace target) {
        var observedEye = surface.eyeOrigin();
        double poseEyeY = pose.y() + pose.eyeHeight();
        double horizontalDrift = Math.hypot(
                observedEye.x() - pose.x(), observedEye.z() - pose.z());
        double verticalDrift = Math.abs(observedEye.y() - poseEyeY);
        if (horizontalDrift
                        > pose.horizontalPositionError() + MAX_BREAK_EYE_ORIGIN_DRIFT
                || verticalDrift
                        > Math.max(pose.yErrorBelow(), pose.yErrorAbove())
                                + MAX_BREAK_EYE_ORIGIN_DRIFT) {
            throw new PlanningException(
                    Code.TARGET_UNKNOWN,
                    "Visible break face was not observed from the planned interaction pose");
        }
        Vec3 point = MinecraftActionPrimitiveExecutor.blockFaceAimPoint(
                target.target(), target.face());
        double nominalDistance = Math.sqrt(
                square(point.x - pose.x())
                        + square(point.y - poseEyeY)
                        + square(point.z - pose.z()));
        double poseError = Math.hypot(
                pose.horizontalPositionError(),
                Math.max(pose.yErrorBelow(), pose.yErrorAbove()));
        if (nominalDistance + poseError > MAX_BREAK_REACH_BLOCKS) {
            throw new PlanningException(
                    Code.TARGET_UNKNOWN,
                    "Visible break face is outside the proven interaction reach");
        }
    }

    private static Aim aim(Pose pose, ActionDsl.Position target) {
        return aim(pose, new Vec3(
                target.x() + 0.5D, target.y() + 0.5D, target.z() + 0.5D));
    }

    private static Aim aim(Pose pose, Vec3 target) {
        double dx = target.x - pose.x();
        double dy = target.y - (pose.y() + pose.eyeHeight());
        double dz = target.z - pose.z();
        double horizontal = Math.hypot(dx, dz);
        if (horizontal < 1.0e-9D && Math.abs(dy) < 1.0e-9D) {
            throw new PlanningException(Code.TARGET_UNKNOWN, "Face target coincides with the eye position");
        }
        return new Aim(
                (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D),
                (float) -Math.toDegrees(Math.atan2(dy, horizontal)));
    }

    private static AimError aimError(Pose pose, ActionDsl.Position target, Aim nominal) {
        return aimError(pose, new Vec3(
                target.x() + 0.5D, target.y() + 0.5D, target.z() + 0.5D), nominal);
    }

    private static AimError aimError(Pose pose, Vec3 target, Aim nominal) {
        double dx = target.x - pose.x();
        double dy = target.y - (pose.y() + pose.eyeHeight());
        double dz = target.z - pose.z();
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

    private static double square(double value) {
        return value * value;
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
            Set<ActionDsl.Position> knownTargets,
            Set<KnownSurface> knownSurfaces,
            Map<String, MutationAim> mutationAims) {
        public Analysis {
            primitiveCosts = Map.copyOf(Objects.requireNonNull(primitiveCosts, "primitiveCosts"));
            routeDependencies = Map.copyOf(
                    Objects.requireNonNull(routeDependencies, "routeDependencies"));
            knownTargets = Set.copyOf(Objects.requireNonNull(knownTargets, "knownTargets"));
            knownSurfaces = Set.copyOf(Objects.requireNonNull(knownSurfaces, "knownSurfaces"));
            mutationAims = Map.copyOf(Objects.requireNonNull(mutationAims, "mutationAims"));
        }

        public Optional<ActionDslCompiler.Cost> worstCase(ActionDsl.Node primitive) {
            return Optional.ofNullable(primitiveCosts.get(primitive.id()));
        }
    }

    public record KnownSurface(
            ActionDsl.Position position,
            ActionDsl.BlockFace face,
            String block,
            Boolean cropMature) {
        public KnownSurface(
                ActionDsl.Position position, ActionDsl.BlockFace face, String block) {
            this(position, face, block, null);
        }

        public KnownSurface {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(face, "face");
            Objects.requireNonNull(block, "block");
        }
    }

    public record MutationAim(
            ActionDsl.Position block,
            ActionDsl.BlockFace face,
            Vec3 point) {
        public MutationAim {
            Objects.requireNonNull(block, "block");
            Objects.requireNonNull(face, "face");
            Objects.requireNonNull(point, "point");
        }
    }

    private record MutationSurface(KnownSurface surface, Vec3 point) {
        private MutationSurface {
            Objects.requireNonNull(surface, "surface");
            Objects.requireNonNull(point, "point");
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
