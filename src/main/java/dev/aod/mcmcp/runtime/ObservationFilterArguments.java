package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.observation.ObservationFilter;
import dev.aod.mcmcp.agent.observation.ObservationRecord.Face;
import dev.aod.mcmcp.agent.observation.ObservationValues.ResourceId;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Minecraft-independent parser for the bounded agent observation delivery projection. */
final class ObservationFilterArguments {
    private static final Set<String> FILTER_KEYS = Set.of(
            "block_ids", "entity_types", "displayed_items", "crop_mature",
            "position_bounds", "faces");
    private static final Set<String> POSITION_BOUND_KEYS = Set.of(
            "dimension", "min_x", "min_y", "min_z", "max_x", "max_y", "max_z");

    private ObservationFilterArguments() {
    }

    static ObservationFilter parse(Map<String, Object> arguments) {
        if (!arguments.containsKey("filter")) {
            return ObservationFilter.NONE;
        }
        Map<String, Object> input = object(arguments.get("filter"), "filter");
        requireAllowedKeys(input, FILTER_KEYS, "filter");
        if (input.isEmpty()) {
            throw new IllegalArgumentException("agent_get_observation filter must not be empty");
        }
        return new ObservationFilter(
                resourceIds(input, "block_ids"),
                resourceIds(input, "entity_types"),
                resourceIds(input, "displayed_items"),
                input.containsKey("crop_mature")
                        ? Optional.of(booleanValue(input, "crop_mature"))
                        : Optional.empty(),
                input.containsKey("position_bounds")
                        ? Optional.of(positionBounds(input))
                        : Optional.empty(),
                faces(input));
    }

    private static ObservationFilter.PositionBounds positionBounds(Map<String, Object> filter) {
        Map<String, Object> input = object(filter.get("position_bounds"), "position_bounds");
        requireExactKeys(input, POSITION_BOUND_KEYS, "position_bounds");
        return new ObservationFilter.PositionBounds(
                new ResourceId(stringValue(input, "dimension")),
                integer(input, "min_x"),
                integer(input, "min_y"),
                integer(input, "min_z"),
                integer(input, "max_x"),
                integer(input, "max_y"),
                integer(input, "max_z"));
    }

    private static Set<ResourceId> resourceIds(Map<String, Object> source, String name) {
        if (!source.containsKey(name)) {
            return Set.of();
        }
        Object raw = source.get(name);
        if (!(raw instanceof List<?> values) || values.isEmpty() || values.size() > 32) {
            throw new IllegalArgumentException(name + " must contain 1..32 values");
        }
        var result = new LinkedHashSet<ResourceId>();
        for (Object value : values) {
            if (!(value instanceof String id) || id.isBlank() || !result.add(new ResourceId(id))) {
                throw new IllegalArgumentException(name + " must contain unique resource IDs");
            }
        }
        return Set.copyOf(result);
    }

    private static Set<Face> faces(Map<String, Object> source) {
        if (!source.containsKey("faces")) {
            return Set.of();
        }
        Object raw = source.get("faces");
        if (!(raw instanceof List<?> values) || values.isEmpty() || values.size() > 6) {
            throw new IllegalArgumentException("faces must contain 1..6 values");
        }
        var result = new LinkedHashSet<Face>();
        for (Object value : values) {
            if (!(value instanceof String name)) {
                throw new IllegalArgumentException(
                        "faces must contain unique lower-case block faces");
            }
            Face face = switch (name) {
                case "down" -> Face.DOWN;
                case "up" -> Face.UP;
                case "north" -> Face.NORTH;
                case "south" -> Face.SOUTH;
                case "west" -> Face.WEST;
                case "east" -> Face.EAST;
                default -> throw new IllegalArgumentException(
                        "faces must contain unique lower-case block faces");
            };
            if (!result.add(face)) {
                throw new IllegalArgumentException(
                        "faces must contain unique lower-case block faces");
            }
        }
        return Set.copyOf(result);
    }

    private static Map<String, Object> object(Object raw, String name) {
        if (!(raw instanceof Map<?, ?> input)) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        var result = new LinkedHashMap<String, Object>();
        for (var entry : input.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(name + " contains an unknown property");
            }
            result.put(key, entry.getValue());
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    private static void requireAllowedKeys(
            Map<String, Object> input, Set<String> allowed, String name) {
        if (!allowed.containsAll(input.keySet())) {
            throw new IllegalArgumentException(name + " contains an unknown property");
        }
    }

    private static void requireExactKeys(
            Map<String, Object> input, Set<String> expected, String name) {
        if (!input.keySet().equals(expected)) {
            throw new IllegalArgumentException(name + " must contain its complete fixed shape");
        }
    }

    private static String stringValue(Map<String, Object> input, String name) {
        Object value = input.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(name + " must be a non-empty string");
        }
        return text;
    }

    private static boolean booleanValue(Map<String, Object> input, String name) {
        Object value = input.get(name);
        if (!(value instanceof Boolean flag)) {
            throw new IllegalArgumentException(name + " must be a boolean");
        }
        return flag;
    }

    private static int integer(Map<String, Object> input, String name) {
        Object value = input.get(name);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        try {
            return new BigDecimal(number.toString()).intValueExact();
        } catch (ArithmeticException | NumberFormatException failure) {
            throw new IllegalArgumentException(name + " must be an integer", failure);
        }
    }
}
