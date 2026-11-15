package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentJobLimits;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit sortable slots; the default leaves the hotbar and equipped items alone. */
record V2InventorySortArguments(List<Integer> slots, boolean consolidateOnly, int maxTicks)
        implements V2InventoryRequest {
    V2InventorySortArguments {
        slots = List.copyOf(slots);
        if (slots.isEmpty() || slots.size() > 36 || Set.copyOf(slots).size() != slots.size()
                || slots.stream().anyMatch(slot -> slot < 0 || slot > 35)
                || maxTicks < 1 || maxTicks > AgentJobLimits.MAX_TICKS)
            throw new IllegalArgumentException("invalid inventory sort bounds");
    }

    static V2InventorySortArguments parse(Map<String, Object> args) {
        RuntimeArguments.requireAllowedKeys(args, "agent_inventory", Set.of("operation", "slots", "max_ticks"));
        var slots = new java.util.ArrayList<Integer>();
        if (args.containsKey("slots")) {
            if (!(args.get("slots") instanceof List<?> values)) throw new IllegalArgumentException("slots must be an array");
            for (var value : values) slots.add(RuntimeArguments.intArgument(Map.of("slot", value), "slot"));
        } else for (int slot = 9; slot < 36; slot++) slots.add(slot);
        return new V2InventorySortArguments(slots, "consolidate".equals(args.get("operation")),
                args.containsKey("max_ticks") ? RuntimeArguments.intArgument(args, "max_ticks") : 2400);
    }
}
