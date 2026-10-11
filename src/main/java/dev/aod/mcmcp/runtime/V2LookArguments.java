package dev.aod.mcmcp.runtime;

import java.util.Map;
import java.util.Set;

/** Exact world point for camera-only rotation; does not assert that the point is visible. */
record V2LookArguments(double x, double y, double z, int maxTicks) {
    V2LookArguments {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000
                || y < -20_000_000 || y > 20_000_000 || maxTicks < 1 || maxTicks > 600) {
            throw new IllegalArgumentException("invalid look point or tick limit");
        }
    }

    static V2LookArguments parse(Map<String, Object> arguments) {
        RuntimeArguments.requireAllowedKeys(arguments, "agent_look", Set.of("x", "y", "z", "max_ticks"));
        return new V2LookArguments(RuntimeArguments.doubleArgument(arguments, "x"),
                RuntimeArguments.doubleArgument(arguments, "y"),
                RuntimeArguments.doubleArgument(arguments, "z"),
                arguments.containsKey("max_ticks") ? RuntimeArguments.intArgument(arguments, "max_ticks") : 100);
    }
}
