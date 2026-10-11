package dev.aod.mcmcp.mcp;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class McpInventorySyncSchemaTest {
    @Test
    void inventoryDiagnosticsAreBoundedAndDoNotExposeRawComponents() {
        var output = new McpToolCatalog().outputSchema("agent_get_state");
        assertThat(output.getAsJsonObject("properties").getAsJsonObject("inventory_sync")
                .get("$ref").getAsString()).isEqualTo("#/$defs/inventory_sync");
        var schema = output.getAsJsonObject("$defs").getAsJsonObject("inventory_sync");
        assertThat(CatalogSchemaValidator.matches(schema,
                JsonParser.parseString("{\"reason\":\"snapshot_missing\",\"last_full_packet_slots\":-1}"))).isTrue();
        var diagnostic = JsonParser.parseString("""
                {"reason":"slot_mismatch","mismatch_count":1,"mismatches_truncated":false,
                 "mismatches":[{"menu_slot":5,"live_item":"minecraft:iron_helmet","live_count":1,
                 "server_item":"minecraft:iron_helmet","server_count":1,"item_and_components_match":false}]}
                """).getAsJsonObject();
        assertThat(CatalogSchemaValidator.matches(schema, diagnostic)).isTrue();
        var first = diagnostic.getAsJsonArray("mismatches").get(0).getAsJsonObject();
        first.addProperty("components", "must not be public");
        assertThat(CatalogSchemaValidator.matches(schema, diagnostic)).isFalse();
        first.remove("components");
        for (int i = 1; i < 9; i++) diagnostic.getAsJsonArray("mismatches").add(first.deepCopy());
        assertThat(CatalogSchemaValidator.matches(schema, diagnostic)).isFalse();
    }
}
