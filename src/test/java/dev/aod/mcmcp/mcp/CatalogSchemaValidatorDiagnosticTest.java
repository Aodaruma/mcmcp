package dev.aod.mcmcp.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogSchemaValidatorDiagnosticTest {
    @Test
    void startActionReportsCatalogDerivedFailurePathsWithoutDispatching() throws Exception {
        assertRejected(startRequest(request -> request.getAsJsonObject("program")
                        .getAsJsonArray("body").get(0).getAsJsonObject().remove("id")),
                "program.body[0].id: required");

        assertRejected(startRequest(request -> request.getAsJsonObject("program")
                        .getAsJsonArray("body").get(0).getAsJsonObject()
                        .addProperty("op", "move_to")),
                "program.body[0].op: unknown catalog value");

        assertRejected(startRequest(request -> request.getAsJsonObject("program")
                        .getAsJsonArray("body").get(0).getAsJsonObject()
                        .getAsJsonObject("target").remove("dimension")),
                "program.body[0].target.dimension: required");

        assertRejected(startRequest(request -> request.getAsJsonObject("program")
                        .getAsJsonArray("body").get(0).getAsJsonObject()
                        .getAsJsonObject("target").addProperty("x", 100.5)),
                "program.body[0].target.x: expected integer");
    }

    @Test
    void diagnosticsGeneralizeToOtherToolsAndDoNotReflectUnknownInput() throws Exception {
        JsonObject observation = JsonParser.parseString("""
                {"schema_version":1,"frame_id":"obs-0000000000000000",
                 "kinds":["visible_surface"],"cursor":null,"limit":300}
                """).getAsJsonObject();
        assertRejected("agent_get_observation", observation, "limit: above catalog maximum");

        JsonObject untrusted = new JsonObject();
        untrusted.addProperty("secret-token-should-not-echo", "sensitive-value");
        String message = rejection("agent_get_state", untrusted);
        assertThat(message).isEqualTo("$: unknown property");
        assertThat(message).doesNotContain("secret", "sensitive");
    }

    @Test
    void wrongSmallEnumReportsOnlyBoundedCatalogValues() throws Exception {
        JsonObject request = takeRequest();
        request.getAsJsonObject("program").getAsJsonArray("body").get(0)
                .getAsJsonObject().addProperty("stack_policy", "private-submitted-policy");

        String message = rejection("agent_start_action", request);
        assertThat(message).isEqualTo(
                "program.body[0].stack_policy: expected one of "
                        + "[\"default_components_only\", \"item_id_any_components\"]");
        assertThat(message).doesNotContain("private-submitted-policy");
    }

    @Test
    void reportsMultipleMissingFieldsInStableCatalogOrder() throws Exception {
        JsonObject request = takeRequest();
        JsonObject node = takeNode(request);
        node.remove("target");
        node.remove("expected_block");
        node.remove("item");

        String expected = "program.body[0].target: required; "
                + "program.body[0].expected_block: required; "
                + "program.body[0].item: required";
        assertThat(rejection("agent_start_action", request)).isEqualTo(expected);

        JsonObject reorderedRequest = request.deepCopy();
        JsonObject reordered = new JsonObject();
        reordered.addProperty("minimum_inventory_count", 64);
        reordered.addProperty("stack_policy", "default_components_only");
        reordered.addProperty("op", "take_known_container_stack");
        reordered.addProperty("id", "take");
        reorderedRequest.getAsJsonObject("program").getAsJsonArray("body").set(0, reordered);
        assertThat(rejection("agent_start_action", reorderedRequest)).isEqualTo(expected);
    }

    @Test
    void capsAggregatedFailuresAtFourAndFiveHundredTwelveCharacters() throws Exception {
        JsonObject request = takeRequest();
        JsonObject node = takeNode(request);
        node.remove("target");
        node.remove("expected_block");
        node.remove("item");
        node.remove("stack_policy");
        node.remove("minimum_inventory_count");

        var schema = new McpToolCatalog().inputSchema("agent_start_action");
        var report = CatalogSchemaValidator.failures(schema, request);
        assertThat(report.failures()).hasSize(CatalogSchemaValidator.MAX_REPORTED_FAILURES);
        assertThat(report.summary())
                .hasSizeLessThanOrEqualTo(
                        CatalogSchemaValidator.MAX_FAILURE_SUMMARY_CHARACTERS)
                .isEqualTo("program.body[0].target: required; "
                        + "program.body[0].expected_block: required; "
                        + "program.body[0].item: required; "
                        + "program.body[0].stack_policy: required");
        assertThat(rejection("agent_start_action", request)).isEqualTo(report.summary());
    }

    @Test
    void truncatesOnlyCatalogDerivedSummaryTextAtTheCharacterLimit() {
        String first = "a".repeat(300);
        String second = "b".repeat(300);
        JsonObject schema = JsonParser.parseString("""
                {"type":"object","required":[]}
                """).getAsJsonObject();
        schema.getAsJsonArray("required").add(first);
        schema.getAsJsonArray("required").add(second);

        String summary = CatalogSchemaValidator.failures(schema, new JsonObject()).summary();
        assertThat(summary)
                .hasSize(CatalogSchemaValidator.MAX_FAILURE_SUMMARY_CHARACTERS)
                .startsWith(first + ": required; ")
                .doesNotContain("submitted");
    }

    @Test
    void aggregatesUnknownPropertiesWithoutReflectingNamesOrValues() throws Exception {
        JsonObject request = takeRequest();
        JsonObject node = takeNode(request);
        node.remove("expected_block");
        node.remove("item");
        node.addProperty("secret-token-should-not-echo", "sensitive-value");

        String message = rejection("agent_start_action", request);
        assertThat(message).isEqualTo(
                "program.body[0].expected_block: required; "
                        + "program.body[0].item: required; "
                        + "program.body[0]: unknown property");
        assertThat(message).doesNotContain("secret", "sensitive");
    }

    @Test
    void smallOneOfDiscriminatorReportsCatalogValuesButLargeEnumsStayGeneric() {
        JsonObject discriminatorSchema = JsonParser.parseString("""
                {"oneOf":[
                  {"type":"object","properties":{"kind":{"const":"alpha"}},
                   "required":["kind"]},
                  {"type":"object","properties":{"kind":{"const":"beta"}},
                   "required":["kind"]}
                ]}
                """).getAsJsonObject();
        JsonObject submitted = JsonParser.parseString(
                "{\"kind\":\"private-submitted-kind\"}").getAsJsonObject();
        assertThat(CatalogSchemaValidator.firstFailure(discriminatorSchema, submitted).summary())
                .isEqualTo("kind: expected one of [\"alpha\", \"beta\"]")
                .doesNotContain("private-submitted-kind");

        JsonObject largeEnumSchema = JsonParser.parseString("""
                {"enum":["a","b","c","d","e","f","g","h","i"]}
                """).getAsJsonObject();
        assertThat(CatalogSchemaValidator.firstFailure(
                largeEnumSchema, JsonParser.parseString("\"private-value\"")).summary())
                .isEqualTo("$: not in catalog enum")
                .doesNotContain("private-value");
    }

    private static JsonObject startRequest(java.util.function.Consumer<JsonObject> mutation) {
        JsonObject request = new McpToolCatalog().inputSchema("agent_start_action")
                .getAsJsonArray("examples").get(0).getAsJsonObject().deepCopy();
        mutation.accept(request);
        return request;
    }

    private static JsonObject takeRequest() {
        return new McpToolCatalog().inputSchema("agent_start_action")
                .getAsJsonArray("examples").asList().stream()
                .map(example -> example.getAsJsonObject())
                .filter(example -> example.getAsJsonObject("program").getAsJsonArray("body")
                        .get(0).getAsJsonObject().get("op").getAsString()
                        .equals("take_known_container_stack"))
                .findFirst()
                .orElseThrow()
                .deepCopy();
    }

    private static JsonObject takeNode(JsonObject request) {
        return request.getAsJsonObject("program").getAsJsonArray("body").get(0)
                .getAsJsonObject();
    }

    private static void assertRejected(JsonObject request, String expected) throws Exception {
        assertRejected("agent_start_action", request, expected);
    }

    private static void assertRejected(
            String tool, JsonObject request, String expected) throws Exception {
        assertThat(rejection(tool, request)).isEqualTo(expected);
    }

    private static String rejection(String tool, JsonObject request) throws Exception {
        var dispatches = new AtomicInteger();
        var registry = new McmcpToolRegistry((command, context) -> {
            dispatches.incrementAndGet();
            throw new AssertionError("invalid input must not reach the Minecraft runtime");
        }, Duration.ofSeconds(1));

        JsonObject response = registry.call(tool, request);
        assertThat(response.get("isError").getAsBoolean()).isTrue();
        assertThat(response.has("structuredContent")).isFalse();
        assertThat(dispatches).hasValue(0);
        JsonObject error = JsonParser.parseString(response.getAsJsonArray("content").get(0)
                .getAsJsonObject().get("text").getAsString()).getAsJsonObject();
        assertThat(error.keySet()).containsExactlyInAnyOrder(
                "code", "message", "recoverable");
        assertThat(error.get("code").getAsString()).isEqualTo("INVALID_ARGUMENT");
        assertThat(error.get("recoverable").getAsBoolean()).isTrue();
        return error.get("message").getAsString();
    }
}
