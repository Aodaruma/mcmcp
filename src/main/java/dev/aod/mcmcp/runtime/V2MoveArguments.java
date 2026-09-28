package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.navigation.NavCell;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Coordinate goal accepted without requiring prior observation of the destination. */
record V2MoveArguments(NavCell goal, double tolerance, int maxTicks, double maxDistance) {
    V2MoveArguments {
        Objects.requireNonNull(goal, "goal");
        if (!Double.isFinite(tolerance) || tolerance < 0.1D || tolerance > 0.49D
                || maxTicks < 1 || maxTicks > 1_200
                || !Double.isFinite(maxDistance) || maxDistance < 1.0D
                || maxDistance > 256.0D) {
            throw new IllegalArgumentException("invalid move limits");
        }
    }

    static V2MoveArguments parse(Map<String, Object> arguments, String dimension) {
        RuntimeArguments.requireAllowedKeys(arguments, "agent_move",
                Set.of("x", "y", "z", "tolerance", "max_ticks", "max_distance"));
        if (!arguments.keySet().containsAll(Set.of("x", "y", "z"))) {
            throw new IllegalArgumentException("agent_move requires x, y and z");
        }
        var goal = new NavCell(Objects.requireNonNull(dimension, "dimension"),
                RuntimeArguments.intArgument(arguments, "x"),
                RuntimeArguments.intArgument(arguments, "y"),
                RuntimeArguments.intArgument(arguments, "z"));
        double tolerance = arguments.containsKey("tolerance")
                ? RuntimeArguments.doubleArgument(arguments, "tolerance") : 0.25D;
        int maxTicks = arguments.containsKey("max_ticks")
                ? RuntimeArguments.intArgument(arguments, "max_ticks") : 1_200;
        double maxDistance = arguments.containsKey("max_distance")
                ? RuntimeArguments.doubleArgument(arguments, "max_distance") : 256.0D;
        return new V2MoveArguments(goal, tolerance, maxTicks, maxDistance);
    }
}
