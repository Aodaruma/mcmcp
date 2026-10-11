package dev.aod.mcmcp.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogSchemaValidatorDiagnosticTest {
    @Test
    void nestedInputFailuresDoNotReachTheRuntime() throws Exception {
        assertRejected("agent_input_sequence", json("""
                {"steps":[{"inputs":["forward"],"hold_ticks":1.5}]}
                """), "steps[0].hold_ticks: expected integer");
        assertRejected("agent_input_sequence", json("""
                {"steps":[{"hold_ticks":1}]}
                """), "steps[0].inputs: required");
        assertRejected("agent_input_sequence", json("""
                {"steps":[{"inputs":["private-key"],"hold_ticks":1}]}
                """), "steps[0].inputs[0]: not in catalog enum");
    }

    @Test
    void diagnosticsGeneralizeToOtherToolsAndDoNotReflectUnknownInput() throws Exception {
        assertRejected("agent_place_block", json("""
                {"x":1,"y":64,"z":2,"block":"minecraft:stone","max_blocks":4097}
                """), "max_blocks: above catalog maximum");
        String message = rejection("agent_input_sequence", json("""
                {"steps":[{"inputs":["forward"],"hold_ticks":1}],
                 "secret-token-should-not-echo":"sensitive-value"}
                """));
        assertThat(message).isEqualTo("$: unknown property");
        assertThat(message).doesNotContain("secret", "sensitive");
    }

    @Test
    void wrongSmallEnumReportsOnlyBoundedCatalogValues() throws Exception {
        String message = rejection("agent_click", json("{\"button\":\"private-button\"}"));
        assertThat(message).isEqualTo("button: expected one of [\"left\", \"right\", \"middle\"]");
        assertThat(message).doesNotContain("private-button");
    }

    @Test
    void reportsMultipleMissingFieldsInStableCatalogOrder() throws Exception {
        String expected = "x: required; y: required; z: required";
        assertThat(rejection("agent_place_block", json("""
                {"block":"minecraft:stone","advance":true}
                """))).isEqualTo(expected);
        assertThat(rejection("agent_place_block", json("""
                {"advance":true,"block":"minecraft:stone"}
                """))).isEqualTo(expected);
    }

    @Test
    void capsAggregatedFailuresAtFourAndFiveHundredTwelveCharacters() {
        JsonObject schema = json("""
                {"type":"object","required":["a","b","c","d","e"]}
                """);
        var report = CatalogSchemaValidator.failures(schema, new JsonObject());
        assertThat(report.failures()).hasSize(CatalogSchemaValidator.MAX_REPORTED_FAILURES);
        assertThat(report.summary())
                .hasSizeLessThanOrEqualTo(CatalogSchemaValidator.MAX_FAILURE_SUMMARY_CHARACTERS)
                .isEqualTo("a: required; b: required; c: required; d: required");
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
        String message = rejection("agent_place_block", json("""
                {"x":1,"y":64,"secret-token-should-not-echo":"sensitive-value"}
                """));
        assertThat(message).isEqualTo("z: required; block: required; $: unknown property");
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

    @Test
    void validatesSchemaValuedAdditionalPropertiesWithoutReflectingMapEntries() {
        JsonObject schema = JsonParser.parseString("""
                {"type":"object","maxProperties":2,
                 "propertyNames":{"pattern":"^[a-z_]+$"},
                 "additionalProperties":{"type":"string","pattern":"^[a-z]+$"}}
                """).getAsJsonObject();
        JsonObject valid = JsonParser.parseString(
                "{\"axis\":\"x\",\"facing\":\"north\"}").getAsJsonObject();
        assertThat(CatalogSchemaValidator.matches(schema, valid)).isTrue();

        JsonObject invalidValue = JsonParser.parseString(
                "{\"private_key\":\"PRIVATE-SUBMITTED-VALUE\"}").getAsJsonObject();
        String valueFailure = CatalogSchemaValidator.firstFailure(schema, invalidValue).summary();
        assertThat(valueFailure).isEqualTo("$: invalid additional property")
                .doesNotContain("private", "PRIVATE", "SUBMITTED");

        JsonObject invalidName = JsonParser.parseString(
                "{\"secret-token\":\"value\"}").getAsJsonObject();
        assertThat(CatalogSchemaValidator.firstFailure(schema, invalidName).summary())
                .isEqualTo("$: invalid property name")
                .doesNotContain("secret-token");

        JsonObject tooMany = JsonParser.parseString(
                "{\"a\":\"x\",\"b\":\"y\",\"c\":\"z\"}").getAsJsonObject();
        assertThat(CatalogSchemaValidator.firstFailure(schema, tooMany).summary())
                .isEqualTo("$: above catalog maximum properties");

        assertThat(CatalogSchemaValidator.failures(schema, valid).failures()).isEmpty();
        assertThat(CatalogSchemaValidator.failures(schema, invalidValue).summary())
                .isEqualTo("$: invalid additional property")
                .doesNotContain("private", "PRIVATE", "SUBMITTED");
        assertThat(CatalogSchemaValidator.failures(schema, invalidName).summary())
                .isEqualTo("$: invalid property name")
                .doesNotContain("secret-token");
    }

    private static JsonObject json(String value) {
        return JsonParser.parseString(value).getAsJsonObject();
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
