package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.dsl.ActionDslCompiler;
import dev.aod.mcmcp.agent.dsl.ActionDslException;
import dev.aod.mcmcp.agent.dsl.ActionDslValidator;
import dev.aod.mcmcp.agent.navigation.DeterministicAStar;
import dev.aod.mcmcp.agent.navigation.KnownTraversabilitySnapshot;
import dev.aod.mcmcp.agent.navigation.NavCell;
import dev.aod.mcmcp.agent.navigation.NavigationDistanceBudget;
import dev.aod.mcmcp.agent.navigation.RoutePlan;
import dev.aod.mcmcp.agent.navigation.TraversabilityEdge;
import dev.aod.mcmcp.agent.observation.ObservationFrame;
import dev.aod.mcmcp.agent.observation.ObservationRecord;
import dev.aod.mcmcp.agent.observation.ObservationValues;
import dev.aod.mcmcp.agent.observation.PlacementStateResolver;
import dev.aod.mcmcp.construction.SafeConstructionBlocks;
import dev.aod.mcmcp.observation.BlockPlan;
import dev.aod.mcmcp.observation.BlockPlanStateTransformer;
import dev.aod.mcmcp.observation.BlockStateView;
import dev.aod.mcmcp.redstone.RedstoneSpec;
import dev.aod.mcmcp.routine.BlockStateFingerprint;
import dev.aod.mcmcp.routine.KnownBrewingRequest;
import dev.aod.mcmcp.routine.KnownPillarUpRequest;
import dev.aod.mcmcp.routine.NavigationViewLease;
import dev.aod.mcmcp.routine.SafePlacementSupportPolicy;
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
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.ToLongFunction;

/** Pure admission planner for map- and pose-dependent Action DSL primitive costs. */
public final class AgentPrimitivePlanner {
    private static final long TICK_MILLIS = 50L;
    private static final int MAX_ABSTRACT_POSES = 4_096;
    private static final double MAX_CELL_HORIZONTAL_ERROR =
            NavigationDistanceBudget.MAX_HORIZONTAL_POSITION_ERROR;
    private static final double NAVIGATION_VERTICAL_ERROR_ABOVE =
            NavigationDistanceBudget.MAX_VERTICAL_POSITION_ERROR;
    private static final double FACE_COMPLETION_ERROR_DEGREES = 1.5D;
    /**
     * Vanilla's {@code player.turn} path converts the requested delta through a 0.15 scale and
     * float player rotations. Reserve one bounded sub-degree step so a geometrically zero or
     * near-zero aim cannot consume more camera budget than admission proved.
     */
    public static final double CAMERA_QUANTIZATION_RESERVE_DEGREES = 0.25D;
    private static final int MAX_TOTAL_ROUTE_EXPANSIONS = 32_768;
    private static final int MAX_POSE_TRANSITIONS = 16_384;
    private static final double MAX_BREAK_REACH_BLOCKS = 4.5D;
    private static final double APPROACH_REACH_BLOCKS = 4.25D;
    private static final double APPROACH_EYE_HEIGHT = 1.62D;
    private static final double APPROACH_TOLERANCE = 0.25D;
    private static final double MAX_BREAK_EYE_ORIGIN_DRIFT = 0.125D;
    public static final double WAIT_WITNESS_EYE_EPSILON_BLOCKS = 1.0D / 1024.0D;
    private static final double FARMLAND_SETTLING_BLOCKS = 1.0D / 16.0D;
    private static final double VISIBLE_ITEM_MATCH_RADIUS = 0.75D;
    // Vanilla scans an item against player.getBoundingBox().inflate(1.0, 0.5, 1.0).
    // A route may finish 0.25 blocks from its cell center, so keep the horizontal
    // admission envelope slightly smaller than the nominal 0.3 + 1.0 block reach.
    private static final double PICKUP_HORIZONTAL_REACH = 1.0D;
    private static final double PICKUP_VERTICAL_INFLATE = 0.5D;
    private static final double MIN_NAVIGATING_PLAYER_HEIGHT = 1.5D;
    public static final long BREAK_TICK_UPPER_BOUND = 60L;
    public static final long BREAK_REOBSERVATION_TICKS = 40L;
    public static final long MUTATION_BATCH_REPROOF_TICKS = 40L;
    public static final long BLOCK_MUTATION_TICK_UPPER_BOUND = 100L;
    public static final long CONTAINER_OPERATION_TICK_UPPER_BOUND = 400L;
    public static final long CONTAINER_TICK_UPPER_BOUND = 600L;
    public static final long BREWING_TICK_UPPER_BOUND =
            ActionDslCompiler.KNOWN_BREWING_TICKS;
    /**
     * Cumulative time reserved by every newly bound movement occurrence for a bounded route
     * replan. The executor's original route does not own this reserve; only a later rebind may
     * consume it, and the runtime never replenishes it.
     */
    public static final long NAVIGATION_REPLAN_RESERVE_TICKS = 20L;
    // Player-thrown item entities can retain a 40-tick pickup delay. Leave a bounded
    // synchronization margin without exposing hidden pickup-delay state to the model.
    public static final long PICKUP_CONFIRM_TICKS = 60L;

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
                maxCameraDegreesPerTick, map.worldRevision(),
                ignored -> map.worldRevision(), () -> true);
    }

    public static Analysis analyze(
            ActionDsl.Program program,
            KnownTraversabilitySnapshot map,
            DeterministicAStar pathfinder,
            Pose initialPose,
            Optional<ObservationFrame> latestFrame,
            float maxCameraDegreesPerTick,
            BooleanSupplier canContinue) {
        return analyze(
                program, map, pathfinder, initialPose, latestFrame,
                maxCameraDegreesPerTick, map.worldRevision(),
                ignored -> map.worldRevision(), canContinue);
    }

    public static Analysis analyze(
            ActionDsl.Program program,
            KnownTraversabilitySnapshot map,
            DeterministicAStar pathfinder,
            Pose initialPose,
            Optional<ObservationFrame> latestFrame,
            float maxCameraDegreesPerTick,
            long visualBarrierWorldRevision) {
        return analyze(
                program, map, pathfinder, initialPose, latestFrame,
                maxCameraDegreesPerTick, visualBarrierWorldRevision,
                ignored -> map.worldRevision(), () -> true);
    }

    /** Admission variant allowing item evidence since the last visual-invalidating mutation. */
    public static Analysis analyze(
            ActionDsl.Program program,
            KnownTraversabilitySnapshot map,
            DeterministicAStar pathfinder,
            Pose initialPose,
            Optional<ObservationFrame> latestFrame,
            float maxCameraDegreesPerTick,
            long visualBarrierWorldRevision,
            BooleanSupplier canContinue) {
        return analyze(
                program, map, pathfinder, initialPose, latestFrame,
                maxCameraDegreesPerTick, visualBarrierWorldRevision,
                ignored -> map.worldRevision(), canContinue);
    }

    /** Runtime admission variant with item-global and position-specific surface barriers. */
    public static Analysis analyze(
            ActionDsl.Program program,
            KnownTraversabilitySnapshot map,
            DeterministicAStar pathfinder,
            Pose initialPose,
            Optional<ObservationFrame> latestFrame,
            float maxCameraDegreesPerTick,
            long visualBarrierWorldRevision,
            ToLongFunction<ActionDsl.Position> surfaceRevisionBarrier,
            BooleanSupplier canContinue) {
        return analyze(
                program, map, pathfinder, initialPose, latestFrame,
                maxCameraDegreesPerTick, visualBarrierWorldRevision,
                surfaceRevisionBarrier, canContinue, PlacementStateResolver.none());
    }

    /** Runtime admission variant with session-scoped observed placement-state memory. */
    public static Analysis analyze(
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
        requireVisualBarrierWorldRevision(
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
                knownSurfaces,
                mutationAims,
                mutationBatchPlans,
                routeCache,
                waitsBackedByPriorPlant,
                placementStates,
                work);
        return new Analysis(
                costs, routeDependencies, knownTargets, knownSurfaces,
                mutationAims, mutationBatchPlans);
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
        if (!knownFacingTarget(map, latestFrame, target)) {
            throw new PlanningException(
                    Code.TARGET_UNKNOWN,
                    "Face target is not delivered policy evidence in this world session");
        }
        return new MinecraftActionPrimitiveExecutor.KnownFaceTarget(
                map.worldSessionId(), map.worldRevision(), target, true);
    }

    /**
     * Camera-only facing may use a successfully delivered coordinate until that delivery expires.
     *
     * <p>The runtime supplies its delivery-filtered planner frame, so accepting an older revision
     * here does not reveal hidden world state. Facing is the recovery operation which lets the
     * observer obtain a new ray after a nearby mutation invalidated visual evidence. Mutation
     * primitives continue to use {@link #knownSurface} and its current revision barrier.</p>
     */
    public static boolean knownFacingTarget(
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
                .filter(record -> record.worldRevision() <= map.worldRevision())
                .anyMatch(record -> matches(record, target));
    }

    public static boolean knownTarget(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.Position target) {
        return knownTarget(map, latestFrame, target, map.worldRevision());
    }

    public static boolean knownTarget(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.Position target,
            long surfaceBarrierWorldRevision) {
        Objects.requireNonNull(map, "map");
        Objects.requireNonNull(latestFrame, "latestFrame");
        Objects.requireNonNull(target, "target");
        requireSurfaceBarrierWorldRevision(map, surfaceBarrierWorldRevision);
        if (!map.dimension().equals(target.dimension())) {
            return false;
        }
        if (map.containsCell(navCell(target))) {
            return true;
        }
        return latestFrame.stream()
                .filter(frame -> frame.dimension().value().equals(target.dimension()))
                .flatMap(frame -> frame.records().stream())
                .filter(record -> record.worldRevision() >= surfaceBarrierWorldRevision
                        && record.worldRevision() <= map.worldRevision())
                .anyMatch(record -> matches(record, target));
    }

    public static KnownSurface requireKnownBreakSurface(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.BreakKnownFace target) {
        return requireKnownBreakSurface(
                map, latestFrame, target, map.worldRevision());
    }

    public static KnownSurface requireKnownBreakSurface(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.BreakKnownFace target,
            long surfaceBarrierWorldRevision) {
        var required = new KnownSurface(
                target.target(), target.face(), target.expectedBlock());
        if (knownSurfaceRecord(
                map, latestFrame, required, surfaceBarrierWorldRevision).isEmpty()) {
            throw new PlanningException(
                    Code.TARGET_UNKNOWN,
                    "Break target face is not current matching visible-surface evidence");
        }
        return required;
    }

    public static MutationAim requireKnownBreakAim(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.BreakKnownFace target,
            long surfaceBarrierWorldRevision) {
        KnownSurface required = requireKnownBreakSurface(
                map, latestFrame, target, surfaceBarrierWorldRevision);
        ObservationRecord.VisibleSurface surface = knownSurfaceRecord(
                map, latestFrame, required, surfaceBarrierWorldRevision).orElseThrow();
        if (surface.rayHit() == null) {
            throw new PlanningException(
                    Code.TARGET_UNKNOWN,
                    "Break target face lacks an exact visible ray witness");
        }
        return new MutationAim(target.target(), target.face(), rayHit(surface));
    }

    public static boolean knownSurface(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            KnownSurface required) {
        return knownSurface(
                map, latestFrame, required, map.worldRevision());
    }

    public static boolean knownSurface(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            KnownSurface required,
            long surfaceBarrierWorldRevision) {
        return knownSurfaceRecord(
                map, latestFrame, required, surfaceBarrierWorldRevision).isPresent();
    }

    public static KnownSurface requireKnownSurface(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.Position position,
            String block) {
        return requireKnownSurface(
                map, latestFrame, position, block, map.worldRevision());
    }

    public static KnownSurface requireKnownSurface(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.Position position,
            String block,
            long surfaceBarrierWorldRevision) {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(block, "block");
        requireSurfaceBarrierWorldRevision(map, surfaceBarrierWorldRevision);
        return latestFrame.stream()
                .filter(frame -> frame.dimension().value().equals(map.dimension()))
                .flatMap(frame -> frame.records().stream())
                .filter(ObservationRecord.VisibleSurface.class::isInstance)
                .map(ObservationRecord.VisibleSurface.class::cast)
                .filter(surface -> surface.worldRevision() >= surfaceBarrierWorldRevision
                        && surface.worldRevision() <= map.worldRevision())
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

    /**
     * Authorizes one explicit crop wait from current policy-visible wheat evidence.
     * Crop maturity is deliberately not retained in the fence: normal AGE changes are
     * the state transition the bounded wait is intended to observe.
     */
    public static KnownSurface requireKnownWheatWaitSurface(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            List<Pose> poses,
            ActionDsl.Position position,
            long surfaceBarrierWorldRevision) {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(poses, "poses");
        if (poses.isEmpty()) {
            throw new IllegalArgumentException("crop wait requires at least one current pose");
        }
        requireSurfaceBarrierWorldRevision(map, surfaceBarrierWorldRevision);
        return latestFrame.stream()
                .filter(frame -> frame.dimension().value().equals(map.dimension()))
                .flatMap(frame -> frame.records().stream())
                .filter(ObservationRecord.VisibleSurface.class::isInstance)
                .map(ObservationRecord.VisibleSurface.class::cast)
                .filter(surface -> surface.worldRevision() >= surfaceBarrierWorldRevision
                        && surface.worldRevision() <= map.worldRevision())
                .filter(surface -> matches(surface, position, "minecraft:wheat"))
                .filter(surface -> surface.cropMature() != null)
                .filter(surface -> poses.stream().allMatch(pose ->
                        waitWitnessOriginMatches(pose, surface.eyeOrigin())))
                .map(surface -> new KnownSurface(
                        position,
                        ActionDsl.BlockFace.valueOf(surface.face().name()),
                        "minecraft:wheat",
                        null,
                        new Vec3(
                                surface.eyeOrigin().x(),
                                surface.eyeOrigin().y(),
                                surface.eyeOrigin().z())))
                .findFirst()
                .orElseThrow(() -> new PlanningException(
                        Code.TARGET_UNKNOWN,
                        "Crop wait target requires current visible wheat evidence"));
    }

    private static MutationSurface requireMutationSurface(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            List<Pose> poses,
            ActionDsl.Position position,
            long surfaceBarrierWorldRevision,
            String block,
            java.util.function.Predicate<ObservationRecord.VisibleSurface> allowed,
            String failure) {
        requireSurfaceBarrierWorldRevision(map, surfaceBarrierWorldRevision);
        return latestFrame.stream()
                .filter(frame -> frame.dimension().value().equals(map.dimension()))
                .flatMap(frame -> frame.records().stream())
                .filter(ObservationRecord.VisibleSurface.class::isInstance)
                .map(ObservationRecord.VisibleSurface.class::cast)
                .filter(surface -> surface.worldRevision() >= surfaceBarrierWorldRevision
                        && surface.worldRevision() <= map.worldRevision())
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

    /**
     * A centered player necessarily occludes the support directly below their feet. Keep the
     * previously delivered UP-face witness usable while the player takes the final centering step;
     * the pillar port still rechecks the complete live state immediately before jumping and use.
     */
    private static MutationSurface requirePillarSupport(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.Position position,
            long surfaceBarrierWorldRevision,
            ActionDsl.BlockStateSpec expected) {
        requireSurfaceBarrierWorldRevision(map, surfaceBarrierWorldRevision);
        return latestFrame.stream()
                .filter(frame -> frame.dimension().value().equals(map.dimension()))
                .flatMap(frame -> frame.records().stream())
                .filter(ObservationRecord.VisibleSurface.class::isInstance)
                .map(ObservationRecord.VisibleSurface.class::cast)
                .filter(surface -> surface.worldRevision() >= surfaceBarrierWorldRevision
                        && surface.worldRevision() <= map.worldRevision())
                .filter(surface -> matches(surface, position, expected.block()))
                .filter(surface -> surface.face() == ObservationRecord.Face.UP
                        && exactObservedState(surface, expected)
                        && surface.rayHit() != null)
                .map(surface -> new MutationSurface(
                        new KnownSurface(
                                position,
                                ActionDsl.BlockFace.UP,
                                expected.block(),
                                null),
                        rayHit(surface)))
                .findFirst()
                .orElseThrow(() -> new PlanningException(
                        Code.TARGET_UNKNOWN,
                        "Pillar support requires a retained delivered exact UP face and block state"));
    }

    private static MutationSurface requireRedstoneSupport(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            List<Pose> poses,
            ActionDsl.Position position,
            long surfaceBarrierWorldRevision) {
        requireSurfaceBarrierWorldRevision(map, surfaceBarrierWorldRevision);
        return latestFrame.stream()
                .filter(frame -> frame.dimension().value().equals(map.dimension()))
                .flatMap(frame -> frame.records().stream())
                .filter(ObservationRecord.VisibleSurface.class::isInstance)
                .map(ObservationRecord.VisibleSurface.class::cast)
                .filter(surface -> surface.worldRevision() >= surfaceBarrierWorldRevision
                        && surface.worldRevision() <= map.worldRevision())
                .filter(surface -> matches(surface, position)
                        && surface.face() == ObservationRecord.Face.UP
                        && SafePlacementSupportPolicy.allowsRegisteredBlockId(
                                surface.block().value()))
                .filter(surface -> surface.rayHit() != null
                        && poses.stream().allMatch(pose -> mutationSurfaceValid(pose, surface)))
                .map(surface -> new MutationSurface(
                        new KnownSurface(
                                position,
                                ActionDsl.BlockFace.UP,
                                surface.block().value(),
                                null),
                        rayHit(surface)))
                .findFirst()
                .orElseThrow(() -> new PlanningException(
                        Code.TARGET_UNKNOWN,
                        "Redstone placement requires a current visible inert UP support"));
    }

    private static Optional<ObservationRecord.VisibleSurface> knownSurfaceRecord(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            KnownSurface required) {
        return knownSurfaceRecord(
                map, latestFrame, required, map.worldRevision());
    }

    private static Optional<ObservationRecord.VisibleSurface> knownSurfaceRecord(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            KnownSurface required,
            long surfaceBarrierWorldRevision) {
        Objects.requireNonNull(map, "map");
        Objects.requireNonNull(latestFrame, "latestFrame");
        Objects.requireNonNull(required, "required");
        requireSurfaceBarrierWorldRevision(map, surfaceBarrierWorldRevision);
        if (!map.dimension().equals(required.position().dimension())) return Optional.empty();
        return latestFrame.stream()
                .filter(frame -> frame.dimension().value().equals(map.dimension()))
                .flatMap(frame -> frame.records().stream())
                .filter(ObservationRecord.VisibleSurface.class::isInstance)
                .map(ObservationRecord.VisibleSurface.class::cast)
                .filter(surface -> surface.worldRevision() >= surfaceBarrierWorldRevision
                        && surface.worldRevision() <= map.worldRevision())
                .filter(surface -> matches(surface, required))
                .findFirst();
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
                if (present.contains(wait.condition().target())) {
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
                    costs, routeDependencies, knownTargets, knownSurfaces,
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
            if (!waitsBackedByPriorPlant.contains(wait.id())) {
                long surfaceBarrier = surfaceBarrierWorldRevision(
                        map, surfaceRevisionBarrier, wait.condition().target());
                KnownSurface surface = requireKnownWheatWaitSurface(
                        map, latestFrame, input,
                        wait.condition().target(), surfaceBarrier);
                knownSurfaces.add(surface);
            }
            merge(costs, node.id(), ActionDslCompiler.intrinsicWaitCost(wait.maxTicks()));
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
        if (node instanceof ActionDsl.ApproachKnownSurface approach) {
            long surfaceBarrier = surfaceBarrierWorldRevision(
                    map, surfaceRevisionBarrier, approach.target());
            KnownSurface surface = requireKnownSurface(
                    map, latestFrame, approach.target(), approach.expectedBlock(), surfaceBarrier);
            knownSurfaces.add(surface);
            ActionDslCompiler.Cost worst = null;
            var output = new ArrayList<Pose>(input.size());
            for (Pose pose : input) {
                work.poseTransition();
                ApproachPlan plan = requireApproachPlan(
                        map, pathfinder, pose.cell(), approach.target(), work);
                addRouteDependencies(map, plan.route(), routeDependencies);
                worst = maximum(worst, navigationCost(plan.route(), pose));
                output.add(pose.at(plan.anchor(), APPROACH_TOLERANCE));
            }
            merge(costs, node.id(), Objects.requireNonNull(worst, "approach cost"));
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
            long surfaceBarrier = surfaceBarrierWorldRevision(
                    map, surfaceRevisionBarrier, block.target());
            var required = requireKnownBreakSurface(
                    map, latestFrame, block, surfaceBarrier);
            var surface = knownSurfaceRecord(
                    map, latestFrame, required, surfaceBarrier).orElseThrow();
            knownSurfaces.add(required);
            Vec3 point = rayHit(surface);
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
                requireBreakPose(pose, surface, point);
                Aim aim = aim(pose, point);
                AimError aimError = aimError(pose, point, aim);
                worst = maximum(worst, breakCost(pose, block, point, cameraLimit));
                output.add(pose.aimed(aim, aimError));
            }
            merge(costs, node.id(), Objects.requireNonNull(worst, "break cost"));
            return distinct(output);
        }
        if (node instanceof ActionDsl.TillKnownBlock till) {
            MutationSurface surface = requireMutationSurface(
                    map, latestFrame, input, till.target(),
                    surfaceBarrierWorldRevision(map, surfaceRevisionBarrier, till.target()),
                    till.expectedBlock(),
                    value -> value.face() != ObservationRecord.Face.DOWN,
                    "Till target requires a current non-DOWN visible surface");
            return analyzeMutation(
                    node, input, cameraLimit, costs, knownSurfaces, mutationAims, work,
                    surface, 1, 0, 0);
        }
        if (node instanceof ActionDsl.TillKnownBatch
                || node instanceof ActionDsl.PlantKnownWheatBatch
                || node instanceof ActionDsl.HarvestKnownWheatBatch) {
            return analyzeMutationBatch(
                    node, input, map, latestFrame, surfaceRevisionBarrier,
                    cameraLimit, costs, knownSurfaces, mutationBatchPlans, work);
        }
        if (node instanceof ActionDsl.PlantKnownWheat plant) {
            if (!directlyAbove(plant.target(), plant.support())) {
                throw new PlanningException(
                        Code.TARGET_UNKNOWN, "Plant target must be directly above its support");
            }
            MutationSurface surface = requireMutationSurface(
                    map, latestFrame, input, plant.support(),
                    surfaceBarrierWorldRevision(map, surfaceRevisionBarrier, plant.support()),
                    "minecraft:farmland",
                    value -> value.face() == ObservationRecord.Face.UP,
                    "Plant support requires its current UP face");
            return analyzeMutation(
                    node, input, cameraLimit, costs, knownSurfaces, mutationAims, work,
                    surface, 0, 0, 1);
        }
        if (node instanceof ActionDsl.HarvestKnownWheat harvest) {
            MutationSurface surface = requireMutationSurface(
                    map, latestFrame, input, harvest.target(),
                    surfaceBarrierWorldRevision(map, surfaceRevisionBarrier, harvest.target()),
                    "minecraft:wheat",
                    value -> Boolean.TRUE.equals(value.cropMature()),
                    "Harvest target requires current crop_mature=true evidence");
            return analyzeMutation(
                    node, input, cameraLimit, costs, knownSurfaces, mutationAims, work,
                    surface, 0, 1, 0);
        }
        if (node instanceof ActionDsl.ApplyKnownBlockPlan plan) {
            long placements = 0L;
            for (ActionDsl.BlockPlanEntry entry : plan.entries()) {
                ConstructionSource source = requireConstructionSource(
                        map, latestFrame, entry, placementStates);
                placements += SafeConstructionBlocks.placementCellCount(
                        source.state().block());
                if (source.surface() != null) {
                    knownSurfaces.add(new KnownSurface(
                            new ActionDsl.Position(
                                    source.surface().position().dimension().value(),
                                    source.surface().position().x(),
                                    source.surface().position().y(),
                                    source.surface().position().z()),
                            ActionDsl.BlockFace.valueOf(source.surface().face().name()),
                            source.surface().block().value(),
                            null));
                }
                ActionDsl.PlacementSupport support = entry.support();
                if (support.expectedState().isPresent()) {
                    ActionDsl.BlockStateSpec expected = support.expectedState().orElseThrow();
                    MutationSurface surface = requireMutationSurface(
                            map,
                            latestFrame,
                            input,
                            support.position(),
                            surfaceBarrierWorldRevision(
                                    map, surfaceRevisionBarrier, support.position()),
                            expected.block(),
                            value -> value.face().name().equals(support.face().name())
                                    && exactObservedState(value, expected),
                            "Construction support requires the delivered exact face and block state");
                    knownSurfaces.add(surface.surface());
                    for (Pose pose : input) {
                        work.poseTransition();
                        requireConstructionAim(
                                pose, supportFaceCenter(support.position(), support.face()));
                    }
                } else {
                    Vec3 supportPoint = supportFaceCenter(support.position(), support.face());
                    for (Pose pose : input) {
                        work.poseTransition();
                        if (!interactionPointReachable(pose, supportPoint)) {
                            throw new PlanningException(
                                    Code.TARGET_UNKNOWN,
                                    "Construction dependency support is outside interaction reach");
                        }
                        requireConstructionAim(pose, supportPoint);
                    }
                }
            }
            merge(costs, node.id(),
                    ActionDslCompiler.intrinsicKnownBlockPlanCost(
                            plan.entries().size(), placements));
            // The construction adapter restores the admitted camera pose and owns no movement.
            return input;
        }
        if (node instanceof ActionDsl.ClearKnownBlockPlan plan) {
            for (ActionDsl.ClearBlockPlanEntry entry : plan.entries()) {
                ActionDsl.Position target = transformedTarget(
                        plan.anchor(), plan.transform(), entry.offset());
                ActionDsl.BlockStateSpec expected = transformedState(
                        plan.transform(), entry.expectedBefore());
                MutationSurface surface = requireMutationSurface(
                        map,
                        latestFrame,
                        input,
                        target,
                        surfaceBarrierWorldRevision(map, surfaceRevisionBarrier, target),
                        expected.block(),
                        value -> value.placementItem() != null
                                && exactObservedState(value, expected),
                        "Construction clear requires the delivered exact target state");
                knownSurfaces.add(surface.surface());
                for (Pose pose : input) {
                    work.poseTransition();
                    requireConstructionAim(
                            pose, supportFaceCenter(target, surface.surface().face()));
                }
            }
            merge(costs, node.id(),
                    ActionDslCompiler.intrinsicKnownBlockClearCost(plan.entries().size()));
            return input;
        }
        if (node instanceof ActionDsl.PillarUpKnown pillar) {
            ConstructionSource source = requirePillarSource(
                    map, latestFrame, pillar, placementStates);
            try {
                KnownPillarUpRequest.requireSourceStateAndItem(
                        new BlockStateFingerprint(
                                source.state().block(), source.state().properties()),
                        source.item());
            } catch (RuntimeException rejected) {
                throw new PlanningException(
                        Code.TARGET_UNKNOWN,
                        "Pillar source requires one safe ordinary full block");
            }
            if (source.surface() != null) {
                knownSurfaces.add(new KnownSurface(
                        new ActionDsl.Position(
                                source.surface().position().dimension().value(),
                                source.surface().position().x(),
                                source.surface().position().y(),
                                source.surface().position().z()),
                        ActionDsl.BlockFace.valueOf(source.surface().face().name()),
                        source.surface().block().value(), null));
            }
            MutationSurface support = requirePillarSupport(
                    map,
                    latestFrame,
                    pillar.support(),
                    surfaceBarrierWorldRevision(
                            map, surfaceRevisionBarrier, pillar.support()),
                    pillar.expectedSupport());
            knownSurfaces.add(support.surface());
            for (Pose pose : input) {
                work.poseTransition();
                requirePillarPose(pose, pillar.support());
            }
            merge(costs, node.id(), ActionDslCompiler.intrinsicPillarUpCost());
            return input;
        }
        if (node instanceof ActionDsl.ApplyKnownRedstoneSpec redstone) {
            var spec = new RedstoneSpec(
                    redstone.components(), redstone.truthTable(), redstone.footprint(),
                    redstone.rotation(),
                    new RedstoneSpec.ExecutionBounds(true, redstone.timing().settleTicks()));
            ActionDsl.Position lampSupport = offset(redstone.anchor(), 0, -1, 0);
            int x = switch (redstone.rotation()) {
                case 0 -> 1;
                case 180 -> -1;
                case 90, 270 -> 0;
                default -> throw new PlanningException(
                        Code.TARGET_UNKNOWN, "Redstone rotation is outside the identity slice");
            };
            int z = switch (redstone.rotation()) {
                case 90 -> 1;
                case 270 -> -1;
                case 0, 180 -> 0;
                default -> throw new PlanningException(
                        Code.TARGET_UNKNOWN, "Redstone rotation is outside the identity slice");
            };
            ActionDsl.Position leverTarget = offset(
                    redstone.anchor(), (1 + spec.wireCount()) * x, 0,
                    (1 + spec.wireCount()) * z);
            ActionDsl.Position leverSupport = offset(leverTarget, 0, -1, 0);
            MutationSurface lamp = requireRedstoneSupport(
                    map,
                    latestFrame,
                    input,
                    lampSupport,
                    surfaceBarrierWorldRevision(
                            map, surfaceRevisionBarrier, lampSupport));
            MutationSurface lever = requireRedstoneSupport(
                    map,
                    latestFrame,
                    input,
                    leverSupport,
                    surfaceBarrierWorldRevision(
                            map, surfaceRevisionBarrier, leverSupport));
            if (!"minecraft:glass".equals(lever.surface().block())
                    || spec.wireCount() == 1
                            && !"minecraft:glass".equals(lamp.surface().block())) {
                throw new PlanningException(
                        Code.TARGET_UNKNOWN,
                        "Redstone placement requires the fixed current visible glass UP support");
            }
            knownSurfaces.add(lamp.surface());
            knownSurfaces.add(lever.surface());
            mutationAims.put(
                    redstone.id() + "/lamp",
                    new MutationAim(lampSupport, ActionDsl.BlockFace.UP, lamp.point()));
            mutationAims.put(
                    redstone.id() + "/lever",
                    new MutationAim(leverSupport, ActionDsl.BlockFace.UP, lever.point()));
            if (spec.outputCount() == 2) {
                ActionDsl.Position secondLampSupport = offset(
                        redstone.anchor(), 2 * x, -1, 2 * z);
                MutationSurface secondLamp = requireRedstoneSupport(
                        map,
                        latestFrame,
                        input,
                        secondLampSupport,
                        surfaceBarrierWorldRevision(
                                map, surfaceRevisionBarrier, secondLampSupport));
                knownSurfaces.add(secondLamp.surface());
                mutationAims.put(
                        redstone.id() + "/lamp_2",
                            new MutationAim(
                                    secondLampSupport, ActionDsl.BlockFace.UP, secondLamp.point()));
            }
            if (spec.wireCount() == 1) {
                ActionDsl.Position wireSupport = offset(redstone.anchor(), x, -1, z);
                MutationSurface wire = requireRedstoneSupport(
                        map,
                        latestFrame,
                        input,
                        wireSupport,
                        surfaceBarrierWorldRevision(
                                map, surfaceRevisionBarrier, wireSupport));
                if (!"minecraft:glass".equals(wire.surface().block())) {
                    throw new PlanningException(
                            Code.TARGET_UNKNOWN,
                            "Redstone wire placement requires a current visible glass UP support");
                }
                knownSurfaces.add(wire.surface());
                mutationAims.put(
                        redstone.id() + "/wire",
                        new MutationAim(wireSupport, ActionDsl.BlockFace.UP, wire.point()));
            }
            merge(costs, node.id(), ActionDslCompiler.intrinsicKnownRedstoneCost(
                    redstone.timing().settleTicks(), spec.outputCount(), spec.wireCount()));
            return input;
        }
        if (node instanceof ActionDsl.OpenKnownFenceGate gate) {
            MutationSurface surface = requireMutationSurface(
                    map, latestFrame, input, gate.target(),
                    surfaceBarrierWorldRevision(map, surfaceRevisionBarrier, gate.target()),
                    "minecraft:oak_fence_gate",
                    value -> true,
                    "Fence gate target requires a current visible oak fence gate surface");
            return analyzeMutation(
                    node, input, cameraLimit, costs, knownSurfaces, mutationAims, work,
                    surface, 1, 0, 0);
        }
        if (node instanceof ActionDsl.OpenKnownPassage passage) {
            MutationSurface surface = requireMutationSurface(
                    map, latestFrame, input, passage.target(),
                    surfaceBarrierWorldRevision(map, surfaceRevisionBarrier, passage.target()),
                    passage.expectedBlock(),
                    value -> true,
                    "Passage target requires a current matching visible wooden surface");
            return analyzeMutation(
                    node, input, cameraLimit, costs, knownSurfaces, mutationAims, work,
                    surface, 1, 0, 0);
        }
        if (node instanceof ActionDsl.InspectKnownContainer inspect) {
            MutationSurface surface = requireMutationSurface(
                    map, latestFrame, input, inspect.target(),
                    surfaceBarrierWorldRevision(map, surfaceRevisionBarrier, inspect.target()),
                    inspect.expectedBlock(),
                    value -> true,
                    "Container target requires a current matching visible surface");
            return analyzeContainer(
                    node, input, cameraLimit, costs, knownSurfaces, mutationAims,
                    work, surface, 1);
        }
        if (node instanceof ActionDsl.TakeKnownContainerStack take) {
            MutationSurface surface = requireMutationSurface(
                    map, latestFrame, input, take.target(),
                    surfaceBarrierWorldRevision(map, surfaceRevisionBarrier, take.target()),
                    take.expectedBlock(),
                    value -> true,
                    "Container target requires a current matching visible surface");
            return analyzeContainer(
                    node, input, cameraLimit, costs, knownSurfaces, mutationAims,
                    work, surface, 3);
        }
        if (node instanceof ActionDsl.CraftKnownRecipe craft) {
            MutationSurface surface = requireMutationSurface(
                    map, latestFrame, input, craft.target(),
                    surfaceBarrierWorldRevision(map, surfaceRevisionBarrier, craft.target()),
                    craft.expectedState().block(),
                    value -> true,
                    "Crafting target requires a current visible crafting table surface");
            return analyzeContainer(
                    node, input, cameraLimit, costs, knownSurfaces, mutationAims, work, surface,
                    ActionDslCompiler.knownCraftInteractions(craft.maxCrafts()));
        }
        if (node instanceof ActionDsl.SmeltKnownRecipe smelt) {
            MutationSurface surface = requireMutationSurface(
                    map, latestFrame, input, smelt.target(),
                    surfaceBarrierWorldRevision(map, surfaceRevisionBarrier, smelt.target()),
                    smelt.expectedState().block(),
                    value -> true,
                    "Smelting target requires a current matching visible surface");
            return analyzeOwnedMenu(
                    node, input, cameraLimit, costs, knownSurfaces, mutationAims,
                    work, surface,
                    ActionDslCompiler.KNOWN_SMELTING_INTERACTIONS,
                    ActionDslCompiler.knownSmeltingTicks(smelt.maxSmelts()),
                    "smelting",
                    true,
                    KnownBrewingRequest.MAX_ONE_WAY_CAMERA_DEGREES);
        }
        if (node instanceof ActionDsl.BrewKnownPotionBatch brew) {
            MutationSurface surface = requireMutationSurface(
                    map, latestFrame, input, brew.target(),
                    surfaceBarrierWorldRevision(map, surfaceRevisionBarrier, brew.target()),
                    brew.expectedBlock(),
                    value -> true,
                    "Brewing target requires a current matching visible surface");
            return analyzeOwnedMenu(
                    node, input, cameraLimit, costs, knownSurfaces, mutationAims,
                    work, surface,
                    ActionDslCompiler.KNOWN_BREWING_INTERACTIONS,
                    BREWING_TICK_UPPER_BOUND,
                    "brewing",
                    true,
                    KnownBrewingRequest.MAX_ONE_WAY_CAMERA_DEGREES);
        }
        if (node instanceof ActionDsl.CollectVisibleItem collect) {
            ObservationRecord.VisibleEntity entity = requireVisibleItem(
                    map, latestFrame, collect, visualBarrierWorldRevision);
            ActionDslCompiler.Cost worst = null;
            var output = new ArrayList<Pose>(input.size());
            for (Pose pose : input) {
                work.poseTransition();
                PickupPlan pickup = requirePickupPlan(
                        map, pathfinder, pose.cell(), entity, work);
                addRouteDependencies(map, pickup.route(), routeDependencies);
                worst = maximum(worst, pickupCost(pickup.route(), pose));
                output.add(pose.at(pickup.pickupCell(), 0.25D));
            }
            merge(costs, node.id(), Objects.requireNonNull(worst, "pickup cost"));
            return distinct(output);
        }
        if (node instanceof ActionDsl.CollectVisibleItemBatch batch) {
            return analyzeCollectBatch(
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
                    knownTargets, knownSurfaces, mutationAims,
                    mutationBatchPlans, routeCache,
                    waitsBackedByPriorPlant, placementStates, work));
            output.addAll(analyzeSequence(
                    conditional.elseBranch(), input, map, pathfinder,
                    latestFrame, visualBarrierWorldRevision,
                    surfaceRevisionBarrier, cameraLimit,
                    costs, routeDependencies,
                    knownTargets, knownSurfaces, mutationAims,
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
                    knownTargets, knownSurfaces, mutationAims,
                    mutationBatchPlans, routeCache,
                    waitsBackedByPriorPlant, placementStates, work);
        }
        return output;
    }

    private static List<Pose> analyzeMutationBatch(
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
                Aim nextAim = aim(plannedPose, point);
                Pose nextPose = plannedPose.aimed(
                        nextAim, aimError(plannedPose, point, nextAim));
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
            worst = maximum(worst, ordered.cost());
            output.add(ordered.pose());
        }
        MutationBatchPlan plan = Objects.requireNonNull(sharedPlan, "mutation batch plan");
        MutationBatchPlan previous = mutationBatchPlans.putIfAbsent(batch.id(), plan);
        if (previous != null && !previous.equals(plan)) {
            throw new PlanningException(
                    Code.PROGRAM_BUDGET_UNPROVABLE,
                    "Mutation batch node resolves to more than one plan");
        }
        merge(costs, batch.id(), Objects.requireNonNull(worst, "mutation batch cost"));
        return distinct(output);
    }

    private static List<Pose> analyzeCollectBatch(
            ActionDsl.CollectVisibleItemBatch batch,
            List<Pose> input,
            KnownTraversabilitySnapshot map,
            DeterministicAStar pathfinder,
            Optional<ObservationFrame> latestFrame,
            long visualBarrierWorldRevision,
            Map<String, ActionDslCompiler.Cost> costs,
            Map<TraversabilityEdge.Key, TraversabilityEdge> routeDependencies,
            PlanningWork work) {
        ActionDslCompiler.Cost worst = null;
        var output = new ArrayList<Pose>(input.size());
        for (Pose initial : input) {
            Pose pose = initial;
            ActionDslCompiler.Cost cost = new ActionDslCompiler.Cost(0, 0, 0, 0, 0, 0, 0);
            var boundEntities = new LinkedHashSet<ObservationRecord.VisibleEntity>();
            for (int index = 0; index < batch.targets().size(); index++) {
                work.poseTransition();
                ActionDsl.CollectVisibleItem child = collectBatchChild(batch, index);
                ObservationRecord.VisibleEntity entity;
                try {
                    long frameTick = latestFrame.map(
                            ObservationFrame::frameCompletedTick).orElse(0L);
                    entity = matchingVisibleItems(
                                    map, latestFrame, child,
                                    visualBarrierWorldRevision, frameTick, 0L)
                            .stream()
                            .filter(boundEntities::add)
                            .findFirst()
                            .orElseThrow(() -> new PlanningException(
                                    Code.TARGET_UNKNOWN,
                                    "Collect target requires current matching visible item evidence"));
                } catch (PlanningException failure) {
                    throw new PlanningException(
                            failure.code(),
                            "Collect batch target[" + index
                                    + "] lacks required current evidence: "
                                    + failure.getMessage());
                }
                PickupPlan pickup = requirePickupPlan(
                        map, pathfinder, pose.cell(), entity, work);
                addRouteDependencies(map, pickup.route(), routeDependencies);
                cost = addCosts(cost, pickupCost(pickup.route(), pose));
                pose = pose.at(pickup.pickupCell(), 0.25D);
            }
            worst = maximum(worst, cost);
            output.add(pose);
        }
        merge(costs, batch.id(), Objects.requireNonNull(worst, "collect batch cost"));
        return distinct(output);
    }

    public static ActionDsl.CollectVisibleItem collectBatchChild(
            ActionDsl.CollectVisibleItemBatch batch, int index) {
        Objects.requireNonNull(batch, "batch");
        if (index < 0 || index >= batch.targets().size()) {
            throw new IllegalArgumentException("collect batch index is outside the target list");
        }
        ActionDsl.CollectTarget target = batch.targets().get(index);
        String suffix = "_c" + (index + 1);
        String id = batch.id().substring(
                0, Math.min(batch.id().length(), 32 - suffix.length())) + suffix;
        return new ActionDsl.CollectVisibleItem(id, target.displayedItem(), target.target());
    }

    /** Preserves the submitted target order while proving one bounded joint camera path. */
    private static BatchPath listedMutationBatch(
            Pose initial, List<MutationBatchTarget> input, float cameraLimit) {
        Pose pose = initial;
        ActionDslCompiler.Cost cost = new ActionDslCompiler.Cost(0, 0, 0, 0, 0, 0, 0);
        for (MutationBatchTarget candidate : input) {
            cost = addCosts(cost, mutationTargetCost(pose, candidate, cameraLimit));
            Aim nextAim = aim(pose, candidate.surface().point());
            Pose nextPose = pose.aimed(
                    nextAim, aimError(pose, candidate.surface().point(), nextAim));
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
        ActionDslCompiler.Cost mutation = mutationCost(
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
    public static ActionDslCompiler.Cost recostMutationBatchRemainder(
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
        Aim currentAim = aim(currentPose, freshCurrentAim.point());
        Pose suffixPose = currentPose.aimed(
                currentAim, aimError(currentPose, freshCurrentAim.point(), currentAim));
        if (current instanceof ActionDsl.TillKnownBlock till
                && mayStandOnTarget(currentPose, till.target())) {
            suffixPose = suffixPose.withAdditionalYErrorBelow(FARMLAND_SETTLING_BLOCKS);
        }

        ActionDslCompiler.Cost required = freshCurrentCost;
        for (int index = currentIndex + 1; index < plan.steps().size(); index++) {
            MutationBatchStep step = plan.steps().get(index);
            required = addCosts(
                    required,
                    mutationNodeCost(suffixPose, step.primitive(), step.aim().point(), cameraLimit));
            Aim nextAim = aim(suffixPose, step.aim().point());
            Pose nextPose = suffixPose.aimed(
                    nextAim, aimError(suffixPose, step.aim().point(), nextAim));
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
            return requireMutationSurface(
                    map, latestFrame, poses, till.target(),
                    surfaceBarrierWorldRevision(map, surfaceRevisionBarrier, till.target()),
                    till.expectedBlock(), value -> value.face() != ObservationRecord.Face.DOWN,
                    "Till batch target requires a current non-DOWN visible surface");
        }
        if (node instanceof ActionDsl.PlantKnownWheat plant) {
            return requireMutationSurface(
                    map, latestFrame, poses, plant.support(),
                    surfaceBarrierWorldRevision(map, surfaceRevisionBarrier, plant.support()),
                    "minecraft:farmland", value -> value.face() == ObservationRecord.Face.UP,
                    "Plant batch support requires its current UP face");
        }
        var harvest = (ActionDsl.HarvestKnownWheat) node;
        return requireMutationSurface(
                map, latestFrame, poses, harvest.target(),
                surfaceBarrierWorldRevision(map, surfaceRevisionBarrier, harvest.target()),
                "minecraft:wheat", value -> Boolean.TRUE.equals(value.cropMature()),
                "Harvest batch target requires current crop_mature=true evidence");
    }

    public static List<ActionDsl.Node> mutationBatchChildren(ActionDsl.Node batch) {
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

    private static ActionDslCompiler.Cost addCosts(
            ActionDslCompiler.Cost left, ActionDslCompiler.Cost right) {
        return new ActionDslCompiler.Cost(
                Math.addExact(left.durationMillis(), right.durationMillis()),
                Math.addExact(left.ticks(), right.ticks()),
                left.distanceBlocks() + right.distanceBlocks(),
                left.cameraDegrees() + right.cameraDegrees(),
                Math.addExact(left.interactions(), right.interactions()),
                Math.addExact(left.blocksBroken(), right.blocksBroken()),
                Math.addExact(left.blocksPlaced(), right.blocksPlaced()));
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

    private static List<Pose> analyzeContainer(
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

    private static List<Pose> analyzeOwnedMenu(
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
            ActionDslCompiler.Cost aim = mutationCost(
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
            worst = maximum(worst, bounded);
            Aim target = aim(pose, point);
            output.add(restoreAdmittedPose
                    ? pose : pose.aimed(target, aimError(pose, point, target)));
        }
        merge(costs, node.id(), Objects.requireNonNull(worst, costLabel + " cost"));
        return distinct(output);
    }

    private static boolean directlyAbove(
            ActionDsl.Position target, ActionDsl.Position support) {
        return target.dimension().equals(support.dimension())
                && target.x() == support.x()
                && target.y() == support.y() + 1
                && target.z() == support.z();
    }

    private static ActionDsl.Position offset(
            ActionDsl.Position origin, int x, int y, int z) {
        return new ActionDsl.Position(
                origin.dimension(),
                Math.addExact(origin.x(), x),
                Math.addExact(origin.y(), y),
                Math.addExact(origin.z(), z));
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

    private static boolean exactObservedState(
            ObservationRecord.VisibleSurface surface,
            ActionDsl.BlockStateSpec expected) {
        return surface.state() != null
                && expected.block().equals(surface.state().block().value())
                && expected.properties().equals(surface.state().properties());
    }

    private static ConstructionSource requireConstructionSource(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.BlockPlanEntry entry,
            PlacementStateResolver placementStates) {
        if (entry.placementStateRef().isPresent()) {
            PlacementStateResolver.PlacementState remembered = placementStates
                    .resolve(entry.placementStateRef().orElseThrow())
                    .orElseThrow(() -> new PlanningException(
                            Code.TARGET_UNKNOWN,
                            "Construction placement_state_ref is unknown in this world session"));
            return new ConstructionSource(
                    new ActionDsl.BlockStateSpec(
                            remembered.state().block().value(), remembered.state().properties()),
                    remembered.placementItem().value(),
                    null);
        }
        ActionDsl.BlockStateSpec expected = entry.sourceState().orElseThrow();
        String item = entry.item().orElseThrow();
        ObservationRecord.VisibleSurface surface = requireConstructionSource(
                map, latestFrame, expected, item);
        return new ConstructionSource(expected, item, surface);
    }

    private static ConstructionSource requirePillarSource(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.PillarUpKnown pillar,
            PlacementStateResolver placementStates) {
        if (pillar.placementStateRef().isPresent()) {
            PlacementStateResolver.PlacementState remembered = placementStates
                    .resolve(pillar.placementStateRef().orElseThrow())
                    .orElseThrow(() -> new PlanningException(
                            Code.TARGET_UNKNOWN,
                            "Pillar placement_state_ref is unknown in this world session"));
            return new ConstructionSource(
                    new ActionDsl.BlockStateSpec(
                            remembered.state().block().value(), remembered.state().properties()),
                    remembered.placementItem().value(),
                    null);
        }
        ActionDsl.BlockStateSpec expected = pillar.sourceState().orElseThrow();
        String item = pillar.item().orElseThrow();
        ObservationRecord.VisibleSurface surface = requireConstructionSource(
                map, latestFrame, expected, item);
        return new ConstructionSource(expected, item, surface);
    }

    /** Legacy inline source admission retained for migration compatibility. */
    private static ObservationRecord.VisibleSurface requireConstructionSource(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.BlockStateSpec expected,
            String item) {
        return latestFrame.stream()
                .filter(frame -> frame.dimension().value().equals(map.dimension()))
                .flatMap(frame -> frame.records().stream())
                .filter(ObservationRecord.VisibleSurface.class::isInstance)
                .map(ObservationRecord.VisibleSurface.class::cast)
                .filter(surface -> surface.worldRevision() <= map.worldRevision())
                .filter(surface -> exactObservedState(surface, expected))
                .filter(surface -> surface.placementItem() != null
                        && item.equals(surface.placementItem().value()))
                .findFirst()
                .orElseThrow(() -> new PlanningException(
                        Code.TARGET_UNKNOWN,
                        "Construction source requires a delivered exact state and placement item"));
    }

    private record ConstructionSource(
            ActionDsl.BlockStateSpec state,
            String item,
            ObservationRecord.VisibleSurface surface) {
        private ConstructionSource {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(item, "item");
        }
    }

    private static ActionDsl.Position transformedTarget(
            ActionDsl.Position anchor,
            ActionDsl.BlockPlanTransform transform,
            ActionDsl.Offset rawOffset) {
        ActionDsl.Offset offset = transform.apply(rawOffset);
        return new ActionDsl.Position(
                anchor.dimension(),
                Math.addExact(anchor.x(), offset.x()),
                Math.addExact(anchor.y(), offset.y()),
                Math.addExact(anchor.z(), offset.z()));
    }

    private static ActionDsl.BlockStateSpec transformedState(
            ActionDsl.BlockPlanTransform transform,
            ActionDsl.BlockStateSpec source) {
        try {
            BlockStateView state = BlockPlanStateTransformer.transformFull(
                    new BlockStateView(source.block(), source.properties()),
                    new BlockPlan.Transform(
                            transform.rotation().degrees(), transform.mirror().wireName()),
                    "construction.clear.expected_before");
            return new ActionDsl.BlockStateSpec(state.block(), state.properties());
        } catch (RuntimeException rejected) {
            throw new PlanningException(
                    Code.TARGET_UNKNOWN,
                    "Construction clear expected_before must be one complete safe state");
        }
    }

    private static Vec3 supportFaceCenter(
            ActionDsl.Position position, ActionDsl.BlockFace face) {
        double x = position.x() + 0.5D;
        double y = position.y() + 0.5D;
        double z = position.z() + 0.5D;
        return switch (face) {
            case DOWN -> new Vec3(x, position.y(), z);
            case UP -> new Vec3(x, position.y() + 1.0D, z);
            case NORTH -> new Vec3(x, y, position.z());
            case SOUTH -> new Vec3(x, y, position.z() + 1.0D);
            case WEST -> new Vec3(position.x(), y, z);
            case EAST -> new Vec3(position.x() + 1.0D, y, z);
        };
    }

    private static boolean interactionPointReachable(Pose pose, Vec3 point) {
        double eyeY = pose.y() + pose.eyeHeight();
        double distance = Math.sqrt(
                square(point.x - pose.x())
                        + square(point.y - eyeY)
                        + square(point.z - pose.z()));
        double poseError = Math.hypot(
                pose.horizontalPositionError(),
                Math.max(pose.yErrorBelow(), pose.yErrorAbove()));
        return distance + poseError <= MAX_BREAK_REACH_BLOCKS;
    }

    private static void requirePillarPose(Pose pose, ActionDsl.Position support) {
        double centerX = support.x() + 0.5D;
        double centerZ = support.z() + 0.5D;
        if (!pose.cell().dimension().equals(support.dimension())
                || pose.cell().x() != support.x()
                || pose.cell().y() != support.y() + 1
                || pose.cell().z() != support.z()
                || Math.abs(pose.x() - centerX) + pose.horizontalPositionError() > 0.15D
                || Math.abs(pose.z() - centerZ) + pose.horizontalPositionError() > 0.15D
                || Math.abs(pose.y() - (support.y() + 1.0D))
                        + Math.max(pose.yErrorBelow(), pose.yErrorAbove()) > 1.0e-4D) {
            throw new PlanningException(
                    Code.TARGET_UNKNOWN,
                    "Pillar support must be the centered block directly below the player");
        }
    }

    private static void requireConstructionAim(Pose pose, Vec3 point) {
        Aim desired = aim(pose, point);
        AimError uncertainty = aimError(pose, point, desired);
        double oneWay = withCameraQuantizationReserve(
                angularError(
                        pose.yaw(), pose.pitch(), desired.yaw(), desired.pitch())
                        + pose.orientationErrorDegrees()
                        + uncertainty.totalDegrees());
        if (oneWay > SafeConstructionBlocks.MAX_ONE_WAY_CAMERA_DEGREES) {
            throw new PlanningException(
                    Code.TARGET_UNKNOWN,
                    "Construction support requires a nearer admitted camera heading");
        }
    }

    private static boolean waitWitnessOriginMatches(
            Pose pose, ObservationValues.WorldPosition eyeOrigin) {
        if (!eyeOrigin.dimension().value().equals(pose.cell().dimension())) {
            return false;
        }
        double epsilonSquared = WAIT_WITNESS_EYE_EPSILON_BLOCKS
                * WAIT_WITNESS_EYE_EPSILON_BLOCKS;
        return square(eyeOrigin.x() - pose.x())
                + square(eyeOrigin.y() - (pose.y() + pose.eyeHeight()))
                + square(eyeOrigin.z() - pose.z()) <= epsilonSquared;
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

    /** Selects a deterministic policy-known navigation cell near an observed block. */
    public static ApproachPlan requireApproachPlan(
            KnownTraversabilitySnapshot map,
            DeterministicAStar pathfinder,
            NavCell start,
            ActionDsl.Position target) {
        return requireApproachPlan(map, pathfinder, start, target, null);
    }

    private static ApproachPlan requireApproachPlan(
            KnownTraversabilitySnapshot map,
            DeterministicAStar pathfinder,
            NavCell start,
            ActionDsl.Position target,
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
            double reach = approachReachSquared(candidate, target);
            if (reach > APPROACH_REACH_BLOCKS * APPROACH_REACH_BLOCKS) {
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
        return square(eyeX - closestX)
                + square(eyeY - closestY)
                + square(eyeZ - closestZ);
    }

    public static ActionDslCompiler.Cost faceCost(
            Pose pose, ActionDsl.Position target, float maxCameraDegreesPerTick) {
        Objects.requireNonNull(pose, "pose");
        if (!Float.isFinite(maxCameraDegreesPerTick) || maxCameraDegreesPerTick <= 0.0F) {
            throw new IllegalArgumentException("camera limit must be positive");
        }
        Aim aim = aim(pose, Objects.requireNonNull(target, "target"));
        AimError aimError = aimError(pose, target, aim);
        double camera = withCameraQuantizationReserve(
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
        return breakCost(pose, target, point, maxCameraDegreesPerTick);
    }

    public static ActionDslCompiler.Cost breakCost(
            Pose pose,
            ActionDsl.BreakKnownFace target,
            Vec3 point,
            float maxCameraDegreesPerTick) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(point, "point");
        var aim = aim(pose, point);
        var error = aimError(pose, point, aim);
        double camera = withCameraQuantizationReserve(
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
        double camera = withCameraQuantizationReserve(
                NavigationViewLease.cameraTravelUpperBound(
                        pose.yaw(), pose.pitch(), aim.yaw(), aim.pitch(),
                        Math.toIntExact(BLOCK_MUTATION_TICK_UPPER_BOUND))
                        + pose.orientationErrorDegrees() + error.totalDegrees());
        long aimTicks = Math.max(1L, (long) Math.ceil(camera / maxCameraDegreesPerTick));
        long ticks = Math.addExact(aimTicks, BLOCK_MUTATION_TICK_UPPER_BOUND);
        return new ActionDslCompiler.Cost(
                Math.multiplyExact(ticks, TICK_MILLIS), ticks, 0.0D, camera,
                interactions, blocksBroken, blocksPlaced);
    }

    /**
     * Initial occurrence cost. In addition to the executable route, this prepays one bounded
     * cumulative replan window without enlarging the route executor's own tick bound.
     */
    public static ActionDslCompiler.Cost navigationCost(RoutePlan route, Pose pose) {
        return withNavigationReplanReserve(navigationExecutionCost(route, pose));
    }

    /** Raw executable cost of a freshly rebound route, including every current probe edge. */
    public static ActionDslCompiler.Cost navigationReplanCost(RoutePlan route, Pose pose) {
        return navigationExecutionCost(route, pose);
    }

    /** Replaces the first center-to-center edge with the real pose-to-first-waypoint distance. */
    private static ActionDslCompiler.Cost navigationExecutionCost(RoutePlan route, Pose pose) {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(pose, "pose");
        if (!route.cells().getFirst().equals(pose.cell())) {
            throw new IllegalArgumentException("route does not start at the supplied pose cell");
        }
        double distance = NavigationDistanceBudget.navigationCost(
                route, pose.cell(), pose.x(), pose.y(), pose.z(),
                pose.horizontalPositionError(), pose.yErrorBelow(), pose.yErrorAbove());
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

    /** Navigation cost plus a bounded post-arrival item pickup confirmation window. */
    public static ActionDslCompiler.Cost pickupCost(RoutePlan route, Pose pose) {
        ActionDslCompiler.Cost navigation = navigationCost(route, pose);
        return withPickupConfirmation(navigation);
    }

    /** Raw rebound navigation plus pickup confirmation; no new replan reserve is granted. */
    public static ActionDslCompiler.Cost pickupReplanCost(RoutePlan route, Pose pose) {
        return withPickupConfirmation(navigationReplanCost(route, pose));
    }

    private static ActionDslCompiler.Cost withNavigationReplanReserve(
            ActionDslCompiler.Cost navigation) {
        Objects.requireNonNull(navigation, "navigation");
        long reserveMillis = Math.multiplyExact(
                NAVIGATION_REPLAN_RESERVE_TICKS, TICK_MILLIS);
        return new ActionDslCompiler.Cost(
                Math.addExact(navigation.durationMillis(), reserveMillis),
                Math.addExact(navigation.ticks(), NAVIGATION_REPLAN_RESERVE_TICKS),
                navigation.distanceBlocks(),
                navigation.cameraDegrees(),
                navigation.interactions(),
                navigation.blocksBroken(),
                navigation.blocksPlaced());
    }

    private static ActionDslCompiler.Cost withPickupConfirmation(
            ActionDslCompiler.Cost navigation) {
        Objects.requireNonNull(navigation, "navigation");
        return new ActionDslCompiler.Cost(
                Math.addExact(navigation.durationMillis(),
                        Math.multiplyExact(PICKUP_CONFIRM_TICKS, TICK_MILLIS)),
                Math.addExact(navigation.ticks(), PICKUP_CONFIRM_TICKS),
                navigation.distanceBlocks(),
                navigation.cameraDegrees(),
                navigation.interactions(),
                navigation.blocksBroken(),
                navigation.blocksPlaced());
    }

    /** Resolves one currently visible item witness to a reachable, known player-feet cell. */
    public static PickupPlan requirePickupPlan(
            KnownTraversabilitySnapshot map,
            DeterministicAStar pathfinder,
            NavCell start,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.CollectVisibleItem target) {
        ObservationRecord.VisibleEntity entity = requireVisibleItem(map, latestFrame, target);
        return requirePickupPlan(
                map, pathfinder, start, entity, new PlanningWork(() -> true));
    }

    /** Runtime variant that rejects an observation frame older than one visual scan cycle. */
    public static PickupPlan requirePickupPlan(
            KnownTraversabilitySnapshot map,
            DeterministicAStar pathfinder,
            NavCell start,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.CollectVisibleItem target,
            long currentTick,
            long maxAgeTicks) {
        return requirePickupPlan(
                map, pathfinder, start, latestFrame, target,
                map.worldRevision(), currentTick, maxAgeTicks);
    }

    /** Runtime variant accepting evidence no older than the current visual barrier. */
    public static PickupPlan requirePickupPlan(
            KnownTraversabilitySnapshot map,
            DeterministicAStar pathfinder,
            NavCell start,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.CollectVisibleItem target,
            long visualBarrierWorldRevision,
            long currentTick,
            long maxAgeTicks) {
        ObservationRecord.VisibleEntity entity = requireVisibleItem(
                map, latestFrame, target,
                visualBarrierWorldRevision, currentTick, maxAgeTicks);
        return requirePickupPlan(
                map, pathfinder, start, entity, new PlanningWork(() -> true));
    }

    /** True only while the exact policy-visible item/position witness remains current. */
    public static boolean visibleItemCurrent(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.CollectVisibleItem target) {
        try {
            requireVisibleItem(map, latestFrame, target);
            return true;
        } catch (PlanningException unavailable) {
            return false;
        }
    }

    /** Runtime freshness check for a moving entity witness. */
    public static boolean visibleItemCurrent(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.CollectVisibleItem target,
            long currentTick,
            long maxAgeTicks) {
        return visibleItemCurrent(
                map, latestFrame, target, map.worldRevision(), currentTick, maxAgeTicks);
    }

    /** Runtime freshness check bounded by the most recent visual-invalidating mutation. */
    public static boolean visibleItemCurrent(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.CollectVisibleItem target,
            long visualBarrierWorldRevision,
            long currentTick,
            long maxAgeTicks) {
        try {
            requireVisibleItem(
                    map, latestFrame, target,
                    visualBarrierWorldRevision, currentTick, maxAgeTicks);
            return true;
        } catch (PlanningException unavailable) {
            return false;
        }
    }

    /** True only while the planned cell can still contact the freshly observed witness. */
    public static boolean visibleItemPickupCellCurrent(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.CollectVisibleItem target,
            NavCell pickupCell,
            long currentTick,
            long maxAgeTicks) {
        return visibleItemPickupCellCurrent(
                map, latestFrame, target, pickupCell,
                map.worldRevision(), currentTick, maxAgeTicks);
    }

    /** Runtime pickup-cell check bounded by the most recent visual-invalidating mutation. */
    public static boolean visibleItemPickupCellCurrent(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.CollectVisibleItem target,
            NavCell pickupCell,
            long visualBarrierWorldRevision,
            long currentTick,
            long maxAgeTicks) {
        Objects.requireNonNull(pickupCell, "pickupCell");
        try {
            ObservationRecord.VisibleEntity entity = requireVisibleItem(
                    map, latestFrame, target,
                    visualBarrierWorldRevision, currentTick, maxAgeTicks);
            return pickupCellCanContact(pickupCell, entity.aabb());
        } catch (PlanningException unavailable) {
            return false;
        }
    }

    /** Returns the freshly revalidated item AABB for an exact runtime pickup-area check. */
    public static Optional<ObservationValues.Aabb> visibleItemAabb(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.CollectVisibleItem target,
            long currentTick,
            long maxAgeTicks) {
        return visibleItemAabb(
                map, latestFrame, target,
                map.worldRevision(), currentTick, maxAgeTicks);
    }

    /** Runtime item bounds check bounded by the most recent visual-invalidating mutation. */
    public static Optional<ObservationValues.Aabb> visibleItemAabb(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.CollectVisibleItem target,
            long visualBarrierWorldRevision,
            long currentTick,
            long maxAgeTicks) {
        try {
            return Optional.of(requireVisibleItem(
                    map, latestFrame, target,
                    visualBarrierWorldRevision, currentTick, maxAgeTicks).aabb());
        } catch (PlanningException unavailable) {
            return Optional.empty();
        }
    }

    /**
     * Resolves only submitted batch witnesses against one fresh delivered frame. One visible
     * record can satisfy at most one listed target; missing or ambiguous suffix entries remain
     * empty and are never discovered from live entities.
     */
    public static List<Optional<ObservationValues.Aabb>> visibleBatchItemAabbs(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.CollectVisibleItemBatch batch,
            long visualBarrierWorldRevision,
            long currentTick,
            long maxAgeTicks) {
        Objects.requireNonNull(batch, "batch");
        var used = new LinkedHashSet<ObservationRecord.VisibleEntity>();
        var result = new ArrayList<Optional<ObservationValues.Aabb>>(batch.targets().size());
        for (int index = 0; index < batch.targets().size(); index++) {
            ActionDsl.CollectVisibleItem child = collectBatchChild(batch, index);
            Optional<ObservationRecord.VisibleEntity> matched = matchingVisibleItems(
                            map, latestFrame, child,
                            visualBarrierWorldRevision, currentTick, maxAgeTicks)
                    .stream()
                    .filter(used::add)
                    .findFirst();
            result.add(matched.map(ObservationRecord.VisibleEntity::aabb));
        }
        return List.copyOf(result);
    }

    private static ObservationRecord.VisibleEntity requireVisibleItem(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.CollectVisibleItem target) {
        return requireVisibleItem(map, latestFrame, target, map.worldRevision());
    }

    private static ObservationRecord.VisibleEntity requireVisibleItem(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.CollectVisibleItem target,
            long visualBarrierWorldRevision) {
        long frameTick = latestFrame.map(ObservationFrame::frameCompletedTick).orElse(0L);
        return requireVisibleItem(
                map, latestFrame, target,
                visualBarrierWorldRevision, frameTick, 0L);
    }

    private static ObservationRecord.VisibleEntity requireVisibleItem(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.CollectVisibleItem target,
            long currentTick,
            long maxAgeTicks) {
        return requireVisibleItem(
                map, latestFrame, target,
                map.worldRevision(), currentTick, maxAgeTicks);
    }

    private static ObservationRecord.VisibleEntity requireVisibleItem(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.CollectVisibleItem target,
            long visualBarrierWorldRevision,
            long currentTick,
            long maxAgeTicks) {
        return matchingVisibleItems(
                        map, latestFrame, target,
                        visualBarrierWorldRevision, currentTick, maxAgeTicks)
                .stream()
                .findFirst()
                .orElseThrow(() -> new PlanningException(
                        Code.TARGET_UNKNOWN,
                        "Collect target requires current matching visible item evidence"));
    }

    private static List<ObservationRecord.VisibleEntity> matchingVisibleItems(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.CollectVisibleItem target,
            long visualBarrierWorldRevision,
            long currentTick,
            long maxAgeTicks) {
        Objects.requireNonNull(map, "map");
        Objects.requireNonNull(latestFrame, "latestFrame");
        Objects.requireNonNull(target, "target");
        if (currentTick < 0L || maxAgeTicks < 0L) {
            throw new IllegalArgumentException("visible item freshness bounds must be non-negative");
        }
        requireVisualBarrierWorldRevision(
                map, map.worldRevision(), visualBarrierWorldRevision);
        return latestFrame.stream()
                .filter(frame -> frame.dimension().value().equals(map.dimension()))
                .filter(frame -> frame.frameCompletedTick() <= currentTick
                        && currentTick - frame.frameCompletedTick() <= maxAgeTicks)
                .flatMap(frame -> frame.records().stream()
                        .filter(record -> record.newestObservedTick()
                                == frame.frameCompletedTick()))
                .filter(ObservationRecord.VisibleEntity.class::isInstance)
                .map(ObservationRecord.VisibleEntity.class::cast)
                .filter(entity -> entity.worldRevision() >= visualBarrierWorldRevision
                        && entity.worldRevision() <= map.worldRevision())
                .filter(entity -> "minecraft:item".equals(entity.entityType().value()))
                .filter(entity -> entity.displayedItem() != null
                        && target.displayedItem().equals(entity.displayedItem().value()))
                .filter(entity -> sameVisibleItemPosition(entity.position(), target.target()))
                .sorted(java.util.Comparator.comparingDouble(entity ->
                        visibleItemDistanceSquared(entity.position(), target.target())))
                .toList();
    }

    /**
     * Fences a reconciliation-provided visual barrier to the exact traversability revision.
     */
    public static long requireVisualBarrierWorldRevision(
            KnownTraversabilitySnapshot map,
            long reconciliationWorldRevision,
            long visualBarrierWorldRevision) {
        Objects.requireNonNull(map, "map");
        return requireVisualBarrierWorldRevision(
                map,
                map.worldSessionId(),
                reconciliationWorldRevision,
                visualBarrierWorldRevision);
    }

    public static long requireVisualBarrierWorldRevision(
            KnownTraversabilitySnapshot map,
            UUID reconciliationWorldSessionId,
            long reconciliationWorldRevision,
            long visualBarrierWorldRevision) {
        Objects.requireNonNull(map, "map");
        Objects.requireNonNull(reconciliationWorldSessionId, "reconciliationWorldSessionId");
        if (!map.worldSessionId().equals(reconciliationWorldSessionId)
                || reconciliationWorldRevision < 0L || visualBarrierWorldRevision < 0L
                || reconciliationWorldRevision != map.worldRevision()
                || visualBarrierWorldRevision > reconciliationWorldRevision) {
            throw new PlanningException(
                    Code.TARGET_UNKNOWN,
                    "Visual evidence revision window does not match the current map");
        }
        return visualBarrierWorldRevision;
    }

    public static long requireSurfaceBarrierWorldRevision(
            KnownTraversabilitySnapshot map,
            long surfaceBarrierWorldRevision) {
        Objects.requireNonNull(map, "map");
        if (surfaceBarrierWorldRevision < 0L
                || surfaceBarrierWorldRevision > map.worldRevision()) {
            throw new PlanningException(
                    Code.TARGET_UNKNOWN,
                    "Surface evidence revision window does not match the current map");
        }
        return surfaceBarrierWorldRevision;
    }

    private static long surfaceBarrierWorldRevision(
            KnownTraversabilitySnapshot map,
            ToLongFunction<ActionDsl.Position> surfaceRevisionBarrier,
            ActionDsl.Position position) {
        Objects.requireNonNull(surfaceRevisionBarrier, "surfaceRevisionBarrier");
        Objects.requireNonNull(position, "position");
        return requireSurfaceBarrierWorldRevision(
                map, surfaceRevisionBarrier.applyAsLong(position));
    }

    private static PickupPlan requirePickupPlan(
            KnownTraversabilitySnapshot map,
            DeterministicAStar pathfinder,
            NavCell start,
            ObservationRecord.VisibleEntity entity,
            PlanningWork work) {
        var candidates = new java.util.TreeSet<NavCell>();
        candidates.add(start);
        for (TraversabilityEdge edge : map.edges().values()) {
            if (edge.traversable()) {
                candidates.add(edge.key().from());
                candidates.add(edge.key().to());
            }
        }
        var pickupCells = candidates.stream()
                .filter(cell -> pickupCellCanContact(cell, entity.aabb()))
                .toList();
        var reachable = new ArrayList<PickupPlan>(pickupCells.size());
        for (NavCell candidate : pickupCells) {
            var result = pathfinder.findRoute(
                    map, start, candidate, work::canContinue, work::routeExpansion);
            if (result.route().isPresent()) {
                reachable.add(new PickupPlan(result.route().orElseThrow(), candidate));
            }
        }
        if (!reachable.isEmpty()) {
            return reachable.stream()
                    .min(java.util.Comparator
                            .comparingLong((PickupPlan plan) -> plan.route().tickUpperBound())
                            .thenComparingDouble(plan -> plan.route().distanceBlocks())
                            .thenComparingDouble(plan -> pickupDistanceSquared(
                                    plan.pickupCell(), entity.aabb()))
                            .thenComparing(PickupPlan::pickupCell))
                    .orElseThrow();
        }
        throw new PlanningException(
                pickupCells.isEmpty() ? Code.TARGET_UNKNOWN : Code.NO_KNOWN_PATH,
                pickupCells.isEmpty()
                        ? "No known safe pickup cell overlaps the visible item"
                        : "No policy-approved route reaches a known item pickup cell");
    }

    private static boolean pickupCellCanContact(
            NavCell cell, ObservationValues.Aabb item) {
        double centerX = cell.x() + 0.5D;
        double centerZ = cell.z() + 0.5D;
        return item.maxX() > centerX - PICKUP_HORIZONTAL_REACH
                && item.minX() < centerX + PICKUP_HORIZONTAL_REACH
                && item.maxZ() > centerZ - PICKUP_HORIZONTAL_REACH
                && item.minZ() < centerZ + PICKUP_HORIZONTAL_REACH
                && item.maxY() > cell.y() - PICKUP_VERTICAL_INFLATE
                && item.minY() < cell.y()
                        + MIN_NAVIGATING_PLAYER_HEIGHT + PICKUP_VERTICAL_INFLATE;
    }

    private static double pickupDistanceSquared(
            NavCell cell, ObservationValues.Aabb item) {
        double x = cell.x() + 0.5D;
        double z = cell.z() + 0.5D;
        double dx = x < item.minX() ? item.minX() - x
                : x > item.maxX() ? x - item.maxX() : 0.0D;
        double dz = z < item.minZ() ? item.minZ() - z
                : z > item.maxZ() ? z - item.maxZ() : 0.0D;
        double pickupMinY = cell.y() - PICKUP_VERTICAL_INFLATE;
        double pickupMaxY = cell.y()
                + MIN_NAVIGATING_PLAYER_HEIGHT + PICKUP_VERTICAL_INFLATE;
        double dy = pickupMinY > item.maxY() ? pickupMinY - item.maxY()
                : pickupMaxY < item.minY() ? item.minY() - pickupMaxY : 0.0D;
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean sameVisibleItemPosition(
            ObservationValues.WorldPosition observed, ActionDsl.WorldPosition requested) {
        return observed.dimension().value().equals(requested.dimension())
                && visibleItemDistanceSquared(observed, requested)
                        <= VISIBLE_ITEM_MATCH_RADIUS * VISIBLE_ITEM_MATCH_RADIUS;
    }

    private static double visibleItemDistanceSquared(
            ObservationValues.WorldPosition observed, ActionDsl.WorldPosition requested) {
        return square(observed.x() - requested.x())
                + square(observed.y() - requested.y())
                + square(observed.z() - requested.z());
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
                        || required.cropMature().equals(surface.cropMature()))
                && (required.eyeOrigin() == null
                        || surface.eyeOrigin().dimension().value().equals(target.dimension())
                                && square(required.eyeOrigin().x - surface.eyeOrigin().x())
                                + square(required.eyeOrigin().y - surface.eyeOrigin().y())
                                + square(required.eyeOrigin().z - surface.eyeOrigin().z())
                                <= WAIT_WITNESS_EYE_EPSILON_BLOCKS
                                        * WAIT_WITNESS_EYE_EPSILON_BLOCKS);
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
            Vec3 point) {
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
        return Math.abs(Mth.wrapDegrees((double) desiredYaw - yaw))
                + Math.abs((double) Mth.clamp(desiredPitch, -90.0F, 90.0F) - pitch);
    }

    private static double withCameraQuantizationReserve(double geometricDegrees) {
        if (!Double.isFinite(geometricDegrees) || geometricDegrees < 0.0D) {
            throw new IllegalArgumentException("camera travel must be finite and non-negative");
        }
        return Math.min(360.0D,
                geometricDegrees + CAMERA_QUANTIZATION_RESERVE_DEGREES);
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

        private Pose withAdditionalYErrorBelow(double additional) {
            if (!Double.isFinite(additional) || additional < 0.0D) {
                throw new IllegalArgumentException("additional y error must be finite");
            }
            return new Pose(
                    cell, x, y, z, eyeHeight, yaw, pitch,
                    horizontalPositionError,
                    yErrorBelow + additional,
                    yErrorAbove,
                    orientationErrorDegrees);
        }
    }

    public record Analysis(
            Map<String, ActionDslCompiler.Cost> primitiveCosts,
            Map<TraversabilityEdge.Key, TraversabilityEdge> routeDependencies,
            Set<ActionDsl.Position> knownTargets,
            Set<KnownSurface> knownSurfaces,
            Map<String, MutationAim> mutationAims,
            Map<String, MutationBatchPlan> mutationBatchPlans) {
        public Analysis {
            primitiveCosts = Map.copyOf(Objects.requireNonNull(primitiveCosts, "primitiveCosts"));
            routeDependencies = Map.copyOf(
                    Objects.requireNonNull(routeDependencies, "routeDependencies"));
            knownTargets = Set.copyOf(Objects.requireNonNull(knownTargets, "knownTargets"));
            knownSurfaces = Set.copyOf(Objects.requireNonNull(knownSurfaces, "knownSurfaces"));
            mutationAims = Map.copyOf(Objects.requireNonNull(mutationAims, "mutationAims"));
            mutationBatchPlans = Map.copyOf(
                    Objects.requireNonNull(mutationBatchPlans, "mutationBatchPlans"));
        }

        public Optional<ActionDslCompiler.Cost> worstCase(ActionDsl.Node primitive) {
            return Optional.ofNullable(primitiveCosts.get(primitive.id()));
        }
    }

    public record KnownSurface(
            ActionDsl.Position position,
            ActionDsl.BlockFace face,
            String block,
            Boolean cropMature,
            Vec3 eyeOrigin) {
        public KnownSurface(
                ActionDsl.Position position, ActionDsl.BlockFace face, String block) {
            this(position, face, block, null, null);
        }

        public KnownSurface(
                ActionDsl.Position position,
                ActionDsl.BlockFace face,
                String block,
                Boolean cropMature) {
            this(position, face, block, cropMature, null);
        }

        public KnownSurface {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(face, "face");
            Objects.requireNonNull(block, "block");
            if (eyeOrigin != null && (!Double.isFinite(eyeOrigin.x)
                    || !Double.isFinite(eyeOrigin.y)
                    || !Double.isFinite(eyeOrigin.z))) {
                throw new IllegalArgumentException("surface eye origin must be finite");
            }
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

    public record ApproachPlan(RoutePlan route, NavCell anchor) {
        public ApproachPlan {
            Objects.requireNonNull(route, "route");
            Objects.requireNonNull(anchor, "anchor");
            if (!route.cells().getLast().equals(anchor)) {
                throw new IllegalArgumentException("approach anchor must terminate the route");
            }
        }
    }

    public record MutationBatchStep(
            ActionDsl.Node primitive,
            MutationAim aim,
            ActionDslCompiler.Cost plannedCost) {
        public MutationBatchStep {
            Objects.requireNonNull(primitive, "primitive");
            Objects.requireNonNull(aim, "aim");
            Objects.requireNonNull(plannedCost, "plannedCost");
            if (!(primitive instanceof ActionDsl.TillKnownBlock)
                    && !(primitive instanceof ActionDsl.PlantKnownWheat)
                    && !(primitive instanceof ActionDsl.HarvestKnownWheat)) {
                throw new IllegalArgumentException("batch step must be a wheat mutation primitive");
            }
        }
    }

    public record MutationBatchPlan(List<MutationBatchStep> steps) {
        public MutationBatchPlan {
            steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
            if (steps.isEmpty()
                    || steps.size() > ActionDslValidator.MAX_MUTATION_BATCH_TARGETS) {
                throw new IllegalArgumentException("mutation batch plan size is outside 1..8");
            }
        }

        /** Fresh current target plus the originally proved, not-yet-started suffix. */
        public ActionDslCompiler.Cost requiredRemainder(
                int currentIndex, ActionDslCompiler.Cost freshCurrent) {
            Objects.requireNonNull(freshCurrent, "freshCurrent");
            if (currentIndex < 0 || currentIndex >= steps.size()) {
                throw new IllegalArgumentException("currentIndex is outside the mutation batch");
            }
            ActionDslCompiler.Cost required = freshCurrent;
            for (int index = currentIndex + 1; index < steps.size(); index++) {
                required = addCosts(required, steps.get(index).plannedCost());
            }
            return required;
        }
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

    public record PickupPlan(RoutePlan route, NavCell pickupCell) {
        public PickupPlan {
            Objects.requireNonNull(route, "route");
            Objects.requireNonNull(pickupCell, "pickupCell");
            if (!route.cells().getLast().equals(pickupCell)) {
                throw new IllegalArgumentException("pickup cell must terminate the route");
            }
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
