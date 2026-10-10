package dev.aod.mcmcp.mcp;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class McpV2StorageInteractionSchemaTest {
    @Test void stateExperienceDistinguishesUnknownFromZeroAndRequiresItsBasis() {
        var schema = new McpToolCatalog().outputSchema("agent_get_state").getAsJsonObject("$defs")
                .getAsJsonObject("player_experience");
        String xp = """
                {"level":30,"progress":0,"points":1395,"points_to_next_level":112,"points_source":"vanilla_level_and_progress"}
                """.strip();
        assertThat(CatalogSchemaValidator.matches(schema, JsonParser.parseString(xp))).isTrue();
        assertThat(CatalogSchemaValidator.matches(schema, JsonParser.parseString(xp.replace("1395", "null")))).isTrue();
        assertThat(CatalogSchemaValidator.matches(schema, JsonParser.parseString(xp.replace("1395", "-1")))).isFalse();
        assertThat(CatalogSchemaValidator.matches(schema, JsonParser.parseString(xp.replace("1395", "1.5")))).isFalse();
        assertThat(CatalogSchemaValidator.matches(schema, JsonParser.parseString("null"))).isTrue();
    }
    private boolean valid(String json) {
        return CatalogSchemaValidator.matches(new McpToolCatalog().inputSchema("agent_interact"), JsonParser.parseString(json));
    }
    @Test void coordinateStorageHasExplicitOperationsAndExactQuantity() {
        String inspect = """
                {"target":"block","operation":"inspect_storage","x":87,"y":49,"z":1,"advance":false}
                """.strip();
        assertThat(valid(inspect)).isTrue();
        assertThat(valid(inspect.replace("}", ",\"count\":1}"))).isFalse();
        String transfer = inspect.replace("inspect_storage", "transfer_storage")
                .replace("}", ",\"item\":\"minecraft:white_concrete\",\"direction\":\"take\",\"count\":64}");
        assertThat(valid(transfer)).isTrue();
        assertThat(valid(transfer.replace("\"count\":64", "\"count\":897"))).isFalse();
        assertThat(valid(transfer.replace("\"count\":64", "\"count\":0"))).isFalse();
        assertThat(valid(transfer.replace("}", ",\"packet\":\"custom\"}"))).isFalse();
    }
}
