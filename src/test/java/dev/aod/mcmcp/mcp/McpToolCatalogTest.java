package dev.aod.mcmcp.mcp;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import dev.aod.mcmcp.agent.action.AgentActionStore;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner;
import dev.aod.mcmcp.agent.dsl.ActionDslCompiler;
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
                .contains("take_known_container_stack:{id,op,target,expected_block,item,stack_policy,minimum_inventory_count}")
                .contains("craft_known_recipe:{id,op,recipe_ref,recipe_fingerprint,goal:")
                .contains("till_known_batch:{id,op,targets:[position],expected_block,hoe_item}")
                .contains("plant_known_wheat_batch:{id,op,targets:[{target,support}],seed_item}")
                .contains("harvest_known_wheat_batch:{id,op,targets:[position]}")
                .contains("apply_known_redstone_spec:{id,op,anchor,rotation,components:")
                .contains("collect_visible_item_batch:{id,op,targets:[{displayed_item,target}]}")
                .contains("(400 + 3*settle_ticks) ticks")
                .contains("up to the 720 camera-degree policy maximum")
                .contains("split it into smaller batches instead of reordering at runtime")
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
        assertThat(description).contains(opcodes.toArray(String[]::new));

        assertThat(schema.getAsJsonArray("examples").asList())
                .allSatisfy(example -> assertThat(CatalogSchemaValidator.matches(schema, example))
                        .isTrue());
        assertThat(schema.getAsJsonArray("examples").asList().stream()
                .map(element -> element.getAsJsonObject())
                .map(example -> example.getAsJsonObject("program").getAsJsonArray("body")
                        .get(0).getAsJsonObject().get("op").getAsString()))
                .contains("take_known_container_stack");

        assertThat(description)
                .contains("take stack_policy is exactly default_components_only")
                .contains("or item_id_any_components")
                .contains("craft goal.stack_policy is exactly default_components_only");
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
                        "identity slice only",
                        "minecraft:glass UP support",
                        "four remaining face-neighbors",
                        "same-client-tick live visual pairs",
                        "never moves");

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
                .get("description").getAsString()).contains("30000 ms", "600 ticks", "360 camera");

        var containerExamples = schema.getAsJsonArray("examples").asList().stream()
                .map(example -> example.getAsJsonObject())
                .filter(example -> {
                    String op = example.getAsJsonObject("program")
                            .getAsJsonArray("body").get(0).getAsJsonObject()
                            .get("op").getAsString();
                    return op.equals("inspect_known_container")
                            || op.equals("take_known_container_stack");
                })
                .toList();

        assertThat(containerExamples).hasSize(2).allSatisfy(example -> {
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
                .contains("execution-start preflight", "one-way stand-center",
                        "270 degrees", "face_known_position")
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
                .get("maximum").getAsInt()).isEqualTo(16);
        var state = catalog.outputSchema("agent_get_state");
        assertThat(state.getAsJsonObject("properties").getAsJsonObject("policy")
                .getAsJsonObject("properties").getAsJsonObject("max_interactions")
                .get("const").getAsInt()).isEqualTo(16);
        assertThat(state.getAsJsonObject("properties").has("standard_potions")).isTrue();
        assertThat(enumValues(state.getAsJsonObject("properties")
                        .getAsJsonObject("standard_potions").getAsJsonObject("items")
                        .getAsJsonObject("properties"), "potion"))
                .containsExactlyInAnyOrderElementsOf(StandardPotionPolicy.potionIds());
        assertThat(catalog.outputSchema("agent_get_action")
                .getAsJsonObject("properties").getAsJsonObject("progress")
                .getAsJsonObject("properties").getAsJsonObject("interactions")
                .get("maximum").getAsInt()).isEqualTo(16);
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
                .contains("position_bounds")
                .contains("traversability at navigation_target")
                .contains("two paged queries")
                .contains("On FRAME_EXPIRED, call agent_get_state")
                .contains("observation.latest_frame_id");

        var observationSchema = new McpToolCatalog().inputSchema("agent_get_observation");
        var filteredObservation = JsonParser.parseString("""
                {"schema_version":1,"frame_id":"obs-0000000000000000",
                 "kinds":["visible_surface","visible_entity","traversability"],
                 "filter":{"displayed_items":["minecraft:wheat"],
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
                        JsonParser.parseString("\"inventory_transfer\""));
    }

    @Test
    void catalogClosesAndBoundsCropMaturityWaits() {
        var schema = new McpToolCatalog().inputSchema("agent_start_action");
        var request = schema.getAsJsonArray("examples").get(0).getAsJsonObject().deepCopy();
        var wait = JsonParser.parseString("""
                {"id":"await_mature","op":"wait_until",
                 "condition":{"type":"crop_mature","target":{
                   "dimension":"minecraft:overworld","x":10,"y":65,"z":10}},
                 "max_ticks":12000}
                """).getAsJsonObject();
        request.getAsJsonObject("program").add("body", new com.google.gson.JsonArray());
        request.getAsJsonObject("program").getAsJsonArray("body").add(wait);
        request.getAsJsonObject("budget").addProperty("max_duration_ms", 600_000);
        request.getAsJsonObject("budget").addProperty("max_ticks", 12_000);
        assertThat(CatalogSchemaValidator.matches(schema, request)).isTrue();

        var unsupported = request.deepCopy();
        unsupported.getAsJsonObject("program").getAsJsonArray("body").get(0)
                .getAsJsonObject().getAsJsonObject("condition")
                .addProperty("type", "expression");
        assertThat(CatalogSchemaValidator.matches(schema, unsupported)).isFalse();

        var unbounded = request.deepCopy();
        unbounded.getAsJsonObject("program").getAsJsonArray("body").get(0)
                .getAsJsonObject().addProperty("max_ticks", 12_001);
        assertThat(CatalogSchemaValidator.matches(schema, unbounded)).isFalse();
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
                   "stack_policy":"default_components_only","minimum_inventory_count":64}
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
                    "trace", List.of());
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
