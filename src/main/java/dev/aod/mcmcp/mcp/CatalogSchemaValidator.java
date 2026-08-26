package dev.aod.mcmcp.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** The small JSON Schema 2020-12 subset used by the checked-in tool catalog. */
final class CatalogSchemaValidator {
    private CatalogSchemaValidator() {
    }

    static boolean matches(JsonObject schema, JsonElement value) {
        return matches(schema, schema, value);
    }

    private static boolean matches(JsonObject root, JsonObject schema, JsonElement value) {
        if (schema.has("$ref")) {
            JsonObject referenced = resolve(root, schema.get("$ref").getAsString());
            if (referenced == null || !matches(root, referenced, value)) {
                return false;
            }
        }
        if (schema.has("oneOf")) {
            int matched = 0;
            for (JsonElement branch : schema.getAsJsonArray("oneOf")) {
                if (matches(root, branch.getAsJsonObject(), value)) {
                    matched++;
                }
            }
            if (matched != 1) {
                return false;
            }
        }
        if (schema.has("type") && !matchesType(schema.get("type"), value)) {
            return false;
        }
        if (schema.has("const") && !schema.get("const").equals(value)) {
            return false;
        }
        if (schema.has("enum")) {
            boolean found = false;
            for (JsonElement candidate : schema.getAsJsonArray("enum")) {
                found |= candidate.equals(value);
            }
            if (!found) {
                return false;
            }
        }
        if (value.isJsonObject() && !matchesObject(root, schema, value.getAsJsonObject())) {
            return false;
        }
        if (value.isJsonArray() && !matchesArray(root, schema, value.getAsJsonArray())) {
            return false;
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                && !matchesString(schema, value.getAsString())) {
            return false;
        }
        return !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                || matchesNumber(schema, value.getAsBigDecimal());
    }

    private static boolean matchesObject(JsonObject root, JsonObject schema, JsonObject value) {
        JsonObject properties = schema.has("properties")
                ? schema.getAsJsonObject("properties") : new JsonObject();
        if (schema.has("required")) {
            for (JsonElement required : schema.getAsJsonArray("required")) {
                if (!value.has(required.getAsString())) {
                    return false;
                }
            }
        }
        if (schema.has("additionalProperties") && !schema.get("additionalProperties").getAsBoolean()) {
            for (String name : value.keySet()) {
                if (!properties.has(name)) {
                    return false;
                }
            }
        }
        for (String name : value.keySet()) {
            if (properties.has(name)
                    && !matches(root, properties.getAsJsonObject(name), value.get(name))) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesArray(JsonObject root, JsonObject schema, JsonArray value) {
        if (schema.has("minItems") && value.size() < schema.get("minItems").getAsInt()
                || schema.has("maxItems") && value.size() > schema.get("maxItems").getAsInt()) {
            return false;
        }
        if (schema.has("uniqueItems") && schema.get("uniqueItems").getAsBoolean()) {
            var seen = new HashSet<JsonElement>();
            for (JsonElement element : value) {
                if (!seen.add(element)) {
                    return false;
                }
            }
        }
        if (schema.has("items")) {
            JsonObject itemSchema = schema.getAsJsonObject("items");
            for (JsonElement element : value) {
                if (!matches(root, itemSchema, element)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean matchesString(JsonObject schema, String value) {
        if (schema.has("minLength") && value.codePointCount(0, value.length()) < schema.get("minLength").getAsInt()
                || schema.has("maxLength") && value.codePointCount(0, value.length()) > schema.get("maxLength").getAsInt()) {
            return false;
        }
        if (schema.has("pattern")) {
            try {
                if (!Pattern.compile(schema.get("pattern").getAsString()).matcher(value).find()) {
                    return false;
                }
            } catch (PatternSyntaxException failure) {
                throw new IllegalStateException("Invalid trusted catalog pattern", failure);
            }
        }
        if (schema.has("format") && "date-time".equals(schema.get("format").getAsString())) {
            try {
                OffsetDateTime.parse(value);
            } catch (DateTimeParseException failure) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesNumber(JsonObject schema, BigDecimal value) {
        if (schema.has("type") && containsType(schema.get("type"), "integer")
                && value.stripTrailingZeros().scale() > 0) {
            return false;
        }
        return (!schema.has("minimum") || value.compareTo(schema.get("minimum").getAsBigDecimal()) >= 0)
                && (!schema.has("maximum") || value.compareTo(schema.get("maximum").getAsBigDecimal()) <= 0);
    }

    private static boolean matchesType(JsonElement declared, JsonElement value) {
        if (declared.isJsonArray()) {
            for (JsonElement candidate : declared.getAsJsonArray()) {
                if (matchesTypeName(candidate.getAsString(), value)) {
                    return true;
                }
            }
            return false;
        }
        return matchesTypeName(declared.getAsString(), value);
    }

    private static boolean matchesTypeName(String type, JsonElement value) {
        return switch (type) {
            case "null" -> value.isJsonNull();
            case "object" -> value.isJsonObject();
            case "array" -> value.isJsonArray();
            case "string" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
            case "boolean" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
            case "number" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
            case "integer" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                    && value.getAsBigDecimal().stripTrailingZeros().scale() <= 0;
            default -> false;
        };
    }

    private static boolean containsType(JsonElement declared, String expected) {
        if (declared.isJsonArray()) {
            for (JsonElement item : declared.getAsJsonArray()) {
                if (expected.equals(item.getAsString())) {
                    return true;
                }
            }
            return false;
        }
        return expected.equals(declared.getAsString());
    }

    private static JsonObject resolve(JsonObject root, String reference) {
        if (!reference.startsWith("#/")) {
            return null;
        }
        JsonElement current = root;
        for (String raw : reference.substring(2).split("/")) {
            if (!current.isJsonObject()) {
                return null;
            }
            String name = raw.replace("~1", "/").replace("~0", "~");
            current = current.getAsJsonObject().get(name);
            if (current == null) {
                return null;
            }
        }
        return current.isJsonObject() ? current.getAsJsonObject() : null;
    }
}
