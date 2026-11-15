package dev.aod.mcmcp.mcp;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.*;

class McpBuildToolTest {
    @Test void previewHasNoDeliveryReceiptAndExecutionUsesTheCommonActionLifecycle() throws Exception {
        var calls=new ArrayList<McpRuntimePort.RuntimeCommand>();
        var id=UUID.randomUUID();
        var registry=new McmcpToolRegistry((command,context)->{
            calls.add(command);
            Map<String,Object> data=command instanceof McpRuntimePort.Build build
                    && !"execute".equals(build.arguments().get("operation"))
                    ? Map.of("schema_version",2,"operation","preview","blueprint",Map.of("total_entries",8))
                    : Map.of("schema_version",2,"action_id",id.toString(),"state","queued");
            return CompletableFuture.completedFuture(McpRuntimePort.RuntimeReply.success(data));
        },Duration.ofSeconds(1));
        var args=JsonParser.parseString("{\"blueprint_file\":\"art.json\",\"dimension\":\"minecraft:overworld\"}").getAsJsonObject();
        var preview=registry.prepareCall("agent_build",args);
        assertThat(preview.response().get("isError").getAsBoolean()).isFalse();
        assertThat(preview.deliveryReceipt()).isNull();
        assertThat(calls.getFirst()).isInstanceOf(McpRuntimePort.Build.class);
        args.addProperty("operation","execute");
        assertThat(registry.prepareCall("agent_build",args).response().get("isError").getAsBoolean()).isTrue();
        assertThat(calls).hasSize(1);
        args.addProperty("world_session_id",UUID.randomUUID().toString());args.addProperty("plan_sha256","a".repeat(64));
        var started=registry.prepareCall("agent_build",args);
        assertThat(started.response().get("isError").getAsBoolean()).isFalse();
        assertThat(started.deliveryReceipt()).isInstanceOf(McpRuntimePort.ActionDeliveryReceipt.class);
        assertThat(McmcpToolRegistry.completionWaitMillis("agent_build",args,started)).isZero();
        registry.confirmDelivery(started);
        assertThat(calls.getLast()).isInstanceOf(McpRuntimePort.ConfirmActionDelivery.class);
    }
    @Test void schemaRejectsUnboundedOrUnknownConstructionRequests() {
        var schema=new McpToolCatalog().inputSchema("agent_build");
        String base="\"blueprint_file\":\"art.json\",\"dimension\":\"minecraft:overworld\"";
        for(String addition:List.of("\"max_blocks\":65537","\"step_max_distance\":4097","\"source\":\"place();\"","\"operation\":\"run\"","\"origin\":{\"x\":0}"))
            assertThat(CatalogSchemaValidator.matches(schema,JsonParser.parseString("{"+base+","+addition+"}"))).as(addition).isFalse();
    }
}
