package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.routine.BlockStateFingerprint;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.Set;

/** A single bounded condition; unknown block evidence never satisfies even an air condition. */
sealed interface V2StopCondition {
    boolean matches(Context context);

    interface Context {
        Vec3 position();
        BlockStateFingerprint visibleBlock(int x, int y, int z);
        int itemCount(String item);
        String screen();
    }

    static V2StopCondition parse(Map<String, Object> args) {
        String type = args.containsKey("type") ? RuntimeArguments.stringArgument(args, "type") : "position";
        return switch (type) {
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
            var observed = context.visibleBlock(x, y, z);
            return observed != null && expected.matches(observed);
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
    }
}
