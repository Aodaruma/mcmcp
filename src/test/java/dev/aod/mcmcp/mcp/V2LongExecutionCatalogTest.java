package dev.aod.mcmcp.mcp;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class V2LongExecutionCatalogTest {
    @Test
    void longProgressAndRealTimeStatusMatchThePublicOutputSchema() {
        assertThat(CatalogSchemaValidator.matches(new McpToolCatalog().outputSchema("agent_get_action"),
                JsonParser.parseString("""
                {"schema_version":2,"action_id":"00000000-0000-4000-8000-000000000001",
                 "kind":"input_sequence","state":"running","progress":{
                 "completed_operations":1500,"max_operations":1728000,
                 "elapsed_seconds":75.0,"remaining_seconds":86325.0,"max_duration_seconds":86400.0},
                 "cancel_requested":false,"failure":null,"stop_reason":null,
                 "result":{"phase":"holding"}}
                """))).isTrue();
    }

    @Test
    void timedAndSequenceFormsStayExclusiveAndEveryButtonSupportsLongInput() {
        var catalog=new McpToolCatalog();
        var schema=catalog.inputSchema("agent_input_sequence");
        for(String input:new String[]{"forward","attack","use","pick"}) {
            assertThat(CatalogSchemaValidator.matches(schema,JsonParser.parseString(
                    "{\"inputs\":[\""+input+"\"],\"duration_seconds\":86400}"))).isTrue();
        }
        assertThat(CatalogSchemaValidator.matches(schema,JsonParser.parseString("""
                {"inputs":["use"],"duration_seconds":86400,"item":"minecraft:black_concrete_powder","refill_wait_seconds":30}
                """))).isTrue();
        assertThat(CatalogSchemaValidator.matches(schema,JsonParser.parseString("""
                {"steps":[{"inputs":["attack"],"hold_ticks":1728000}]}
                """))).isTrue();
        for(String invalid:new String[]{
                "{\"inputs\":[\"use\"],\"duration_seconds\":86401}",
                "{\"inputs\":[\"attack\"],\"duration_seconds\":1,\"item\":\"minecraft:stone\"}",
                "{\"inputs\":[\"use\"],\"duration_seconds\":1,\"steps\":[{\"inputs\":[\"use\"],\"hold_ticks\":1}]}"}) {
            assertThat(CatalogSchemaValidator.matches(schema,JsonParser.parseString(invalid))).isFalse();
        }
    }
}
