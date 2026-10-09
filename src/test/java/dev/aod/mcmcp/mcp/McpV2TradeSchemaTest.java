package dev.aod.mcmcp.mcp;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class McpV2TradeSchemaTest {
    private boolean valid(String json) {
        return CatalogSchemaValidator.matches(new McpToolCatalog().inputSchema("agent_interact"), JsonParser.parseString(json));
    }
    @Test void inspectionAndBoundedTradeHaveDistinctClosedShapes() {
        String inspect = "{\"target\":\"entity\",\"operation\":\"inspect_offers\",\"entity_ref\":\"abcdefghijklmnopqrstuvwx\"}";
        assertThat(valid(inspect)).isTrue();
        assertThat(valid(inspect.replace("inspect_offers", "trade"))).isFalse();
        String trade = inspect.replace("inspect_offers", "trade").replace("}",
                ",\"offer_ref\":\"ABCDEFGHIJKLMNOPQRSTUVWX\",\"count\":3,\"max_total_cost\":[{\"item\":\"minecraft:emerald\",\"count\":9}]}");
        assertThat(valid(trade)).isTrue();
        assertThat(valid(trade.replace("\"count\":3", "\"count\":0"))).isFalse();
        assertThat(valid(trade.replace("\"count\":3", "\"count\":4097"))).isFalse();
        assertThat(valid(trade.replace("\"target\":\"entity\"", "\"target\":\"block\""))).isFalse();
        assertThat(valid(trade.replace("\"count\":3", "\"count\":3,\"slot\":2"))).isFalse();
    }
}
