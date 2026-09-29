package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentJobLimits;

import java.util.Map;
import java.util.Set;

/** One ordinary main-hand interaction with an observed entity. */
record V2EntityInteractArguments(String ref, String type, String item, String resultItem,
        int maxTicks, boolean advance, double maxDistance) {
    static V2EntityInteractArguments parse(Map<String, Object> args) {
        RuntimeArguments.requireAllowedKeys(args, "agent_interact", Set.of(
                "target", "entity_ref", "entity_type", "item", "result_item", "max_ticks",
                "advance", "max_distance"));
        if (!"entity".equals(RuntimeArguments.stringArgument(args, "target"))) {
            throw new IllegalArgumentException("entity interaction requires target=entity");
        }
        return new V2EntityInteractArguments(RuntimeArguments.stringArgument(args, "entity_ref"),
                optionalId(args, "entity_type"), optionalId(args, "item"), optionalId(args, "result_item"),
                args.containsKey("max_ticks") ? RuntimeArguments.intArgument(args, "max_ticks") : 1200,
                !args.containsKey("advance") || RuntimeArguments.booleanArgument(args, "advance"),
                args.containsKey("max_distance") ? RuntimeArguments.doubleArgument(args, "max_distance") : 64.0D);
    }

    V2EntityInteractArguments {
        if (ref == null || !ref.matches("[A-Za-z0-9_-]{24}") || maxTicks < 1 || maxTicks > AgentJobLimits.MAX_TICKS
                || !Double.isFinite(maxDistance) || maxDistance < 0 || maxDistance > AgentJobLimits.MAX_DISTANCE) {
            throw new IllegalArgumentException("entity interaction requires a valid reference and bounded deadline");
        }
        for (String id : new String[] {type, item, resultItem}) {
            if (id != null && (id.length() > 128 || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))) {
                throw new IllegalArgumentException("invalid entity or item ID");
            }
        }
    }

    private static String optionalId(Map<String, Object> args, String key) {
        return args.containsKey(key) ? RuntimeArguments.stringArgument(args, key) : null;
    }
}
