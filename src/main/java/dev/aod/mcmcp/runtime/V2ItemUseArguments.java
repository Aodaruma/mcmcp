package dev.aod.mcmcp.runtime;

import java.util.Map;
import java.util.Set;

/** Use the selected item, or select an explicitly requested inventory item, once. */
record V2ItemUseArguments(String item, String resultItem, int holdTicks, int maxTicks) {
    static V2ItemUseArguments parse(Map<String, Object> args) {
        RuntimeArguments.requireAllowedKeys(args, "agent_interact", Set.of(
                "target", "item", "result_item", "hold_ticks", "max_ticks"));
        if (!"item".equals(RuntimeArguments.stringArgument(args, "target"))) {
            throw new IllegalArgumentException("item use requires target=item");
        }
        int hold = args.containsKey("hold_ticks") ? RuntimeArguments.intArgument(args, "hold_ticks") : 40;
        return new V2ItemUseArguments(optionalId(args, "item"), optionalId(args, "result_item"),
                hold,
                args.containsKey("max_ticks") ? RuntimeArguments.intArgument(args, "max_ticks") : Math.max(200, hold + 80));
    }

    V2ItemUseArguments {
        if (item != null) requireId(item);
        if (resultItem != null) requireId(resultItem);
        if (holdTicks < 1 || holdTicks > 1_000 || maxTicks < holdTicks + 1 || maxTicks > 1_200) {
            throw new IllegalArgumentException("item use requires 1..1000 hold ticks and a longer bounded deadline");
        }
    }

    private static String optionalId(Map<String, Object> args, String key) {
        return args.containsKey(key) ? RuntimeArguments.stringArgument(args, key) : null;
    }

    private static void requireId(String id) {
        if (id.length() > 128 || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("invalid item ID");
        }
    }
}
