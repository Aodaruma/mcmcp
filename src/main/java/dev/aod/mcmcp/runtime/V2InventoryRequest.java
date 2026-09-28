package dev.aod.mcmcp.runtime;

import java.util.Map;

/** Inventory operations sharing one public tool and job contract. */
sealed interface V2InventoryRequest
        permits V2InventoryInspectArguments, V2InventoryDropArguments,
                V2InventorySwapArguments, V2InventoryContainerArguments {
    int maxTicks();

    static V2InventoryRequest parse(Map<String, Object> arguments) {
        return switch (RuntimeArguments.stringArgument(arguments, "operation")) {
            case "inspect" -> arguments.containsKey("target")
                    ? V2InventoryContainerArguments.parse(arguments)
                    : V2InventoryInspectArguments.parse(arguments);
            case "transfer" -> V2InventoryContainerArguments.parse(arguments);
            case "drop" -> V2InventoryDropArguments.parse(arguments);
            case "swap" -> V2InventorySwapArguments.parse(arguments);
            default -> throw new IllegalArgumentException("inventory operation is not supported yet");
        };
    }

    static String requireItemId(String item) {
        if (item == null || item.length() > 128
                || !item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("item must be a resource ID");
        }
        return item;
    }
}
