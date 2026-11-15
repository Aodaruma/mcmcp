package dev.aod.mcmcp.mcp;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class McpV2PortalSchemaTest {
    private boolean valid(String json) {
        return CatalogSchemaValidator.matches(new McpToolCatalog().inputSchema("agent_interact"), JsonParser.parseString(json));
    }
    @Test void portalRequiresExplicitClosedBoundedIntent() {
        String request="{\"target\":\"portal\",\"block\":\"minecraft:end_portal\",\"x\":0,\"y\":64,\"z\":0,\"expected_dimension\":\"minecraft:the_end\",\"max_ticks\":200}";
        assertThat(valid(request)).isTrue();
        assertThat(valid(request.replace("minecraft:the_end","minecraft:overworld"))).isTrue();
        assertThat(valid(request.replace("minecraft:end_portal","minecraft:air"))).isFalse();
        assertThat(valid(request.replace("minecraft:the_end","minecraft:the_nether"))).isFalse();
        assertThat(valid(request.replace("200","1201"))).isFalse();
        assertThat(valid(request.replace("200","19"))).isFalse();
        assertThat(valid(request.replace("\"z\":0,", ""))).isFalse();
        assertThat(valid(request.replace("\"max_ticks\":200", "\"force\":true"))).isFalse();
        assertThat(valid(request.replace("minecraft:end_portal","minecraft:nether_portal")
                .replace("minecraft:the_end","minecraft:the_nether").replace("200","600"))).isTrue();
    }
}
