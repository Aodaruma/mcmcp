package dev.aod.mcmcp.runtime;

import java.util.Map;
import java.util.Set;

/** Exact, bounded player-inventory drop; a slot disambiguates equal item IDs. */
record V2InventoryDropArguments(String itemId, int count, Integer slot, int maxTicks)
        implements V2InventoryRequest {
    V2InventoryDropArguments {
        V2InventoryRequest.requireItemId(itemId);
        if (count < 1 || count > 64 || slot != null && (slot < 0 || slot >= 36)
                || maxTicks < 1 || maxTicks > 1_200) {
            throw new IllegalArgumentException("invalid drop bounds");
        }
    }

    static V2InventoryDropArguments parse(Map<String, Object> arguments) {
        RuntimeArguments.requireAllowedKeys(arguments, "agent_inventory",
                Set.of("operation", "item", "count", "slot", "max_ticks"));
        if (!"drop".equals(RuntimeArguments.stringArgument(arguments, "operation"))) {
            throw new IllegalArgumentException("inventory operation must be drop");
        }
        return new V2InventoryDropArguments(
                RuntimeArguments.stringArgument(arguments, "item"),
                RuntimeArguments.intArgument(arguments, "count"),
                arguments.containsKey("slot")
                        ? RuntimeArguments.intArgument(arguments, "slot") : null,
                arguments.containsKey("max_ticks")
                        ? RuntimeArguments.intArgument(arguments, "max_ticks") : 600);
    }
}
