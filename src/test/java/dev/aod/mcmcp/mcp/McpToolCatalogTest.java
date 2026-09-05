package dev.aod.mcmcp.mcp;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.aod.mcmcp.agent.action.AgentActionStore;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner;
import dev.aod.mcmcp.agent.dsl.ActionDslCompiler;
import dev.aod.mcmcp.agent.dsl.ActionDslOperationManifest;
import dev.aod.mcmcp.brewing.StandardPotionPolicy;
import dev.aod.mcmcp.runtime.McmcpRuntimeContractTestAccess;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolCatalogTest {
    @Test
    void validConstructionExamplePassesAggregateSchemaAndReachesDispatch() throws Exception {
        var schema = new McpToolCatalog().inputSchema("agent_start_action");
        var example = schema
                .getAsJsonArray("examples").asList().stream()
                .map(value -> value.getAsJsonObject())
                .filter(value -> value.getAsJsonObject("program").get("name").getAsString()
                        .equals("copy_known_oak_beam"))
                .findFirst().orElseThrow();
        var commands = new ArrayList<McpRuntimePort.RuntimeCommand>();
        var registry = new McmcpToolRegistry((command, context) -> {
            commands.add(command);
            return CompletableFuture.completedFuture(
                    McpRuntimePort.RuntimeReply.success(toolResult(command)));
        }, Duration.ofSeconds(1));

        var prepared = registry.prepareCall("agent_start_action", example);

        assertThat(commands).singleElement().isInstanceOf(McpRuntimePort.StartAction.class);
        assertThat(schema.getAsJsonObject("$defs").getAsJsonObject("applyKnownBlockPlanNode")
                .get("description").getAsString())
                .contains("adopted without input or placement budget")
                .contains("later fresh exact verification of every entry");
        var clear = schema.getAsJsonObject("$defs")
                .getAsJsonObject("clearKnownBlockPlanNode");
        assertThat(clear.getAsJsonObject("properties").getAsJsonObject("op")
                .get("const").getAsString()).isEqualTo("clear_known_block_plan");
        assertThat(clear.get("description").getAsString())
                .contains("later fresh exact air observation")
                .contains("separate Action after reobservation");
        registry.abandonDelivery(prepared);
        assertThat(commands.getLast()).isInstanceOf(McpRuntimePort.AbandonActionDelivery.class);
    }

    @Test
    void startReceiptIsConfirmedOnlyAfterDeliveryOrAbandonedOnWriteFailure() throws Exception {
        var commands = new ArrayList<McpRuntimePort.RuntimeCommand>();
        var registry = new McmcpToolRegistry((command, context) -> {
            commands.add(command);
            return CompletableFuture.completedFuture(McpRuntimePort.RuntimeReply.success(
                    toolResult(command)));
        }, Duration.ofSeconds(1));
        var start = new McpToolCatalog().inputSchema("agent_start_action")
                .getAsJsonArray("examples").get(0).getAsJsonObject();

        var delivered = registry.prepareCall("agent_start_action", start);
        assertThat(commands).singleElement().isInstanceOf(McpRuntimePort.StartAction.class);
        registry.confirmDelivery(delivered);
        assertThat(commands.getLast()).isInstanceOf(McpRuntimePort.ConfirmActionDelivery.class);

        commands.clear();
        var lost = registry.prepareCall("agent_start_action", start);
        registry.abandonDelivery(lost);
        assertThat(commands).extracting(Object::getClass).containsExactly(
                McpRuntimePort.StartAction.class,
                McpRuntimePort.AbandonActionDelivery.class);
    }

    @Test
    void deliveryConfirmationAndAbandonmentRetainTheOriginalEvaluationFence()
            throws Exception {
        var contexts = new ArrayList<RuntimeCallContext>();
        var registry = new McmcpToolRegistry((command, context) -> {
            contexts.add(context);
            return CompletableFuture.completedFuture(McpRuntimePort.RuntimeReply.success(
                    toolResult(command)));
        }, Duration.ofSeconds(1));
        var expectation = RuntimeCallContext.EvaluationLeaseExpectation.active(
                UUID.randomUUID(), 7L);
        var start = new McpToolCatalog().inputSchema("agent_start_action")
                .getAsJsonArray("examples").get(0).getAsJsonObject();

        var delivered = registry.prepareCall("agent_start_action", start, expectation);
        registry.confirmDelivery(delivered);
        assertThat(contexts)
                .extracting(RuntimeCallContext::evaluationLeaseExpectation)
                .containsExactly(expectation, expectation);

        contexts.clear();
        var lost = registry.prepareCall("agent_start_action", start, expectation);
        registry.abandonDelivery(lost);
        assertThat(contexts)
                .extracting(RuntimeCallContext::evaluationLeaseExpectation)
                .containsExactly(expectation, expectation);
    }

    @Test
    void observationReceiptIsConfirmedOnlyAfterDeliveryOrAbandonedOnWriteFailure()
            throws Exception {
        var commands = new ArrayList<McpRuntimePort.RuntimeCommand>();
        UUID receiptId = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
        var registry = new McmcpToolRegistry((command, context) -> {
            commands.add(command);
            var result = toolResult(command);
            return CompletableFuture.completedFuture(
                    command instanceof McpRuntimePort.GetObservation
                            ? McpRuntimePort.RuntimeReply.success(
                                    result,
                                    new McpRuntimePort.ObservationDeliveryReceipt(receiptId))
                            : McpRuntimePort.RuntimeReply.success(result));
        }, Duration.ofSeconds(1));
        var observation = JsonParser.parseString("""
                {"schema_version":1,"frame_id":"obs-0000000000000000",
                 "kinds":["visible_surface"],"cursor":null,"limit":1}
                """).getAsJsonObject();

        var delivered = registry.prepareCall("agent_get_observation", observation);
        registry.confirmDelivery(delivered);
        assertThat(commands).extracting(Object::getClass).containsExactly(
                McpRuntimePort.GetObservation.class,
                McpRuntimePort.ConfirmObservationDelivery.class);

        commands.clear();
        var lost = registry.prepareCall("agent_get_observation", observation);
        registry.abandonDelivery(lost);
        assertThat(commands).extracting(Object::getClass).containsExactly(
                McpRuntimePort.GetObservation.class,
                McpRuntimePort.AbandonObservationDelivery.class);
    }

    @Test
    void observationConfirmationWaitsForRuntimeActivationBeforeReturning() throws Exception {
        UUID receiptId = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
        var confirmationSubmitted = new CountDownLatch(1);
        var confirmation = new CompletableFuture<McpRuntimePort.RuntimeReply>();
        var registry = new McmcpToolRegistry((command, context) -> {
            if (command instanceof McpRuntimePort.GetObservation) {
                return CompletableFuture.completedFuture(McpRuntimePort.RuntimeReply.success(
                        toolResult(command),
                        new McpRuntimePort.ObservationDeliveryReceipt(receiptId)));
            }
            if (command instanceof McpRuntimePort.ConfirmObservationDelivery) {
                confirmationSubmitted.countDown();
                return confirmation;
            }
            return CompletableFuture.completedFuture(
                    McpRuntimePort.RuntimeReply.success(toolResult(command)));
        }, Duration.ofSeconds(1));
        var observation = JsonParser.parseString("""
                {"schema_version":1,"frame_id":"obs-0000000000000000",
                 "kinds":["visible_surface"],"cursor":null,"limit":1}
                """).getAsJsonObject();
        var delivered = registry.prepareCall("agent_get_observation", observation);

        CompletableFuture<Void> awaiting = CompletableFuture.runAsync(
                () -> registry.confirmDelivery(delivered));
        assertThat(confirmationSubmitted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(awaiting.isDone()).isFalse();

        confirmation.complete(McpRuntimePort.RuntimeReply.success(Map.of("confirmed", true)));
        awaiting.get(1, TimeUnit.SECONDS);
    }

    @Test
    void shippedCatalogIsTheNormativeFileAndHasTheFixedFiveTools() throws Exception {
        var file = JsonParser.parseReader(Files.newBufferedReader(
                Path.of(System.getProperty("mcmcp.projectDir"), "docs", "MCMCP_MCP_Tool_Catalog.json"),
                StandardCharsets.UTF_8));
        try (var stream = getClass().getResourceAsStream(McpToolCatalog.RESOURCE);
             var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            assertThat(JsonParser.parseReader(reader)).isEqualTo(file);
        }

        var catalog = new McpToolCatalog();
        List<String> names = catalog.listResult().getAsJsonArray("tools").asList().stream()
                .map(tool -> tool.getAsJsonObject().get("name").getAsString())
                .toList();
        assertThat(names).containsExactlyElementsOf(McpToolCatalog.REQUIRED_NAMES);
        String stateDescription = catalog.listResult().getAsJsonArray("tools").get(0)
                .getAsJsonObject().get("description").getAsString();
        assertThat(stateDescription)
                .contains("Sophisticated Backpacks 3.25.90")
                .contains("version/hash/class-fixed")
                .contains("Open upgrade/extra slots")
                .contains("inaccessible or oversized stacks");
    }

    @Test
    void startActionDescriptionExposesTheClosedGrammarAndAValidExample() {
        var catalog = new McpToolCatalog();
        var startTool = catalog.listResult().getAsJsonArray("tools").asList().stream()
                .map(element -> element.getAsJsonObject())
                .filter(tool -> tool.get("name").getAsString().equals("agent_start_action"))
                .findFirst()
                .orElseThrow();
        var schema = startTool.getAsJsonObject("inputSchema");
        String description = startTool.get("description").getAsString();

        assertThat(description)
                .contains("Every node needs a unique id")
                .contains("Copy traversability.navigation_target verbatim")
                .contains("never derive, floor, round")
                .contains("collect_visible_item_batch for 2..8 current drops")
                .contains("navigate_to_known:{id,op,target,tolerance}")
                .contains("face_known_block_face:{id,op,target,face,expected_block}")
                .contains("take_known_container_stack:{id,op,target,expected_block,item,stack_policy,minimum_inventory_count}")
                .contains("store_known_container_stack:{id,op,target,expected_block,item,stack_policy,minimum_container_count}")
                .contains("craft_known_recipe:{id,op,recipe_ref,recipe_fingerprint,goal:")
                .contains("smelt_known_recipe:{id,op,recipe_ref,recipe_fingerprint,goal:")
                .contains("operate_known_menu:{id,op,operation_ref}")
                .contains("till_known_batch:{id,op,targets:[position],expected_block,hoe_item}")
                .contains("plant_known_wheat_batch:{id,op,targets:[{target,support}],seed_item}")
                .contains("harvest_known_wheat_batch:{id,op,targets:[position]}")
                .contains("apply_known_redstone_spec:{id,op,anchor,rotation,components:")
                .contains("collect_visible_item_batch:{id,op,targets:[{displayed_item,target}]}")
                .contains("100*(component_count+2) + 3*settle_ticks ticks")
                .contains("up to the 720 camera-degree policy maximum")
                .contains("split it into smaller batches instead of reordering at runtime")
                .contains("face_known_position, face_known_block_face")
                .contains("face_known_position/face_known_block_face=camera")
                .contains("operate_known_menu=inventory_transfer")
                .contains("For operate_known_menu reserve at least 30000 ms, 600 ticks, and 1 interaction")
                .contains("operate_kill_zone=entity_attack")
                .contains("max_operation_duration_ticks+10")
                .contains("accepted for one top-level operate_kill_zone or operate_known_cobblestone_generator")
                .contains("use the exact inputSchema fields and no aliases");

        var definitions = schema.getAsJsonObject("$defs");
        var nodeAlternatives = definitions.getAsJsonObject("node").getAsJsonArray("oneOf");
        var opcodes = nodeAlternatives.asList().stream()
                .map(reference -> reference.getAsJsonObject().get("$ref").getAsString())
                .map(reference -> reference.substring(reference.lastIndexOf('/') + 1))
                .map(definitions::getAsJsonObject)
                .map(node -> node.getAsJsonObject("properties")
                        .getAsJsonObject("op").get("const").getAsString())
                .toList();
        assertThat(description + definitions.getAsJsonObject("operateKillZoneNode")
                        .get("description").getAsString()
                        + definitions.getAsJsonObject("holdBoundedInputsNode")
                        .get("description").getAsString())
                .contains(opcodes.toArray(String[]::new));

        assertThat(schema.getAsJsonArray("examples").asList())
                .allSatisfy(example -> assertThat(CatalogSchemaValidator.matches(schema, example))
                        .isTrue());
        assertThat(schema.getAsJsonArray("examples").asList().stream()
                .map(element -> element.getAsJsonObject())
                .map(example -> example.getAsJsonObject("program").getAsJsonArray("body")
                        .get(0).getAsJsonObject().get("op").getAsString()))
                .contains("take_known_container_stack", "operate_known_menu");

        assertThat(description)
                .contains("take/store stack_policy is exactly default_components_only")
                .contains("or item_id_any_components")
                .contains("craft/smelt goal.stack_policy")
                .contains("smelt fuel.stack_policy are exactly default_components_only");
    }

    @Test
    void catalogAdmitsOnlyTheClosedKnownBlockFaceCameraNodeShape() {
        var schema = new McpToolCatalog().inputSchema("agent_start_action");
        var request = JsonParser.parseString("""
                {
                  "schema_version":1,
                  "program":{
                    "dsl_version":1,
                    "capabilities":["camera"],
                    "body":[{
                      "id":"face_support",
                      "op":"face_known_block_face",
                      "target":{"dimension":"minecraft:overworld","x":2,"y":64,"z":3},
                      "face":"up",
                      "expected_block":"minecraft:smooth_stone"
                    }]
                  },
                  "budget":{
                    "max_duration_ms":10000,"max_ticks":200,
                    "max_distance_blocks":0,"max_camera_degrees":80,
                    "max_interactions":0,"max_blocks_broken":0,"max_blocks_placed":0
                  }
                }
                """).getAsJsonObject();

        assertThat(CatalogSchemaValidator.matches(schema, request)).isTrue();

        var missingFace = request.deepCopy();
        missingFace.getAsJsonObject("program").getAsJsonArray("body")
                .get(0).getAsJsonObject().remove("face");
        assertThat(CatalogSchemaValidator.matches(schema, missingFace)).isFalse();

        var invalidFace = request.deepCopy();
        invalidFace.getAsJsonObject("program").getAsJsonArray("body")
                .get(0).getAsJsonObject().addProperty("face", "diagonal");
        assertThat(CatalogSchemaValidator.matches(schema, invalidFace)).isFalse();

        var invalidBlock = request.deepCopy();
        invalidBlock.getAsJsonObject("program").getAsJsonArray("body")
                .get(0).getAsJsonObject().addProperty("expected_block", "Smooth Stone");
        assertThat(CatalogSchemaValidator.matches(schema, invalidBlock)).isFalse();

        var extraField = request.deepCopy();
        extraField.getAsJsonObject("program").getAsJsonArray("body")
                .get(0).getAsJsonObject().addProperty("raw_hit_point", true);
        assertThat(CatalogSchemaValidator.matches(schema, extraField)).isFalse();
    }

    @Test
    void placementApproachCatalogIsMovementOnlyOpaqueAndExclusiveByContract() {
        var schema = new McpToolCatalog().inputSchema("agent_start_action");
        var definition = schema.getAsJsonObject("$defs")
                .getAsJsonObject("approachKnownPlacementNode");

        assertThat(definition.getAsJsonObject("properties").getAsJsonObject("op")
                .get("const").getAsString()).isEqualTo("approach_known_placement");
        assertThat(definition.get("description").getAsString())
                .contains("Movement-only", "dry bottom oak/cobblestone stair")
                .contains("only top-level node", "reobserve")
                .contains("zero camera/interactions/breaks/placements");

        var example = schema.getAsJsonArray("examples").asList().stream()
                .map(value -> value.getAsJsonObject())
                .filter(value -> value.getAsJsonObject("program").get("name").getAsString()
                        .equals("approach_known_placement"))
                .findFirst().orElseThrow();
        assertThat(CatalogSchemaValidator.matches(schema, example)).isTrue();

        var inline = example.deepCopy();
        var entry = inline.getAsJsonObject("program").getAsJsonArray("body")
                .get(0).getAsJsonObject().getAsJsonArray("entries")
                .get(0).getAsJsonObject();
        entry.remove("placement_state_ref");
        entry.add("source_state", JsonParser.parseString(
                "{\"block\":\"minecraft:oak_stairs\",\"properties\":{}}"));
        entry.addProperty("item", "minecraft:oak_stairs");
        assertThat(CatalogSchemaValidator.matches(schema, inline)).isFalse();

        var sideSupport = example.deepCopy();
        sideSupport.getAsJsonObject("program").getAsJsonArray("body")
                .get(0).getAsJsonObject().getAsJsonArray("entries")
                .get(0).getAsJsonObject().getAsJsonObject("support")
                .addProperty("face", "north");
        assertThat(CatalogSchemaValidator.matches(schema, sideSupport)).isFalse();
    }

    @Test
    void pillarCatalogAcceptsRefOrInlineIdentityButRejectsNullAndMixedForms() {
        var schema = new McpToolCatalog().inputSchema("agent_start_action");
        var request = JsonParser.parseString("""
                {
                  "schema_version":1,
                  "program":{
                    "dsl_version":1,
                    "capabilities":["movement","camera","block_place"],
                    "body":[{
                      "id":"up",
                      "op":"pillar_up_known",
                      "support":{"dimension":"minecraft:overworld","x":0,"y":64,"z":0},
                      "expected_support":{"block":"minecraft:stone","properties":{}},
                      "placement_state_ref":"psr_0123456789abcdef0123456789abcdef"
                    }]
                  },
                  "budget":{
                    "max_duration_ms":15000,"max_ticks":300,
                    "max_distance_blocks":2,"max_camera_degrees":360,
                    "max_interactions":0,"max_blocks_broken":0,"max_blocks_placed":1
                  }
                }
                """).getAsJsonObject();
        var node = request.getAsJsonObject("program").getAsJsonArray("body")
                .get(0).getAsJsonObject();

        assertThat(CatalogSchemaValidator.matches(schema, request)).isTrue();

        node.remove("placement_state_ref");
        node.add("source_state", JsonParser.parseString(
                "{\"block\":\"minecraft:oak_planks\",\"properties\":{}}"));
        node.addProperty("item", "minecraft:oak_planks");
        assertThat(CatalogSchemaValidator.matches(schema, request)).isTrue();

        node.addProperty(
                "placement_state_ref", "psr_0123456789abcdef0123456789abcdef");
        assertThat(CatalogSchemaValidator.matches(schema, request)).isFalse();

        node.remove("source_state");
        node.remove("item");
        node.add("placement_state_ref", com.google.gson.JsonNull.INSTANCE);
        assertThat(CatalogSchemaValidator.matches(schema, request)).isFalse();
    }

    @Test
    void knownMenuOperationCatalogContractIsClosedTerminalAndBudgeted() {
        var schema = new McpToolCatalog().inputSchema("agent_start_action");
        var node = schema.getAsJsonObject("$defs").getAsJsonObject("operateKnownMenuNode");

        assertThat(node.getAsJsonObject("properties").keySet())
                .containsExactlyInAnyOrder("id", "op", "operation_ref");
        assertThat(node.getAsJsonArray("required").toString())
                .isEqualTo("[\"id\",\"op\",\"operation_ref\"]");
        assertThat(node.getAsJsonObject("properties").getAsJsonObject("operation_ref")
                .get("pattern").getAsString()).isEqualTo("^[A-Za-z0-9_-]{24}$");
        assertThat(node.get("description").getAsString())
                .contains("exactly one current opaque operation_ref")
                .contains("final top-level Action node")
                .contains("never appears inside if/repeat")
                .contains("30000 ms", "600 ticks", "1 interaction")
                .contains("distance, camera, breaks, and placements are zero");

        var example = schema.getAsJsonArray("examples").asList().stream()
                .map(value -> value.getAsJsonObject())
                .filter(value -> value.getAsJsonObject("program").get("name").getAsString()
                        .equals("operate_known_menu"))
                .findFirst().orElseThrow();
        assertThat(CatalogSchemaValidator.matches(schema, example)).isTrue();
        assertThat(example.getAsJsonObject("program").getAsJsonArray("capabilities").toString())
                .isEqualTo("[\"inventory_transfer\"]");
        var budget = example.getAsJsonObject("budget");
        assertThat(budget.get("max_duration_ms").getAsLong())
                .isEqualTo(ActionDslCompiler.KNOWN_MENU_OPERATION_DURATION_MILLIS);
        assertThat(budget.get("max_ticks").getAsLong())
                .isEqualTo(ActionDslCompiler.KNOWN_MENU_OPERATION_TICKS);
        assertThat(budget.get("max_interactions").getAsLong())
                .isEqualTo(ActionDslCompiler.KNOWN_MENU_OPERATION_INTERACTIONS);
        assertThat(budget.get("max_distance_blocks").getAsLong()).isZero();
        assertThat(budget.get("max_camera_degrees").getAsLong()).isZero();
        assertThat(budget.get("max_blocks_broken").getAsLong()).isZero();
        assertThat(budget.get("max_blocks_placed").getAsLong()).isZero();

        var invalidRef = example.deepCopy();
        invalidRef.getAsJsonObject("program").getAsJsonArray("body").get(0)
                .getAsJsonObject().addProperty("operation_ref", "not+base64url============");
        assertThat(CatalogSchemaValidator.matches(schema, invalidRef)).isFalse();
        var extraField = example.deepCopy();
        extraField.getAsJsonObject("program").getAsJsonArray("body").get(0)
                .getAsJsonObject().addProperty("menu_ref", "abcdefghijklmnopqrstuvwx");
        assertThat(CatalogSchemaValidator.matches(schema, extraField)).isFalse();
    }

    @Test
    void knownMenuStateProjectionIsOptionalClosedAndBounded() {
        var stateTool = new McpToolCatalog().listResult().getAsJsonArray("tools").asList().stream()
                .map(value -> value.getAsJsonObject())
                .filter(tool -> tool.get("name").getAsString().equals("agent_get_state"))
                .findFirst().orElseThrow();
        var output = stateTool.getAsJsonObject("outputSchema");
        var menu = output.getAsJsonObject("$defs").getAsJsonObject("known_menu");
        var operation = menu.getAsJsonObject("properties").getAsJsonObject("operations")
                .getAsJsonObject("items");

        assertThat(stateTool.get("description").getAsString())
                .contains("known_menu", "single-use operation_ref", "never infer raw slots");
        assertThat(output.getAsJsonArray("required").asList().stream()
                .map(value -> value.getAsString())).doesNotContain("known_menu");
        assertThat(output.getAsJsonObject("properties").getAsJsonObject("known_menu")
                .get("$ref").getAsString()).isEqualTo("#/$defs/known_menu");
        assertThat(menu.get("additionalProperties").getAsBoolean()).isFalse();
        assertThat(menu.getAsJsonObject("properties").getAsJsonObject("operations")
                .get("maxItems").getAsInt()).isEqualTo(16);
        assertThat(operation.get("additionalProperties").getAsBoolean()).isFalse();
        assertThat(operation.getAsJsonObject("properties").getAsJsonObject("operation_ref")
                .get("pattern").getAsString()).isEqualTo("^[A-Za-z0-9_-]{24}$");
        assertThat(operation.getAsJsonArray("required").asList().stream()
                .map(JsonElement::getAsString)).contains("valid_through_client_tick");
        assertThat(operation.getAsJsonObject("properties").getAsJsonObject("stack")
                .get("additionalProperties").getAsBoolean()).isFalse();
    }

    @Test
    void redstoneIdentityCatalogSchemaIsClosedAndDiscoverable() {
        var schema = new McpToolCatalog().inputSchema("agent_start_action");
        var request = JsonParser.parseString("""
                {
                  "schema_version":1,
                  "program":{
                    "dsl_version":1,
                    "capabilities":["camera","block_interact","block_place"],
                    "body":[{
                      "id":"identity","op":"apply_known_redstone_spec",
                      "anchor":{"dimension":"minecraft:overworld","x":2,"y":65,"z":3},
                      "rotation":90,
                      "components":[
                        {"id":"input","role":"input","block":"minecraft:lever"},
                        {"id":"output","role":"output","block":"minecraft:redstone_lamp"}
                      ],
                      "truth_table":[
                        {"inputs":{"input":false},"outputs":{"output":false}},
                        {"inputs":{"input":true},"outputs":{"output":true}}
                      ],
                      "footprint":{"x":2,"y":1,"z":1},
                      "timing":{"settle_ticks":20}
                    }]
                  },
                  "budget":{
                    "max_duration_ms":23000,"max_ticks":460,
                    "max_distance_blocks":0,"max_camera_degrees":720,
                    "max_interactions":2,"max_blocks_broken":0,"max_blocks_placed":2
                  }
                }
                """).getAsJsonObject();

        assertThat(CatalogSchemaValidator.matches(schema, request)).isTrue();
        assertThat(schema.getAsJsonObject("$defs")
                .getAsJsonObject("applyKnownRedstoneSpecNode")
                .get("description").getAsString())
                .contains(
                        "identity slices only",
                        "fan-out slice",
                        "straight-wire slice",
                        "current visible minecraft:glass",
                        "power 0, 15, and 0",
                        "same-client-tick live sets",
                        "never moves");

        var fanOut = request.deepCopy();
        var fanOutNode = fanOut.getAsJsonObject("program").getAsJsonArray("body").get(0)
                .getAsJsonObject();
        fanOutNode.getAsJsonArray("components").add(JsonParser.parseString("""
                {"id":"output_2","role":"output","block":"minecraft:redstone_lamp"}
                """));
        fanOutNode.getAsJsonArray("truth_table").forEach(row -> {
            var object = row.getAsJsonObject();
            boolean value = object.getAsJsonObject("inputs").get("input").getAsBoolean();
            object.getAsJsonObject("outputs").addProperty("output_2", value);
        });
        fanOutNode.getAsJsonObject("footprint").addProperty("x", 3);
        var fanOutBudget = fanOut.getAsJsonObject("budget");
        fanOutBudget.addProperty("max_duration_ms", 28_000);
        fanOutBudget.addProperty("max_ticks", 560);
        fanOutBudget.addProperty("max_blocks_placed", 3);
        assertThat(CatalogSchemaValidator.matches(schema, fanOut)).isTrue();

        var wire = request.deepCopy();
        var wireNode = wire.getAsJsonObject("program").getAsJsonArray("body").get(0)
                .getAsJsonObject();
        wireNode.getAsJsonArray("components").add(JsonParser.parseString("""
                {"id":"wire","role":"wire","block":"minecraft:redstone_wire"}
                """));
        wireNode.getAsJsonObject("footprint").addProperty("x", 3);
        var wireBudget = wire.getAsJsonObject("budget");
        wireBudget.addProperty("max_duration_ms", 28_000);
        wireBudget.addProperty("max_ticks", 560);
        wireBudget.addProperty("max_blocks_placed", 3);
        assertThat(CatalogSchemaValidator.matches(schema, wire)).isTrue();

        var unsupported = request.deepCopy();
        unsupported.getAsJsonObject("program").getAsJsonArray("body").get(0)
                .getAsJsonObject().getAsJsonArray("components").get(0)
                .getAsJsonObject().addProperty("block", "minecraft:redstone_wire");
        assertThat(CatalogSchemaValidator.matches(schema, unsupported)).isFalse();
    }

    @Test
    void publishedContainerExamplesLeaveDispatchAndJitHeadroom() {
        var schema = new McpToolCatalog().inputSchema("agent_start_action");
        var definitions = schema.getAsJsonObject("$defs");
        assertThat(definitions.getAsJsonObject("inspectContainerNode")
                .get("description").getAsString()).contains("30000 ms", "600 ticks", "360 camera");
        assertThat(definitions.getAsJsonObject("takeContainerStackNode")
                .get("description").getAsString()).contains(
                        "600+60*(max_stacks-1) ticks", "50 times that in ms", "360 camera",
                        "2+max_stacks interactions", "leaving 200 ticks");
        assertThat(definitions.getAsJsonObject("storeContainerStackNode")
                .get("description").getAsString()).contains(
                        "600+60*(max_stacks-1) ticks", "50 times that in ms", "360 camera",
                        "2+max_stacks interactions", "leaving 200 ticks");

        var containerExamples = schema.getAsJsonArray("examples").asList().stream()
                .map(example -> example.getAsJsonObject())
                .filter(example -> {
                    String op = example.getAsJsonObject("program")
                            .getAsJsonArray("body").get(0).getAsJsonObject()
                            .get("op").getAsString();
                    return op.equals("inspect_known_container")
                            || op.equals("take_known_container_stack")
                            || op.equals("store_known_container_stack");
                })
                .toList();

        assertThat(containerExamples).hasSize(3).allSatisfy(example -> {
            var budget = example.getAsJsonObject("budget");
            assertThat(budget.get("max_duration_ms").getAsLong()).isEqualTo(30_000L);
            assertThat(budget.get("max_ticks").getAsLong()).isEqualTo(600L)
                    .isEqualTo(AgentPrimitivePlanner.CONTAINER_TICK_UPPER_BOUND);
            assertThat(budget.get("max_camera_degrees").getAsDouble()).isEqualTo(360.0D);
            assertThat(budget.get("max_duration_ms").getAsLong())
                    .isEqualTo(AgentPrimitivePlanner.CONTAINER_TICK_UPPER_BOUND * 50L);
            assertThat(CatalogSchemaValidator.matches(schema, example)).isTrue();
        });
    }

    @Test
    void knownRecipeCraftContractIsClosedDiscoverableAndBounded() {
        var schema = new McpToolCatalog().inputSchema("agent_start_action");
        var definition = schema.getAsJsonObject("$defs")
                .getAsJsonObject("craftKnownRecipeNode");
        assertThat(definition.get("description").getAsString())
                .contains("latest agent_get_state recipe result")
                .contains("1..3 times")
                .contains("absolute inventory goal")
                .contains("1+4*max_crafts interactions");

        var example = schema.getAsJsonArray("examples").asList().stream()
                .map(value -> value.getAsJsonObject())
                .filter(value -> value.getAsJsonObject("program").get("name").getAsString()
                        .equals("craft_known_oak_planks"))
                .findFirst().orElseThrow();
        assertThat(CatalogSchemaValidator.matches(schema, example)).isTrue();

        var tooManyCrafts = example.deepCopy();
        tooManyCrafts.getAsJsonObject("program").getAsJsonArray("body").get(0)
                .getAsJsonObject().addProperty("max_crafts", 4);
        assertThat(CatalogSchemaValidator.matches(schema, tooManyCrafts)).isFalse();

        var wrongRef = example.deepCopy();
        wrongRef.getAsJsonObject("program").getAsJsonArray("body").get(0)
                .getAsJsonObject().addProperty("recipe_ref", "not-opaque");
        assertThat(CatalogSchemaValidator.matches(schema, wrongRef)).isFalse();

        var inventedState = example.deepCopy();
        inventedState.getAsJsonObject("program").getAsJsonArray("body").get(0)
                .getAsJsonObject().getAsJsonObject("station")
                .getAsJsonObject("expected_state").getAsJsonObject("properties")
                .addProperty("invented", "true");
        assertThat(CatalogSchemaValidator.matches(schema, inventedState)).isFalse();
    }

    @Test
    void knownSmeltContractIsClosedDiscoverableAndBounded() {
        var schema = new McpToolCatalog().inputSchema("agent_start_action");
        var definition = schema.getAsJsonObject("$defs")
                .getAsJsonObject("smeltKnownRecipeNode");
        assertThat(definition.get("description").getAsString())
                .contains("exact full input stack of 1..64 items")
                .contains("furnace, blast furnace, or smoker")
                .contains("delivery-backed visible-surface ray hit",
                        "live exact-target hit", "270 degrees")
                .contains("Raw slots and GUI coordinates remain private")
                .contains("2200+200*max_smelts ticks", "540 camera degrees", "7 interactions");

        var example = schema.getAsJsonArray("examples").asList().stream()
                .map(value -> value.getAsJsonObject())
                .filter(value -> value.getAsJsonObject("program").get("name").getAsString()
                        .equals("smelt_known_iron_ingot"))
                .findFirst().orElseThrow();
        assertThat(CatalogSchemaValidator.matches(schema, example)).isTrue();

        var twoSmelts = example.deepCopy();
        twoSmelts.getAsJsonObject("program").getAsJsonArray("body").get(0)
                .getAsJsonObject().addProperty("max_smelts", 2);
        assertThat(CatalogSchemaValidator.matches(schema, twoSmelts)).isTrue();

        var tooManySmelts = example.deepCopy();
        tooManySmelts.getAsJsonObject("program").getAsJsonArray("body").get(0)
                .getAsJsonObject().addProperty("max_smelts", 65);
        assertThat(CatalogSchemaValidator.matches(schema, tooManySmelts)).isFalse();

        var arbitraryFuelPolicy = example.deepCopy();
        arbitraryFuelPolicy.getAsJsonObject("program").getAsJsonArray("body").get(0)
                .getAsJsonObject().getAsJsonObject("fuel")
                .addProperty("stack_policy", "item_id_any_components");
        assertThat(CatalogSchemaValidator.matches(schema, arbitraryFuelPolicy)).isFalse();

        var mismatchedStation = example.deepCopy();
        mismatchedStation.getAsJsonObject("program").getAsJsonArray("body").get(0)
                .getAsJsonObject().getAsJsonObject("station")
                .addProperty("kind", "smoker");
        assertThat(CatalogSchemaValidator.matches(schema, mismatchedStation)).isFalse();
    }

    @Test
    void brewingContractIsClosedTerminalAndSynchronizedWithStandardPotionPolicy() {
        var catalog = new McpToolCatalog();
        var schema = catalog.inputSchema("agent_start_action");
        var definitions = schema.getAsJsonObject("$defs");
        var brew = definitions.getAsJsonObject("brewKnownPotionBatchNode");
        String contract = brew.get("description").getAsString();

        assertThat(contract)
                .contains("catalog-fixed standard Vanilla one-step recipe")
                .contains("1..3 equal-count potions")
                .contains("component-exact")
                .contains("aggregated singleton count")
                .contains("All five stand item slots must be empty")
                .contains("hidden Vanilla fuel counter must be within 0..20")
                .contains("skips inventory fuel insertion")
                .contains("one inventory powder when precharged and two when uncharged")
                .contains("fixed replan diagnostic")
                .contains("cannot be resumed or replayed")
                .contains("final top-level Action node")
                .contains("never inside if/repeat")
                .contains("execution-start preflight",
                        "delivery-backed visible-surface ray hit",
                        "live exact-target hit", "270 degrees", "face_known_position")
                .contains("70000 ms", "1400 ticks", "540 camera degrees", "16 interactions")
                .contains("recovery dispatches no gameplay interaction")
                .contains("interaction ceiling remains 16")
                .contains("returns all five item slots empty")
                .contains("exactly one fuel use was consumed")
                .contains("releases the screen/cursor/input owner");
        assertThat(definitions.getAsJsonObject("standardPotionBatch")
                .get("description").getAsString())
                .contains("aggregated count is at least this declared 1..3 count")
                .contains("expected_output instead declares the closed recipe result")
                .contains("need not already exist in inventory");

        assertThat(enumValues(definitions, "standardPotionItem"))
                .containsExactlyInAnyOrderElementsOf(StandardPotionPolicy.potionItems());
        assertThat(enumValues(definitions, "standardPotionId"))
                .containsExactlyInAnyOrderElementsOf(StandardPotionPolicy.potionIds());
        assertThat(enumValues(definitions, "brewingIngredient"))
                .containsExactlyInAnyOrderElementsOf(StandardPotionPolicy.ingredientItems());
        assertThat(definitions.getAsJsonObject("brewingIngredient")
                .get("description").getAsString())
                .contains("water+ingredient->mundane")
                .contains("awkward+ingredient->effect")
                .contains("breeze_rod->wind_charged")
                .contains("water+fermented_spider_eye->weakness")
                .contains("potion+gunpowder->splash_potion")
                .contains("Any other tuple is rejected before input");

        var example = schema.getAsJsonArray("examples").asList().stream()
                .map(value -> value.getAsJsonObject())
                .filter(value -> value.getAsJsonObject("program").get("name").getAsString()
                        .equals("brew_awkward_potions"))
                .findFirst().orElseThrow();
        assertThat(CatalogSchemaValidator.matches(schema, example)).isTrue();
        var budget = example.getAsJsonObject("budget");
        assertThat(budget.get("max_duration_ms").getAsLong())
                .isEqualTo(ActionDslCompiler.KNOWN_BREWING_DURATION_MILLIS);
        assertThat(budget.get("max_ticks").getAsLong())
                .isEqualTo(ActionDslCompiler.KNOWN_BREWING_TICKS);
        assertThat(budget.get("max_camera_degrees").getAsLong()).isEqualTo(540);
        assertThat(budget.get("max_interactions").getAsLong())
                .isEqualTo(ActionDslCompiler.KNOWN_BREWING_INTERACTIONS);

        assertThat(schema.getAsJsonObject("properties").getAsJsonObject("budget")
                .getAsJsonObject("properties").getAsJsonObject("max_interactions")
                .get("maximum").getAsInt()).isEqualTo(2_048);
        var state = catalog.outputSchema("agent_get_state");
        assertThat(state.getAsJsonObject("properties").getAsJsonObject("policy")
                .getAsJsonObject("properties").getAsJsonObject("max_interactions")
                .get("const").getAsInt()).isEqualTo(16);
        assertThat(state.getAsJsonObject("properties").has("standard_potions")).isTrue();
        var entityConsent = state.getAsJsonObject("properties")
                .getAsJsonObject("entity_attack_consent");
        assertThat(entityConsent).isNotNull();
        assertThat(entityConsent.get("description").getAsString())
                .contains("bounded kill-zone")
                .contains("newly spawned mobs")
                .contains("effective-health decrease revokes authority");
        assertThat(catalog.outputSchema("agent_get_action")
                .getAsJsonObject("$defs").getAsJsonObject("effectObservation")
                .getAsJsonObject("properties").has("health_before")).isTrue();
        assertThat(catalog.outputSchema("agent_get_action")
                .getAsJsonObject("$defs").getAsJsonObject("effectObservation")
                .getAsJsonObject("properties").getAsJsonObject("cycle")
                .get("maximum").getAsInt()).isEqualTo(64);
        var consentProperties = entityConsent.getAsJsonObject("properties");
        assertThat(consentProperties.has("policy_binding_hash")).isTrue();
        assertThat(consentProperties.has("action_binding_hash")).isFalse();
        assertThat(consentProperties.has("remaining_attacks")).isFalse();
        assertThat(consentProperties.has("next_attack_not_before_tick")).isFalse();
        var consentScope = consentProperties.getAsJsonObject("scope")
                .getAsJsonArray("oneOf").get(1).getAsJsonObject()
                .getAsJsonObject("properties");
        assertThat(consentScope.keySet()).contains(
                "player_station_bounds", "target_kill_zone_bounds",
                "entity_type_allowlist", "main_hand", "max_attacks",
                "minimum_interval_ticks", "max_operation_duration_ticks");
        assertThat(consentScope.has("entity_ref")).isFalse();
        assertThat(consentScope.toString()).doesNotContain("component_fingerprint");
        assertThat(enumValues(state.getAsJsonObject("properties")
                        .getAsJsonObject("standard_potions").getAsJsonObject("items")
                        .getAsJsonObject("properties"), "potion"))
                .containsExactlyInAnyOrderElementsOf(StandardPotionPolicy.potionIds());
        assertThat(catalog.outputSchema("agent_get_action")
                .getAsJsonObject("properties").getAsJsonObject("progress")
                .getAsJsonObject("properties").getAsJsonObject("interactions")
                .get("maximum").getAsInt()).isEqualTo(2_048);
        var actionOutput = catalog.outputSchema("agent_get_action");
        assertThat(actionOutput.getAsJsonObject("properties")
                .getAsJsonObject("effect_aggregate")
                .getAsJsonObject("properties")
                .getAsJsonObject("dispatched_attacks")
                .get("maximum").getAsInt()).isEqualTo(2_048);
        assertThat(actionOutput.getAsJsonObject("properties")
                .getAsJsonObject("trace").getAsJsonObject("items")
                .getAsJsonObject("properties").getAsJsonObject("tick")
                .get("maximum").getAsInt()).isEqualTo(AgentActionStore.MAX_RECORDED_TICKS);
    }

    @Test
    void cropWaitCatalogDocumentsItsExactLiveReadBoundary() {
        var schema = new McpToolCatalog().inputSchema("agent_start_action");
        String contract = schema.getAsJsonObject("$defs")
                .getAsJsonObject("waitUntilNode")
                .get("description").getAsString();

        assertThat(contract)
                .contains("current policy-visible wheat surface")
                .contains("target-scoped fresh proof")
                .contains("without being invalidated by unrelated mutation evictions")
                .contains("global eviction floor applies fail-closed")
                .contains("visible_surface.eye_origin must match the current JIT observer eye")
                .contains("a stale frame from a previous observer position is rejected")
                .contains("preserves that witness eye_origin rather than substituting")
                .contains("terminates before BlockState is read")
                .contains("navigation-neutral wheat AGE updates")
                .contains("only at that authorized loaded coordinate")
                .contains("replacement, unload, or world/session change")
                .contains("without exposing live state");

        var wheatCycle = schema.getAsJsonArray("examples").asList().stream()
                .map(example -> example.getAsJsonObject())
                .filter(example -> example.getAsJsonObject("program")
                        .get("name").getAsString().equals("wheat_cycle"))
                .findFirst().orElseThrow();
        assertThat(CatalogSchemaValidator.matches(schema, wheatCycle)).isTrue();
        assertThat(wheatCycle.getAsJsonObject("program").getAsJsonArray("body").asList()
                .stream()
                .map(node -> node.getAsJsonObject().get("op").getAsString())
                .toList())
                .containsSubsequence(
                        "plant_known_wheat_batch",
                        "wait_until",
                        "harvest_known_wheat_batch");
    }

    @Test
    void catalogClosesVisibleItemCollectionAroundContinuousObservationEvidence() {
        var schema = new McpToolCatalog().inputSchema("agent_start_action");
        var request = schema.getAsJsonArray("examples").get(0).getAsJsonObject().deepCopy();
        var program = request.getAsJsonObject("program");
        program.add("capabilities", JsonParser.parseString("[\"movement\"]"));
        program.add("body", JsonParser.parseString("""
                [{"id":"collect_drop","op":"collect_visible_item",
                  "displayed_item":"minecraft:wheat","target":{
                    "dimension":"minecraft:overworld",
                    "x":100.375,"y":64.125,"z":120.75}}]
                """));
        var budget = request.getAsJsonObject("budget");
        budget.addProperty("max_duration_ms", 30_000);
        budget.addProperty("max_ticks", 600);
        budget.addProperty("max_distance_blocks", 32);
        budget.addProperty("max_camera_degrees", 0);
        budget.addProperty("max_interactions", 0);
        assertThat(CatalogSchemaValidator.matches(schema, request)).isTrue();

        var widened = request.deepCopy();
        widened.getAsJsonObject("program").getAsJsonArray("body").get(0)
                .getAsJsonObject().addProperty("entity_id", "hidden-identity");
        assertThat(CatalogSchemaValidator.matches(schema, widened)).isFalse();

        var missingItem = request.deepCopy();
        missingItem.getAsJsonObject("program").getAsJsonArray("body").get(0)
                .getAsJsonObject().remove("displayed_item");
        assertThat(CatalogSchemaValidator.matches(schema, missingItem)).isFalse();

        var startToolContract = new McpToolCatalog().listResult().getAsJsonArray("tools").asList()
                .stream()
                .map(element -> element.getAsJsonObject())
                .filter(element -> element.get("name").getAsString().equals("agent_start_action"))
                .findFirst().orElseThrow();
        String description = startToolContract.get("description").getAsString();
        String collectContract = schema.getAsJsonObject("$defs")
                .getAsJsonObject("collectVisibleItemNode").get("description").getAsString();
        String batchContract = schema.getAsJsonObject("$defs")
                .getAsJsonObject("collectVisibleItemBatchNode").get("description").getAsString();
        assertThat(collectContract)
                .contains("fresh visible minecraft:item")
                .contains("absolute inventory-count increase");
        assertThat(batchContract)
                .contains("2..8 currently visible item witnesses")
                .contains("preserve listed order")
                .contains("fresh policy-visible item AABB actually intersects")
                .contains("witness disappearance, movement, or merge alone never succeeds")
                .contains("Any failure stops the unstarted suffix");
        assertThat(description)
                .contains("when a mutation creates new drops or newly exposed surfaces, finish, reobserve")
                .contains("Put wait_until immediately after its plant node");
    }

    @Test
    void readToolDescriptionsExposeFrequentlyMissedBoundsAndFrameRecovery() {
        var tools = new McpToolCatalog().listResult().getAsJsonArray("tools");
        String observation = tools.asList().stream()
                .map(tool -> tool.getAsJsonObject())
                .filter(tool -> tool.get("name").getAsString().equals("agent_get_observation"))
                .findFirst().orElseThrow().get("description").getAsString();
        assertThat(observation)
                .contains("Use the smallest useful limit")
                .contains("Use optional filter")
                .contains("displayed_items")
                .contains("faces")
                .contains("before the one-face-per-position representative")
                .contains("position_bounds")
                .contains("traversability at navigation_target")
                .contains("two paged queries")
                .contains("On FRAME_EXPIRED, call agent_get_state")
                .contains("observation.latest_frame_id");

        var observationSchema = new McpToolCatalog().inputSchema("agent_get_observation");
        var filteredObservation = JsonParser.parseString("""
                {"schema_version":1,"frame_id":"obs-0000000000000000",
                 "kinds":["visible_surface","visible_entity","traversability"],
                 "filter":{"displayed_items":["minecraft:wheat"],"faces":["up","north"],
                   "position_bounds":{"dimension":"minecraft:overworld",
                     "min_x":0,"min_y":60,"min_z":0,"max_x":7,"max_y":70,"max_z":7}},
                 "cursor":null,"limit":64}
                """).getAsJsonObject();
        assertThat(CatalogSchemaValidator.matches(observationSchema, filteredObservation)).isTrue();

        String action = tools.asList().stream()
                .map(tool -> tool.getAsJsonObject())
                .filter(tool -> tool.get("name").getAsString().equals("agent_get_action"))
                .findFirst().orElseThrow().get("description").getAsString();
        assertThat(action)
                .contains("wait_timeout_ms is an optional integer from 0 through 25000")
                .contains("zero or omission returns immediately");
    }

    @Test
    void catalogSchemasDriveInputAndOutputValidation() {
        var catalog = new McpToolCatalog();
        var actionSchema = catalog.inputSchema("agent_start_action");
        assertThat(CatalogSchemaValidator.matches(
                actionSchema, actionSchema.getAsJsonArray("examples").get(0))).isTrue();

        var invalid = actionSchema.getAsJsonArray("examples").get(0).getAsJsonObject().deepCopy();
        invalid.addProperty("raw_key", "attack");
        assertThat(CatalogSchemaValidator.matches(actionSchema, invalid)).isFalse();

        var stateSchema = catalog.listResult().getAsJsonArray("tools").get(0)
                .getAsJsonObject().getAsJsonObject("outputSchema");
        var state = new GsonBuilder().serializeNulls().create().toJsonTree(McpTestFixtures.state());
        assertThat(CatalogSchemaValidator.matches(stateSchema, state)).as(state.toString()).isTrue();
    }

    @Test
    void stateSchemaAllowsOnlyTheTypedFreshMerchantProjection() {
        var catalog = new McpToolCatalog();
        var schema = catalog.outputSchema("agent_get_state");
        var state = new GsonBuilder().serializeNulls().create()
                .toJsonTree(McpTestFixtures.state()).getAsJsonObject();
        state.add("merchant_offers", JsonParser.parseString("""
                {"world_session_id":"550e8400-e29b-41d4-a716-446655440000",
                 "container_id":9,"signal_revision":3,"open_packet_revision":2,
                 "received_tick":44,"offers":[{
                   "world_session_id":"550e8400-e29b-41d4-a716-446655440000",
                   "container_id":9,"signal_revision":3,"received_tick":44,"offer_index":0,
                   "cost_a":{"item":"minecraft:emerald","count":12},
                   "cost_b":{"item":"minecraft:book","count":1},
                   "result":{"item":"minecraft:enchanted_book","count":1},
                   "uses":0,"max_uses":12,"out_of_stock":false,
                   "merchant_level":1,"merchant_xp":0,
                   "book_result_kind":"single_known_enchantment",
                   "stored_enchantment_count":1,"unresolved_enchantment_count":0,
                   "stored_enchantments":[{"enchantment":"minecraft:mending","level":1}]
                 }]}
                """).getAsJsonObject());

        assertThat(CatalogSchemaValidator.matches(schema, state)).isTrue();
        state.getAsJsonObject("merchant_offers").getAsJsonArray("offers")
                .get(0).getAsJsonObject().addProperty("raw_nbt", "forbidden");
        assertThat(CatalogSchemaValidator.matches(schema, state)).isFalse();
        assertThat(catalog.listResult().getAsJsonArray("tools").get(0)
                .getAsJsonObject().get("description").getAsString())
                .contains("current merchant screen")
                .contains("never raw slots, components, NBT, lore, or display text");
    }

    @Test
    void productionReadyStatePayloadMatchesTheNormativeOutputSchema() {
        var catalog = new McpToolCatalog();
        var state = new GsonBuilder().serializeNulls().create().toJsonTree(
                McmcpRuntimeContractTestAccess.readyStatePayload());

        assertThat(CatalogSchemaValidator.matches(
                catalog.outputSchema("agent_get_state"), state))
                .as(state.toString())
                .isTrue();
        assertThat(state.getAsJsonObject()
                .getAsJsonObject("policy")
                .getAsJsonObject("action_dsl")
                .getAsJsonArray("allowed_capabilities"))
                .containsExactlyInAnyOrder(
                        JsonParser.parseString("\"movement\""),
                        JsonParser.parseString("\"camera\""),
                        JsonParser.parseString("\"block_break\""),
                        JsonParser.parseString("\"block_interact\""),
                        JsonParser.parseString("\"block_place\""),
                        JsonParser.parseString("\"inventory_transfer\""),
                        JsonParser.parseString("\"item_use\""),
                        JsonParser.parseString("\"entity_attack\""));
        var actionDsl = state.getAsJsonObject()
                .getAsJsonObject("policy")
                .getAsJsonObject("action_dsl");
        assertThat(actionDsl.getAsJsonArray("available_operations"))
                .hasSize(ActionDslOperationManifest.operations().size());
        assertThat(actionDsl.getAsJsonArray("reference_descriptors")).hasSize(6);
        assertThat(actionDsl.getAsJsonObject("missing_capability_guidance")
                .get("code").getAsString()).isEqualTo("MISSING_CAPABILITY");
        assertThat(state.getAsJsonObject().getAsJsonObject("control")
                .getAsJsonArray("granted_capabilities")).hasSize(8);
    }

    @Test
    void actionHistorySchemaBoundsCanonicalSourceAndForcesOpaqueReferenceRefresh() {
        var catalog = new McpToolCatalog();
        var output = catalog.outputSchema("agent_get_action");
        var properties = output.getAsJsonObject("properties");

        assertThat(properties.getAsJsonObject("source")
                .getAsJsonObject("properties")
                .getAsJsonObject("canonical_json")
                .get("maxLength").getAsInt())
                .isEqualTo(dev.aod.mcmcp.agent.dsl.ActionDslSource.MAX_CANONICAL_JSON_CHARS);
        assertThat(properties.getAsJsonObject("template").get("description").getAsString())
                .contains("intentionally invalid", "fresh producer result");
        assertThat(properties.getAsJsonObject("reference_requirements")
                .getAsJsonObject("items")
                .getAsJsonObject("properties")
                .getAsJsonObject("status")
                .get("const").getAsString()).isEqualTo("refresh_required");
        assertThat(output.getAsJsonArray("required").asList().stream()
                .map(JsonElement::getAsString))
                .contains("source", "template", "reference_requirements");
        assertThat(catalog.listResult().getAsJsonArray("tools")).hasSize(5);
    }

    @Test
    void fishingEffectsAndTheirRefreshProducerMatchThePublishedSchema() {
        var catalog = new McpToolCatalog();
        var output = catalog.outputSchema("agent_get_action");
        var observationSchema = output.getAsJsonObject("$defs")
                .getAsJsonObject("effectObservation");
        var castAfter = JsonParser.parseString("""
                {"hand":"main_hand","rod_item":"minecraft:fishing_rod",
                 "bobber_present":true,"fishing_session_ref":"f_0123456789abcdef012345"}
                """);
        assertThat(CatalogSchemaValidator.matches(observationSchema, castAfter)).isTrue();
        var effectKinds = output.getAsJsonObject("properties")
                .getAsJsonObject("effects").getAsJsonObject("items")
                .getAsJsonObject("properties").getAsJsonObject("kind").getAsJsonArray("enum");
        assertThat(effectKinds.asList().stream().map(JsonElement::getAsString))
                .contains("fishing_cast", "fishing_reel");

        var refreshTools = output.getAsJsonObject("properties")
                .getAsJsonObject("reference_requirements").getAsJsonObject("items")
                .getAsJsonObject("properties").getAsJsonObject("refresh_tool")
                .getAsJsonArray("enum");
        assertThat(refreshTools).anySatisfy(value ->
                assertThat(value.getAsString()).isEqualTo("agent_get_action"));

        assertThat(catalog.inputSchema("agent_start_action").getAsJsonObject("$defs")
                .getAsJsonObject("fishingSplashCondition").get("description").getAsString())
                .contains("max_ticks is additionally limited to 900")
                .contains("1200-tick fishing_session_ref");
    }

    @Test
    void knownBlockBreakEffectsMatchThePublishedSchema() {
        var observationSchema = new McpToolCatalog().outputSchema("agent_get_action")
                .getAsJsonObject("$defs").getAsJsonObject("effectObservation");
        var before = JsonParser.parseString("""
                {"block":"minecraft:cobblestone","properties":{},
                 "expected_drop":"minecraft:cobblestone","minimum_inventory_count":8}
                """);
        assertThat(CatalogSchemaValidator.matches(observationSchema, before)).isTrue();
    }

    @Test
    void catalogClosesAndBoundsCropMaturityWaits() {
        var schema = new McpToolCatalog().inputSchema("agent_start_action");
        var request = schema.getAsJsonArray("examples").get(0).getAsJsonObject().deepCopy();
        var wait = JsonParser.parseString("""
                {"id":"await_mature","op":"wait_until",
                 "condition":{"type":"crop_mature","target":{
                   "dimension":"minecraft:overworld","x":10,"y":65,"z":10}},
                 "max_ticks":15000}
                """).getAsJsonObject();
        request.getAsJsonObject("program").add("body", new com.google.gson.JsonArray());
        request.getAsJsonObject("program").getAsJsonArray("body").add(wait);
        request.getAsJsonObject("budget").addProperty("max_duration_ms", 750_000);
        request.getAsJsonObject("budget").addProperty("max_ticks", 15_000);
        assertThat(CatalogSchemaValidator.matches(schema, request)).isTrue();

        var unsupported = request.deepCopy();
        unsupported.getAsJsonObject("program").getAsJsonArray("body").get(0)
                .getAsJsonObject().getAsJsonObject("condition")
                .addProperty("type", "expression");
        assertThat(CatalogSchemaValidator.matches(schema, unsupported)).isFalse();

        var unbounded = request.deepCopy();
        unbounded.getAsJsonObject("program").getAsJsonArray("body").get(0)
                .getAsJsonObject().addProperty("max_ticks", 15_001);
        assertThat(CatalogSchemaValidator.matches(schema, unbounded)).isFalse();
    }

    @Test
    void catalogClosesBoundedInputHoldsAndPublishesTheTwentyFourHourCeiling() {
        var schema = new McpToolCatalog().inputSchema("agent_start_action");
        var definitions = schema.getAsJsonObject("$defs");
        var hold = definitions.getAsJsonObject("holdBoundedInputsNode");
        var properties = hold.getAsJsonObject("properties");

        assertThat(properties.getAsJsonObject("inputs").getAsJsonObject("items")
                        .getAsJsonArray("enum").asList().stream()
                        .map(JsonElement::getAsString))
                .containsExactly("forward", "back", "left", "right", "jump", "sneak",
                        "attack", "use");
        assertThat(properties.getAsJsonObject("duration_ticks").get("maximum").getAsLong())
                .isEqualTo(1_728_000L);
        assertThat(hold.get("description").getAsString())
                .contains("only top-level Action node")
                .contains("target_guard")
                .contains("selected item")
                .contains("releases every held input before terminal")
                .contains("24 hours");
        var budget = schema.getAsJsonObject("properties").getAsJsonObject("budget")
                .getAsJsonObject("properties");
        assertThat(budget.getAsJsonObject("max_duration_ms").get("maximum").getAsLong())
                .isEqualTo(86_400_000L);
        assertThat(budget.getAsJsonObject("max_ticks").get("maximum").getAsLong())
                .isEqualTo(1_728_000L);

        var request = schema.getAsJsonArray("examples").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(example -> example.getAsJsonObject("program").get("name").getAsString()
                        .equals("hold_attack_on_obsidian"))
                .findFirst().orElseThrow();
        assertThat(CatalogSchemaValidator.matches(schema, request)).isTrue();

        var fullDay = request.deepCopy();
        fullDay.getAsJsonObject("program").getAsJsonArray("body").get(0)
                .getAsJsonObject().addProperty("duration_ticks", 1_728_000L);
        var fullDayBudget = fullDay.getAsJsonObject("budget");
        fullDayBudget.addProperty("max_duration_ms", 86_400_000L);
        fullDayBudget.addProperty("max_ticks", 1_728_000L);
        assertThat(CatalogSchemaValidator.matches(schema, fullDay)).isTrue();

        var unbounded = fullDay.deepCopy();
        unbounded.getAsJsonObject("program").getAsJsonArray("body").get(0)
                .getAsJsonObject().addProperty("duration_ticks", 1_728_001L);
        assertThat(CatalogSchemaValidator.matches(schema, unbounded)).isFalse();

        var targetedCondition = hold.getAsJsonArray("allOf").get(0).getAsJsonObject();
        assertThat(targetedCondition.getAsJsonObject("then").getAsJsonArray("required")
                .asList().stream().map(JsonElement::getAsString))
                .containsExactly("target_guard", "selected_item");
        assertThat(targetedCondition.getAsJsonObject("else").has("not")).isTrue();

        var duplicateInput = request.deepCopy();
        duplicateInput.getAsJsonObject("program").getAsJsonArray("body").get(0)
                .getAsJsonObject().add("inputs", JsonParser.parseString("[\"attack\",\"attack\"]"));
        assertThat(CatalogSchemaValidator.matches(schema, duplicateInput)).isFalse();

    }

    @Test
    void catalogAdmitsOnlyTheClosedFenceGateOpenNodeShape() {
        var schema = new McpToolCatalog().inputSchema("agent_start_action");
        var request = schema.getAsJsonArray("examples").get(0).getAsJsonObject().deepCopy();
        var program = request.getAsJsonObject("program");
        program.add("capabilities", JsonParser.parseString(
                "[\"camera\",\"block_interact\"]"));
        program.add("body", JsonParser.parseString("""
                [{"id":"open_gate","op":"open_known_fence_gate","target":{
                  "dimension":"minecraft:overworld","x":-11,"y":56,"z":-15}}]
                """));
        var budget = request.getAsJsonObject("budget");
        budget.addProperty("max_distance_blocks", 0);
        budget.addProperty("max_camera_degrees", 360);
        budget.addProperty("max_interactions", 1);
        assertThat(CatalogSchemaValidator.matches(schema, request)).isTrue();

        var widened = request.deepCopy();
        widened.getAsJsonObject("program").getAsJsonArray("body").get(0)
                .getAsJsonObject().addProperty("expected_block", "minecraft:oak_fence_gate");
        assertThat(CatalogSchemaValidator.matches(schema, widened)).isFalse();
    }

    @Test
    void catalogAdmitsBoundedPassageAndContainerNodesWithoutAddingTools() {
        var schema = new McpToolCatalog().inputSchema("agent_start_action");
        var request = schema.getAsJsonArray("examples").get(0).getAsJsonObject().deepCopy();
        var program = request.getAsJsonObject("program");
        program.add("capabilities", JsonParser.parseString(
                "[\"camera\",\"block_interact\",\"inventory_transfer\"]"));
        program.add("body", JsonParser.parseString("""
                [
                  {"id":"open","op":"open_known_passage","target":{
                    "dimension":"minecraft:overworld","x":1,"y":64,"z":2},
                   "expected_block":"minecraft:oak_door"},
                  {"id":"inspect","op":"inspect_known_container","target":{
                    "dimension":"minecraft:overworld","x":2,"y":64,"z":2},
                   "expected_block":"minecraft:chest"},
                  {"id":"take","op":"take_known_container_stack","target":{
                    "dimension":"minecraft:overworld","x":2,"y":64,"z":2},
                   "expected_block":"minecraft:chest","item":"minecraft:wheat_seeds",
                   "stack_policy":"default_components_only","minimum_inventory_count":64,
                   "routing_label":{"entity_ref":"abcdefghijklmnopqrstuvwx",
                     "item":"minecraft:wheat_seeds"}}
                ]
                """));
        var budget = request.getAsJsonObject("budget");
        budget.addProperty("max_duration_ms", 600_000);
        budget.addProperty("max_ticks", 12_000);
        budget.addProperty("max_distance_blocks", 0);
        budget.addProperty("max_camera_degrees", 360);
        budget.addProperty("max_interactions", 5);
        assertThat(CatalogSchemaValidator.matches(schema, request)).isTrue();

        var iron = request.deepCopy();
        iron.getAsJsonObject("program").getAsJsonArray("body").get(0)
                .getAsJsonObject().addProperty("expected_block", "minecraft:iron_door");
        assertThat(CatalogSchemaValidator.matches(schema, iron)).isFalse();

        var rawSlot = request.deepCopy();
        rawSlot.getAsJsonObject("program").getAsJsonArray("body").get(2)
                .getAsJsonObject().addProperty("slot", 0);
        assertThat(CatalogSchemaValidator.matches(schema, rawSlot)).isFalse();
        var rawRoutingRef = request.deepCopy();
        rawRoutingRef.getAsJsonObject("program").getAsJsonArray("body").get(2)
                .getAsJsonObject().getAsJsonObject("routing_label")
                .addProperty("entity_ref", "raw-uuid");
        assertThat(CatalogSchemaValidator.matches(schema, rawRoutingRef)).isFalse();
        assertThat(new McpToolCatalog().listResult().getAsJsonArray("tools")).hasSize(5);
    }

    @Test
    void actionProgressSchemaMatchesTheRuntimeRecordingLimits() {
        var output = new McpToolCatalog().outputSchema("agent_get_action");
        var progress = output.getAsJsonObject("properties")
                .getAsJsonObject("progress")
                .getAsJsonObject("properties");

        assertThat(progress.getAsJsonObject("ticks").get("maximum").getAsInt())
                .isEqualTo(AgentActionStore.MAX_RECORDED_TICKS);
        assertThat(progress.getAsJsonObject("camera_degrees").get("maximum").getAsDouble())
                .isEqualTo(AgentActionStore.MAX_RECORDED_CAMERA_DEGREES);
        assertThat(progress.getAsJsonObject("interactions").get("maximum").getAsInt())
                .isEqualTo(AgentActionStore.MAX_RECORDED_INTERACTIONS);
        assertThat(progress.getAsJsonObject("blocks_broken").get("maximum").getAsInt())
                .isEqualTo(AgentActionStore.MAX_RECORDED_BLOCKS_BROKEN);
        assertThat(progress.getAsJsonObject("blocks_placed").get("maximum").getAsInt())
                .isEqualTo(AgentActionStore.MAX_RECORDED_BLOCKS_PLACED);
        var failureCodes = output.getAsJsonObject("properties")
                .getAsJsonObject("failure")
                .getAsJsonArray("oneOf")
                .get(1).getAsJsonObject()
                .getAsJsonObject("properties")
                .getAsJsonObject("code")
                .getAsJsonArray("enum");
        assertThat(failureCodes).anySatisfy(code -> assertThat(code.getAsString())
                .isEqualTo(AgentActionStore.FailureCode.DELIVERY_UNCONFIRMED.wireName()));
    }

    @Test
    void getActionLongPollIsOptionalBoundedAndReceivesDispatchHeadroom() {
        var catalog = new McpToolCatalog();
        var schema = catalog.inputSchema("agent_get_action");
        var immediate = new com.google.gson.JsonObject();
        immediate.addProperty("action_id", "550e8400-e29b-41d4-a716-446655440000");
        assertThat(CatalogSchemaValidator.matches(schema, immediate)).isTrue();

        var maximum = immediate.deepCopy();
        maximum.addProperty(
                "wait_timeout_ms", AgentActionStore.MAX_TERMINAL_WAIT_MILLIS);
        assertThat(CatalogSchemaValidator.matches(schema, maximum)).isTrue();
        var unbounded = maximum.deepCopy();
        unbounded.addProperty(
                "wait_timeout_ms", AgentActionStore.MAX_TERMINAL_WAIT_MILLIS + 1);
        assertThat(CatalogSchemaValidator.matches(schema, unbounded)).isFalse();
        var fractional = maximum.deepCopy();
        fractional.addProperty("wait_timeout_ms", 1.5D);
        assertThat(CatalogSchemaValidator.matches(schema, fractional)).isFalse();

        var registry = new McmcpToolRegistry((command, context) ->
                CompletableFuture.completedFuture(McpRuntimePort.RuntimeReply.success(
                        toolResult(command))), Duration.ofSeconds(1));
        var command = new McpRuntimePort.GetAction(Map.of(
                "action_id", "550e8400-e29b-41d4-a716-446655440000",
                "wait_timeout_ms", AgentActionStore.MAX_TERMINAL_WAIT_MILLIS));
        assertThat(registry.effectiveDispatchTimeout(command))
                .isEqualTo(Duration.ofSeconds(27));
        assertThat(registry.effectiveDispatchTimeout(new McpRuntimePort.GetState()))
                .isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void completeContainerResultsHaveBoundedOptionalQueriesAndActualPayloadMatchesSchema() {
        var catalog = new McpToolCatalog();
        var input = JsonParser.parseString("""
                {"action_id":"550e8400-e29b-41d4-a716-446655440000",
                "include_container_results":true,"container_results_limit":8}
                """).getAsJsonObject();
        assertThat(CatalogSchemaValidator.matches(catalog.inputSchema("agent_get_action"), input)).isTrue();
        input.addProperty("container_results_limit", 9);
        assertThat(CatalogSchemaValidator.matches(catalog.inputSchema("agent_get_action"), input)).isFalse();
        input.addProperty("container_results_limit", 1);
        input.addProperty("container_results_cursor", "invalid");
        assertThat(CatalogSchemaValidator.matches(catalog.inputSchema("agent_get_action"), input)).isFalse();
        var items = java.util.stream.IntStream.range(0, 54).mapToObj(index ->
                new dev.aod.mcmcp.agent.action.KnownContainerAttempt.ItemCount(
                        "minecraft:" + "a".repeat(116) + "%02d".formatted(index), 64)).toList();
        var result = new dev.aod.mcmcp.agent.action.ContainerInspection.Result(1, "inspect", 1,
                new dev.aod.mcmcp.agent.dsl.ActionDsl.Position("minecraft:overworld", 1, 64, 2),
                new dev.aod.mcmcp.agent.action.ContainerInspection.Contents(
                        java.util.UUID.randomUUID(), 5, 7, items));
        var schema = catalog.outputSchema("agent_get_action").getAsJsonObject("properties")
                .getAsJsonObject("container_results").getAsJsonObject("properties")
                .getAsJsonObject("results").getAsJsonObject("items");
        var payload = new com.google.gson.Gson().toJsonTree(result.payload());
        assertThat(CatalogSchemaValidator.matches(schema, payload)).isTrue();
        assertThat(payload.getAsJsonObject().getAsJsonArray("items")).hasSize(54);
        payload.getAsJsonObject().addProperty("truncated", true);
        assertThat(CatalogSchemaValidator.matches(schema, payload)).isFalse();
    }

    @Test
    void keepsOneHttpWorkerAvailableByAdmittingOnlyOneTerminalWait() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var registry = new McmcpToolRegistry((command, context) -> {
            if (command instanceof McpRuntimePort.GetAction) {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(failure);
                }
            }
            return CompletableFuture.completedFuture(McpRuntimePort.RuntimeReply.success(
                    toolResult(command)));
        }, Duration.ofSeconds(1));
        var arguments = new com.google.gson.JsonObject();
        arguments.addProperty("action_id", "550e8400-e29b-41d4-a716-446655440000");
        arguments.addProperty("wait_timeout_ms", 1_000);

        var first = CompletableFuture.supplyAsync(() -> {
            try {
                return registry.call("agent_get_action", arguments);
            } catch (McmcpToolRegistry.UnknownToolException failure) {
                throw new RuntimeException(failure);
            }
        });
        try {
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            var rejected = registry.call("agent_get_action", arguments);
            assertThat(rejected.get("isError").getAsBoolean()).isTrue();
            assertThat(rejected.toString()).contains("SERVER_BUSY");
        } finally {
            release.countDown();
        }
        assertThat(first.get(1, TimeUnit.SECONDS).get("isError").getAsBoolean()).isFalse();
    }

    @Test
    void registryDispatchesExactlyTheFixedFiveToolsAndValidatesTheirOutputs() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var registry = new McmcpToolRegistry((command, context) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(McpRuntimePort.RuntimeReply.success(
                    toolResult(command)));
        }, Duration.ofSeconds(1));

        var state = registry.call("agent_get_state", new com.google.gson.JsonObject());
        assertThat(state.get("isError").getAsBoolean()).isFalse();
        assertThat(state.has("structuredContent")).isTrue();
        assertThat(state.getAsJsonArray("content")).hasSize(1);

        var malformed = new com.google.gson.JsonObject();
        malformed.addProperty("raw_mouse", true);
        var rejected = registry.call("agent_get_state", malformed);
        assertThat(rejected.get("isError").getAsBoolean()).isTrue();
        assertThat(rejected.has("structuredContent")).isFalse();
        assertThat(rejected.toString()).contains("INVALID_ARGUMENT");

        var catalog = new McpToolCatalog();
        var observation = JsonParser.parseString("""
                {"schema_version":1,"frame_id":"obs-0000000000000000",
                 "kinds":["visible_surface"],"cursor":null,"limit":1}
                """).getAsJsonObject();
        var start = catalog.inputSchema("agent_start_action")
                .getAsJsonArray("examples").get(0).getAsJsonObject();
        var action = new com.google.gson.JsonObject();
        action.addProperty("action_id", "550e8400-e29b-41d4-a716-446655440000");
        assertThat(registry.call("agent_get_observation", observation).get("isError").getAsBoolean())
                .isFalse();
        assertThat(registry.call("agent_start_action", start).get("isError").getAsBoolean())
                .isFalse();
        assertThat(registry.call("agent_get_action", action).get("isError").getAsBoolean())
                .isFalse();
        assertThat(registry.call("agent_cancel_action", action).get("isError").getAsBoolean())
                .isFalse();
        assertThat(calls).hasValue(6);
    }

    @Test
    void stateRecipeQueryReusesTheClosedRecipeGrammarWithoutReflectingRejectedInput()
            throws Exception {
        var schema = new McpToolCatalog().inputSchema("agent_get_state");
        var itemQuery = JsonParser.parseString("""
                {"query":{"kind":"result_item","item":"minecraft:stick"},"max_results":4}
                """).getAsJsonObject();
        var tagQuery = JsonParser.parseString("""
                {"query":{"kind":"result_tag","tag":"minecraft:planks"},"max_results":64}
                """).getAsJsonObject();
        assertThat(CatalogSchemaValidator.matches(schema, new com.google.gson.JsonObject())).isTrue();
        assertThat(CatalogSchemaValidator.matches(schema, itemQuery)).isTrue();
        assertThat(CatalogSchemaValidator.matches(schema, tagQuery)).isTrue();

        var commands = new ArrayList<McpRuntimePort.RuntimeCommand>();
        var registry = new McmcpToolRegistry((command, context) -> {
            commands.add(command);
            return CompletableFuture.completedFuture(McpRuntimePort.RuntimeReply.success(
                    command instanceof McpRuntimePort.GetState state
                                    && !state.arguments().isEmpty()
                            ? McpTestFixtures.stateWithEmptyRecipeQuery()
                            : toolResult(command)));
        }, Duration.ofSeconds(1));

        var response = registry.call("agent_get_state", itemQuery);

        assertThat(response.get("isError").getAsBoolean()).isFalse();
        assertThat(commands).singleElement().isInstanceOfSatisfying(
                McpRuntimePort.GetState.class,
                state -> assertThat(new GsonBuilder().create().toJsonTree(state.arguments()))
                        .isEqualTo(itemQuery));

        for (String malformed : List.of(
                "{\"query\":null}",
                "{\"max_results\":null}",
                "{\"query\":null,\"max_results\":null}")) {
            var arguments = JsonParser.parseString(malformed).getAsJsonObject();
            assertThat(CatalogSchemaValidator.matches(schema, arguments)).isFalse();
            assertThat(registry.call("agent_get_state", arguments).get("isError").getAsBoolean())
                    .isTrue();
        }

        var rejectedQuery = JsonParser.parseString("""
                {"query":{"kind":"result_item","item":"minecraft:stick",
                 "private_token":"do-not-reflect"},"max_results":4}
                """).getAsJsonObject();
        var rejected = registry.call("agent_get_state", rejectedQuery).toString();
        assertThat(rejected).contains("INVALID_ARGUMENT")
                .doesNotContain("private_token")
                .doesNotContain("do-not-reflect");
        assertThat(commands).hasSize(1);
    }

    private static List<String> enumValues(
            com.google.gson.JsonObject parent, String property) {
        return parent.getAsJsonObject(property).getAsJsonArray("enum").asList().stream()
                .map(value -> value.getAsString())
                .toList();
    }

    private static Map<String, Object> toolResult(McpRuntimePort.RuntimeCommand command) {
        return switch (command) {
            case McpRuntimePort.GetState state -> state.arguments().isEmpty()
                    ? McpTestFixtures.state()
                    : McpTestFixtures.stateWithEmptyRecipeQuery();
            case McpRuntimePort.GetObservation ignored -> nullableMap(
                    "schema_version", 1,
                    "frame_id", "obs-0000000000000000",
                    "frame_completed_tick", 0,
                    "visible_entities_truncated", false,
                    "records", List.of(),
                    "next_cursor", null,
                    "sampling_coverage", 1);
            case McpRuntimePort.StartAction ignored -> Map.of(
                    "schema_version", 1,
                    "action_id", "550e8400-e29b-41d4-a716-446655440000",
                    "state", "queued",
                    "accepted_at", "2026-08-26T00:00:00Z");
            case McpRuntimePort.GetAction ignored -> nullableMap(
                    "schema_version", 1,
                    "action_id", "550e8400-e29b-41d4-a716-446655440000",
                    "state", "queued",
                    "progress", nullableMap(
                            "phase", "queued", "current_node_id", null,
                            "executed_nodes", 0, "total_node_upper_bound", 1,
                            "distance_travelled", 0, "camera_degrees", 0,
                            "interactions", 0, "blocks_broken", 0,
                            "blocks_placed", 0, "ticks", 0),
                    "failure", null,
                    "trace", List.of(),
                    "effects", List.of(),
                    "effect_aggregate", Map.of(
                            "total_effects", 0,
                            "retained_effects", 0,
                            "confirmed_effects", 0,
                            "qualified_effects", 0,
                            "unknown_effects", 0,
                            "dispatched_attacks", 0,
                            "confirmed_attacks", 0,
                            "unknown_attacks", 0),
                    "partial", null,
                    "source", Map.of(
                            "media_type", "application/vnd.mcmcp.action-dsl+json;version=1",
                            "canonical_json", "{}",
                            "sha256", "sha256:" + "0".repeat(64),
                            "contains_opaque_refs", false,
                            "replayable", true),
                    "template", nullableMap(
                            "media_type", "application/vnd.mcmcp.action-dsl+json;version=1",
                            "canonical_json", "{}",
                            "ready_for_agent_start_action", true,
                            "blocked_by", null),
                    "reference_requirements", List.of());
            case McpRuntimePort.CancelAction ignored -> Map.of(
                    "schema_version", 1,
                    "action_id", "550e8400-e29b-41d4-a716-446655440000",
                    "cancel_requested", true,
                    "state_at_request", "queued");
            case McpRuntimePort.ConfirmActionDelivery delivery -> Map.of(
                    "action_id", delivery.actionId().toString(),
                    "confirmed", true);
            case McpRuntimePort.AbandonActionDelivery delivery -> Map.of(
                    "action_id", delivery.actionId().toString(),
                    "abandoned", true);
            case McpRuntimePort.ConfirmObservationDelivery delivery -> Map.of(
                    "receipt_id", delivery.receiptId().toString(),
                    "confirmed", true);
            case McpRuntimePort.AbandonObservationDelivery delivery -> Map.of(
                    "receipt_id", delivery.receiptId().toString(),
                    "abandoned", true);
            default -> throw new AssertionError("Legacy runtime command escaped the five-tool registry");
        };
    }

    private static Map<String, Object> nullableMap(Object... pairs) {
        var result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < pairs.length; index += 2) {
            result.put((String) pairs[index], pairs[index + 1]);
        }
        return result;
    }
}
