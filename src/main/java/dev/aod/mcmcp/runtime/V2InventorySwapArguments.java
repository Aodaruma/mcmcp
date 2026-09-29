package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentJobLimits;

import java.util.Map;
import java.util.Set;

/** Explicit main-inventory/hotbar exchange, including an occupied destination. */
record V2InventorySwapArguments(int sourceSlot, int hotbarSlot,
                                String itemId, int maxTicks) implements V2InventoryRequest {
    V2InventorySwapArguments {
        V2InventoryRequest.requireItemId(itemId);
        if (sourceSlot < 9 || sourceSlot >= 36 || hotbarSlot < 0 || hotbarSlot >= 9
                || maxTicks < 1 || maxTicks > AgentJobLimits.MAX_TICKS) {
            throw new IllegalArgumentException("invalid inventory swap bounds");
        }
    }

    static V2InventorySwapArguments parse(Map<String, Object> arguments) {
        RuntimeArguments.requireAllowedKeys(arguments, "agent_inventory",
                Set.of("operation", "source_slot", "hotbar_slot", "item", "max_ticks"));
        if (!"swap".equals(RuntimeArguments.stringArgument(arguments, "operation"))) {
            throw new IllegalArgumentException("inventory operation must be swap");
        }
        return new V2InventorySwapArguments(
                RuntimeArguments.intArgument(arguments, "source_slot"),
                RuntimeArguments.intArgument(arguments, "hotbar_slot"),
                RuntimeArguments.stringArgument(arguments, "item"),
                arguments.containsKey("max_ticks")
                        ? RuntimeArguments.intArgument(arguments, "max_ticks") : 100);
    }
}
