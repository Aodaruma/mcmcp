package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentJobLimits;

import dev.aod.mcmcp.agent.navigation.NavCell;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Coordinate goal accepted without requiring prior observation of the destination. */
record V2MoveArguments(NavCell goal, double arrivalRadius,
        double tolerance, int maxTicks, double maxDistance, V2StopCondition stopWhen,
        boolean clearPath, String bridgeBlock, boolean autoReplan, boolean sneak) {
    V2MoveArguments {
        Objects.requireNonNull(goal, "goal");
        if (bridgeBlock != null && (!clearPath || !bridgeBlock.matches("minecraft:[a-z0-9_./-]{1,112}"))) {
            throw new IllegalArgumentException("bridge_block requires clear_path and a Vanilla block ID");
        }
        if (!Double.isFinite(arrivalRadius) || arrivalRadius < 0.0D
                || arrivalRadius > 16.0D
                || !Double.isFinite(tolerance) || tolerance < 0.1D || tolerance > 0.49D
                || maxTicks < 1 || maxTicks > AgentJobLimits.MAX_TICKS
                || !Double.isFinite(maxDistance) || maxDistance < 1.0D
                || maxDistance > AgentJobLimits.MAX_DISTANCE) {
            throw new IllegalArgumentException("invalid move limits");
        }
    }

    static V2MoveArguments parse(Map<String, Object> arguments, NavCell origin) {
        Objects.requireNonNull(origin, "origin");
        RuntimeArguments.requireAllowedKeys(arguments, "agent_move",
                Set.of("x", "y", "z", "direction", "distance",
                        "arrival_radius", "tolerance", "max_ticks", "max_distance",
                        "stop_when", "clear_path", "bridge_block", "auto_replan", "sneak"));
        boolean coordinates = arguments.keySet().containsAll(Set.of("x", "y", "z"));
        boolean relative = arguments.keySet().containsAll(Set.of("direction", "distance"));
        if (coordinates == relative || arguments.containsKey("direction") != arguments.containsKey("distance")
                || coordinates && (arguments.containsKey("direction") || arguments.containsKey("distance"))
                || relative && (arguments.containsKey("x") || arguments.containsKey("y")
                        || arguments.containsKey("z"))) {
            throw new IllegalArgumentException(
                    "agent_move requires either x/y/z or direction/distance");
        }
        NavCell goal = coordinates
                ? new NavCell(origin.dimension(),
                        RuntimeArguments.intArgument(arguments, "x"),
                        RuntimeArguments.intArgument(arguments, "y"),
                        RuntimeArguments.intArgument(arguments, "z"))
                : relativeGoal(origin, arguments);
        double tolerance = arguments.containsKey("tolerance")
                ? RuntimeArguments.doubleArgument(arguments, "tolerance") : 0.25D;
        double arrivalRadius = arguments.containsKey("arrival_radius")
                ? RuntimeArguments.doubleArgument(arguments, "arrival_radius") : 0.0D;
        int maxTicks = arguments.containsKey("max_ticks")
                ? RuntimeArguments.intArgument(arguments, "max_ticks") : 1_200;
        double maxDistance = arguments.containsKey("max_distance")
                ? RuntimeArguments.doubleArgument(arguments, "max_distance") : 256.0D;
        return new V2MoveArguments(goal, arrivalRadius, tolerance, maxTicks, maxDistance,
                arguments.containsKey("stop_when") ? V2StopCondition.parse(RuntimeArguments.objectArgument(arguments, "stop_when")) : null,
                arguments.containsKey("clear_path") && RuntimeArguments.booleanArgument(arguments, "clear_path"),
                arguments.containsKey("bridge_block") ? RuntimeArguments.stringArgument(arguments, "bridge_block") : null,
                !arguments.containsKey("auto_replan") || RuntimeArguments.booleanArgument(arguments, "auto_replan"),
                arguments.containsKey("sneak") && RuntimeArguments.booleanArgument(arguments, "sneak"));
    }

    private static NavCell relativeGoal(NavCell origin, Map<String, Object> arguments) {
        String direction = RuntimeArguments.stringArgument(arguments, "direction");
        int distance = RuntimeArguments.intArgument(arguments, "distance");
        if (distance < 1 || distance > AgentJobLimits.MAX_DISTANCE) {
            throw new IllegalArgumentException("distance must be in 1..4096 blocks");
        }
        int dx;
        int dy = 0;
        int dz;
        switch (direction) {
            case "north" -> { dx = 0; dz = -1; }
            case "south" -> { dx = 0; dz = 1; }
            case "east" -> { dx = 1; dz = 0; }
            case "west" -> { dx = -1; dz = 0; }
            case "northeast" -> { dx = 1; dz = -1; }
            case "northwest" -> { dx = -1; dz = -1; }
            case "southeast" -> { dx = 1; dz = 1; }
            case "southwest" -> { dx = -1; dz = 1; }
            case "up" -> { dx = 0; dy = 1; dz = 0; }
            case "down" -> { dx = 0; dy = -1; dz = 0; }
            default -> throw new IllegalArgumentException("unknown move direction");
        }
        try {
            return new NavCell(origin.dimension(),
                    Math.addExact(origin.x(), dx * distance),
                    Math.addExact(origin.y(), dy * distance),
                    Math.addExact(origin.z(), dz * distance));
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("relative move coordinate overflows", overflow);
        }
    }
}
