package dev.aod.mcmcp.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** An atomic open, inspect/whole-stack shift-click, and close request. */
record V2MenuArguments(V2BlockInteractArguments block, String menuType, List<Click> clicks) {
    V2MenuArguments { clicks = List.copyOf(clicks); }

    static V2MenuArguments parse(Map<String, Object> arguments, String dimension) {
        RuntimeArguments.requireAllowedKeys(arguments, "agent_interact", Set.of(
                "target", "x", "y", "z", "block", "menu_type", "clicks",
                "advance", "max_distance", "max_ticks"));
        if (!"menu".equals(arguments.get("target"))) throw new IllegalArgumentException("menu target required");
        String type = RuntimeArguments.stringArgument(arguments, "menu_type");
        V2InventoryRequest.requireItemId(type);
        var blockArguments = new LinkedHashMap<>(arguments);
        blockArguments.put("target", "block");
        blockArguments.remove("menu_type");
        blockArguments.remove("clicks");
        var block = V2BlockInteractArguments.parse(blockArguments, dimension);
        if (block.blockId() == null) throw new IllegalArgumentException("menu requires a block condition");
        var clicks = new ArrayList<Click>();
        if (arguments.containsKey("clicks")) {
            if (!(arguments.get("clicks") instanceof List<?> list) || list.size() > 16) {
                throw new IllegalArgumentException("clicks must be a list of at most 16 entries");
            }
            for (Object value : list) {
                var click = RuntimeArguments.objectArgument(Map.of("click", value), "click");
                RuntimeArguments.requireAllowedKeys(click, "menu click", Set.of("slot", "item", "count", "type"));
                if (!"quick_move".equals(click.get("type"))) throw new IllegalArgumentException("only quick_move is supported");
                int slot = RuntimeArguments.intArgument(click, "slot");
                int count = RuntimeArguments.intArgument(click, "count");
                String item = RuntimeArguments.stringArgument(click, "item");
                V2InventoryRequest.requireItemId(item);
                if (slot < 0 || slot > 255 || count < 1 || count > 99) throw new IllegalArgumentException("invalid menu slot or count");
                clicks.add(new Click(slot, item, count));
            }
        }
        return new V2MenuArguments(block, type, clicks);
    }

    record Click(int slot, String item, int count) { }
}
