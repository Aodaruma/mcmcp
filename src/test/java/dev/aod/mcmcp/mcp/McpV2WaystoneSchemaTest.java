package dev.aod.mcmcp.mcp;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class McpV2WaystoneSchemaTest {
    private boolean valid(String json) {
        return CatalogSchemaValidator.matches(new McpToolCatalog().inputSchema("agent_interact"), JsonParser.parseString(json));
    }
    @Test void warpStoneRequiresInspectionAndRejectsArbitraryProtocolFields() {
        String inspect = """
                {"target":"item","item":"waystones:warp_stone","operation":"inspect_destinations"}
                """.strip();
        assertThat(valid(inspect)).isTrue();
        assertThat(valid(inspect.replace("warp_stone", "return_scroll"))).isFalse();
        assertThat(valid(inspect.replace("inspect_destinations", "select_destination"))).isFalse();
        String select = inspect.replace("inspect_destinations", "select_destination")
                .replace("}", ",\"destination_ref\":\"abcdefghijklmnopqrstuvwx\",\"max_xp_levels\":3}");
        assertThat(valid(select)).isTrue();
        assertThat(valid(select.replace(":3}", ":-1}"))).isFalse();
        assertThat(valid(select.replace("destination_ref", "waystone_uuid"))).isFalse();
        assertThat(valid(select.replace("}", ",\"packet\":\"custom\"}"))).isFalse();
    }
}
