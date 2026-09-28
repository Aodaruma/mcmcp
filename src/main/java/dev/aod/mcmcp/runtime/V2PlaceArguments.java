package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.BlockWorkRegion;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** A bounded placement request. The desired block properties and target condition are optional. */
record V2PlaceArguments(BlockWorkRegion region, String blockId, String itemId,
                        Map<String, String> properties, Set<String> replaceBlocks,
                        int maxBlocks, int maxTicks, double maxDistance, boolean advance)
        implements V2BlockWorkRequest {
    V2PlaceArguments {
        Objects.requireNonNull(region, "region");
        requireMinecraftId(blockId, "block");
        requireMinecraftId(itemId, "item");
        properties = Map.copyOf(properties);
        replaceBlocks = Set.copyOf(replaceBlocks);
        if (maxBlocks < 1 || maxBlocks > region.size()
                || maxTicks < 1 || maxTicks > 1_200
                || !Double.isFinite(maxDistance) || maxDistance < 0.0D
                || maxDistance > 256.0D) {
            throw new IllegalArgumentException("invalid place bounds");
        }
    }

    static V2PlaceArguments parse(Map<String, Object> arguments, String dimension) {
        RuntimeArguments.requireAllowedKeys(arguments, "agent_place_block", Set.of(
                "x", "y", "z", "dx", "dy", "dz", "block", "item", "properties",
                "replace_blocks", "max_blocks", "max_ticks", "max_distance", "advance"));
        if (!arguments.keySet().containsAll(Set.of("x", "y", "z", "block"))) {
            throw new IllegalArgumentException("agent_place_block requires x, y, z and block");
        }
        var region = new BlockWorkRegion(Objects.requireNonNull(dimension, "dimension"),
                RuntimeArguments.intArgument(arguments, "x"),
                RuntimeArguments.intArgument(arguments, "y"),
                RuntimeArguments.intArgument(arguments, "z"),
                optionalInt(arguments, "dx", 0), optionalInt(arguments, "dy", 0),
                optionalInt(arguments, "dz", 0));
        String blockId = RuntimeArguments.stringArgument(arguments, "block");
        String itemId = arguments.containsKey("item")
                ? RuntimeArguments.stringArgument(arguments, "item") : blockId;
        return new V2PlaceArguments(region, blockId, itemId,
                properties(arguments), replaceBlocks(arguments),
                optionalInt(arguments, "max_blocks", region.size()),
                optionalInt(arguments, "max_ticks", 1_200),
                arguments.containsKey("max_distance")
                        ? RuntimeArguments.doubleArgument(arguments, "max_distance") : 64.0D,
                arguments.containsKey("advance")
                        && RuntimeArguments.booleanArgument(arguments, "advance"));
    }

    boolean acceptsTarget(String blockId) {
        return replaceBlocks.isEmpty()
                ? "minecraft:air".equals(blockId)
                        || "minecraft:cave_air".equals(blockId)
                        || "minecraft:void_air".equals(blockId)
                : replaceBlocks.contains(blockId);
    }

    private static int optionalInt(Map<String, Object> arguments, String key, int fallback) {
        return arguments.containsKey(key) ? RuntimeArguments.intArgument(arguments, key) : fallback;
    }

    private static Map<String, String> properties(Map<String, Object> arguments) {
        if (!arguments.containsKey("properties")) return Map.of();
        var raw = RuntimeArguments.objectArgument(arguments, "properties");
        if (raw.size() > 32) {
            throw new IllegalArgumentException("properties exceeds 32 entries");
        }
        var properties = new LinkedHashMap<String, String>();
        for (var entry : raw.entrySet()) {
            if (entry.getKey() == null || !entry.getKey().matches("[a-z0-9_]{1,64}")
                    || !(entry.getValue() instanceof String value)
                    || value.isEmpty() || value.length() > 64
                    || !value.matches("[a-z0-9_./-]+")) {
                throw new IllegalArgumentException("properties contains an invalid entry");
            }
            properties.put(entry.getKey(), value);
        }
        return Map.copyOf(properties);
    }

    private static Set<String> replaceBlocks(Map<String, Object> arguments) {
        if (!arguments.containsKey("replace_blocks")) return Set.of();
        Object raw = arguments.get("replace_blocks");
        if (!(raw instanceof List<?> list) || list.isEmpty() || list.size() > 128) {
            throw new IllegalArgumentException("replace_blocks must contain 1..128 block IDs");
        }
        var ids = new LinkedHashSet<String>();
        for (Object value : list) {
            if (!(value instanceof String id) || !ids.add(id)) {
                throw new IllegalArgumentException("replace_blocks contains an invalid block ID");
            }
            requireMinecraftId(id, "replace_blocks");
        }
        return Set.copyOf(ids);
    }

    private static void requireMinecraftId(String id, String key) {
        if (id == null || !id.matches("minecraft:[a-z0-9_./-]{1,112}")) {
            throw new IllegalArgumentException(key + " must be a Vanilla resource ID");
        }
    }
}
