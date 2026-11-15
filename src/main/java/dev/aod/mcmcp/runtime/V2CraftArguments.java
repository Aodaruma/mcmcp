package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentJobLimits;
import java.util.Map;
import java.util.Set;

/** A bounded number of newly produced items, independent of existing inventory. */
record V2CraftArguments(boolean plan, boolean inventory, int x, int y, int z,
        String recipeRef, String fingerprint, int count, int maxCrafts,
        boolean allowSurplus, int maxTicks, boolean advance, double maxDistance)
        implements V2InventoryRequest {
    static final int MAX_CRAFTS = 4096;

    static V2CraftArguments parse(Map<String, Object> args) {
        RuntimeArguments.requireAllowedKeys(args, "agent_craft", Set.of("operation", "station",
                "recipe_ref", "recipe_fingerprint", "count", "max_crafts", "allow_surplus",
                "max_ticks", "advance", "max_distance"));
        String operation = RuntimeArguments.stringArgument(args, "operation");
        if (!Set.of("plan", "execute").contains(operation)) throw new IllegalArgumentException("invalid craft operation");
        var station = RuntimeArguments.objectArgument(args, "station");
        String kind = RuntimeArguments.stringArgument(station, "kind");
        boolean inventory = "inventory_2x2".equals(kind);
        if (!inventory && !"crafting_table".equals(kind)) throw new IllegalArgumentException("unsupported crafting station");
        RuntimeArguments.requireAllowedKeys(station, "station", inventory ? Set.of("kind") : Set.of("kind", "x", "y", "z"));
        return new V2CraftArguments("plan".equals(operation), inventory,
                inventory ? 0 : RuntimeArguments.intArgument(station, "x"),
                inventory ? 0 : RuntimeArguments.intArgument(station, "y"),
                inventory ? 0 : RuntimeArguments.intArgument(station, "z"),
                RuntimeArguments.stringArgument(args, "recipe_ref"),
                RuntimeArguments.stringArgument(args, "recipe_fingerprint"),
                RuntimeArguments.intArgument(args, "count"),
                integer(args, "max_crafts", 64),
                args.containsKey("allow_surplus") && RuntimeArguments.booleanArgument(args, "allow_surplus"),
                integer(args, "max_ticks", 1200),
                !args.containsKey("advance") || RuntimeArguments.booleanArgument(args, "advance"),
                args.containsKey("max_distance") ? RuntimeArguments.doubleArgument(args, "max_distance") : 64);
    }

    V2CraftArguments {
        if (recipeRef == null || !recipeRef.matches("[A-Za-z0-9_-]{24}")
                || fingerprint == null || !fingerprint.matches("sha256:[a-f0-9]{64}")
                || count < 1 || count > 262_144 || maxCrafts < 1 || maxCrafts > MAX_CRAFTS
                || maxTicks < 1 || maxTicks > AgentJobLimits.MAX_TICKS
                || !Double.isFinite(maxDistance) || maxDistance < 0 || maxDistance > AgentJobLimits.MAX_DISTANCE) {
            throw new IllegalArgumentException("invalid craft bounds or recipe reference");
        }
    }

    private static int integer(Map<String, Object> args, String key, int fallback) {
        return args.containsKey(key) ? RuntimeArguments.intArgument(args, key) : fallback;
    }
}
