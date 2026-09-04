package dev.aod.mcmcp.agent.dsl;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static dev.aod.mcmcp.agent.dsl.ActionDslException.Code.INVALID_ARGUMENT;

/** Bounded canonical source retained for one accepted Action and its safe clone template. */
public final class ActionDslSource {
    public static final int MAX_CANONICAL_JSON_CHARS = 131_072;
    public static final String MEDIA_TYPE = "application/vnd.mcmcp.action-dsl+json;version=1";

    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Set<String> OPAQUE_REFERENCE_FIELDS = Set.of(
            "operation_ref", "placement_state_ref", "recipe_ref", "fishing_session_ref");

    private final String canonicalJson;
    private final String sha256;
    private final String templateJson;
    private final List<ReferenceRequirement> referenceRequirements;

    private ActionDslSource(
            String canonicalJson,
            String sha256,
            String templateJson,
            List<ReferenceRequirement> referenceRequirements) {
        this.canonicalJson = requireBounded(canonicalJson, "canonical source");
        this.sha256 = Objects.requireNonNull(sha256, "sha256");
        this.templateJson = requireBounded(templateJson, "canonical template");
        this.referenceRequirements = List.copyOf(
                Objects.requireNonNull(referenceRequirements, "referenceRequirements"));
    }

    /**
     * Captures only parser-recognized Action DSL fields. Calling the parser here prevents an
     * accidental future caller from reflecting an unknown secret or hidden runtime field.
     */
    public static ActionDslSource capture(JsonObject source) {
        Objects.requireNonNull(source, "source");
        ActionDslParser.parse(source);

        String canonical = canonicalJson(source, Set.of());
        requireBounded(canonical, "canonical source");
        List<ReferenceRequirement> requirements = referenceRequirements(source);
        var redactedPaths = new LinkedHashSet<String>();
        for (ReferenceRequirement requirement : requirements) {
            redactedPaths.add(requirement.path());
            redactedPaths.addAll(requirement.coupledPaths());
        }
        String template = canonicalJson(source, redactedPaths);
        return new ActionDslSource(canonical, sha256(canonical), template, requirements);
    }

    public String canonicalJson() {
        return canonicalJson;
    }

    public String sha256() {
        return sha256;
    }

    public String templateJson() {
        return templateJson;
    }

    public boolean containsOpaqueReferences() {
        return !referenceRequirements.isEmpty();
    }

    public List<ReferenceRequirement> referenceRequirements() {
        return referenceRequirements;
    }

    public Map<String, Object> sourcePayload() {
        var result = new LinkedHashMap<String, Object>();
        result.put("media_type", MEDIA_TYPE);
        result.put("canonical_json", canonicalJson);
        result.put("sha256", sha256);
        result.put("contains_opaque_refs", containsOpaqueReferences());
        result.put("replayable", !containsOpaqueReferences());
        return Map.copyOf(result);
    }

    public Map<String, Object> templatePayload() {
        var result = new LinkedHashMap<String, Object>();
        result.put("media_type", MEDIA_TYPE);
        result.put("canonical_json", templateJson);
        result.put("ready_for_agent_start_action", !containsOpaqueReferences());
        result.put("blocked_by", containsOpaqueReferences()
                ? "FRESH_REFERENCE_REQUIRED" : null);
        return java.util.Collections.unmodifiableMap(result);
    }

    public List<Map<String, Object>> referenceRequirementPayload() {
        return referenceRequirements.stream().map(ReferenceRequirement::toMap).toList();
    }

    private static List<ReferenceRequirement> referenceRequirements(JsonObject source) {
        var result = new ArrayList<ReferenceRequirement>();
        collectReferences(source, "", result);
        return List.copyOf(result);
    }

    private static void collectReferences(
            JsonElement value, String path, List<ReferenceRequirement> result) {
        if (value == null || value.isJsonNull() || value.isJsonPrimitive()) {
            return;
        }
        if (value.isJsonArray()) {
            JsonArray array = value.getAsJsonArray();
            for (int index = 0; index < array.size(); index++) {
                collectReferences(array.get(index), path + "/" + index, result);
            }
            return;
        }

        JsonObject object = value.getAsJsonObject();
        for (String name : object.keySet().stream().sorted().toList()) {
            String childPath = path + "/" + pointerToken(name);
            if (OPAQUE_REFERENCE_FIELDS.contains(name)) {
                result.add(requirement(name, childPath, object, path));
            }
            collectReferences(object.get(name), childPath, result);
        }
    }

    private static ReferenceRequirement requirement(
            String field, String path, JsonObject object, String objectPath) {
        return switch (field) {
            case "operation_ref" -> new ReferenceRequirement(
                    path,
                    "operation_ref",
                    List.of(),
                    "agent_get_state",
                    "/known_menu/operations/*/operation_ref");
            case "placement_state_ref" -> new ReferenceRequirement(
                    path,
                    "placement_state_ref",
                    List.of(),
                    "agent_get_observation",
                    "/records/*/placement_state_ref");
            case "recipe_ref" -> {
                String fingerprintPath = object.has("recipe_fingerprint")
                        ? objectPath + "/recipe_fingerprint" : null;
                yield new ReferenceRequirement(
                        path,
                        "recipe_ref",
                        fingerprintPath == null ? List.of() : List.of(fingerprintPath),
                        "agent_get_state",
                        "/recipe_query/recipes/*/{recipe_ref,fingerprint}");
            }
            case "fishing_session_ref" -> new ReferenceRequirement(
                    path,
                    "fishing_session_ref",
                    List.of(),
                    "agent_get_action",
                    "/effects/*/observed_after/fishing_session_ref");
            default -> throw new IllegalArgumentException("Unknown opaque reference field");
        };
    }

    private static String canonicalJson(JsonElement value, Set<String> redactedPaths) {
        var output = new StringBuilder();
        appendCanonical(value, "", redactedPaths, output);
        return output.toString();
    }

    private static void appendCanonical(
            JsonElement value,
            String path,
            Set<String> redactedPaths,
            StringBuilder output) {
        if (redactedPaths.contains(path) || value == null || value.isJsonNull()) {
            output.append("null");
            return;
        }
        if (value.isJsonPrimitive()) {
            appendPrimitive(value.getAsJsonPrimitive(), output);
            return;
        }
        if (value.isJsonArray()) {
            output.append('[');
            List<JsonElement> values = new ArrayList<>();
            value.getAsJsonArray().forEach(values::add);
            if ("/program/capabilities".equals(path)) {
                values.sort(Comparator.comparing(JsonElement::getAsString));
            }
            for (int index = 0; index < values.size(); index++) {
                if (index > 0) output.append(',');
                appendCanonical(values.get(index), path + "/" + index, redactedPaths, output);
            }
            output.append(']');
            return;
        }

        output.append('{');
        JsonObject object = value.getAsJsonObject();
        List<String> keys = object.keySet().stream().sorted().toList();
        for (int index = 0; index < keys.size(); index++) {
            if (index > 0) output.append(',');
            String key = keys.get(index);
            output.append(JSON.toJson(key)).append(':');
            appendCanonical(
                    object.get(key), path + "/" + pointerToken(key), redactedPaths, output);
        }
        output.append('}');
    }

    private static void appendPrimitive(JsonPrimitive primitive, StringBuilder output) {
        if (primitive.isString()) {
            output.append(JSON.toJson(primitive.getAsString()));
        } else if (primitive.isBoolean()) {
            output.append(primitive.getAsBoolean());
        } else if (primitive.isNumber()) {
            BigDecimal decimal;
            try {
                decimal = primitive.getAsBigDecimal();
            } catch (NumberFormatException failure) {
                throw new ActionDslException(INVALID_ARGUMENT, "Action source number is invalid", failure);
            }
            if (decimal.signum() == 0) {
                output.append('0');
            } else {
                output.append(decimal.stripTrailingZeros().toPlainString());
            }
        } else {
            throw new ActionDslException(INVALID_ARGUMENT, "Action source primitive is invalid");
        }
    }

    private static String sha256(String source) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String pointerToken(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static String requireBounded(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.length() > MAX_CANONICAL_JSON_CHARS) {
            throw new ActionDslException(
                    INVALID_ARGUMENT,
                    name + " exceeds " + MAX_CANONICAL_JSON_CHARS + " characters");
        }
        return value;
    }

    public record ReferenceRequirement(
            String path,
            String kind,
            List<String> coupledPaths,
            String refreshTool,
            String sourcePath) {
        public ReferenceRequirement {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(kind, "kind");
            coupledPaths = List.copyOf(Objects.requireNonNull(coupledPaths, "coupledPaths"));
            Objects.requireNonNull(refreshTool, "refreshTool");
            Objects.requireNonNull(sourcePath, "sourcePath");
        }

        public Map<String, Object> toMap() {
            var result = new LinkedHashMap<String, Object>();
            result.put("path", path);
            result.put("kind", kind);
            result.put("coupled_paths", coupledPaths);
            result.put("refresh_tool", refreshTool);
            result.put("source_path", sourcePath);
            result.put("status", "refresh_required");
            return Map.copyOf(result);
        }
    }
}
