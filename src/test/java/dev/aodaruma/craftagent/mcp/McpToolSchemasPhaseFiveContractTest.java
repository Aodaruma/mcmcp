package dev.aodaruma.craftagent.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolSchemasPhaseFiveContractTest {
    @Test
    void getRecipesDescriptorIsReadOnlyAndUsesTheKnownDisplaySchemas() {
        var tool = CraftAgentToolRegistry.getRecipesTool();

        assertThat(tool.name()).isEqualTo("get_recipes");
        assertThat(tool.annotations().readOnlyHint()).isTrue();
        assertThat(tool.annotations().destructiveHint()).isFalse();
        assertThat(tool.inputSchema()).isEqualTo(McpToolSchemas.getRecipesInput());
        assertThat(tool.outputSchema()).isEqualTo(McpToolSchemas.getRecipesOutput());
        assertThat(tool.description()).contains("client-known RecipeDisplay").contains("incomplete");
    }

    @Test
    void getRecipesAcceptsOnlyClosedBoundedKnownDisplayQueries() {
        Map<String, Object> byItem = McpTestFixtures.fields(
                "query", Map.of("kind", "result_item", "item", "minecraft:hopper"),
                "max_results", 16);
        Map<String, Object> byTag = McpTestFixtures.fields(
                "query", Map.of("kind", "result_tag", "tag", "minecraft:planks"),
                "max_results", 64);

        assertValid(McpToolSchemas.getRecipesInput(), byItem);
        assertValid(McpToolSchemas.getRecipesInput(), byTag);
        assertEveryObjectSchemaControlsAdditionalProperties(McpToolSchemas.getRecipesInput());

        Map<String, Object> unknownQuery = McpTestFixtures.fields(
                "query", Map.of("kind", "all_recipes"),
                "max_results", 16);
        Map<String, Object> extraQueryField = McpTestFixtures.fields(
                "query", McpTestFixtures.fields(
                        "kind", "result_item",
                        "item", "minecraft:hopper",
                        "recipe_id", "minecraft:hopper"),
                "max_results", 16);
        Map<String, Object> unbounded = McpTestFixtures.fields(
                "query", Map.of("kind", "result_item", "item", "minecraft:hopper"),
                "max_results", 65);

        assertInvalid(McpToolSchemas.getRecipesInput(), unknownQuery);
        assertInvalid(McpToolSchemas.getRecipesInput(), extraQueryField);
        assertInvalid(McpToolSchemas.getRecipesInput(), unbounded);
    }

    @Test
    void getRecipesOutputCannotClaimCompleteRecipeManagerCoverage() {
        Map<String, Object> envelope = recipeEnvelope();

        assertValid(McpToolSchemas.getRecipesOutput(), envelope);
        assertEveryObjectSchemaControlsAdditionalProperties(McpToolSchemas.getRecipesOutput());

        Map<String, Object> invalidEnvelope = deepMutableCopy(envelope);
        Map<String, Object> data = object(invalidEnvelope, "data");
        Map<String, Object> coverage = object(data, "coverage");
        coverage.put("source", "recipe_manager");
        coverage.put("complete", true);

        assertInvalid(McpToolSchemas.getRecipesOutput(), invalidEnvelope);
    }

    @Test
    void unsupportedKnownDisplayMayReturnNoResolvedResultAlternatives() {
        Map<String, Object> envelope = recipeEnvelope();
        Map<String, Object> data = object(envelope, "data");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recipes = (List<Map<String, Object>>) data.get("recipes");
        Map<String, Object> recipe = recipes.getFirst();
        recipe.put("supported", false);
        recipe.put("required_screen", "unsupported");
        recipe.put("unsupported_reason", "The client display is not a bounded crafting recipe.");
        object(recipe, "result").put("alternatives", List.of());
        recipe.put("ingredients", List.of());
        recipe.put("shape", null);

        assertValid(McpToolSchemas.getRecipesOutput(), envelope);
    }

    private static Map<String, Object> recipeEnvelope() {
        Map<String, Object> recipe = McpTestFixtures.fields(
                "recipe_ref", "AbCdEfGhIjKlMnOpQrStUvWx",
                "fingerprint", sha256('a'),
                "display_kind", "shaped",
                "required_screen", "crafting_table",
                "supported", true,
                "unsupported_reason", null,
                "result", McpTestFixtures.fields(
                        "deterministic", true,
                        "alternatives", List.of(McpTestFixtures.fields(
                                "item", "minecraft:hopper",
                                "count", 1,
                                "stack_fingerprint", sha256('b')))),
                "ingredients", List.of(McpTestFixtures.fields(
                        "index", 0,
                        "count_per_craft", 5,
                        "alternatives", List.of(Map.of("item", "minecraft:iron_ingot")))),
                "shape", Map.of("width", 3, "height", 3));
        return McpTestFixtures.fields(
                "ok", true,
                "tool", "get_recipes",
                "request_id", "request-test",
                "data", McpTestFixtures.fields(
                        "basis", McpTestFixtures.fields(
                                "world_session_id", "session-test",
                                "client_tick", 200L,
                                "recipe_book_revision", 12L),
                        "coverage", McpTestFixtures.fields(
                                "source", "client_known_recipe_displays",
                                "complete", false,
                                "known", 20,
                                "matched", 2,
                                "returned", 1,
                                "truncated", true),
                        "recipes", new java.util.ArrayList<>(List.of(recipe))));
    }

    private static void assertValid(Map<String, Object> schema, Map<String, Object> value) {
        var validation = McpJsonDefaults.getSchemaValidator().validate(schema, value);
        assertThat(validation.valid()).as("schema validation: %s", validation).isTrue();
    }

    private static void assertInvalid(Map<String, Object> schema, Map<String, Object> value) {
        var validation = McpJsonDefaults.getSchemaValidator().validate(schema, value);
        assertThat(validation.valid()).as("schema unexpectedly accepted %s", value).isFalse();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Map<String, Object> parent, String key) {
        return (Map<String, Object>) parent.get(key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepMutableCopy(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (value instanceof Map<?, ?> nested) {
                result.put(key, deepMutableCopy((Map<String, Object>) nested));
            }
            else if (value instanceof List<?> list) {
                result.put(key, list.stream()
                        .map(element -> element instanceof Map<?, ?> nested
                                ? deepMutableCopy((Map<String, Object>) nested)
                                : element)
                        .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new)));
            }
            else {
                result.put(key, value);
            }
        });
        return result;
    }

    private static void assertEveryObjectSchemaControlsAdditionalProperties(Object value) {
        if (value instanceof Map<?, ?> map) {
            if ("object".equals(map.get("type"))) {
                assertThat(map.containsKey("additionalProperties")).isTrue();
            }
            map.values().forEach(
                    McpToolSchemasPhaseFiveContractTest::assertEveryObjectSchemaControlsAdditionalProperties);
        }
        else if (value instanceof Iterable<?> iterable) {
            iterable.forEach(
                    McpToolSchemasPhaseFiveContractTest::assertEveryObjectSchemaControlsAdditionalProperties);
        }
    }

    private static String sha256(char digit) {
        return "sha256:" + String.valueOf(digit).repeat(64);
    }
}
