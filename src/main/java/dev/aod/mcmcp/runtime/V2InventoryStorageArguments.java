package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.navigation.NavCell;
import dev.aod.mcmcp.routine.BlockTarget;
import dev.aod.mcmcp.routine.PhaseFiveBounds;
import dev.aod.mcmcp.routine.PhaseFiveRequest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** A carried storage item; discovery and the opening protocol are provider-owned. */
record V2InventoryStorageArguments(boolean inspect, int storageSlot, String storageItem,
        String item, boolean store, int count, int maxTicks) implements V2InventoryRequest {
    V2InventoryStorageArguments {
        if (storageSlot < 0 || storageSlot > 40 || maxTicks < 1 || maxTicks > 1200
                || (inspect ? count != 0 || store : item == null || count < 1 || count > 896)) {
            throw new IllegalArgumentException("invalid storage operation or bounds");
        }
        if (storageItem != null) V2InventoryRequest.requireItemId(storageItem);
        if (item != null) V2InventoryRequest.requireItemId(item);
    }

    static V2InventoryStorageArguments parse(Map<String, Object> input) {
        String operation = RuntimeArguments.stringArgument(input, "operation");
        boolean inspect = "inspect".equals(operation);
        if (!inspect && !"transfer".equals(operation)) throw new IllegalArgumentException("invalid storage operation");
        RuntimeArguments.requireAllowedKeys(input, "agent_inventory", inspect
                ? Set.of("operation", "target", "storage_slot", "storage_item", "item", "max_ticks")
                : Set.of("operation", "target", "storage_slot", "storage_item", "item", "direction", "count", "max_ticks"));
        if (!"storage".equals(RuntimeArguments.stringArgument(input, "target"))) {
            throw new IllegalArgumentException("storage target required");
        }
        String direction = inspect ? "take" : RuntimeArguments.stringArgument(input, "direction");
        if (!Set.of("take", "store").contains(direction)) throw new IllegalArgumentException("invalid transfer direction");
        return new V2InventoryStorageArguments(inspect, RuntimeArguments.intArgument(input, "storage_slot"),
                input.containsKey("storage_item") ? RuntimeArguments.stringArgument(input, "storage_item") : null,
                input.containsKey("item") ? RuntimeArguments.stringArgument(input, "item") : null,
                "store".equals(direction), inspect ? 0 : RuntimeArguments.intArgument(input, "count"),
                input.containsKey("max_ticks") ? RuntimeArguments.intArgument(input, "max_ticks") : 1200);
    }

    PhaseFiveRequest operation(String reference, NavCell playerCell) {
        if (reference == null || !reference.matches("[A-Za-z0-9_-]{24}")) {
            throw new IllegalArgumentException("invalid internal storage reference");
        }
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("operation_ref", reference);
        parameters.put("operation", inspect ? "inspect" : store ? "store" : "take");
        if (!inspect) {
            parameters.put("item", item);
            parameters.put("transfer_count", count);
        }
        var target = new BlockTarget(playerCell.dimension(), playerCell.x(), playerCell.y(), playerCell.z());
        return new PhaseFiveRequest("operate_known_menu", parameters,
                new PhaseFiveBounds(playerCell.dimension(), target, target, 0,
                        Math.max(1, Math.ceilDiv(maxTicks, 20)), false), inspect ? 0 : count, "items");
    }
}
