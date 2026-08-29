package dev.aod.mcmcp.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** The small JSON Schema 2020-12 subset used by the checked-in tool catalog. */
final class CatalogSchemaValidator {
    static final int MAX_REPORTED_FAILURES = 4;
    static final int MAX_FAILURE_SUMMARY_CHARACTERS = 512;
    private static final int MAX_ENUM_DIAGNOSTIC_VALUES = 8;
    private static final int MAX_ENUM_VALUE_CODE_POINTS = 64;
    private static final int MAX_ENUM_DIAGNOSTIC_CHARACTERS = 320;
    private static final ValidationFailure INVALID =
            new ValidationFailure("$", "does not match catalog schema", 0);

    private CatalogSchemaValidator() {
    }

    static boolean matches(JsonObject schema, JsonElement value) {
        return validate(schema, schema, value, "", 0, false) == null;
    }

    /**
     * Returns the first useful schema failure without reflecting submitted values.
     * Paths and constraints come only from the trusted catalog.
     */
    static ValidationFailure firstFailure(JsonObject schema, JsonElement value) {
        return validate(schema, schema, value, "", 0, true);
    }

    /**
     * Returns a bounded catalog-ordered report without reflecting submitted values or unknown
     * property names. A discriminated {@code oneOf} is traversed only through the catalog branch
     * selected by its required literal property (for example {@code op}).
     */
    static ValidationReport failures(JsonObject schema, JsonElement value) {
        var failures = new ArrayList<ValidationFailure>(MAX_REPORTED_FAILURES);
        collectFailures(schema, schema, value, "", 0, failures);
        return new ValidationReport(failures);
    }

    private static void collectFailures(
            JsonObject root,
            JsonObject schema,
            JsonElement value,
            String path,
            int depth,
            List<ValidationFailure> failures) {
        if (failures.size() >= MAX_REPORTED_FAILURES) {
            return;
        }
        if (schema.has("$ref")) {
            JsonObject referenced = resolve(root, schema.get("$ref").getAsString());
            if (referenced == null) {
                addFailure(failures, path, "catalog reference unavailable", depth);
                return;
            }
            int before = failures.size();
            collectFailures(root, referenced, value, path, depth, failures);
            if (failures.size() != before) {
                return;
            }
        }
        if (schema.has("oneOf")) {
            JsonArray alternatives = schema.getAsJsonArray("oneOf");
            DiscriminatorSelection selected = selectDiscriminatedBranch(
                    root, alternatives, value, path, depth);
            if (selected.applicable()) {
                if (selected.failure() != null) {
                    addFailure(failures, selected.failure());
                    return;
                }
                int before = failures.size();
                collectFailures(root, selected.branch(), value, path, depth, failures);
                if (failures.size() != before) {
                    return;
                }
            } else {
                var branchFailures = new ArrayList<ValidationFailure>(alternatives.size());
                int matched = 0;
                for (JsonElement branch : alternatives) {
                    ValidationFailure branchFailure = validate(
                            root, branch.getAsJsonObject(), value, path, depth, true);
                    if (branchFailure == null) {
                        matched++;
                    } else {
                        branchFailures.add(branchFailure);
                    }
                }
                if (matched == 0) {
                    addFailure(failures, deepestFailure(branchFailures, path, depth));
                    return;
                }
                if (matched != 1) {
                    addFailure(failures, path, "matches multiple catalog variants", depth);
                    return;
                }
            }
        }
        if (schema.has("type") && !matchesType(schema.get("type"), value)) {
            addFailure(failures, path, expectedTypeReason(schema.get("type")), depth);
            return;
        }
        if (schema.has("const") && !schema.get("const").equals(value)) {
            addFailure(failures, path, "not the required catalog value", depth);
            return;
        }
        if (schema.has("enum") && !contains(schema.getAsJsonArray("enum"), value)) {
            addFailure(failures, path, allowedValuesReason(
                    schema.getAsJsonArray("enum"), "not in catalog enum"), depth);
            return;
        }
        if (value.isJsonObject()) {
            collectObjectFailures(
                    root, schema, value.getAsJsonObject(), path, depth, failures);
        } else if (value.isJsonArray()) {
            collectArrayFailures(root, schema, value.getAsJsonArray(), path, depth, failures);
        } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            addFailure(failures, validateString(
                    schema, value.getAsString(), path, depth, true));
        } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            addFailure(failures, validateNumber(
                    schema, value.getAsBigDecimal(), path, depth, true));
        }
    }

    private static void collectObjectFailures(
            JsonObject root,
            JsonObject schema,
            JsonObject value,
            String path,
            int depth,
            List<ValidationFailure> failures) {
        JsonObject properties = schema.has("properties")
                ? schema.getAsJsonObject("properties") : new JsonObject();
        if (schema.has("required")) {
            for (JsonElement required : schema.getAsJsonArray("required")) {
                String name = required.getAsString();
                if (!value.has(name)) {
                    addFailure(failures, propertyPath(path, name), "required", depth + 1);
                    if (failures.size() >= MAX_REPORTED_FAILURES) {
                        return;
                    }
                }
            }
        }
        if (schema.has("additionalProperties")
                && !schema.get("additionalProperties").getAsBoolean()) {
            for (String name : value.keySet()) {
                if (!properties.has(name)) {
                    // Report at most one generic failure and never reflect the submitted name.
                    addFailure(failures, path, "unknown property", depth);
                    break;
                }
            }
        }
        for (String name : properties.keySet()) {
            if (failures.size() >= MAX_REPORTED_FAILURES) {
                return;
            }
            if (value.has(name)) {
                collectFailures(
                        root, properties.getAsJsonObject(name), value.get(name),
                        propertyPath(path, name), depth + 1, failures);
            }
        }
    }

    private static void collectArrayFailures(
            JsonObject root,
            JsonObject schema,
            JsonArray value,
            String path,
            int depth,
            List<ValidationFailure> failures) {
        if (schema.has("minItems") && value.size() < schema.get("minItems").getAsInt()) {
            addFailure(failures, path, "too few items", depth);
        }
        if (schema.has("maxItems") && value.size() > schema.get("maxItems").getAsInt()) {
            addFailure(failures, path, "too many items", depth);
        }
        if (schema.has("uniqueItems") && schema.get("uniqueItems").getAsBoolean()
                && failures.size() < MAX_REPORTED_FAILURES) {
            var seen = new HashSet<JsonElement>();
            for (JsonElement element : value) {
                if (!seen.add(element)) {
                    addFailure(failures, path, "duplicate items", depth);
                    break;
                }
            }
        }
        if (schema.has("items")) {
            JsonObject itemSchema = schema.getAsJsonObject("items");
            for (int index = 0;
                    index < value.size() && failures.size() < MAX_REPORTED_FAILURES;
                    index++) {
                collectFailures(
                        root, itemSchema, value.get(index), itemPath(path, index),
                        depth + 1, failures);
            }
        }
    }

    private static ValidationFailure validate(
            JsonObject root, JsonObject schema, JsonElement value, String path, int depth,
            boolean report) {
        if (schema.has("$ref")) {
            JsonObject referenced = resolve(root, schema.get("$ref").getAsString());
            if (referenced == null) {
                return failure(path, "catalog reference unavailable", depth, report);
            }
            ValidationFailure referencedFailure = validate(
                    root, referenced, value, path, depth, report);
            if (referencedFailure != null) {
                return referencedFailure;
            }
        }
        if (schema.has("oneOf")) {
            JsonArray alternatives = schema.getAsJsonArray("oneOf");
            List<ValidationFailure> branchFailures = report
                    ? new ArrayList<>(alternatives.size()) : null;
            int matched = 0;
            for (JsonElement branch : alternatives) {
                ValidationFailure branchFailure = validate(
                        root, branch.getAsJsonObject(), value, path, depth, report);
                if (branchFailure == null) {
                    matched++;
                } else if (report) {
                    branchFailures.add(branchFailure);
                }
            }
            if (matched == 0) {
                if (!report) {
                    return INVALID;
                }
                ValidationFailure discriminated = discriminateOneOf(
                        root, alternatives, value, path, depth);
                return discriminated != null
                        ? discriminated : deepestFailure(branchFailures, path, depth);
            }
            if (matched != 1) {
                return failure(path, "matches multiple catalog variants", depth, report);
            }
        }
        if (schema.has("type") && !matchesType(schema.get("type"), value)) {
            return failure(path, expectedTypeReason(schema.get("type")), depth, report);
        }
        if (schema.has("const") && !schema.get("const").equals(value)) {
            return failure(path, "not the required catalog value", depth, report);
        }
        if (schema.has("enum") && !contains(schema.getAsJsonArray("enum"), value)) {
            return failure(path, allowedValuesReason(
                    schema.getAsJsonArray("enum"), "not in catalog enum"), depth, report);
        }
        if (value.isJsonObject()) {
            ValidationFailure objectFailure = validateObject(
                    root, schema, value.getAsJsonObject(), path, depth, report);
            if (objectFailure != null) {
                return objectFailure;
            }
        }
        if (value.isJsonArray()) {
            ValidationFailure arrayFailure = validateArray(
                    root, schema, value.getAsJsonArray(), path, depth, report);
            if (arrayFailure != null) {
                return arrayFailure;
            }
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            ValidationFailure stringFailure = validateString(
                    schema, value.getAsString(), path, depth, report);
            if (stringFailure != null) {
                return stringFailure;
            }
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            return validateNumber(schema, value.getAsBigDecimal(), path, depth, report);
        }
        return null;
    }

    private static ValidationFailure validateObject(
            JsonObject root, JsonObject schema, JsonObject value, String path, int depth,
            boolean report) {
        JsonObject properties = schema.has("properties")
                ? schema.getAsJsonObject("properties") : new JsonObject();
        if (schema.has("required")) {
            for (JsonElement required : schema.getAsJsonArray("required")) {
                String name = required.getAsString();
                if (!value.has(name)) {
                    return failure(report ? propertyPath(path, name) : "",
                            "required", depth + 1, report);
                }
            }
        }
        if (schema.has("additionalProperties") && !schema.get("additionalProperties").getAsBoolean()) {
            for (String name : value.keySet()) {
                if (!properties.has(name)) {
                    // Never reflect an untrusted property name into the public error.
                    return failure(path, "unknown property", depth, report);
                }
            }
        }
        // Catalog order makes the first reported failure stable across JSON member ordering.
        for (String name : properties.keySet()) {
            if (value.has(name)) {
                ValidationFailure propertyFailure = validate(
                        root, properties.getAsJsonObject(name), value.get(name),
                        report ? propertyPath(path, name) : "", depth + 1, report);
                if (propertyFailure != null) {
                    return propertyFailure;
                }
            }
        }
        return null;
    }

    private static ValidationFailure validateArray(
            JsonObject root, JsonObject schema, JsonArray value, String path, int depth,
            boolean report) {
        if (schema.has("minItems") && value.size() < schema.get("minItems").getAsInt()) {
            return failure(path, "too few items", depth, report);
        }
        if (schema.has("maxItems") && value.size() > schema.get("maxItems").getAsInt()) {
            return failure(path, "too many items", depth, report);
        }
        if (schema.has("uniqueItems") && schema.get("uniqueItems").getAsBoolean()) {
            var seen = new HashSet<JsonElement>();
            for (JsonElement element : value) {
                if (!seen.add(element)) {
                    return failure(path, "duplicate items", depth, report);
                }
            }
        }
        if (schema.has("items")) {
            JsonObject itemSchema = schema.getAsJsonObject("items");
            for (int index = 0; index < value.size(); index++) {
                ValidationFailure itemFailure = validate(
                        root, itemSchema, value.get(index),
                        report ? itemPath(path, index) : "", depth + 1, report);
                if (itemFailure != null) {
                    return itemFailure;
                }
            }
        }
        return null;
    }

    private static ValidationFailure validateString(
            JsonObject schema, String value, String path, int depth, boolean report) {
        int length = value.codePointCount(0, value.length());
        if (schema.has("minLength") && length < schema.get("minLength").getAsInt()) {
            return failure(path, "too short", depth, report);
        }
        if (schema.has("maxLength") && length > schema.get("maxLength").getAsInt()) {
            return failure(path, "too long", depth, report);
        }
        if (schema.has("pattern")) {
            try {
                if (!Pattern.compile(schema.get("pattern").getAsString()).matcher(value).find()) {
                    return failure(path, "does not match catalog pattern", depth, report);
                }
            } catch (PatternSyntaxException failure) {
                throw new IllegalStateException("Invalid trusted catalog pattern", failure);
            }
        }
        if (schema.has("format") && "date-time".equals(schema.get("format").getAsString())) {
            try {
                OffsetDateTime.parse(value);
            } catch (DateTimeParseException failure) {
                return failure(path, "invalid date-time", depth, report);
            }
        }
        return null;
    }

    private static ValidationFailure validateNumber(
            JsonObject schema, BigDecimal value, String path, int depth, boolean report) {
        if (schema.has("type") && containsType(schema.get("type"), "integer")
                && value.stripTrailingZeros().scale() > 0) {
            return failure(path, "expected integer", depth, report);
        }
        if (schema.has("minimum") && value.compareTo(schema.get("minimum").getAsBigDecimal()) < 0) {
            return failure(path, "below catalog minimum", depth, report);
        }
        if (schema.has("maximum") && value.compareTo(schema.get("maximum").getAsBigDecimal()) > 0) {
            return failure(path, "above catalog maximum", depth, report);
        }
        return null;
    }

    /**
     * Selects a oneOf branch through a required literal-valued property such as
     * {@code op} or {@code field}. Both the discriminator and its values are read
     * from the catalog; no DSL grammar is duplicated here.
     */
    private static ValidationFailure discriminateOneOf(
            JsonObject root, JsonArray alternatives, JsonElement value, String path, int depth) {
        DiscriminatorSelection selected = selectDiscriminatedBranch(
                root, alternatives, value, path, depth);
        if (!selected.applicable()) {
            return null;
        }
        if (selected.failure() != null) {
            return selected.failure();
        }
        return validate(root, selected.branch(), value, path, depth, true);
    }

    private static DiscriminatorSelection selectDiscriminatedBranch(
            JsonObject root, JsonArray alternatives, JsonElement value, String path, int depth) {
        if (!value.isJsonObject() || alternatives.isEmpty()) {
            return DiscriminatorSelection.notApplicable();
        }
        JsonObject object = value.getAsJsonObject();
        JsonObject first = dereference(root, alternatives.get(0).getAsJsonObject());
        if (first == null || !first.has("properties")) {
            return DiscriminatorSelection.notApplicable();
        }
        for (String property : first.getAsJsonObject("properties").keySet()) {
            List<JsonObject> branches = new ArrayList<>(alternatives.size());
            boolean literalDiscriminator = true;
            for (JsonElement alternative : alternatives) {
                JsonObject branch = dereference(root, alternative.getAsJsonObject());
                if (branch == null || !required(branch, property) || !branch.has("properties")) {
                    literalDiscriminator = false;
                    break;
                }
                JsonObject properties = branch.getAsJsonObject("properties");
                if (!properties.has(property)
                        || literalValues(root, properties.getAsJsonObject(property)) == null) {
                    literalDiscriminator = false;
                    break;
                }
                branches.add(alternative.getAsJsonObject());
            }
            if (!literalDiscriminator) {
                continue;
            }
            String discriminatorPath = propertyPath(path, property);
            if (!object.has(property)) {
                return DiscriminatorSelection.failed(
                        new ValidationFailure(discriminatorPath, "required", depth + 1));
            }
            JsonElement submitted = object.get(property);
            var candidates = new ArrayList<JsonObject>();
            var allowedValues = new JsonArray();
            for (JsonObject branch : branches) {
                JsonObject resolved = dereference(root, branch);
                JsonArray literals = literalValues(
                        root, resolved.getAsJsonObject("properties").getAsJsonObject(property));
                for (JsonElement literal : literals) {
                    if (!contains(allowedValues, literal)) {
                        allowedValues.add(literal);
                    }
                }
                if (contains(literals, submitted)) {
                    candidates.add(branch);
                }
            }
            if (candidates.isEmpty()) {
                return DiscriminatorSelection.failed(new ValidationFailure(
                        discriminatorPath,
                        allowedValuesReason(allowedValues, "unknown catalog value"),
                        depth + 1));
            }
            if (candidates.size() == 1) {
                return DiscriminatorSelection.selected(candidates.get(0));
            }
        }
        return DiscriminatorSelection.notApplicable();
    }

    private static JsonArray literalValues(JsonObject root, JsonObject schema) {
        JsonObject resolved = dereference(root, schema);
        if (resolved == null) {
            return null;
        }
        if (resolved.has("const")) {
            JsonArray values = new JsonArray();
            values.add(resolved.get("const"));
            return values;
        }
        return resolved.has("enum") ? resolved.getAsJsonArray("enum") : null;
    }

    private static JsonObject dereference(JsonObject root, JsonObject schema) {
        JsonObject current = schema;
        var visited = new HashSet<String>();
        while (current.has("$ref")) {
            String reference = current.get("$ref").getAsString();
            if (!visited.add(reference)) {
                return null;
            }
            current = resolve(root, reference);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private static boolean required(JsonObject schema, String property) {
        if (!schema.has("required")) {
            return false;
        }
        for (JsonElement item : schema.getAsJsonArray("required")) {
            if (property.equals(item.getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static ValidationFailure deepestFailure(
            List<ValidationFailure> failures, String path, int depth) {
        ValidationFailure deepest = null;
        for (ValidationFailure candidate : failures) {
            if (deepest == null || candidate.depth() > deepest.depth()) {
                deepest = candidate;
            }
        }
        return deepest != null
                ? deepest : failure(path, "does not match catalog schema", depth, true);
    }

    private static boolean contains(JsonArray values, JsonElement value) {
        for (JsonElement candidate : values) {
            if (candidate.equals(value)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Adds exact recovery guidance only when the trusted catalog enum is small and
     * each value has a bounded primitive representation. Submitted values never
     * participate in this message.
     */
    private static String allowedValuesReason(JsonArray values, String fallback) {
        if (values.isEmpty() || values.size() > MAX_ENUM_DIAGNOSTIC_VALUES) {
            return fallback;
        }
        var rendered = new ArrayList<String>(values.size());
        int characters = "expected one of []".length();
        for (JsonElement value : values) {
            if (!value.isJsonPrimitive()) {
                return fallback;
            }
            var primitive = value.getAsJsonPrimitive();
            if (!primitive.isString() && !primitive.isNumber() && !primitive.isBoolean()) {
                return fallback;
            }
            String raw = primitive.isString() ? primitive.getAsString() : primitive.toString();
            if (raw.codePointCount(0, raw.length()) > MAX_ENUM_VALUE_CODE_POINTS) {
                return fallback;
            }
            String item = primitive.toString();
            characters += item.length() + (rendered.isEmpty() ? 0 : 2);
            if (characters > MAX_ENUM_DIAGNOSTIC_CHARACTERS) {
                return fallback;
            }
            rendered.add(item);
        }
        return "expected one of [" + String.join(", ", rendered) + "]";
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

    private static String expectedTypeReason(JsonElement declared) {
        if (declared.isJsonPrimitive()) {
            return "expected " + declared.getAsString();
        }
        return "expected catalog type";
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

    private static String propertyPath(String path, String property) {
        return path.isEmpty() ? property : path + "." + property;
    }

    private static String itemPath(String path, int index) {
        return (path.isEmpty() ? "$" : path) + "[" + index + "]";
    }

    private static ValidationFailure failure(
            String path, String reason, int depth, boolean report) {
        return report
                ? new ValidationFailure(path.isEmpty() ? "$" : path, reason, depth)
                : INVALID;
    }

    private static void addFailure(
            List<ValidationFailure> failures, String path, String reason, int depth) {
        addFailure(failures, new ValidationFailure(
                path.isEmpty() ? "$" : path, reason, depth));
    }

    private static void addFailure(
            List<ValidationFailure> failures, ValidationFailure failure) {
        if (failure != null && failures.size() < MAX_REPORTED_FAILURES) {
            failures.add(failure);
        }
    }

    private record DiscriminatorSelection(
            boolean applicable, JsonObject branch, ValidationFailure failure) {
        private static DiscriminatorSelection notApplicable() {
            return new DiscriminatorSelection(false, null, null);
        }

        private static DiscriminatorSelection selected(JsonObject branch) {
            return new DiscriminatorSelection(true, branch, null);
        }

        private static DiscriminatorSelection failed(ValidationFailure failure) {
            return new DiscriminatorSelection(true, null, failure);
        }
    }

    record ValidationReport(List<ValidationFailure> failures) {
        ValidationReport {
            failures = List.copyOf(failures);
            if (failures.size() > MAX_REPORTED_FAILURES) {
                throw new IllegalArgumentException("too many validation failures");
            }
        }

        boolean isEmpty() {
            return failures.isEmpty();
        }

        String summary() {
            var summary = new StringBuilder();
            for (ValidationFailure failure : failures) {
                String item = failure.summary();
                String separator = summary.isEmpty() ? "" : "; ";
                int remaining = MAX_FAILURE_SUMMARY_CHARACTERS
                        - summary.length() - separator.length();
                if (remaining <= 0) {
                    break;
                }
                summary.append(separator);
                if (item.length() <= remaining) {
                    summary.append(item);
                } else {
                    summary.append(item, 0, remaining);
                    break;
                }
            }
            return summary.toString();
        }
    }

    record ValidationFailure(String path, String reason, int depth) {
        String summary() {
            return path + ": " + reason;
        }
    }
}
