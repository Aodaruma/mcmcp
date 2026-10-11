package dev.aod.mcmcp.runtime;

import java.util.Map;
import java.util.Set;

/** A player-inventory readback without opening a menu. */
record V2InventoryInspectArguments(String itemFilter) implements V2InventoryRequest {
    static V2InventoryInspectArguments parse(Map<String, Object> arguments) {
        RuntimeArguments.requireAllowedKeys(arguments, "agent_inventory",
                Set.of("operation", "item"));
        if (!"inspect".equals(RuntimeArguments.stringArgument(arguments, "operation"))) {
            throw new IllegalArgumentException("inventory operation is not supported yet");
        }
        String item = arguments.containsKey("item")
                ? RuntimeArguments.stringArgument(arguments, "item") : null;
        if (item != null) V2InventoryRequest.requireItemId(item);
        return new V2InventoryInspectArguments(item);
    }

    @Override
    public int maxTicks() { return 1; }
}
