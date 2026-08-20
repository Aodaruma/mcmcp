package dev.aodaruma.craftagent.observation;

import net.minecraft.core.BlockPos;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Parser and canonicalizer for the existing compare_block_plan wire contract. */
public final class BlockPlanParser {
    private BlockPlanParser() {
    }

    public static BlockPlan parse(Map<String, Object> arguments) {
        Objects.requireNonNull(arguments, "arguments");
        var anchorInput = map(arguments.get("anchor"), "anchor");
        var anchor = new BlockPosition(
                string(anchorInput.get("dimension"), "anchor.dimension"),
                integerInRange(anchorInput.get("x"), "anchor.x", -30_000_000, 29_999_999),
                integerInRange(anchorInput.get("y"), "anchor.y", -2_048, 2_047),
                integerInRange(anchorInput.get("z"), "anchor.z", -30_000_000, 29_999_999));

        var transformInput = optionalMap(arguments.get("transform"), "transform");
        var transform = new BlockPlan.Transform(
                optionalInteger(transformInput.get("rotation"), 0, "transform.rotation"),
                optionalString(transformInput.get("mirror"), "none", "transform.mirror"));
        var includeMatches = optionalBoolean(
                arguments.get("include_matches"), false, "include_matches");

        if (!(arguments.get("expected") instanceof List<?> rawExpected)) {
            throw invalid("invalid_plan_field", "expected", "expected must be an array");
        }
        if (rawExpected.isEmpty() || rawExpected.size() > BlockPlan.MAX_EXPECTED_BLOCKS) {
            throw invalid(
                    "invalid_plan_size", "expected",
                    "expected must contain 1.." + BlockPlan.MAX_EXPECTED_BLOCKS + " entries",
                    Map.of("count", rawExpected.size()));
        }
        var ids = new HashSet<String>();
        var coordinates = new HashSet<BlockPos>();
        var expected = new ArrayList<BlockPlan.Expected>(rawExpected.size());
        for (int index = 0; index < rawExpected.size(); index++) {
            String entryPath = "expected[" + index + "]";
            var entry = map(rawExpected.get(index), entryPath);
            var id = string(entry.get("id"), entryPath + ".id");
            if (!id.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,95}")) {
                throw invalid(
                        "invalid_plan_id", entryPath + ".id",
                        "expected.id must contain 1..96 supported characters", Map.of("id", id));
            }
            if (!ids.add(id)) {
                throw invalid(
                        "duplicate_plan_id", entryPath + ".id",
                        "expected.id must be unique", Map.of("id", id));
            }
            var offsetInput = map(entry.get("offset"), entryPath + ".offset");
            var rawOffset = new BlockPlan.Offset(
                    integerInRange(offsetInput.get("x"), entryPath + ".offset.x", -4_096, 4_096),
                    integerInRange(offsetInput.get("y"), entryPath + ".offset.y", -4_096, 4_096),
                    integerInRange(offsetInput.get("z"), entryPath + ".offset.z", -4_096, 4_096));
            var transformedOffset = transform.apply(rawOffset);
            final int worldX;
            final int worldY;
            final int worldZ;
            try {
                worldX = Math.addExact(anchor.x(), transformedOffset.x());
                worldY = Math.addExact(anchor.y(), transformedOffset.y());
                worldZ = Math.addExact(anchor.z(), transformedOffset.z());
            } catch (ArithmeticException overflow) {
                throw invalid(
                        "plan_coordinate_out_of_range", entryPath + ".offset",
                        "transformed world position overflows the supported coordinate range");
            }
            if (worldX < -30_000_000 || worldX > 29_999_999
                    || worldY < -2_048 || worldY > 2_047
                    || worldZ < -30_000_000 || worldZ > 29_999_999) {
                throw invalid(
                        "plan_coordinate_out_of_range", entryPath + ".offset",
                        "transformed world position is outside the supported coordinate range");
            }
            var world = new BlockPos(worldX, worldY, worldZ);
            if (!coordinates.add(world)) {
                throw invalid(
                        "duplicate_plan_position", entryPath + ".offset",
                        "expected contains duplicate world coordinates",
                        Map.of("x", worldX, "y", worldY, "z", worldZ));
            }

            var stateInput = map(entry.get("state"), entryPath + ".state");
            var block = string(stateInput.get("block"), entryPath + ".state.block");
            if (!block.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                throw invalid(
                        "invalid_block_id", entryPath + ".state.block",
                        "expected.state.block must be a namespaced registry id",
                        Map.of("block", block));
            }
            var propertyInput = optionalMap(
                    stateInput.get("properties"), entryPath + ".state.properties");
            if (propertyInput.size() > 128) {
                throw invalid(
                        "invalid_block_state", entryPath + ".state.properties",
                        "expected.state.properties contains more than 128 entries",
                        Map.of("count", propertyInput.size()));
            }
            var properties = new TreeMap<String, String>();
            for (var property : propertyInput.entrySet()) {
                var name = string(property.getKey(), entryPath + ".state property name");
                var value = string(
                        property.getValue(), entryPath + ".state.properties." + name);
                if (!name.matches("[a-z0-9_]+") || value.isEmpty() || value.length() > 64) {
                    throw invalid(
                            "invalid_block_property", entryPath + ".state.properties." + name,
                            "block property name or value is outside the supported schema");
                }
                properties.put(name, value);
            }
            var sourceState = new BlockStateView(block, properties);
            var transformedState = BlockPlanStateTransformer.transform(
                    sourceState, transform, entryPath + ".state");
            expected.add(new BlockPlan.Expected(
                    id,
                    rawOffset,
                    transformedOffset,
                    new BlockPosition(anchor.dimension(), worldX, worldY, worldZ),
                    sourceState,
                    transformedState,
                    optionalBoolean(entry.get("required"), true, entryPath + ".required")));
        }

        var immutableExpected = List.copyOf(expected);
        return new BlockPlan(
                anchor,
                transform,
                immutableExpected,
                includeMatches,
                hash(anchor, transform, immutableExpected));
    }

    private static String hash(
            BlockPosition anchor,
            BlockPlan.Transform transform,
            List<BlockPlan.Expected> expected) {
        // Keep the Phase 1 canonical representation byte-for-byte stable. Directional state is
        // transformed for comparison, but the hash binds the caller's source blueprint plus its
        // declared transform rather than hashing a derived representation.
        var canonical = new StringBuilder();
        canonical.append(anchor.dimension()).append('|').append(anchor.x()).append('|')
                .append(anchor.y()).append('|').append(anchor.z()).append('|')
                .append(transform.rotation()).append('|').append(transform.mirror());
        for (var entry : expected) {
            canonical.append('\n').append(entry.id()).append('|')
                    .append(entry.rawOffset().x()).append('|').append(entry.rawOffset().y()).append('|')
                    .append(entry.rawOffset().z()).append('|').append(entry.sourceState().block()).append('|')
                    .append(entry.required());
            entry.sourceState().properties().forEach((name, value) ->
                    canonical.append('|').append(name).append('=').append(value));
        }
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> map(Object value, String path) {
        if (!(value instanceof Map<?, ?> input)) {
            throw invalid("invalid_plan_field", path, path + " must be an object");
        }
        return (Map<Object, Object>) input;
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> optionalMap(Object value, String path) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> input)) {
            throw invalid("invalid_plan_field", path, path + " must be an object");
        }
        return (Map<Object, Object>) input;
    }

    private static String string(Object value, String path) {
        if (!(value instanceof String text)) {
            throw invalid("invalid_plan_field", path, path + " must be a string");
        }
        return text;
    }

    private static String optionalString(Object value, String fallback, String path) {
        return value == null ? fallback : string(value, path);
    }

    private static int integer(Object value, String path) {
        if (!(value instanceof Number number)) {
            throw invalid("invalid_plan_field", path, path + " must be an integer");
        }
        var result = number.longValue();
        if (number.doubleValue() != result || result < -30_000_000L || result > 30_000_000L) {
            throw invalid(
                    "invalid_plan_field", path, path + " is outside the supported integer range");
        }
        return (int) result;
    }

    private static int integerInRange(Object value, String path, int minimum, int maximum) {
        int result = integer(value, path);
        if (result < minimum || result > maximum) {
            throw invalid(
                    "invalid_plan_field", path, path + " is outside the supported integer range",
                    Map.of("minimum", minimum, "maximum", maximum, "actual", result));
        }
        return result;
    }

    private static int optionalInteger(Object value, int fallback, String path) {
        return value == null ? fallback : integer(value, path);
    }

    private static boolean optionalBoolean(Object value, boolean fallback, String path) {
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Boolean result)) {
            throw invalid("invalid_plan_field", path, path + " must be a boolean");
        }
        return result;
    }

    private static BlockPlanValidationException invalid(
            String code,
            String path,
            String message) {
        return new BlockPlanValidationException(code, path, message);
    }

    private static BlockPlanValidationException invalid(
            String code,
            String path,
            String message,
            Map<String, Object> details) {
        return new BlockPlanValidationException(code, path, message, details);
    }
}
