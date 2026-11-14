package dev.aod.mcmcp.runtime;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Flight uses ordinary physics and ends only after a witnessed landing near the requested goal. */
final class MinecraftV2ElytraDriver implements V2OperationJobExecution.Driver {
    private final Minecraft minecraft;
    private final V2MoveArguments request;
    private final MinecraftElytraFlight flight;
    private final Vec3 goal;
    private final BooleanSupplier stop;
    private final java.util.function.Supplier<dev.aod.mcmcp.agent.navigation.KnownTraversabilitySnapshot> observations;
    private final java.util.function.DoubleSupplier remainingDistance;
    private dev.aod.mcmcp.agent.action.ExactPositionPrimitive finalApproach;
    private MinecraftV2MoveDriver groundApproach;
    private dev.aod.mcmcp.agent.navigation.CoordinateGoalPlanner groundPlanner;
    private long started, landedAt;
    private boolean landed;
    private String failure;

    MinecraftV2ElytraDriver(Minecraft minecraft, UUID session, V2MoveArguments request, BooleanSupplier stop,
            java.util.function.Supplier<dev.aod.mcmcp.agent.navigation.KnownTraversabilitySnapshot> observations,
            java.util.function.DoubleSupplier remainingDistance) {
        this.minecraft = minecraft; this.request = request; this.stop = stop;
        this.observations = observations; this.remainingDistance = remainingDistance;
        flight = new MinecraftElytraFlight(minecraft, session);
        goal = request.exactPosition() == null ? new Vec3(request.goal().x() + 0.5, request.goal().y(), request.goal().z() + 0.5)
                : request.exactPosition();
    }
    public void begin(long tick, BooleanSupplier allowed) { started = tick; }
    public V2OperationJobExecution.Step tick(long tick, BooleanSupplier allowed) {
        if (stop.getAsBoolean()) { failure = "flight_stop_condition"; return V2OperationJobExecution.Step.FAILED; }
        if (landed) return approach(tick, allowed);
        var state = flight.tick(goal, null, tick, request.maxRockets(), false,
                request.maxTicks() - (tick - started), remainingDistance.getAsDouble(), Double.POSITIVE_INFINITY, allowed);
        if (state == MinecraftElytraFlight.Result.RUNNING) return V2OperationJobExecution.Step.RUNNING;
        if (state == MinecraftElytraFlight.Result.LANDED) {
            if (dev.aod.mcmcp.agent.action.ExactPositionPrimitive.reached(minecraft.player.position(), goal,
                    request.tolerance(), request.arrivalRadius())) return V2OperationJobExecution.Step.CONFIRMED;
            if (minecraft.player.onGround()
                    && minecraft.player.position().distanceTo(goal) <= remainingDistance.getAsDouble()) {
                landed = true; landedAt = tick;
                groundApproach = new MinecraftV2MoveDriver(minecraft, request);
                return approach(tick, allowed);
            }
        }
        failure = state == MinecraftElytraFlight.Result.LANDED ? "flight_landed_short_of_goal" : flight.failure();
        return V2OperationJobExecution.Step.FAILED;
    }
    public boolean allowsFlight() { return true; }
    private V2OperationJobExecution.Step approach(long tick, BooleanSupplier allowed) {
        var map = observations.get();
        double remaining = remainingDistance.getAsDouble();
        if (finalApproach == null && !groundApproach.active()) {
            if (minecraft.player.position().distanceTo(goal) <= 2) {
                finalApproach = new dev.aod.mcmcp.agent.action.ExactPositionPrimitive(goal, request.tolerance(), false);
            } else {
                if (groundPlanner == null) groundPlanner = new dev.aod.mcmcp.agent.navigation.CoordinateGoalPlanner(
                        map.worldSessionId(), request.goal(), request.arrivalRadius());
                var plan = groundPlanner.plan(map, ActionPlanning.playerCell(minecraft.player, map.dimension()),
                        map.worldSessionId(), map.worldRevision(),
                        dev.aod.mcmcp.agent.navigation.CoordinateGoalPlanner.Budget.DEFAULT, () -> !allowed.getAsBoolean());
                if (plan.route().isEmpty()) {
                    if (tick - landedAt <= 40) return V2OperationJobExecution.Step.RUNNING;
                    failure = "flight_ground_approach_unreachable"; return V2OperationJobExecution.Step.FAILED;
                }
                groundApproach.begin(plan.route().orElseThrow(), request.tolerance());
            }
        }
        var step = finalApproach != null ? finalApproach.tick(minecraft, map, remaining, tick, allowed)
                : groundApproach.tick(map, remaining, tick, allowed);
        return switch (step.status()) {
            case SUCCEEDED -> {
                groundApproach.close();
                yield dev.aod.mcmcp.agent.action.ExactPositionPrimitive.reached(minecraft.player.position(), goal,
                        request.tolerance(), request.arrivalRadius())
                        ? V2OperationJobExecution.Step.CONFIRMED : V2OperationJobExecution.Step.RUNNING;
            }
            case RUNNING -> V2OperationJobExecution.Step.RUNNING;
            case REPLAN_REQUIRED -> {
                groundApproach.close();
                if (!request.autoReplan()) { failure = "flight_ground_route_changed"; yield V2OperationJobExecution.Step.FAILED; }
                yield V2OperationJobExecution.Step.RUNNING;
            }
            default -> { failure = "flight_final_approach_failed"; yield V2OperationJobExecution.Step.FAILED; }
        };
    }
    public Map<String, Object> result() {
        var result = new LinkedHashMap<>(flight.result());
        if (failure != null) result.put("failure", failure);
        var player = minecraft.player;
        if (player != null) result.put("position", Map.of("x", player.getX(), "y", player.getY(), "z", player.getZ()));
        result.put("requested_position", Map.of("x", goal.x, "y", goal.y, "z", goal.z));
        return Map.copyOf(result);
    }
    public void close() {
        flight.close(); if (finalApproach != null) finalApproach.close();
        if (groundApproach != null) groundApproach.close();
    }
}
