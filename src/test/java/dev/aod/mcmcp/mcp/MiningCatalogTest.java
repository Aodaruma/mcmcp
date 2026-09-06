package dev.aod.mcmcp.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.dsl.ActionDslCompiler;
import dev.aod.mcmcp.agent.dsl.ActionDslParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MiningCatalogTest {
    @Test
    void bothMinimalExamplesPassSchemaParserAndExactBudgetCompiler() {
        var schema = new McpToolCatalog().inputSchema("agent_start_action");
        var examples = examples();
        assertThat(examples).hasSize(2);
        for (var example : examples) {
            assertThat(CatalogSchemaValidator.matches(schema, example)).isTrue();
            var request = ActionDslParser.parse(example);
            var compiled = ActionDslCompiler.compile(request, ignored -> Optional.empty(), request.program().capabilities());
            assertThat(compiled.primitiveCostBounds()).hasSize(1);
            assertThat(compiled.worstCaseCost().distanceBlocks()).isEqualTo(request.budget().maxDistanceBlocks());
            assertThat(compiled.worstCaseCost().cameraDegrees()).isEqualTo(request.budget().maxCameraDegrees());
            assertThat(compiled.worstCaseCost().blocksBroken()).isEqualTo(request.budget().maxBlocksBroken());
        }
        assertThat(schema.getAsJsonObject("$defs").getAsJsonObject("excavateTunnelNode")
                .get("description").getAsString()).contains("600*C+100*R+100", "not evidence", "TTL", "unknown-ACK replay");
        assertThat(new McpToolCatalog().listResult().getAsJsonArray("tools")).hasSize(5);
    }

    @Test
    void patternSpecificKeysAreClosedAndInvalidEnumsNeverReflectSubmittedValues() {
        var schema = new McpToolCatalog().inputSchema("agent_start_action");
        var straight = examples().getFirst().deepCopy();
        var node = straight.getAsJsonObject("program").getAsJsonArray("body").get(0).getAsJsonObject();
        node.addProperty("branch_length_blocks", 6);
        // The catalog's allOf is declarative; the small local schema validator delegates
        // conditional cross-field constraints to the closed DSL parser before dispatch.
        assertThatThrownBy(() -> ActionDslParser.parse(straight))
                .isInstanceOf(dev.aod.mcmcp.agent.dsl.ActionDslException.class)
                .hasMessageContaining("straight pattern forbids branch parameters");
        node.remove("branch_length_blocks");
        node.addProperty("pattern", "private-pattern-secret");
        var failure = CatalogSchemaValidator.failures(schema, straight);
        assertThat(failure.summary()).contains("pattern", "straight", "branches").doesNotContain("private-pattern-secret");
        node.addProperty("pattern", "branches");
        node.addProperty("branch_spacing_blocks", 17);
        assertThat(CatalogSchemaValidator.failures(schema, straight).summary())
                .contains("branch_spacing_blocks", "above catalog maximum");
        node.remove("branch_spacing_blocks");
        node.addProperty("private-key-secret", "private-value-secret");
        assertThat(CatalogSchemaValidator.failures(schema, straight).summary())
                .contains("unknown property").doesNotContain("private-key-secret", "private-value-secret");
    }

    @Test
    void schemaUpperBoundsMatchTheDedicatedReservationAndGenericPolicyStaysSmall() {
        var catalog = new McpToolCatalog();
        var inputBudget = catalog.inputSchema("agent_start_action").getAsJsonObject("properties")
                .getAsJsonObject("budget").getAsJsonObject("properties");
        assertThat(inputBudget.getAsJsonObject("max_distance_blocks").get("maximum").getAsDouble())
                .isEqualTo(ActionDslCompiler.MAX_TUNNEL_COST.distanceBlocks());
        assertThat(inputBudget.getAsJsonObject("max_camera_degrees").get("maximum").getAsDouble())
                .isEqualTo(ActionDslCompiler.MAX_TUNNEL_COST.cameraDegrees());
        assertThat(inputBudget.getAsJsonObject("max_blocks_broken").get("maximum").getAsLong())
                .isEqualTo(ActionDslCompiler.MAX_TUNNEL_COST.blocksBroken());
        var policy = catalog.outputSchema("agent_get_state").getAsJsonObject("properties")
                .getAsJsonObject("policy").getAsJsonObject("properties");
        assertThat(policy.getAsJsonObject("max_distance_blocks").get("const").getAsDouble()).isEqualTo(32);
        assertThat(policy.getAsJsonObject("max_camera_degrees").get("const").getAsDouble()).isEqualTo(720);
        assertThat(policy.getAsJsonObject("max_blocks_broken").get("const").getAsLong()).isEqualTo(8);
    }

    private static List<JsonObject> examples() {
        return new McpToolCatalog().inputSchema("agent_start_action").getAsJsonArray("examples").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(example -> example.getAsJsonObject("program").getAsJsonArray("body").get(0)
                        .getAsJsonObject().get("op").getAsString().equals("excavate_tunnel")).toList();
    }
}
