package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.routine.BlockStateFingerprint;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.Set;

/** Bounded three-valued predicates: negating an unknown observation remains unknown. */
sealed interface V2StopCondition {
    boolean matches(Context context);
    default Boolean evaluate(Context context) { return matches(context); }
    default boolean matchesScreen(Context context) { return false; }

    interface Context {
        Vec3 position();
        BlockStateFingerprint visibleBlock(int x, int y, int z);
        int itemCount(String item);
        String screen();
        default Map<String, Object> entity(String ref) { return null; }
        default Map<String, Object> player() { return null; }
    }

    static V2StopCondition parse(Map<String, Object> args) {
        return parse(args, 0, new int[]{0});
    }

    private static V2StopCondition parse(Map<String, Object> args, int depth, int[] nodes) {
        if (depth > 8 || ++nodes[0] > 64) throw new IllegalArgumentException("condition complexity limit");
        String type = args.containsKey("type") ? RuntimeArguments.stringArgument(args, "type") : "position";
        return switch (type) {
            case "all", "any" -> {
                RuntimeArguments.requireAllowedKeys(args, "stop_when", Set.of("type", "conditions"));
                var children = RuntimeArguments.objectListArgument(args, "conditions", 1, 16).stream()
                        .map(child -> parse(child, depth + 1, nodes)).toList();
                yield new Composite(type, children);
            }
            case "not" -> {
                RuntimeArguments.requireAllowedKeys(args, "stop_when", Set.of("type", "condition"));
                yield new Composite(type, java.util.List.of(parse(RuntimeArguments.objectArgument(args, "condition"), depth + 1, nodes)));
            }
            case "entity", "player" -> {
                RuntimeArguments.requireAllowedKeys(args, "stop_when", type.equals("entity")
                        ? Set.of("type", "entity_ref", "property", "value", "comparison")
                        : Set.of("type", "property", "value", "comparison"));
                String ref = type.equals("entity") ? RuntimeArguments.stringArgument(args, "entity_ref") : null;
                if (ref != null && !ref.matches("[A-Za-z0-9_-]{24}")) throw new IllegalArgumentException("invalid entity reference");
                String property = RuntimeArguments.stringArgument(args, "property");
                var flags = type.equals("entity") ? Set.of("on_fire", "in_water", "passenger", "on_ground", "baby")
                        : Set.of("on_fire", "in_water", "passenger", "on_ground");
                var numbers = type.equals("entity") ? Set.of("distance") : Set.of("health", "food", "air");
                Object value = args.get("value");
                String comparison = args.containsKey("comparison") ? RuntimeArguments.stringArgument(args, "comparison") : "equals";
                if (!Set.of("equals", "at_least", "at_most").contains(comparison)
                        || flags.contains(property) && (!(value instanceof Boolean) || !comparison.equals("equals"))
                        || numbers.contains(property) && (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()))
                        || !flags.contains(property) && !numbers.contains(property))
                    throw new IllegalArgumentException("invalid observed property comparison");
                yield new Observed(ref, property, value, comparison);
            }
            case "position" -> {
                RuntimeArguments.requireAllowedKeys(args, "stop_when", Set.of("type", "x", "y", "z", "radius"));
                double radius = args.containsKey("radius") ? RuntimeArguments.doubleArgument(args, "radius") : 0.75D;
                if (!Double.isFinite(radius) || radius < 0.1D || radius > 16) throw new IllegalArgumentException("invalid stop radius");
                yield new Position(RuntimeArguments.intArgument(args, "x"), RuntimeArguments.intArgument(args, "y"),
                        RuntimeArguments.intArgument(args, "z"), radius);
            }
            case "block" -> {
                RuntimeArguments.requireAllowedKeys(args, "stop_when", Set.of("type", "x", "y", "z", "block", "properties"));
                yield new Block(RuntimeArguments.intArgument(args, "x"), RuntimeArguments.intArgument(args, "y"),
                        RuntimeArguments.intArgument(args, "z"),
                        new BlockStateFingerprint(id(args, "block"), V2PlaceArguments.properties(args)));
            }
            case "item" -> {
                RuntimeArguments.requireAllowedKeys(args, "stop_when", Set.of("type", "item", "count", "comparison"));
                int count = args.containsKey("count") ? RuntimeArguments.intArgument(args, "count") : 1;
                String comparison = args.containsKey("comparison") ? RuntimeArguments.stringArgument(args, "comparison") : "at_least";
                if (count < 0 || count > 1_000_000 || !Set.of("at_least", "at_most", "equals").contains(comparison)) {
                    throw new IllegalArgumentException("invalid item condition");
                }
                yield new Item(id(args, "item"), count, comparison);
            }
            case "screen" -> {
                RuntimeArguments.requireAllowedKeys(args, "stop_when", Set.of("type", "screen"));
                String screen = RuntimeArguments.stringArgument(args, "screen");
                if (!Set.of("none", "container", "inventory", "chat").contains(screen)) throw new IllegalArgumentException("invalid screen condition");
                yield new Screen(screen);
            }
            default -> throw new IllegalArgumentException("unknown stop condition");
        };
    }

    private static String id(Map<String, Object> args, String key) {
        String id = RuntimeArguments.stringArgument(args, key);
        if (id.length() > 128 || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("invalid registry ID");
        return id;
    }

    record Position(int x, int y, int z, double radius) implements V2StopCondition {
        boolean reached(Vec3 position) {
            return position.distanceToSqr(x + 0.5D, y, z + 0.5D) <= radius * radius;
        }
        public boolean matches(Context context) { return reached(context.position()); }
    }

    record Block(int x, int y, int z, BlockStateFingerprint expected) implements V2StopCondition {
        public boolean matches(Context context) {
            return Boolean.TRUE.equals(evaluate(context));
        }
        public Boolean evaluate(Context context) {
            var observed = context.visibleBlock(x, y, z);
            return observed == null ? null : expected.matches(observed);
        }
    }

    record Composite(String operation, java.util.List<V2StopCondition> conditions) implements V2StopCondition {
        public Composite { conditions = java.util.List.copyOf(conditions); }
        public boolean matches(Context context) { return Boolean.TRUE.equals(evaluate(context)); }
        public boolean matchesScreen(Context context) {
            return !operation.equals("not") && matches(context) && conditions.stream().anyMatch(c -> c.matchesScreen(context));
        }
        public Boolean evaluate(Context context) {
            if (operation.equals("not")) {
                Boolean value = conditions.getFirst().evaluate(context);
                return value == null ? null : !value;
            }
            boolean all = operation.equals("all");
            boolean unknown = false;
            for (var condition : conditions) {
                Boolean value = condition.evaluate(context);
                if (value == null) unknown = true;
                else if (value != all) return !all;
            }
            return unknown ? null : all;
        }
    }

    record Observed(String ref, String property, Object value, String comparison) implements V2StopCondition {
        public boolean matches(Context context) { return Boolean.TRUE.equals(evaluate(context)); }
        public Boolean evaluate(Context context) {
            var state = ref == null ? context.player() : context.entity(ref);
            if (state == null || !state.containsKey(property)) return null;
            var actual = state.get(property);
            if (actual instanceof Number a && value instanceof Number b) return switch (comparison) {
                case "at_least" -> a.doubleValue() >= b.doubleValue();
                case "at_most" -> a.doubleValue() <= b.doubleValue();
                default -> a.doubleValue() == b.doubleValue();
            };
            return java.util.Objects.equals(actual, value);
        }
    }

    record Item(String item, int count, String comparison) implements V2StopCondition {
        public boolean matches(Context context) {
            int actual = context.itemCount(item);
            return switch (comparison) {
                case "at_least" -> actual >= count;
                case "at_most" -> actual <= count;
                default -> actual == count;
            };
        }
    }

    record Screen(String screen) implements V2StopCondition {
        public boolean matches(Context context) { return screen.equals(context.screen()); }
        public boolean matchesScreen(Context context) { return matches(context); }
    }
}
