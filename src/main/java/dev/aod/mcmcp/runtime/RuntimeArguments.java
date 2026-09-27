package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.observation.ObservationFilter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/** MCP内部コマンドの型・キー・値の検証。入力値を診断へ反射しない。 */
final class RuntimeArguments {
    private RuntimeArguments() {}

    static ObservationFilter observationFilterArgument(Map<String, Object> arguments) {
        return ObservationFilterArguments.parse(arguments);
    }

    static UUID actionId(Map<String, Object> arguments) {
        try {
            return UUID.fromString(stringArgument(arguments, "action_id"));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("action_id must be a UUID", failure);
        }
    }

    static List<Map<String, Object>> objectListArgument(
            Map<String, Object> source,
            String name,
            int minimum,
            int maximum) {
        Object raw = source.get(name);
        if (!(raw instanceof List<?> values)
                || values.size() < minimum
                || values.size() > maximum) {
            throw new IllegalArgumentException(
                    name + " must contain " + minimum + ".." + maximum + " objects");
        }
        var result = new ArrayList<Map<String, Object>>(values.size());
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException(name + " must contain only objects");
            }
            @SuppressWarnings("unchecked")
            var typed = (Map<String, Object>) map;
            result.add(typed);
        }
        return List.copyOf(result);
    }

    static void requireUniqueLocalId(Set<String> ids, String id, String path) {
        if (!id.matches("[a-z][a-z0-9_.-]{0,63}") || !ids.add(id)) {
            throw new IllegalArgumentException(path + " must be a unique local identifier");
        }
    }

    static void requireRegisteredItemId(String itemId) {
        Identifier identifier = Identifier.tryParse(itemId);
        var registered = identifier == null
                ? Optional.<Holder.Reference<net.minecraft.world.item.Item>>empty()
                : BuiltInRegistries.ITEM.get(identifier);
        if (registered.isEmpty()
                || registered.orElseThrow().value() == net.minecraft.world.item.Items.AIR) {
            throw new IllegalArgumentException("item must be a registered item ID");
        }
    }

    static void requireOpaqueReference(Map<String, Object> source, String name) {
        if (!stringArgument(source, name).matches("[A-Za-z0-9_-]{24}")) {
            throw new IllegalArgumentException(name + " must be a 24-character opaque reference");
        }
    }

    static void requireSha256Fingerprint(Map<String, Object> source, String name) {
        if (!stringArgument(source, name).matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a SHA-256 fingerprint");
        }
    }

    static void requireLiteral(
            Map<String, Object> source, String name, String expected) {
        if (!expected.equals(stringArgument(source, name))) {
            throw new IllegalArgumentException(name + " must be " + expected);
        }
    }

    static void requireTrue(Map<String, Object> source, String name) {
        if (!booleanArgument(source, name)) {
            throw new IllegalArgumentException(name + " must be true");
        }
    }

    static int requireRange(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    name + " must be in " + minimum + ".." + maximum);
        }
        return value;
    }

    static int relativeCoordinate(
            Map<String, Object> offset,
            String coordinate,
            String entryPath) {
        int value = intArgument(offset, coordinate);
        if (value < -4_096 || value > 4_096) {
            throw new IllegalArgumentException(
                    entryPath + ".offset." + coordinate + " must be in -4096..4096");
        }
        return value;
    }

    static String completionIntentArgument(Map<String, Object> arguments) {
        Objects.requireNonNull(arguments, "arguments");
        String intent = arguments.containsKey("completion_intent")
                ? stringArgument(arguments, "completion_intent")
                : GoalContinuationSession.FINISH_GOAL;
        GoalContinuationSession.requireIntent(intent);
        return intent;
    }

    static void requireExactKeys(
            Map<String, Object> source,
            String name,
            Set<String> expected) {
        requireAllowedKeys(source, name, expected);
        if (source.size() != expected.size() || !source.keySet().containsAll(expected)) {
            throw new IllegalArgumentException(name + " must contain exactly "
                    + expected.stream().sorted().toList());
        }
    }

    static void requireAllowedKeys(
            Map<String, Object> source,
            String name,
            Set<String> allowed) {
        Objects.requireNonNull(source, "source");
        for (var key : source.keySet()) {
            if (key == null || !allowed.contains(key)) {
                throw new IllegalArgumentException(name + " contains an unknown property");
            }
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> objectArgument(Map<String, Object> source, String name) {
        var value = source.get(name);
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        return (Map<String, Object>) map;
    }

    static String stringArgument(Map<String, Object> source, String name) {
        var value = source.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(name + " must be a non-empty string");
        }
        return text;
    }

    static int intArgument(Map<String, Object> source, String name) {
        var value = source.get(name);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        try {
            return Math.toIntExact(exactLong(number));
        }
        catch (ArithmeticException invalid) {
            throw new IllegalArgumentException(name + " must be an integer", invalid);
        }
    }

    static double doubleArgument(Map<String, Object> source, String name) {
        var value = source.get(name);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(name + " must be a finite number");
        }
        double result = number.doubleValue();
        if (!Double.isFinite(result)) {
            throw new IllegalArgumentException(name + " must be a finite number");
        }
        return result;
    }

    static boolean booleanArgument(Map<String, Object> source, String name) {
        var value = source.get(name);
        if (!(value instanceof Boolean flag)) {
            throw new IllegalArgumentException(name + " must be a boolean");
        }
        return flag;
    }

    static long optionalLong(Map<String, Object> source, String name, long fallback) {
        var value = source.get(name);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        try {
            return exactLong(number);
        }
        catch (ArithmeticException invalid) {
            throw new IllegalArgumentException(name + " must be an integer", invalid);
        }
    }

    static long exactLong(Number number) {
        return switch (number) {
            case Byte value -> value.longValue();
            case Short value -> value.longValue();
            case Integer value -> value.longValue();
            case Long value -> value;
            case BigInteger value -> value.longValueExact();
            case BigDecimal value -> value.longValueExact();
            case Float value -> exactFloatingLong(value.doubleValue());
            case Double value -> exactFloatingLong(value);
            default -> new BigDecimal(number.toString()).longValueExact();
        };
    }

    static long exactFloatingLong(double value) {
        if (!Double.isFinite(value)
                || value != Math.rint(value)
                || value < Long.MIN_VALUE
                || value >= 0x1.0p63) {
            throw new ArithmeticException("not an exact long");
        }
        return (long) value;
    }

    static UUID uuidArgument(Map<String, Object> source, String name) {
        try {
            return UUID.fromString(stringArgument(source, name));
        }
        catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(name + " must be a UUID", invalid);
        }
    }

    static Set<String> stringSetArgument(Map<String, Object> source, String name) {
        var value = source.get(name);
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(name + " must be an array");
        }
        if (list.isEmpty() || list.size() > 16) {
            throw new IllegalArgumentException(name + " must contain 1..16 registry IDs");
        }
        var result = new java.util.LinkedHashSet<String>();
        for (var element : list) {
            if (!(element instanceof String text)
                    || !text.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                throw new IllegalArgumentException(name + " must contain registry IDs");
            }
            result.add(text);
        }
        if (result.size() != list.size()) {
            throw new IllegalArgumentException(name + " must contain unique registry IDs");
        }
        return Set.copyOf(result);
    }
}
