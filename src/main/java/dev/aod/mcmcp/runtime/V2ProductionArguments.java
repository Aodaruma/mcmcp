package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentJobLimits;
import dev.aod.mcmcp.brewing.StandardPotionStackSpec;
import dev.aod.mcmcp.routine.BlockTarget;
import dev.aod.mcmcp.routine.KnownBrewingRequest;
import java.util.Map;
import java.util.Set;

record V2ProductionArguments(boolean brew, int x, int y, int z, String block,
        String recipeRef, String fingerprint, int count, int maxSmelts, String fuel, int maxFuel,
        StandardPotionStackSpec input, StandardPotionStackSpec output, String ingredient,
        boolean advance, double maxDistance, int maxTicks) {
    static boolean matches(Map<String, Object> args) {
        return "block".equals(args.get("target")) && Set.of("smelt", "brew").contains(args.getOrDefault("operation", ""));
    }
    static V2ProductionArguments parse(Map<String, Object> args, String dimension) {
        boolean brew = "brew".equals(args.get("operation"));
        RuntimeArguments.requireAllowedKeys(args, "agent_interact", brew
                ? Set.of("target", "operation", "x", "y", "z", "block", "input", "output", "ingredient", "count",
                        "fuel", "advance", "max_distance", "max_ticks")
                : Set.of("target", "operation", "x", "y", "z", "block", "recipe_ref", "recipe_fingerprint", "count",
                        "max_smelts", "fuel", "advance", "max_distance", "max_ticks"));
        if (!matches(args)) throw new IllegalArgumentException("invalid production operation");
        int x = RuntimeArguments.intArgument(args, "x"), y = RuntimeArguments.intArgument(args, "y"), z = RuntimeArguments.intArgument(args, "z");
        String block = RuntimeArguments.stringArgument(args, "block");
        if (brew ? !block.equals("minecraft:brewing_stand")
                : !Set.of("minecraft:furnace", "minecraft:blast_furnace", "minecraft:smoker").contains(block)) {
            throw new IllegalArgumentException("unsupported production station");
        }
        int count = RuntimeArguments.intArgument(args, "count");
        int maxSmelts = integer(args, "max_smelts", 64);
        var fuel = RuntimeArguments.objectArgument(args, "fuel");
        RuntimeArguments.requireAllowedKeys(fuel, "fuel", Set.of("item", "max_count"));
        String fuelItem = V2InventoryRequest.requireItemId(RuntimeArguments.stringArgument(fuel, "item"));
        int maxFuel = RuntimeArguments.intArgument(fuel, "max_count");
        int maxTicks = integer(args, "max_ticks", brew ? 2400 : 15000);
        double distance = args.containsKey("max_distance") ? RuntimeArguments.doubleArgument(args, "max_distance") : 64;
        if (count < 1 || count > (brew ? 3 : 4096) || maxSmelts < 1 || maxSmelts > 64
                || maxFuel < 1 || maxFuel > 64 || maxTicks < 1 || maxTicks > AgentJobLimits.MAX_TICKS
                || !Double.isFinite(distance) || distance < 0 || distance > AgentJobLimits.MAX_DISTANCE) {
            throw new IllegalArgumentException("invalid production bounds");
        }
        String ref = brew ? null : RuntimeArguments.stringArgument(args, "recipe_ref");
        String fingerprint = brew ? null : RuntimeArguments.stringArgument(args, "recipe_fingerprint");
        if (!brew && (!ref.matches("[A-Za-z0-9_-]{24}") || !fingerprint.matches("sha256:[a-f0-9]{64}"))) {
            throw new IllegalArgumentException("invalid recipe reference");
        }
        var input = brew ? potion(RuntimeArguments.objectArgument(args, "input"), count) : null;
        var output = brew ? potion(RuntimeArguments.objectArgument(args, "output"), count) : null;
        String ingredient = brew ? RuntimeArguments.stringArgument(args, "ingredient") : null;
        if (brew) new KnownBrewingRequest(new BlockTarget(dimension, x, y, z), input, ingredient, fuelItem, output);
        return new V2ProductionArguments(brew, x, y, z, block, ref, fingerprint, count, maxSmelts, fuelItem, maxFuel,
                input, output, ingredient, !args.containsKey("advance") || RuntimeArguments.booleanArgument(args, "advance"), distance, maxTicks);
    }
    private static StandardPotionStackSpec potion(Map<String, Object> args, int count) {
        RuntimeArguments.requireAllowedKeys(args, "potion", Set.of("item", "potion"));
        return new StandardPotionStackSpec(RuntimeArguments.stringArgument(args, "item"), RuntimeArguments.stringArgument(args, "potion"), count);
    }
    private static int integer(Map<String, Object> args, String key, int fallback) {
        return args.containsKey(key) ? RuntimeArguments.intArgument(args, key) : fallback;
    }
}
