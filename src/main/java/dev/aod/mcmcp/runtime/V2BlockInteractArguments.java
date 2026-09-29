package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentJobLimits;

import dev.aod.mcmcp.agent.action.BlockWorkRegion;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Exact block targets with optional before/after conditions; no hidden-world read at admission. */
record V2BlockInteractArguments(BlockWorkRegion region, String blockId, String itemId,
                                String expectedAfterBlockId,
                                Map<String, String> expectedAfterProperties,
                                int maxBlocks, int maxTicks,
                                double maxDistance, boolean advance, boolean sneak)
        implements V2BlockWorkRequest {
    V2BlockInteractArguments {
        Objects.requireNonNull(region, "region");
        if (blockId != null) requireId(blockId);
        if (itemId != null) requireId(itemId);
        if (expectedAfterBlockId != null) requireId(expectedAfterBlockId);
        expectedAfterProperties = Map.copyOf(expectedAfterProperties);
        if (region.size() > 1 && blockId == null) {
            throw new IllegalArgumentException("a block range requires a block ID condition");
        }
        if (maxBlocks < 1 || maxBlocks > region.size()
                || maxTicks < 1 || maxTicks > AgentJobLimits.MAX_TICKS
                || !Double.isFinite(maxDistance) || maxDistance < 0.0D
                || maxDistance > AgentJobLimits.MAX_DISTANCE) {
            throw new IllegalArgumentException("invalid interaction bounds");
        }
    }

    static V2BlockInteractArguments parse(Map<String, Object> arguments, String dimension) {
        RuntimeArguments.requireAllowedKeys(arguments, "agent_interact", Set.of(
                "target", "x", "y", "z", "dx", "dy", "dz", "block", "item",
                "expected_after_block", "expected_after_properties", "max_interactions",
                "max_ticks", "max_distance", "advance", "sneak"));
        if (!"block".equals(RuntimeArguments.stringArgument(arguments, "target"))
                || !arguments.keySet().containsAll(Set.of("x", "y", "z"))) {
            throw new IllegalArgumentException("block interaction requires target=block and x/y/z");
        }
        var region = new BlockWorkRegion(Objects.requireNonNull(dimension, "dimension"),
                RuntimeArguments.intArgument(arguments, "x"),
                RuntimeArguments.intArgument(arguments, "y"),
                RuntimeArguments.intArgument(arguments, "z"),
                optionalInt(arguments, "dx", 0), optionalInt(arguments, "dy", 0),
                optionalInt(arguments, "dz", 0));
        return new V2BlockInteractArguments(region,
                optionalId(arguments, "block"), optionalId(arguments, "item"),
                optionalId(arguments, "expected_after_block"),
                properties(arguments),
                optionalInt(arguments, "max_interactions", region.size()),
                optionalInt(arguments, "max_ticks", 1_200),
                arguments.containsKey("max_distance")
                        ? RuntimeArguments.doubleArgument(arguments, "max_distance") : 64.0D,
                !arguments.containsKey("advance")
                        || RuntimeArguments.booleanArgument(arguments, "advance"),
                arguments.containsKey("sneak") && RuntimeArguments.booleanArgument(arguments, "sneak"));
    }

    boolean accepts(String currentBlockId) {
        return blockId == null || blockId.equals(currentBlockId);
    }

    private static int optionalInt(Map<String, Object> arguments, String key, int fallback) {
        return arguments.containsKey(key) ? RuntimeArguments.intArgument(arguments, key) : fallback;
    }

    private static String optionalId(Map<String, Object> arguments, String key) {
        return arguments.containsKey(key) ? RuntimeArguments.stringArgument(arguments, key) : null;
    }

    private static void requireId(String id) {
        if (id.length() > 128 || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("invalid registry ID");
        }
    }

    private static Map<String, String> properties(Map<String, Object> arguments) {
        if (!arguments.containsKey("expected_after_properties")) return Map.of();
        var raw = RuntimeArguments.objectArgument(arguments, "expected_after_properties");
        if (raw.size() > 32) throw new IllegalArgumentException("too many expected properties");
        var result = new LinkedHashMap<String, String>();
        for (var entry : raw.entrySet()) {
            if (entry.getKey() == null || !entry.getKey().matches("[a-z0-9_]{1,64}")
                    || !(entry.getValue() instanceof String value)
                    || value.isEmpty() || value.length() > 64
                    || !value.matches("[a-z0-9_./-]+")) {
                throw new IllegalArgumentException("invalid expected property");
            }
            result.put(entry.getKey(), value);
        }
        return Map.copyOf(result);
    }
}
