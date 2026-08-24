package dev.aodaruma.craftagent.routine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Small, data-only IR for one bounded sequence of existing routines. */
public record FinitePlanRequest(String planId, int maxTicks, List<Step> steps) {
    public static final int MAX_DECLARED_STEPS = 256;
    public static final int MAX_EXECUTED_STEPS = 12_000;
    public static final int MAX_TICKS = 144_000;
    public static final int MAX_REPEAT_ITERATIONS = 128;
    private static final int MAX_PLAN_DEPTH = 8;
    private static final int MAX_ARGUMENT_DEPTH = 16;

    public FinitePlanRequest {
        planId = localId(planId, "plan_id");
        if (maxTicks < 1 || maxTicks > MAX_TICKS) {
            throw new IllegalArgumentException("max_ticks must be in 1.." + MAX_TICKS);
        }
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("steps must not be empty");
        }
        var ids = new HashSet<String>();
        var declared = new Counter();
        long executed = validateSteps(steps, maxTicks, 1, ids, declared);
        if (executed > MAX_EXECUTED_STEPS) {
            throw new IllegalArgumentException(
                    "expanded plan exceeds " + MAX_EXECUTED_STEPS + " steps");
        }
    }

    /** Parses the deliberately small JSON-shaped wire form into immutable typed nodes. */
    public static FinitePlanRequest parse(Map<String, ?> source) {
        var root = object(source, "plan");
        requireExactKeys(root, "plan", Set.of("plan_id", "max_ticks", "steps"));
        int maxTicks = integer(root.get("max_ticks"), "max_ticks");
        return new FinitePlanRequest(
                text(root.get("plan_id"), "plan_id"),
                maxTicks,
                parseSteps(root.get("steps"), "steps"));
    }

    public sealed interface Step permits Action, Assert, WaitUntil, RepeatUntil {
        String id();
    }

    /** Child payload omits repeated kind/idempotency/completion fields and reuses typed validation. */
    public record Action(String id, RoutineKind kind, Map<String, Object> arguments)
            implements Step {
        public Action {
            id = localId(id, "step id");
            Objects.requireNonNull(kind, "kind");
            requireExactKeys(arguments, "action arguments", Set.of("parameters", "bounds"));
            arguments = immutableJsonObject(arguments, "arguments");
        }
    }

    public record Assert(String id, Condition condition) implements Step {
        public Assert {
            id = localId(id, "step id");
            Objects.requireNonNull(condition, "condition");
        }
    }

    public record WaitUntil(String id, Condition condition, int maxTicks) implements Step {
        public WaitUntil {
            id = localId(id, "step id");
            Objects.requireNonNull(condition, "condition");
            positiveBound(maxTicks, MAX_TICKS, "wait max_ticks");
        }
    }

    public record RepeatUntil(
            String id,
            Condition until,
            int maxIterations,
            int maxTicks,
            List<Step> steps) implements Step {
        public RepeatUntil {
            id = localId(id, "step id");
            Objects.requireNonNull(until, "until");
            positiveBound(maxIterations, MAX_REPEAT_ITERATIONS, "max_iterations");
            positiveBound(maxTicks, MAX_TICKS, "repeat max_ticks");
            steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
            if (steps.isEmpty()) {
                throw new IllegalArgumentException("repeat steps must not be empty");
            }
        }
    }

    public sealed interface Condition permits InventoryAtLeast, BlockMatches {
    }

    public record InventoryAtLeast(String item, int minimumCount) implements Condition {
        public InventoryAtLeast {
            item = PlaceBlockRequest.requireRegistryId(item, "item");
            if (minimumCount < 1 || minimumCount > 2_304) {
                throw new IllegalArgumentException("minimum_count must be in 1..2304");
            }
        }
    }

    public record BlockMatches(BlockTarget target, BlockStateFingerprint expectedState)
            implements Condition {
        public BlockMatches {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(expectedState, "expectedState");
        }
    }

    public enum RoutineKind {
        NAVIGATE_TO("navigate_to"),
        BREAK_BLOCK("break_block"),
        PLACE_BLOCK("place_block"),
        INTERACT_BLOCK("interact_block"),
        USE_ITEM_ON_BLOCK("use_item_on_block"),
        CRAFT_ITEMS("craft_items"),
        TRANSFER_ITEMS("transfer_items"),
        TEND_CROP_AREA("tend_crop_area"),
        HARVEST_TREE_AREA("harvest_tree_area"),
        SLEEP_AT_BED("sleep_at_bed"),
        SURVEY_AREA("survey_area");

        private final String wireName;

        RoutineKind(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        static RoutineKind parse(String value) {
            for (var kind : values()) {
                if (kind.wireName.equals(value)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("unsupported routine kind: " + value);
        }
    }

    private static List<Step> parseSteps(Object raw, String path) {
        var values = array(raw, path);
        if (values.isEmpty()) {
            throw new IllegalArgumentException(path + " must not be empty");
        }
        var result = new ArrayList<Step>(values.size());
        for (int index = 0; index < values.size(); index++) {
            result.add(parseStep(object(values.get(index), path + "[" + index + "]"),
                    path + "[" + index + "]"));
        }
        return List.copyOf(result);
    }

    private static Step parseStep(Map<String, Object> value, String path) {
        String operation = text(value.get("op"), path + ".op");
        return switch (operation) {
            case "action" -> {
                requireExactKeys(value, path, Set.of("id", "op", "kind", "arguments"));
                yield new Action(
                        text(value.get("id"), path + ".id"),
                        RoutineKind.parse(text(value.get("kind"), path + ".kind")),
                        object(value.get("arguments"), path + ".arguments"));
            }
            case "assert" -> {
                requireExactKeys(value, path, Set.of("id", "op", "condition"));
                yield new Assert(
                        text(value.get("id"), path + ".id"),
                        parseCondition(value.get("condition"), path + ".condition"));
            }
            case "wait_until" -> {
                requireExactKeys(value, path, Set.of("id", "op", "condition", "max_ticks"));
                yield new WaitUntil(
                        text(value.get("id"), path + ".id"),
                        parseCondition(value.get("condition"), path + ".condition"),
                        integer(value.get("max_ticks"), path + ".max_ticks"));
            }
            case "repeat_until" -> {
                requireExactKeys(value, path, Set.of(
                        "id", "op", "until", "max_iterations", "max_ticks", "steps"));
                yield new RepeatUntil(
                        text(value.get("id"), path + ".id"),
                        parseCondition(value.get("until"), path + ".until"),
                        integer(value.get("max_iterations"), path + ".max_iterations"),
                        integer(value.get("max_ticks"), path + ".max_ticks"),
                        parseSteps(value.get("steps"), path + ".steps"));
            }
            default -> throw new IllegalArgumentException("unsupported plan op: " + operation);
        };
    }

    private static Condition parseCondition(Object raw, String path) {
        var value = object(raw, path);
        String kind = text(value.get("kind"), path + ".kind");
        return switch (kind) {
            case "inventory_at_least" -> {
                requireExactKeys(value, path, Set.of("kind", "item", "minimum_count"));
                yield new InventoryAtLeast(
                        text(value.get("item"), path + ".item"),
                        integer(value.get("minimum_count"), path + ".minimum_count"));
            }
            case "block_matches" -> {
                requireExactKeys(value, path, Set.of("kind", "target", "expected_state"));
                yield new BlockMatches(
                        target(value.get("target"), path + ".target"),
                        state(value.get("expected_state"), path + ".expected_state"));
            }
            default -> throw new IllegalArgumentException("unsupported condition kind: " + kind);
        };
    }

    private static long validateSteps(
            List<Step> steps,
            int planMaxTicks,
            int depth,
            Set<String> ids,
            Counter declared) {
        if (depth > MAX_PLAN_DEPTH) {
            throw new IllegalArgumentException("plan nesting exceeds " + MAX_PLAN_DEPTH);
        }
        long executed = 0;
        for (var step : steps) {
            Objects.requireNonNull(step, "step");
            if (++declared.value > MAX_DECLARED_STEPS) {
                throw new IllegalArgumentException(
                        "plan contains more than " + MAX_DECLARED_STEPS + " declared steps");
            }
            if (!ids.add(step.id())) {
                throw new IllegalArgumentException("duplicate step id: " + step.id());
            }
            long stepExecutions = 1;
            if (step instanceof WaitUntil wait && wait.maxTicks() > planMaxTicks) {
                throw new IllegalArgumentException("wait max_ticks exceeds plan max_ticks");
            } else if (step instanceof RepeatUntil repeat) {
                if (repeat.maxTicks() > planMaxTicks) {
                    throw new IllegalArgumentException("repeat max_ticks exceeds plan max_ticks");
                }
                long body = validateSteps(
                        repeat.steps(), planMaxTicks, depth + 1, ids, declared);
                try {
                    stepExecutions = Math.addExact(
                            1L, Math.multiplyExact(1L + body, repeat.maxIterations()));
                } catch (ArithmeticException overflow) {
                    throw new IllegalArgumentException("expanded plan is too large", overflow);
                }
            }
            try {
                executed = Math.addExact(executed, stepExecutions);
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("expanded plan is too large", overflow);
            }
            if (executed > MAX_EXECUTED_STEPS) {
                throw new IllegalArgumentException(
                        "expanded plan exceeds " + MAX_EXECUTED_STEPS + " steps");
            }
        }
        return executed;
    }

    private static BlockTarget target(Object raw, String path) {
        var value = object(raw, path);
        requireExactKeys(value, path, Set.of("dimension", "x", "y", "z"));
        return new BlockTarget(
                text(value.get("dimension"), path + ".dimension"),
                integer(value.get("x"), path + ".x"),
                integer(value.get("y"), path + ".y"),
                integer(value.get("z"), path + ".z"));
    }

    private static BlockStateFingerprint state(Object raw, String path) {
        var value = object(raw, path);
        requireExactKeys(value, path, Set.of("block", "properties"));
        var properties = object(value.get("properties"), path + ".properties");
        var typed = new LinkedHashMap<String, String>();
        properties.forEach((key, entry) ->
                typed.put(key, text(entry, path + ".properties." + key)));
        return new BlockStateFingerprint(
                text(value.get("block"), path + ".block"), typed);
    }

    private static Map<String, Object> object(Object raw, String path) {
        if (!(raw instanceof Map<?, ?> value)) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        var result = new LinkedHashMap<String, Object>();
        value.forEach((key, entry) -> {
            if (!(key instanceof String textKey) || textKey.isBlank()) {
                throw new IllegalArgumentException(path + " has a non-string or blank key");
            }
            result.put(textKey, entry);
        });
        return result;
    }

    private static List<?> array(Object raw, String path) {
        if (!(raw instanceof Collection<?> values)) {
            throw new IllegalArgumentException(path + " must be an array");
        }
        return List.copyOf(values);
    }

    private static void requireExactKeys(
            Map<String, ?> source, String path, Set<String> expected) {
        if (!source.keySet().equals(expected)) {
            throw new IllegalArgumentException(path + " must contain exactly "
                    + expected.stream().sorted().toList());
        }
    }

    private static String text(Object raw, String path) {
        if (!(raw instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException(path + " must be a non-blank string");
        }
        return value;
    }

    private static int integer(Object raw, String path) {
        if (!(raw instanceof Number value)) {
            throw new IllegalArgumentException(path + " must be an integer");
        }
        long integer = value.longValue();
        if (value.doubleValue() != integer
                || integer < Integer.MIN_VALUE || integer > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(path + " must be an integer");
        }
        return (int) integer;
    }

    private static String localId(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.matches("[a-z][a-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("invalid " + name);
        }
        return value;
    }

    private static void positiveBound(int value, int maximum, String name) {
        if (value < 1 || value > maximum) {
            throw new IllegalArgumentException(name + " must be in 1.." + maximum);
        }
    }

    private static Map<String, Object> immutableJsonObject(
            Map<String, ?> source, String path) {
        return immutableJsonObject(source, path, 1);
    }

    private static Map<String, Object> immutableJsonObject(
            Map<String, ?> source, String path, int depth) {
        Objects.requireNonNull(source, path);
        if (depth > MAX_ARGUMENT_DEPTH) {
            throw new IllegalArgumentException(path + " exceeds the JSON nesting limit");
        }
        var result = new LinkedHashMap<String, Object>();
        source.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException(path + " has a blank key");
            }
            result.put(key, immutableJsonValue(value, path + "." + key, depth + 1));
        });
        return Collections.unmodifiableMap(result);
    }

    private static Object immutableJsonValue(Object value, String path, int depth) {
        if (depth > MAX_ARGUMENT_DEPTH) {
            throw new IllegalArgumentException(path + " exceeds the JSON nesting limit");
        }
        if (value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Number number) {
            if (number instanceof Double doubleValue && !Double.isFinite(doubleValue)
                    || number instanceof Float floatValue && !Float.isFinite(floatValue)) {
                throw new IllegalArgumentException(path + " must be finite");
            }
            return number;
        }
        if (value instanceof Map<?, ?> map) {
            return immutableJsonObject(object(map, path), path, depth);
        }
        if (value instanceof Collection<?> values) {
            return values.stream()
                    .map(entry -> immutableJsonValue(entry, path + "[]", depth + 1))
                    .toList();
        }
        throw new IllegalArgumentException(path + " is not a JSON value");
    }

    private static final class Counter {
        private int value;
    }
}
