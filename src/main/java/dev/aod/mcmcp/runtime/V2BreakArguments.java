package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.BlockWorkRegion;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** A coordinate or signed inclusive box; observed block details are optional. */
record V2BreakArguments(BlockWorkRegion region, Set<String> includeBlocks,
                        Set<String> excludeBlocks, int maxBlocks,
                        int maxTicks, double maxDistance, boolean advance) {
    V2BreakArguments {
        Objects.requireNonNull(region, "region");
        includeBlocks = Set.copyOf(includeBlocks);
        excludeBlocks = Set.copyOf(excludeBlocks);
        if (maxBlocks < 1 || maxBlocks > region.size()
                || maxTicks < 1 || maxTicks > 1_200
                || !Double.isFinite(maxDistance) || maxDistance < 0.0D
                || maxDistance > 256.0D) {
            throw new IllegalArgumentException("invalid break bounds");
        }
    }

    static V2BreakArguments parse(Map<String, Object> arguments, String dimension) {
        RuntimeArguments.requireAllowedKeys(arguments, "agent_break_block", Set.of(
                "x", "y", "z", "dx", "dy", "dz", "include_blocks", "exclude_blocks",
                "max_blocks", "max_ticks", "max_distance", "advance"));
        if (!arguments.keySet().containsAll(Set.of("x", "y", "z"))) {
            throw new IllegalArgumentException("agent_break_block requires x, y and z");
        }
        var region = new BlockWorkRegion(Objects.requireNonNull(dimension, "dimension"),
                RuntimeArguments.intArgument(arguments, "x"),
                RuntimeArguments.intArgument(arguments, "y"),
                RuntimeArguments.intArgument(arguments, "z"),
                optionalInt(arguments, "dx", 0), optionalInt(arguments, "dy", 0),
                optionalInt(arguments, "dz", 0));
        return new V2BreakArguments(region,
                blockIds(arguments, "include_blocks"), blockIds(arguments, "exclude_blocks"),
                optionalInt(arguments, "max_blocks", region.size()),
                optionalInt(arguments, "max_ticks", 1_200),
                arguments.containsKey("max_distance")
                        ? RuntimeArguments.doubleArgument(arguments, "max_distance") : 64.0D,
                arguments.containsKey("advance")
                        && RuntimeArguments.booleanArgument(arguments, "advance"));
    }

    boolean accepts(String blockId) {
        return (includeBlocks.isEmpty() || includeBlocks.contains(blockId))
                && !excludeBlocks.contains(blockId);
    }

    private static int optionalInt(Map<String, Object> arguments, String key, int fallback) {
        return arguments.containsKey(key) ? RuntimeArguments.intArgument(arguments, key) : fallback;
    }

    private static Set<String> blockIds(Map<String, Object> arguments, String key) {
        if (!arguments.containsKey(key)) return Set.of();
        Object raw = arguments.get(key);
        if (!(raw instanceof List<?> list) || list.isEmpty() || list.size() > 128) {
            throw new IllegalArgumentException(key + " must contain 1..128 block IDs");
        }
        var ids = new LinkedHashSet<String>();
        for (Object value : list) {
            if (!(value instanceof String id)
                    || !id.matches("minecraft:[a-z0-9_./-]{1,112}")
                    || !ids.add(id)) {
                throw new IllegalArgumentException(key + " contains an invalid or duplicate block ID");
            }
        }
        return Set.copyOf(ids);
    }
}
