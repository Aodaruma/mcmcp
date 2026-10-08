package dev.aod.mcmcp.mcp;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class McpV2PrioritySchemaTest {
    private void valid(String tool,String json,boolean expected) {
        assertThat(CatalogSchemaValidator.matches(new McpToolCatalog().inputSchema(tool),JsonParser.parseString(json))).as(tool+":"+json).isEqualTo(expected);
    }
    @Test void resourcesAreRegisteredSeparatelyAndSourceIsExclusive() {
        valid("agent_run_script","{\"source_file\":\"build.mcscript\",\"data_files\":{\"art\":{\"path\":\"art.json\",\"format\":\"json\"}}}",true);
        valid("agent_run_script","{\"source\":\"move();\",\"source_file\":\"build.mcscript\"}",false);
        valid("agent_run_script","{\"source\":\"move();\",\"data_files\":{\"bad/name\":{\"path\":\"art.json\",\"format\":\"json\"}}}",false);
        valid("agent_run_script","{\"source\":\"move();\",\"data_files\":{\"art\":{\"path\":\"art.json\",\"format\":\"javascript\"}}}",false);
    }
    @Test void compoundConditionsAndTypedPlayerPropertiesAreDiscoverable() {
        String prefix="{\"x\":0,\"y\":64,\"z\":0,\"stop_when\":";
        valid("agent_move",prefix+"{\"type\":\"any\",\"conditions\":[{\"type\":\"player\",\"property\":\"air\",\"comparison\":\"at_most\",\"value\":80},{\"type\":\"not\",\"condition\":{\"type\":\"screen\",\"screen\":\"none\"}}]}}",true);
        valid("agent_move",prefix+"{\"type\":\"player\",\"property\":\"air\",\"value\":true}}",false);
    }
    @Test void sortingEquipmentMountAndDismountHaveClosedShapes() {
        valid("agent_inventory","{\"operation\":\"sort\",\"slots\":[9,10,11]}",true);
        valid("agent_inventory","{\"operation\":\"consolidate\",\"slots\":[9,9]}",false);
        valid("agent_inventory","{\"operation\":\"move\",\"source_slot\":9,\"destination_slot\":40,\"item\":\"minecraft:shield\",\"count\":1}",true);
        valid("agent_interact","{\"target\":\"vehicle\",\"operation\":\"dismount\"}",true);
        valid("agent_interact","{\"target\":\"entity\",\"operation\":\"mount\",\"entity_ref\":\"abcdefghijklmnopqrstuvwx\"}",true);
        valid("agent_interact","{\"target\":\"entity\",\"operation\":\"mount\",\"entity_ref\":\"abcdefghijklmnopqrstuvwx\",\"item\":\"minecraft:apple\"}",false);
    }
}
