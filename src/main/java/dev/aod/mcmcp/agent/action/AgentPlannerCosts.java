package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.agent.action.AgentPlannerGeometry.Aim;
import dev.aod.mcmcp.agent.action.AgentPlannerGeometry.AimError;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.Pose;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.dsl.ActionDslCompiler;
import dev.aod.mcmcp.agent.navigation.NavigationDistanceBudget;
import dev.aod.mcmcp.agent.navigation.RoutePlan;
import dev.aod.mcmcp.routine.NavigationViewLease;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.Objects;

import static dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.BLOCK_MUTATION_TICK_UPPER_BOUND;
import static dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.BREAK_REOBSERVATION_TICKS;
import static dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.BREAK_TICK_UPPER_BOUND;
import static dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.NAVIGATION_REPLAN_RESERVE_TICKS;
import static dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.PICKUP_CONFIRM_TICKS;

/** primitiveの時刻・距離・カメラ予算と再計画予約を計算し、費用を集約する。 */
final class AgentPlannerCosts {
    private AgentPlannerCosts() {
    }

    static final long TICK_MILLIS = 50L;

    static ActionDslCompiler.Cost addCosts(
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

    static ActionDslCompiler.Cost faceCost(
            Pose pose, ActionDsl.Position target, float maxCameraDegreesPerTick) {
        Objects.requireNonNull(pose, "pose");
        if (!Float.isFinite(maxCameraDegreesPerTick) || maxCameraDegreesPerTick <= 0.0F) {
            throw new IllegalArgumentException("camera limit must be positive");
        }
        Aim aim = AgentPlannerGeometry.aim(pose, Objects.requireNonNull(target, "target"));
        AimError aimError = AgentPlannerGeometry.aimError(pose, target, aim);
        return faceCost(pose, aim, aimError, maxCameraDegreesPerTick);
    }

    static ActionDslCompiler.Cost faceCost(
            Pose pose, ActionDsl.FaceKnownBlockFace target, float maxCameraDegreesPerTick) {
        Objects.requireNonNull(target, "target");
        return faceCost(
                pose,
                MinecraftActionPrimitiveExecutor.blockFaceAimPoint(
                        target.target(), target.face()),
                maxCameraDegreesPerTick);
    }

    static ActionDslCompiler.Cost faceCost(
            Pose pose, Vec3 target, float maxCameraDegreesPerTick) {
        Objects.requireNonNull(pose, "pose");
        if (!Float.isFinite(maxCameraDegreesPerTick) || maxCameraDegreesPerTick <= 0.0F) {
            throw new IllegalArgumentException("camera limit must be positive");
        }
        Aim aim = AgentPlannerGeometry.aim(pose, Objects.requireNonNull(target, "target"));
        AimError aimError = AgentPlannerGeometry.aimError(pose, target, aim);
        return faceCost(pose, aim, aimError, maxCameraDegreesPerTick);
    }

    private static ActionDslCompiler.Cost faceCost(
            Pose pose,
            Aim aim,
            AimError aimError,
            float maxCameraDegreesPerTick) {
        double camera = AgentPlannerGeometry.withCameraQuantizationReserve(
                AgentPlannerGeometry.angularError(pose.yaw(), pose.pitch(), aim.yaw(), aim.pitch())
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

    static ActionDslCompiler.Cost breakCost(
            Pose pose, ActionDsl.BreakKnownFace target, float maxCameraDegreesPerTick) {
        Objects.requireNonNull(target, "target");
        Vec3 point = MinecraftActionPrimitiveExecutor.blockFaceAimPoint(
                target.target(), target.face());
        return breakCost(pose, target, point, maxCameraDegreesPerTick);
    }

    static ActionDslCompiler.Cost breakCost(
            Pose pose,
            ActionDsl.BreakKnownFace target,
            Vec3 point,
            float maxCameraDegreesPerTick) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(point, "point");
        var aim = AgentPlannerGeometry.aim(pose, point);
        var error = AgentPlannerGeometry.aimError(pose, point, aim);
        double camera = AgentPlannerGeometry.withCameraQuantizationReserve(
                AgentPlannerGeometry.angularError(pose.yaw(), pose.pitch(), aim.yaw(), aim.pitch())
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

    static ActionDslCompiler.Cost breakCost(
            Pose pose,
            ActionDsl.BreakKnownBlock target,
            Vec3 point,
            float maxCameraDegreesPerTick) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(point, "point");
        var aim = AgentPlannerGeometry.aim(pose, point);
        var error = AgentPlannerGeometry.aimError(pose, point, aim);
        double camera = AgentPlannerGeometry.withCameraQuantizationReserve(
                AgentPlannerGeometry.angularError(pose.yaw(), pose.pitch(), aim.yaw(), aim.pitch())
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

    static ActionDslCompiler.Cost mutationCost(
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
        Aim aim = AgentPlannerGeometry.aim(pose, aimPoint);
        AimError error = AgentPlannerGeometry.aimError(pose, aimPoint, aim);
        double camera = AgentPlannerGeometry.withCameraQuantizationReserve(
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
    static ActionDslCompiler.Cost navigationCost(RoutePlan route, Pose pose) {
        return withNavigationReplanReserve(navigationExecutionCost(route, pose));
    }

    /** Raw executable cost of a freshly rebound route, including every current probe edge. */
    static ActionDslCompiler.Cost navigationReplanCost(RoutePlan route, Pose pose) {
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
    static ActionDslCompiler.Cost pickupCost(RoutePlan route, Pose pose) {
        ActionDslCompiler.Cost navigation = navigationCost(route, pose);
        return withPickupConfirmation(navigation);
    }

    /** Raw rebound navigation plus pickup confirmation; no new replan reserve is granted. */
    static ActionDslCompiler.Cost pickupReplanCost(RoutePlan route, Pose pose) {
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

    static void merge(
            Map<String, ActionDslCompiler.Cost> costs,
            String nodeId,
            ActionDslCompiler.Cost cost) {
        costs.merge(nodeId, cost, AgentPlannerCosts::maximum);
    }

    static ActionDslCompiler.Cost maximum(
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
}
