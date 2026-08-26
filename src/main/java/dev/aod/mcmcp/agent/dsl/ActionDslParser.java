package dev.aod.mcmcp.agent.dsl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static dev.aod.mcmcp.agent.dsl.ActionDslException.Code.INVALID_ARGUMENT;
import static dev.aod.mcmcp.agent.dsl.ActionDslException.Code.PROGRAM_TOO_COMPLEX;

/** Strict JSON parser for the closed {@code agent_start_action} Action DSL v1 envelope. */
public final class ActionDslParser {
    private ActionDslParser() {
    }

    public static ActionDsl.Request parse(String source) {
        if (source == null) {
            throw invalid("Action DSL JSON is required");
        }
        if (source.getBytes(StandardCharsets.UTF_8).length > ActionDslValidator.MAX_REQUEST_BYTES) {
            throw new ActionDslException(
                    PROGRAM_TOO_COMPLEX,
                    "Action DSL request exceeds " + ActionDslValidator.MAX_REQUEST_BYTES + " UTF-8 bytes");
        }
        final JsonElement parsed;
        try {
            parsed = JsonParser.parseString(source);
        } catch (JsonParseException | IllegalStateException failure) {
            throw new ActionDslException(INVALID_ARGUMENT, "Action DSL JSON is malformed", failure);
        }
        return parseElement(parsed);
    }

    /** Integration-friendly form; the HTTP layer must additionally enforce the original byte size. */
    public static ActionDsl.Request parse(JsonObject source) {
        if (source == null) {
            throw invalid("Action DSL JSON is required");
        }
        return parse(source.toString());
    }

    private static ActionDsl.Request parseElement(JsonElement source) {
        JsonObject root = object(source, "$", Set.of("schema_version", "program", "budget"),
                Set.of("schema_version", "program", "budget"));
        ActionDsl.Request request = new ActionDsl.Request(
                integer(root.get("schema_version"), "schema_version"),
                program(root.get("program")),
                budget(root.get("budget")));
        ActionDslValidator.validate(request);
        return request;
    }

    private static ActionDsl.Program program(JsonElement value) {
        JsonObject object = object(value, "program",
                Set.of("dsl_version", "name", "capabilities", "body"),
                Set.of("dsl_version", "capabilities", "body"));
        Optional<String> name = object.has("name")
                ? Optional.of(string(object.get("name"), "program.name"))
                : Optional.empty();
        JsonArray capabilityValues = array(object.get("capabilities"), "program.capabilities");
        if (capabilityValues.size() > 3) {
            throw invalid("program.capabilities must contain at most 3 values");
        }
        var capabilities = EnumSet.noneOf(ActionDsl.Capability.class);
        var seen = new HashSet<String>();
        for (int index = 0; index < capabilityValues.size(); index++) {
            String raw = string(capabilityValues.get(index), "program.capabilities[" + index + "]");
            if (!seen.add(raw)) {
                throw invalid("program.capabilities must contain unique values");
            }
            capabilities.add(capability(raw, "program.capabilities[" + index + "]"));
        }
        return new ActionDsl.Program(
                integer(object.get("dsl_version"), "program.dsl_version"),
                name,
                capabilities,
                nodes(object.get("body"), "program.body"));
    }

    private static ActionDsl.Budget budget(JsonElement value) {
        Set<String> fields = Set.of(
                "max_duration_ms", "max_ticks", "max_distance_blocks", "max_camera_degrees",
                "max_interactions", "max_blocks_broken", "max_blocks_placed");
        JsonObject object = object(value, "budget", fields, fields);
        return new ActionDsl.Budget(
                longInteger(object.get("max_duration_ms"), "budget.max_duration_ms"),
                longInteger(object.get("max_ticks"), "budget.max_ticks"),
                number(object.get("max_distance_blocks"), "budget.max_distance_blocks"),
                number(object.get("max_camera_degrees"), "budget.max_camera_degrees"),
                longInteger(object.get("max_interactions"), "budget.max_interactions"),
                longInteger(object.get("max_blocks_broken"), "budget.max_blocks_broken"),
                longInteger(object.get("max_blocks_placed"), "budget.max_blocks_placed"));
    }

    private static List<ActionDsl.Node> nodes(JsonElement value, String path) {
        JsonArray array = array(value, path);
        var nodes = new ArrayList<ActionDsl.Node>(array.size());
        for (int index = 0; index < array.size(); index++) {
            nodes.add(node(array.get(index), path + "[" + index + "]"));
        }
        return List.copyOf(nodes);
    }

    private static ActionDsl.Node node(JsonElement value, String path) {
        JsonObject object = rawObject(value, path);
        String operation = string(required(object, "op", path), path + ".op");
        return switch (operation) {
            case "navigate_to_known" -> navigate(object, path);
            case "face_known_position" -> face(object, path);
            case "break_known_face" -> breakKnownFace(object, path);
            case "wait_ticks" -> waitTicks(object, path);
            case "if" -> conditional(object, path);
            case "repeat" -> repeat(object, path);
            default -> throw invalid("Unknown Action DSL opcode at " + path + ": " + operation);
        };
    }

    private static ActionDsl.NavigateToKnown navigate(JsonObject source, String path) {
        exactKeys(source, path, Set.of("id", "op", "target", "tolerance"),
                Set.of("id", "op", "target", "tolerance"));
        return new ActionDsl.NavigateToKnown(
                string(source.get("id"), path + ".id"),
                position(source.get("target"), path + ".target"),
                number(source.get("tolerance"), path + ".tolerance"));
    }

    private static ActionDsl.FaceKnownPosition face(JsonObject source, String path) {
        exactKeys(source, path, Set.of("id", "op", "target"), Set.of("id", "op", "target"));
        return new ActionDsl.FaceKnownPosition(
                string(source.get("id"), path + ".id"),
                position(source.get("target"), path + ".target"));
    }

    private static ActionDsl.BreakKnownFace breakKnownFace(JsonObject source, String path) {
        exactKeys(source, path,
                Set.of("id", "op", "target", "face", "expected_block", "tool_item"),
                Set.of("id", "op", "target", "face", "expected_block", "tool_item"));
        return new ActionDsl.BreakKnownFace(
                string(source.get("id"), path + ".id"),
                position(source.get("target"), path + ".target"),
                blockFace(string(source.get("face"), path + ".face"), path + ".face"),
                string(source.get("expected_block"), path + ".expected_block"),
                string(source.get("tool_item"), path + ".tool_item"));
    }

    private static ActionDsl.WaitTicks waitTicks(JsonObject source, String path) {
        exactKeys(source, path, Set.of("id", "op", "ticks"), Set.of("id", "op", "ticks"));
        return new ActionDsl.WaitTicks(
                string(source.get("id"), path + ".id"),
                integer(source.get("ticks"), path + ".ticks"));
    }

    private static ActionDsl.If conditional(JsonObject source, String path) {
        exactKeys(source, path, Set.of("id", "op", "condition", "then", "else"),
                Set.of("id", "op", "condition", "then", "else"));
        return new ActionDsl.If(
                string(source.get("id"), path + ".id"),
                predicate(source.get("condition"), path + ".condition"),
                nodes(source.get("then"), path + ".then"),
                nodes(source.get("else"), path + ".else"));
    }

    private static ActionDsl.Repeat repeat(JsonObject source, String path) {
        exactKeys(source, path, Set.of("id", "op", "count", "body"),
                Set.of("id", "op", "count", "body"));
        return new ActionDsl.Repeat(
                string(source.get("id"), path + ".id"),
                integer(source.get("count"), path + ".count"),
                nodes(source.get("body"), path + ".body"));
    }

    private static ActionDsl.Position position(JsonElement value, String path) {
        JsonObject object = object(value, path, Set.of("dimension", "x", "y", "z"),
                Set.of("dimension", "x", "y", "z"));
        return new ActionDsl.Position(
                string(object.get("dimension"), path + ".dimension"),
                integer(object.get("x"), path + ".x"),
                integer(object.get("y"), path + ".y"),
                integer(object.get("z"), path + ".z"));
    }

    private static ActionDsl.Predicate predicate(JsonElement value, String path) {
        JsonObject object = rawObject(value, path);
        boolean hasAll = object.has("all");
        boolean hasAny = object.has("any");
        if (hasAll || hasAny) {
            if (hasAll == hasAny) {
                throw invalid(path + " must contain exactly one of all or any");
            }
            String key = hasAll ? "all" : "any";
            exactKeys(object, path, Set.of(key), Set.of(key));
            JsonArray values = array(object.get(key), path + "." + key);
            var operands = new ArrayList<ActionDsl.AtomicPredicate>(values.size());
            for (int index = 0; index < values.size(); index++) {
                operands.add(atomicPredicate(values.get(index), path + "." + key + "[" + index + "]"));
            }
            return new ActionDsl.LogicalPredicate(
                    hasAll ? ActionDsl.LogicalOperator.ALL : ActionDsl.LogicalOperator.ANY,
                    operands);
        }
        return atomicPredicate(object, path);
    }

    private static ActionDsl.AtomicPredicate atomicPredicate(JsonElement value, String path) {
        JsonObject object = rawObject(value, path);
        String field = string(required(object, "field", path), path + ".field");
        return switch (field) {
            case "health", "hunger", "air" -> numericPredicate(object, field, path);
            case "on_fire", "submerged" -> booleanPredicate(object, field, path);
            case "inventory_count" -> inventoryPredicate(object, path);
            case "has_status_effect" -> statusPredicate(object, path);
            default -> throw invalid("Unsupported snapshot predicate field at " + path + ": " + field);
        };
    }

    private static ActionDsl.NumericPredicate numericPredicate(
            JsonObject source, String field, String path) {
        exactKeys(source, path, Set.of("field", "comparison", "value"),
                Set.of("field", "comparison", "value"));
        return new ActionDsl.NumericPredicate(
                numericField(field),
                comparison(string(source.get("comparison"), path + ".comparison"), path),
                number(source.get("value"), path + ".value"));
    }

    private static ActionDsl.BooleanPredicate booleanPredicate(
            JsonObject source, String field, String path) {
        exactKeys(source, path, Set.of("field", "comparison", "value"),
                Set.of("field", "comparison", "value"));
        ActionDsl.Comparison comparison = comparison(
                string(source.get("comparison"), path + ".comparison"), path);
        if (comparison != ActionDsl.Comparison.EQ) {
            throw invalid(path + ".comparison must be eq");
        }
        return new ActionDsl.BooleanPredicate(
                booleanField(field), comparison, bool(source.get("value"), path + ".value"));
    }

    private static ActionDsl.InventoryPredicate inventoryPredicate(JsonObject source, String path) {
        exactKeys(source, path, Set.of("field", "item", "comparison", "value"),
                Set.of("field", "item", "comparison", "value"));
        return new ActionDsl.InventoryPredicate(
                string(source.get("item"), path + ".item"),
                comparison(string(source.get("comparison"), path + ".comparison"), path),
                integer(source.get("value"), path + ".value"));
    }

    private static ActionDsl.StatusPredicate statusPredicate(JsonObject source, String path) {
        exactKeys(source, path, Set.of("field", "effect", "comparison", "value"),
                Set.of("field", "effect", "comparison", "value"));
        ActionDsl.Comparison comparison = comparison(
                string(source.get("comparison"), path + ".comparison"), path);
        if (comparison != ActionDsl.Comparison.EQ) {
            throw invalid(path + ".comparison must be eq");
        }
        return new ActionDsl.StatusPredicate(
                string(source.get("effect"), path + ".effect"),
                comparison,
                bool(source.get("value"), path + ".value"));
    }

    private static ActionDsl.Capability capability(String value, String path) {
        return switch (value) {
            case "movement" -> ActionDsl.Capability.MOVEMENT;
            case "camera" -> ActionDsl.Capability.CAMERA;
            case "block_break" -> ActionDsl.Capability.BLOCK_BREAK;
            default -> throw invalid("Unsupported capability at " + path + ": " + value);
        };
    }

    private static ActionDsl.BlockFace blockFace(String value, String path) {
        return switch (value) {
            case "down" -> ActionDsl.BlockFace.DOWN;
            case "up" -> ActionDsl.BlockFace.UP;
            case "north" -> ActionDsl.BlockFace.NORTH;
            case "south" -> ActionDsl.BlockFace.SOUTH;
            case "west" -> ActionDsl.BlockFace.WEST;
            case "east" -> ActionDsl.BlockFace.EAST;
            default -> throw invalid("Unsupported block face at " + path + ": " + value);
        };
    }

    private static ActionDsl.NumericField numericField(String value) {
        return switch (value) {
            case "health" -> ActionDsl.NumericField.HEALTH;
            case "hunger" -> ActionDsl.NumericField.HUNGER;
            case "air" -> ActionDsl.NumericField.AIR;
            default -> throw new AssertionError(value);
        };
    }

    private static ActionDsl.BooleanField booleanField(String value) {
        return switch (value) {
            case "on_fire" -> ActionDsl.BooleanField.ON_FIRE;
            case "submerged" -> ActionDsl.BooleanField.SUBMERGED;
            default -> throw new AssertionError(value);
        };
    }

    private static ActionDsl.Comparison comparison(String value, String path) {
        return switch (value) {
            case "lt" -> ActionDsl.Comparison.LT;
            case "lte" -> ActionDsl.Comparison.LTE;
            case "eq" -> ActionDsl.Comparison.EQ;
            case "gte" -> ActionDsl.Comparison.GTE;
            case "gt" -> ActionDsl.Comparison.GT;
            default -> throw invalid("Unsupported comparison at " + path + ": " + value);
        };
    }

    private static JsonObject object(
            JsonElement value,
            String path,
            Set<String> allowed,
            Set<String> required) {
        JsonObject result = rawObject(value, path);
        exactKeys(result, path, allowed, required);
        return result;
    }

    private static JsonObject rawObject(JsonElement value, String path) {
        if (value == null || !value.isJsonObject()) {
            throw invalid(path + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static JsonArray array(JsonElement value, String path) {
        if (value == null || !value.isJsonArray()) {
            throw invalid(path + " must be an array");
        }
        return value.getAsJsonArray();
    }

    private static JsonElement required(JsonObject object, String key, String path) {
        if (!object.has(key)) {
            throw invalid(path + " is missing required field " + key);
        }
        return object.get(key);
    }

    private static void exactKeys(
            JsonObject object,
            String path,
            Set<String> allowed,
            Set<String> required) {
        var unexpected = new HashSet<>(object.keySet());
        unexpected.removeAll(allowed);
        var missing = new HashSet<>(required);
        missing.removeAll(object.keySet());
        if (!unexpected.isEmpty() || !missing.isEmpty()) {
            throw invalid(path + " has unexpected keys " + unexpected + " and missing keys " + missing);
        }
    }

    private static String string(JsonElement value, String path) {
        JsonPrimitive primitive = primitive(value, path);
        if (!primitive.isString()) {
            throw invalid(path + " must be a string");
        }
        return primitive.getAsString();
    }

    private static boolean bool(JsonElement value, String path) {
        JsonPrimitive primitive = primitive(value, path);
        if (!primitive.isBoolean()) {
            throw invalid(path + " must be a boolean");
        }
        return primitive.getAsBoolean();
    }

    private static int integer(JsonElement value, String path) {
        long parsed = longInteger(value, path);
        if (parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE) {
            throw invalid(path + " is outside the integer range");
        }
        return (int) parsed;
    }

    private static long longInteger(JsonElement value, String path) {
        BigDecimal parsed = decimal(value, path);
        try {
            return parsed.longValueExact();
        } catch (ArithmeticException failure) {
            throw new ActionDslException(INVALID_ARGUMENT, path + " must be an exact integer", failure);
        }
    }

    private static double number(JsonElement value, String path) {
        double parsed = decimal(value, path).doubleValue();
        if (!Double.isFinite(parsed)) {
            throw invalid(path + " must be finite");
        }
        return parsed;
    }

    private static BigDecimal decimal(JsonElement value, String path) {
        JsonPrimitive primitive = primitive(value, path);
        if (!primitive.isNumber()) {
            throw invalid(path + " must be a number");
        }
        try {
            return primitive.getAsBigDecimal();
        } catch (NumberFormatException failure) {
            throw new ActionDslException(INVALID_ARGUMENT, path + " must be a finite JSON number", failure);
        }
    }

    private static JsonPrimitive primitive(JsonElement value, String path) {
        if (value == null || !value.isJsonPrimitive()) {
            throw invalid(path + " must be a scalar value");
        }
        return value.getAsJsonPrimitive();
    }

    private static ActionDslException invalid(String message) {
        return new ActionDslException(INVALID_ARGUMENT, message);
    }
}
