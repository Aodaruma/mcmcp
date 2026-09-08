package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.agent.action.AgentPlannerGeometry.Aim;
import dev.aod.mcmcp.agent.action.AgentPlannerGeometry.AimError;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.dsl.ActionDslCompiler;
import dev.aod.mcmcp.agent.dsl.ActionDslValidator;
import dev.aod.mcmcp.agent.navigation.DeterministicAStar;
import dev.aod.mcmcp.agent.navigation.KnownTraversabilitySnapshot;
import dev.aod.mcmcp.agent.navigation.NavCell;
import dev.aod.mcmcp.agent.navigation.NavigationDistanceBudget;
import dev.aod.mcmcp.agent.navigation.RoutePlan;
import dev.aod.mcmcp.agent.navigation.TraversabilityEdge;
import dev.aod.mcmcp.agent.observation.ObservationFrame;
import dev.aod.mcmcp.agent.observation.ObservationValues;
import dev.aod.mcmcp.agent.observation.PlacementStateResolver;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.ToLongFunction;

/** 公開planner APIと不変の解析結果型。各責務の計画処理へ委譲する。 */
public final class AgentPrimitivePlanner {
    private static final double MAX_CELL_HORIZONTAL_ERROR =
            NavigationDistanceBudget.MAX_HORIZONTAL_POSITION_ERROR;

    static final double NAVIGATION_VERTICAL_ERROR_ABOVE =
            NavigationDistanceBudget.MAX_VERTICAL_POSITION_ERROR;

    private static final double FACE_COMPLETION_ERROR_DEGREES = 1.5D;

    /**
     * Vanilla's {@code player.turn} path converts the requested delta through a 0.15 scale and
     * float player rotations. Reserve one bounded sub-degree step so a geometrically zero or
     * near-zero aim cannot consume more camera budget than admission proved.
     */
    public static final double CAMERA_QUANTIZATION_RESERVE_DEGREES = 0.25D;

    public static final double WAIT_WITNESS_EYE_EPSILON_BLOCKS = 1.0D / 1024.0D;

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
        return AgentProgramPlanner.analyze(
                program, map, pathfinder, initialPose, latestFrame, maxCameraDegreesPerTick,
                visualBarrierWorldRevision, surfaceRevisionBarrier, canContinue, placementStates);
    }

    public static RoutePlan requireRoute(
            KnownTraversabilitySnapshot map,
            DeterministicAStar pathfinder,
            NavCell start,
            ActionDsl.Position target) {
        return AgentNavigationPlanner.requireRoute(
                map, pathfinder, start, target);
    }

    public static MinecraftActionPrimitiveExecutor.KnownFaceTarget requireKnownFaceTarget(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.Position target) {
        return AgentSurfaceEvidence.requireKnownFaceTarget(
                map, latestFrame, target);
    }

    public static MinecraftActionPrimitiveExecutor.KnownFaceTarget requireKnownBlockFaceTarget(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.FaceKnownBlockFace target) {
        return AgentSurfaceEvidence.requireKnownBlockFaceTarget(
                map, latestFrame, target);
    }

    /**
     * Camera-only recovery may reuse an unexpired delivered surface identity.
     * Mutation admission remains separately fenced by {@link #knownSurface}.
     */
    public static boolean knownFacingSurface(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            KnownSurface required) {
        return AgentSurfaceEvidence.knownFacingSurface(
                map, latestFrame, required);
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
        return AgentSurfaceEvidence.knownFacingTarget(
                map, latestFrame, target);
    }

    public static boolean knownTarget(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.Position target) {
        return AgentSurfaceEvidence.knownTarget(
                map, latestFrame, target);
    }

    public static boolean knownTarget(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.Position target,
            long surfaceBarrierWorldRevision) {
        return AgentSurfaceEvidence.knownTarget(
                map, latestFrame, target, surfaceBarrierWorldRevision);
    }

    public static KnownSurface requireKnownBreakSurface(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.BreakKnownFace target) {
        return AgentSurfaceEvidence.requireKnownBreakSurface(
                map, latestFrame, target);
    }

    public static KnownSurface requireKnownBreakSurface(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.BreakKnownFace target,
            long surfaceBarrierWorldRevision) {
        return AgentSurfaceEvidence.requireKnownBreakSurface(
                map, latestFrame, target, surfaceBarrierWorldRevision);
    }

    public static MutationAim requireKnownBreakAim(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.BreakKnownFace target,
            long surfaceBarrierWorldRevision) {
        return AgentSurfaceEvidence.requireKnownBreakAim(
                map, latestFrame, target, surfaceBarrierWorldRevision);
    }

    public static KnownSurface requireKnownBreakSurface(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.BreakKnownBlock target,
            long surfaceBarrierWorldRevision) {
        return AgentSurfaceEvidence.requireKnownBreakSurface(
                map, latestFrame, target, surfaceBarrierWorldRevision);
    }

    public static MutationAim requireKnownBreakAim(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.BreakKnownBlock target,
            long surfaceBarrierWorldRevision) {
        return AgentSurfaceEvidence.requireKnownBreakAim(
                map, latestFrame, target, surfaceBarrierWorldRevision);
    }

    public static boolean knownSurface(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            KnownSurface required) {
        return AgentSurfaceEvidence.knownSurface(
                map, latestFrame, required);
    }

    public static boolean knownSurface(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            KnownSurface required,
            long surfaceBarrierWorldRevision) {
        return AgentSurfaceEvidence.knownSurface(
                map, latestFrame, required, surfaceBarrierWorldRevision);
    }

    public static boolean knownExactSurface(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.Position target,
            ActionDsl.BlockFace face,
            ActionDsl.BlockStateSpec expected,
            long surfaceBarrierWorldRevision) {
        return AgentSurfaceEvidence.knownExactSurface(
                map, latestFrame, target, face, expected, surfaceBarrierWorldRevision);
    }

    public static KnownSurface requireKnownSurface(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.Position position,
            String block) {
        return AgentSurfaceEvidence.requireKnownSurface(
                map, latestFrame, position, block);
    }

    public static KnownSurface requireKnownSurface(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.Position position,
            String block,
            long surfaceBarrierWorldRevision) {
        return AgentSurfaceEvidence.requireKnownSurface(
                map, latestFrame, position, block, surfaceBarrierWorldRevision);
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
        return AgentSurfaceEvidence.requireKnownWheatWaitSurface(
                map, latestFrame, poses, position, surfaceBarrierWorldRevision);
    }

    public static ActionDsl.CollectVisibleItem collectBatchChild(
            ActionDsl.CollectVisibleItemBatch batch, int index) {
        return AgentPickupPlanner.collectBatchChild(
                batch, index);
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
        return AgentMutationPlanner.recostMutationBatchRemainder(
                plan, currentIndex, currentPose, freshCurrentAim, freshCurrentCost, cameraLimit);
    }

    public static List<ActionDsl.Node> mutationBatchChildren(ActionDsl.Node batch) {
        return AgentMutationPlanner.mutationBatchChildren(
                batch);
    }

    /**
     * Selects one known stand cell that can serve every entry of the stationary placement plan.
     * The opaque placement identities and support witnesses are re-resolved at runtime.
     */
    public static ApproachPlan requireKnownPlacementApproachPlan(
            KnownTraversabilitySnapshot map,
            DeterministicAStar pathfinder,
            Pose startPose,
            ActionDsl.ApproachKnownPlacement approach,
            Optional<ObservationFrame> latestFrame,
            ToLongFunction<ActionDsl.Position> surfaceRevisionBarrier,
            PlacementStateResolver placementStates) {
        return AgentConstructionPlanner.requireKnownPlacementApproachPlan(
                map, pathfinder, startPose, approach, latestFrame, surfaceRevisionBarrier,
                placementStates);
    }

    /**
     * Legacy geometry-only helper retained for API compatibility.
     * Product Action admission uses the delivery-backed {@link Pose} overload below.
     */
    public static ApproachPlan requireApproachPlan(
            KnownTraversabilitySnapshot map,
            DeterministicAStar pathfinder,
            NavCell start,
            ActionDsl.Position target) {
        return AgentNavigationPlanner.requireApproachPlan(
                map, pathfinder, start, target);
    }

    /**
     * Selects an approach cell which remains within mutation reach of at least one current,
     * delivery-backed ray witness even at the admitted navigation settlement error. Reach is
     * safe for both the current eye height and standing height because navigation input cleanup
     * may release a crouched pose before arrival.
     */
    public static ApproachPlan requireApproachPlan(
            KnownTraversabilitySnapshot map,
            DeterministicAStar pathfinder,
            Pose startPose,
            ActionDsl.Position target,
            String expectedBlock,
            Optional<ObservationFrame> latestFrame,
            long surfaceBarrierWorldRevision) {
        return AgentNavigationPlanner.requireApproachPlan(
                map, pathfinder, startPose, target, expectedBlock, latestFrame,
                surfaceBarrierWorldRevision);
    }

    public static ActionDslCompiler.Cost faceCost(
            Pose pose, ActionDsl.Position target, float maxCameraDegreesPerTick) {
        return AgentPlannerCosts.faceCost(
                pose, target, maxCameraDegreesPerTick);
    }

    public static ActionDslCompiler.Cost faceCost(
            Pose pose, ActionDsl.FaceKnownBlockFace target, float maxCameraDegreesPerTick) {
        return AgentPlannerCosts.faceCost(
                pose, target, maxCameraDegreesPerTick);
    }

    public static ActionDslCompiler.Cost breakCost(
            Pose pose, ActionDsl.BreakKnownFace target, float maxCameraDegreesPerTick) {
        return AgentPlannerCosts.breakCost(
                pose, target, maxCameraDegreesPerTick);
    }

    public static ActionDslCompiler.Cost breakCost(
            Pose pose,
            ActionDsl.BreakKnownFace target,
            Vec3 point,
            float maxCameraDegreesPerTick) {
        return AgentPlannerCosts.breakCost(
                pose, target, point, maxCameraDegreesPerTick);
    }

    public static ActionDslCompiler.Cost breakCost(
            Pose pose,
            ActionDsl.BreakKnownBlock target,
            Vec3 point,
            float maxCameraDegreesPerTick) {
        return AgentPlannerCosts.breakCost(
                pose, target, point, maxCameraDegreesPerTick);
    }

    public static ActionDslCompiler.Cost mutationCost(
            Pose pose,
            Vec3 aimPoint,
            float maxCameraDegreesPerTick,
            long interactions,
            long blocksBroken,
            long blocksPlaced) {
        return AgentPlannerCosts.mutationCost(
                pose, aimPoint, maxCameraDegreesPerTick, interactions, blocksBroken, blocksPlaced);
    }

    /**
     * Initial occurrence cost. In addition to the executable route, this prepays one bounded
     * cumulative replan window without enlarging the route executor's own tick bound.
     */
    public static ActionDslCompiler.Cost navigationCost(RoutePlan route, Pose pose) {
        return AgentPlannerCosts.navigationCost(
                route, pose);
    }

    /** Raw executable cost of a freshly rebound route, including every current probe edge. */
    public static ActionDslCompiler.Cost navigationReplanCost(RoutePlan route, Pose pose) {
        return AgentPlannerCosts.navigationReplanCost(
                route, pose);
    }

    /** Navigation cost plus a bounded post-arrival item pickup confirmation window. */
    public static ActionDslCompiler.Cost pickupCost(RoutePlan route, Pose pose) {
        return AgentPlannerCosts.pickupCost(
                route, pose);
    }

    /** Raw rebound navigation plus pickup confirmation; no new replan reserve is granted. */
    public static ActionDslCompiler.Cost pickupReplanCost(RoutePlan route, Pose pose) {
        return AgentPlannerCosts.pickupReplanCost(
                route, pose);
    }

    /** Resolves one currently visible item witness to a reachable, known player-feet cell. */
    public static PickupPlan requirePickupPlan(
            KnownTraversabilitySnapshot map,
            DeterministicAStar pathfinder,
            NavCell start,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.CollectVisibleItem target) {
        return AgentPickupPlanner.requirePickupPlan(
                map, pathfinder, start, latestFrame, target);
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
        return AgentPickupPlanner.requirePickupPlan(
                map, pathfinder, start, latestFrame, target, currentTick, maxAgeTicks);
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
        return AgentPickupPlanner.requirePickupPlan(
                map, pathfinder, start, latestFrame, target, visualBarrierWorldRevision,
                currentTick, maxAgeTicks);
    }

    /** True only while the exact policy-visible item/position witness remains current. */
    public static boolean visibleItemCurrent(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.CollectVisibleItem target) {
        return AgentPickupPlanner.visibleItemCurrent(
                map, latestFrame, target);
    }

    /** Runtime freshness check for a moving entity witness. */
    public static boolean visibleItemCurrent(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.CollectVisibleItem target,
            long currentTick,
            long maxAgeTicks) {
        return AgentPickupPlanner.visibleItemCurrent(
                map, latestFrame, target, currentTick, maxAgeTicks);
    }

    /** Runtime freshness check bounded by the most recent visual-invalidating mutation. */
    public static boolean visibleItemCurrent(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.CollectVisibleItem target,
            long visualBarrierWorldRevision,
            long currentTick,
            long maxAgeTicks) {
        return AgentPickupPlanner.visibleItemCurrent(
                map, latestFrame, target, visualBarrierWorldRevision, currentTick, maxAgeTicks);
    }

    /** True only while the planned cell can still contact the freshly observed witness. */
    public static boolean visibleItemPickupCellCurrent(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.CollectVisibleItem target,
            NavCell pickupCell,
            long currentTick,
            long maxAgeTicks) {
        return AgentPickupPlanner.visibleItemPickupCellCurrent(
                map, latestFrame, target, pickupCell, currentTick, maxAgeTicks);
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
        return AgentPickupPlanner.visibleItemPickupCellCurrent(
                map, latestFrame, target, pickupCell, visualBarrierWorldRevision, currentTick,
                maxAgeTicks);
    }

    /** Returns the freshly revalidated item AABB for an exact runtime pickup-area check. */
    public static Optional<ObservationValues.Aabb> visibleItemAabb(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.CollectVisibleItem target,
            long currentTick,
            long maxAgeTicks) {
        return AgentPickupPlanner.visibleItemAabb(
                map, latestFrame, target, currentTick, maxAgeTicks);
    }

    /** Runtime item bounds check bounded by the most recent visual-invalidating mutation. */
    public static Optional<ObservationValues.Aabb> visibleItemAabb(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.CollectVisibleItem target,
            long visualBarrierWorldRevision,
            long currentTick,
            long maxAgeTicks) {
        return AgentPickupPlanner.visibleItemAabb(
                map, latestFrame, target, visualBarrierWorldRevision, currentTick, maxAgeTicks);
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
        return AgentPickupPlanner.visibleBatchItemAabbs(
                map, latestFrame, batch, visualBarrierWorldRevision, currentTick, maxAgeTicks);
    }

    /**
     * Fences a reconciliation-provided visual barrier to the exact traversability revision.
     */
    public static long requireVisualBarrierWorldRevision(
            KnownTraversabilitySnapshot map,
            long reconciliationWorldRevision,
            long visualBarrierWorldRevision) {
        return AgentSurfaceEvidence.requireVisualBarrierWorldRevision(
                map, reconciliationWorldRevision, visualBarrierWorldRevision);
    }

    public static long requireVisualBarrierWorldRevision(
            KnownTraversabilitySnapshot map,
            UUID reconciliationWorldSessionId,
            long reconciliationWorldRevision,
            long visualBarrierWorldRevision) {
        return AgentSurfaceEvidence.requireVisualBarrierWorldRevision(
                map, reconciliationWorldSessionId, reconciliationWorldRevision,
                visualBarrierWorldRevision);
    }

    public static long requireSurfaceBarrierWorldRevision(
            KnownTraversabilitySnapshot map,
            long surfaceBarrierWorldRevision) {
        return AgentSurfaceEvidence.requireSurfaceBarrierWorldRevision(
                map, surfaceBarrierWorldRevision);
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

        Pose at(NavCell target, double tolerance) {
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

        Pose aimed(Aim aim, AimError aimError) {
            return new Pose(
                    cell, x, y, z, eyeHeight, aim.yaw(), aim.pitch(),
                    horizontalPositionError, yErrorBelow, yErrorAbove,
                    Math.min(360.0D,
                            aimError.totalDegrees() + FACE_COMPLETION_ERROR_DEGREES));
        }

        Pose withAdditionalYErrorBelow(double additional) {
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

    /** Resolves only a delivered front-face witness; no guessed position or container label is used. */
    public static FrameItemAim requireFrameItemAim(
            KnownTraversabilitySnapshot map, Pose pose, Optional<ObservationFrame> latestFrame,
            ActionDsl.Node node, long visualBarrierWorldRevision) {
        return AgentSurfaceEvidence.requireFrameItemAim(
                map, pose, latestFrame, node, visualBarrierWorldRevision);
    }

    public record FrameItemAim(String entityRef, String entityType, Optional<String> expectedItem,
            Optional<String> insertedItem, int rotation, Vec3 aimPoint, long observedTick, long worldRevision) {
        public FrameItemAim {
            Objects.requireNonNull(entityRef);
            Objects.requireNonNull(entityType);
            Objects.requireNonNull(expectedItem);
            Objects.requireNonNull(insertedItem);
            Objects.requireNonNull(aimPoint);
            if (expectedItem.isPresent() == insertedItem.isPresent()
                    || rotation < 0 || rotation > 7 || observedTick < 0 || worldRevision < 0
                    || !Double.isFinite(aimPoint.x) || !Double.isFinite(aimPoint.y) || !Double.isFinite(aimPoint.z)) {
                throw new IllegalArgumentException("invalid frame item aim");
            }
        }
    }

    public record Analysis(
            Map<String, ActionDslCompiler.Cost> primitiveCosts,
            Map<TraversabilityEdge.Key, TraversabilityEdge> routeDependencies,
            Set<ActionDsl.Position> knownTargets,
            Set<KnownSurface> knownFacingSurfaces,
            Set<KnownSurface> knownSurfaces,
            Map<String, MutationAim> mutationAims,
            Map<String, MutationBatchPlan> mutationBatchPlans) {
        public Analysis {
            primitiveCosts = Map.copyOf(Objects.requireNonNull(primitiveCosts, "primitiveCosts"));
            routeDependencies = Map.copyOf(
                    Objects.requireNonNull(routeDependencies, "routeDependencies"));
            knownTargets = Set.copyOf(Objects.requireNonNull(knownTargets, "knownTargets"));
            knownFacingSurfaces = Set.copyOf(
                    Objects.requireNonNull(knownFacingSurfaces, "knownFacingSurfaces"));
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
                required = AgentPlannerCosts.addCosts(required, steps.get(index).plannedCost());
            }
            return required;
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

    public enum Code { TARGET_UNKNOWN, NO_KNOWN_PATH, TIMEOUT, PROGRAM_BUDGET_UNPROVABLE }

    public static final class PlanningException extends IllegalArgumentException {
        private final Code code;

        PlanningException(Code code, String message) {
            super(message);
            this.code = Objects.requireNonNull(code, "code");
        }

        public Code code() {
            return code;
        }
    }
}
