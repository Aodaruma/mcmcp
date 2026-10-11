package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentJobLimits;
import java.util.Map;
import java.util.Set;

/** Native warp-stone destination UI; never an arbitrary packet or destination UUID. */
record V2WaystoneArguments(boolean inspect, String destinationRef, int maxLevels, int maxPoints, int maxTicks) {
    static final String ITEM = "waystones:warp_stone";

    static boolean matches(Map<String, Object> args) {
        return "item".equals(args.get("target"))
                && Set.of("inspect_destinations", "select_destination").contains(args.getOrDefault("operation", ""));
    }

    static V2WaystoneArguments parse(Map<String, Object> args) {
        if (!matches(args) || !ITEM.equals(args.get("item")))
            throw new IllegalArgumentException("a supported warp stone is required");
        boolean inspect = "inspect_destinations".equals(args.get("operation"));
        RuntimeArguments.requireAllowedKeys(args, "waystone interaction", inspect
                ? Set.of("target", "operation", "item", "max_ticks")
                : Set.of("target", "operation", "item", "destination_ref", "max_xp_levels", "max_xp_points", "max_ticks"));
        String ref = inspect ? null : RuntimeArguments.stringArgument(args, "destination_ref");
        int levels = !inspect && args.containsKey("max_xp_levels") ? RuntimeArguments.intArgument(args, "max_xp_levels") : 0;
        int points = !inspect && args.containsKey("max_xp_points") ? RuntimeArguments.intArgument(args, "max_xp_points") : 0;
        int ticks = args.containsKey("max_ticks") ? RuntimeArguments.intArgument(args, "max_ticks") : 600;
        if (!inspect && !ref.matches("[A-Za-z0-9_-]{24}") || levels < 0 || levels > 1_000_000
                || points < 0 || points > 1_000_000 || ticks < 1 || ticks > AgentJobLimits.MAX_TICKS)
            throw new IllegalArgumentException("invalid destination reference or budget");
        return new V2WaystoneArguments(inspect, ref, levels, points, ticks);
    }
}
