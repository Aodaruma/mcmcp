package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentJobLimits;
import java.util.Map;
import java.util.Set;

/** Exact count between two ordinary player inventory slots; component identity is preserved. */
record V2InventoryMoveArguments(int source, int destination, String item, int count, int maxTicks)
        implements V2InventoryRequest {
    V2InventoryMoveArguments {
        V2InventoryRequest.requireItemId(item);
        if (source < 0 || source > 35 || destination < 0 || destination > 35 || source == destination
                || count < 1 || count > 64 || maxTicks < 1 || maxTicks > AgentJobLimits.MAX_TICKS) {
            throw new IllegalArgumentException("invalid player inventory move bounds");
        }
    }
    static V2InventoryMoveArguments parse(Map<String, Object> args) {
        RuntimeArguments.requireAllowedKeys(args, "agent_inventory", Set.of("operation", "source_slot",
                "destination_slot", "item", "count", "max_ticks"));
        return new V2InventoryMoveArguments(RuntimeArguments.intArgument(args, "source_slot"),
                RuntimeArguments.intArgument(args, "destination_slot"), RuntimeArguments.stringArgument(args, "item"),
                RuntimeArguments.intArgument(args, "count"), args.containsKey("max_ticks")
                        ? RuntimeArguments.intArgument(args, "max_ticks") : 400);
    }
}
