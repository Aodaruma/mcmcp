package dev.aod.mcmcp.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

record V2TradeArguments(V2EntityInteractArguments entity, String operation, String offerRef,
        int count, Map<String, Integer> maxTotalCost) {
    V2TradeArguments { maxTotalCost = Map.copyOf(maxTotalCost); }

    static boolean matches(Map<String, Object> args) {
        return "entity".equals(args.get("target"))
                && Set.of("inspect_offers", "trade").contains(args.getOrDefault("operation", ""));
    }

    static V2TradeArguments parse(Map<String, Object> args) {
        String operation = RuntimeArguments.stringArgument(args, "operation");
        boolean trade = operation.equals("trade");
        RuntimeArguments.requireAllowedKeys(args, "merchant interaction", trade ? Set.of(
                "target", "operation", "entity_ref", "entity_type", "advance", "max_distance", "max_ticks",
                "offer_ref", "count", "max_total_cost") : Set.of(
                "target", "operation", "entity_ref", "entity_type", "advance", "max_distance", "max_ticks"));
        if (!matches(args)) throw new IllegalArgumentException("merchant operation required");
        var base = new LinkedHashMap<>(args);
        for (String key : List.of("offer_ref", "count", "max_total_cost")) base.remove(key);
        base.put("operation", "use");
        // Empty hand prevents feeding, dyeing, or applying an item before opening a merchant.
        base.put("item", "minecraft:air");
        var entity = V2EntityInteractArguments.parse(base);
        String ref = trade ? RuntimeArguments.stringArgument(args, "offer_ref") : null;
        int count = trade ? RuntimeArguments.intArgument(args, "count") : 0;
        var costs = new LinkedHashMap<String, Integer>();
        if (trade) {
            if (!ref.matches("[A-Za-z0-9_-]{24}") || count < 1 || count > 4096)
                throw new IllegalArgumentException("invalid offer reference or trade count");
            if (!(args.get("max_total_cost") instanceof List<?> list) || list.isEmpty() || list.size() > 2)
                throw new IllegalArgumentException("max_total_cost requires one or two payment items");
            for (var raw : list) {
                var cost = RuntimeArguments.objectArgument(Map.of("cost", raw), "cost");
                RuntimeArguments.requireAllowedKeys(cost, "trade cost", Set.of("item", "count"));
                String item = RuntimeArguments.stringArgument(cost, "item");
                V2InventoryRequest.requireItemId(item);
                int limit = RuntimeArguments.intArgument(cost, "count");
                if (limit < 1 || limit > 1_048_576 || costs.put(item, limit) != null)
                    throw new IllegalArgumentException("invalid or duplicate trade cost");
            }
        }
        return new V2TradeArguments(entity, operation, ref, count, costs);
    }

    boolean permits(Map<String, Integer> oneTrade) {
        return oneTrade.entrySet().stream().allMatch(entry ->
                (long) entry.getValue() * count <= maxTotalCost.getOrDefault(entry.getKey(), 0));
    }
}
