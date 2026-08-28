package dev.aod.mcmcp.agent.dsl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActionDslTest {
    @Test
    void parsesEveryNormativeCatalogExample() throws IOException {
        JsonArray examples = startActionSchema().getAsJsonArray("examples");

        assertThat(examples).hasSize(9);
        for (int index = 0; index < examples.size(); index++) {
            ActionDsl.Request parsed = ActionDslParser.parse(examples.get(index).getAsJsonObject());
            assertThat(parsed.schemaVersion()).isEqualTo(1);
            assertThat(parsed.program().dslVersion()).isEqualTo(1);
            assertThat(ActionDslValidator.validate(parsed).sourceNodes()).isPositive();
        }
    }

    @Test
    void compilesSequenceAndComponentWiseIfMaximumAndEvaluatesSnapshotOnce() throws IOException {
        ActionDsl.Request request = ActionDslParser.parse(exampleNamed("approach_and_face"));
        var compiled = ActionDslCompiler.compile(
                request,
                primitive -> {
                    if (primitive instanceof ActionDsl.NavigateToKnown) {
                        return Optional.of(new ActionDslCompiler.Cost(
                                10_000, 200, 12, 80, 0, 0, 0));
                    }
                    if (primitive instanceof ActionDsl.FaceKnownPosition) {
                        return Optional.of(new ActionDslCompiler.Cost(
                                1_000, 20, 0, 90, 0, 0, 0));
                    }
                    return Optional.empty();
                },
                Set.of(ActionDsl.Capability.MOVEMENT, ActionDsl.Capability.CAMERA));

        // The else wait is 20 ticks / 1000 ms. The if takes each component's branch maximum.
        assertThat(compiled.worstCaseCost()).isEqualTo(
                new ActionDslCompiler.Cost(11_000, 220, 12, 170, 0, 0, 0));
        assertThat(compiled.sourceNodes()).isEqualTo(4);
        assertThat(compiled.executedNodesUpperBound()).isEqualTo(3);

        var condition = ((ActionDsl.If) request.program().body().get(1)).condition();
        var available = snapshot(Map.of(ActionDsl.NumericField.HEALTH, 9.0));
        assertThat(PredicateEvaluator.evaluate(condition, available)).isTrue();
        assertThatThrownBy(() -> PredicateEvaluator.evaluate(condition, snapshot(Map.of())))
                .isInstanceOf(ActionDslException.class)
                .extracting(failure -> ((ActionDslException) failure).code())
                .isEqualTo(ActionDslException.Code.PREDICATE_UNAVAILABLE);
    }

    @Test
    void compiledProgramRetainsEveryPrimitiveOccurrenceCostBound() {
        ActionDsl.Request request = ActionDslParser.parse(request(
                capabilities("movement", "camera"),
                array(waitNode("hold", 3), navigate("move"), face("look")),
                budget(30_000, 600, 32, 360)));
        var navigation = new ActionDslCompiler.Cost(1_000, 20, 2, 0, 0, 0, 0);
        var camera = new ActionDslCompiler.Cost(500, 10, 0, 45, 0, 0, 0);

        var compiled = ActionDslCompiler.compile(
                request,
                primitive -> Optional.of(primitive instanceof ActionDsl.NavigateToKnown
                        ? navigation : camera),
                Set.of(ActionDsl.Capability.MOVEMENT, ActionDsl.Capability.CAMERA));

        assertThat(compiled.primitiveCostBounds())
                .hasSize(3)
                .containsEntry("hold", new ActionDslCompiler.Cost(150, 3, 0, 0, 0, 0, 0))
                .containsEntry("move", navigation)
                .containsEntry("look", camera);
    }

    @Test
    void repeatMultipliesTheComponentWiseBranchCost() {
        JsonObject request = request(
                capabilities("movement", "camera"),
                repeat("twice", 2, array(
                        conditional("choose", numeric("health", "gte", 8),
                                array(face("look")), array(navigate("move"))),
                        waitNode("settle", 3))),
                budget(30_000, 600, 32, 360));
        var compiled = ActionDslCompiler.compile(
                ActionDslParser.parse(request),
                primitive -> {
                    if (primitive instanceof ActionDsl.FaceKnownPosition) {
                        return Optional.of(new ActionDslCompiler.Cost(100, 2, 0, 30, 0, 0, 0));
                    }
                    return Optional.of(new ActionDslCompiler.Cost(200, 5, 4, 5, 0, 0, 0));
                },
                Set.of(ActionDsl.Capability.MOVEMENT, ActionDsl.Capability.CAMERA));

        assertThat(compiled.worstCaseCost())
                .isEqualTo(new ActionDslCompiler.Cost(700, 16, 8, 60, 0, 0, 0));
        assertThat(compiled.executedNodesUpperBound()).isEqualTo(7);
    }

    @Test
    void parsesAndCompilesBoundedKnownFaceBreaks() {
        JsonArray breaks = new JsonArray();
        for (int index = 0; index < 8; index++) {
            JsonObject block = breakKnownFace("break_" + index);
            block.add("target", position(64 + index));
            breaks.add(block);
        }
        ActionDsl.Request request = ActionDslParser.parse(request(
                capabilities("camera", "block_break"),
                breaks,
                budget(30_000, 600, 0, 360, 0, 8, 0)));

        ActionDsl.BreakKnownFace breaking =
                (ActionDsl.BreakKnownFace) request.program().body().getFirst();
        assertThat(breaking.target()).isEqualTo(new ActionDsl.Position(
                "minecraft:overworld", 10, 64, 10));
        assertThat(breaking.face()).isEqualTo(ActionDsl.BlockFace.WEST);
        assertThat(breaking.expectedBlock()).isEqualTo("minecraft:oak_log");
        assertThat(breaking.toolItem()).isEqualTo("minecraft:iron_axe");
        assertThat(ActionDslValidator.validate(request).requiredCapabilities())
                .containsExactlyInAnyOrder(
                        ActionDsl.Capability.CAMERA,
                        ActionDsl.Capability.BLOCK_BREAK);

        var perBreak = new ActionDslCompiler.Cost(3_750, 75, 0, 45, 0, 1, 0);
        var compiled = ActionDslCompiler.compile(
                request,
                primitive -> Optional.of(perBreak),
                Set.of(ActionDsl.Capability.CAMERA, ActionDsl.Capability.BLOCK_BREAK));

        assertThat(compiled.worstCaseCost())
                .isEqualTo(new ActionDslCompiler.Cost(30_000, 600, 0, 360, 0, 8, 0));
        assertThat(compiled.primitiveCostBounds()).containsEntry("break_0", perBreak);
    }

    @Test
    void parsesAndCompilesClosedWheatMutations() {
        JsonArray body = array(
                tillKnownBlock("till"),
                plantKnownWheat("plant"),
                harvestKnownWheat("harvest"));
        ActionDsl.Request request = ActionDslParser.parse(request(
                capabilities("camera", "block_interact", "block_place", "block_break"),
                body,
                budget(30_000, 600, 0, 360, 1, 1, 1)));

        assertThat(request.program().body())
                .extracting(node -> node.getClass().getSimpleName())
                .containsExactly("TillKnownBlock", "PlantKnownWheat", "HarvestKnownWheat");
        assertThat(ActionDslValidator.validate(request).requiredCapabilities())
                .containsExactlyInAnyOrder(
                        ActionDsl.Capability.CAMERA,
                        ActionDsl.Capability.BLOCK_INTERACT,
                        ActionDsl.Capability.BLOCK_PLACE,
                        ActionDsl.Capability.BLOCK_BREAK);

        var compiled = ActionDslCompiler.compile(
                request,
                node -> Optional.of(node instanceof ActionDsl.TillKnownBlock
                        ? new ActionDslCompiler.Cost(5_000, 100, 0, 60, 1, 0, 0)
                        : node instanceof ActionDsl.PlantKnownWheat
                        ? new ActionDslCompiler.Cost(5_000, 100, 0, 60, 0, 0, 1)
                        : new ActionDslCompiler.Cost(5_000, 100, 0, 60, 0, 1, 0)),
                Set.of(
                        ActionDsl.Capability.CAMERA,
                        ActionDsl.Capability.BLOCK_INTERACT,
                        ActionDsl.Capability.BLOCK_PLACE,
                        ActionDsl.Capability.BLOCK_BREAK));
        assertThat(compiled.worstCaseCost())
                .isEqualTo(new ActionDslCompiler.Cost(15_000, 300, 0, 180, 1, 1, 1));
    }

    @Test
    void parsesAndCompilesWoodenPassageAndBoundedContainerNodes() {
        JsonObject open = baseNode("open", "open_known_passage");
        open.add("target", position());
        open.addProperty("expected_block", "minecraft:oak_door");
        JsonObject inspect = baseNode("inspect", "inspect_known_container");
        inspect.add("target", position());
        inspect.addProperty("expected_block", "minecraft:chest");
        JsonObject take = baseNode("take", "take_known_container_stack");
        take.add("target", position());
        take.addProperty("expected_block", "minecraft:chest");
        take.addProperty("item", "minecraft:wheat_seeds");
        take.addProperty("stack_policy", "default_components_only");
        take.addProperty("minimum_inventory_count", 64);

        ActionDsl.Request request = ActionDslParser.parse(request(
                capabilities("camera", "block_interact", "inventory_transfer"),
                array(open, inspect, take),
                budget(600_000, 12_000, 0, 360, 5, 0, 0)));

        assertThat(ActionDslValidator.validate(request).requiredCapabilities())
                .containsExactlyInAnyOrder(
                        ActionDsl.Capability.CAMERA,
                        ActionDsl.Capability.BLOCK_INTERACT,
                        ActionDsl.Capability.INVENTORY_TRANSFER);
        var compiled = ActionDslCompiler.compile(
                request,
                node -> Optional.of(new ActionDslCompiler.Cost(
                        1_000, 20, 0, 30,
                        node instanceof ActionDsl.TakeKnownContainerStack ? 3 : 1,
                        0, 0)),
                request.program().capabilities());
        assertThat(compiled.worstCaseCost().interactions()).isEqualTo(5);

        open.addProperty("expected_block", "minecraft:iron_door");
        assertThatThrownBy(() -> ActionDslValidator.validate(ActionDslParser.parse(request(
                capabilities("camera", "block_interact"), open,
                budget(1_000, 20, 0, 30, 1, 0, 0)))))
                .isInstanceOf(ActionDslException.class);
    }

    @Test
    void parsesAndCompilesOnlyTheClosedOpenFenceGateShape() {
        ActionDsl.Request request = ActionDslParser.parse(request(
                capabilities("camera", "block_interact"),
                openKnownFenceGate("open_gate"),
                budget(5_000, 100, 0, 60, 1, 0, 0)));

        assertThat(request.program().body()).singleElement()
                .isInstanceOf(ActionDsl.OpenKnownFenceGate.class);
        assertThat(ActionDslValidator.validate(request).requiredCapabilities())
                .containsExactlyInAnyOrder(
                        ActionDsl.Capability.CAMERA,
                        ActionDsl.Capability.BLOCK_INTERACT);
        var cost = new ActionDslCompiler.Cost(5_000, 100, 0, 60, 1, 0, 0);
        assertThat(ActionDslCompiler.compile(
                request,
                node -> Optional.of(cost),
                request.program().capabilities()).worstCaseCost()).isEqualTo(cost);

        JsonObject openNode = openKnownFenceGate("open_gate");
        openNode.addProperty("expected_block", "minecraft:oak_fence_gate");
        assertCode(request(
                        capabilities("camera", "block_interact"),
                        openNode,
                        budget(5_000, 100, 0, 60, 1, 0, 0)),
                ActionDslException.Code.INVALID_ARGUMENT);
        assertCode(request(
                        capabilities("block_interact"),
                        openKnownFenceGate("open_gate"),
                        budget(5_000, 100, 0, 60, 1, 0, 0)),
                ActionDslException.Code.CAPABILITY_DENIED);
    }

    @Test
    void parsesAndCompilesBoundedCropMaturityWait() {
        ActionDsl.Request request = ActionDslParser.parse(request(
                capabilities(), waitUntil("await_mature", 12_000),
                budget(600_000, 12_000, 0, 0)));

        ActionDsl.WaitUntil wait = (ActionDsl.WaitUntil) request.program().body().getFirst();
        assertThat(wait.condition().target()).isEqualTo(new ActionDsl.Position(
                "minecraft:overworld", 10, 65, 10));
        assertThat(wait.maxTicks()).isEqualTo(12_000);
        assertThat(ActionDslValidator.validate(request).requiredCapabilities()).isEmpty();

        var compiled = ActionDslCompiler.compile(
                request,
                primitive -> {
                    throw new AssertionError("wait_until must have an intrinsic cost");
                },
                Set.of());
        var expected = new ActionDslCompiler.Cost(600_000, 12_000, 0, 0, 0, 0, 0);
        assertThat(compiled.worstCaseCost()).isEqualTo(expected);
        assertThat(compiled.primitiveCostBounds()).containsOnlyKeys("await_mature")
                .containsEntry("await_mature", expected);
    }

    @Test
    void rejectsOpenOrUnboundedCropMaturityWaits() {
        assertCode(request(capabilities(), waitNode("legacy_wait", 201),
                        budget(600_000, 12_000, 0, 0)),
                ActionDslException.Code.INVALID_ARGUMENT);

        for (int maxTicks : new int[] {0, 12_001}) {
            assertCode(request(capabilities(), waitUntil("bad_ticks", maxTicks),
                            budget(600_000, 12_000, 0, 0)),
                    ActionDslException.Code.INVALID_ARGUMENT);
        }

        JsonObject unsupported = waitUntil("bad_type", 1);
        unsupported.getAsJsonObject("condition").addProperty("type", "block_matches");
        assertCode(request(capabilities(), unsupported, budget(100, 2, 0, 0)),
                ActionDslException.Code.INVALID_ARGUMENT);

        JsonObject open = waitUntil("open_condition", 1);
        open.getAsJsonObject("condition").addProperty("expression", "age >= 7");
        assertCode(request(capabilities(), open, budget(100, 2, 0, 0)),
                ActionDslException.Code.INVALID_ARGUMENT);

        assertCode(request(capabilities(), waitUntil("long", 12_000),
                        budget(600_001, 12_000, 0, 0)),
                ActionDslException.Code.INVALID_ARGUMENT);
        assertCode(request(capabilities(), waitUntil("long", 12_000),
                        budget(600_000, 12_001, 0, 0)),
                ActionDslException.Code.INVALID_ARGUMENT);
    }

    @Test
    void acceptsTwoRevolutionsOfActionCameraButRejectsMore() {
        var maximum = request(
                capabilities("camera"), face("look"),
                budget(30_000, 600, 0, ActionDslValidator.MAX_ACTION_CAMERA_DEGREES));
        assertThat(ActionDslValidator.validate(ActionDslParser.parse(maximum)))
                .isNotNull();

        var excessive = request(
                capabilities("camera"), face("look"),
                budget(30_000, 600, 0,
                        ActionDslValidator.MAX_ACTION_CAMERA_DEGREES + 0.01D));
        assertCode(excessive, ActionDslException.Code.INVALID_ARGUMENT);
    }

    @Test
    void rejectsUntypedBreakTargetsToolsFacesCapabilitiesAndCosts() {
        JsonObject missingCamera = request(
                capabilities("block_break"), breakKnownFace("break_log"),
                budget(3_750, 75, 0, 45, 0, 1, 0));
        assertCode(missingCamera, ActionDslException.Code.CAPABILITY_DENIED);

        JsonObject wrongFace = breakKnownFace("wrong_face");
        wrongFace.addProperty("face", "center");
        assertCode(request(capabilities("camera", "block_break"), wrongFace,
                        budget(3_750, 75, 0, 45, 0, 1, 0)),
                ActionDslException.Code.INVALID_ARGUMENT);

        JsonObject wrongLog = breakKnownFace("wrong_log");
        wrongLog.addProperty("expected_block", "minecraft:spruce_log");
        assertCode(request(capabilities("camera", "block_break"), wrongLog,
                        budget(3_750, 75, 0, 45, 0, 1, 0)),
                ActionDslException.Code.INVALID_ARGUMENT);

        JsonObject wrongTool = breakKnownFace("wrong_tool");
        wrongTool.addProperty("tool_item", "minecraft:diamond_pickaxe");
        assertCode(request(capabilities("camera", "block_break"), wrongTool,
                        budget(3_750, 75, 0, 45, 0, 1, 0)),
                ActionDslException.Code.INVALID_ARGUMENT);

        ActionDsl.Request valid = ActionDslParser.parse(request(
                capabilities("camera", "block_break"), breakKnownFace("break_log"),
                budget(3_750, 75, 0, 45, 0, 1, 0)));
        for (var invalid : new ActionDslCompiler.Cost[] {
                new ActionDslCompiler.Cost(3_750, 75, 1, 45, 0, 1, 0),
                new ActionDslCompiler.Cost(3_750, 75, 0, 45, 1, 1, 0),
                new ActionDslCompiler.Cost(3_750, 75, 0, 45, 0, 0, 0),
                new ActionDslCompiler.Cost(3_750, 75, 0, 45, 0, 1, 1)
        }) {
            assertThatThrownBy(() -> ActionDslCompiler.compile(
                            valid,
                            primitive -> Optional.of(invalid),
                            Set.of(ActionDsl.Capability.CAMERA, ActionDsl.Capability.BLOCK_BREAK)))
                    .isInstanceOf(ActionDslException.class)
                    .extracting(failure -> ((ActionDslException) failure).code())
                    .isEqualTo(ActionDslException.Code.PROGRAM_BUDGET_UNPROVABLE);
        }

        assertCode(request(
                        capabilities("camera", "block_break"),
                        repeat("twice", 2, array(breakKnownFace("break_log"))),
                        budget(7_500, 150, 0, 90, 0, 2, 0)),
                ActionDslException.Code.PROGRAM_BUDGET_UNPROVABLE);
    }

    @Test
    void rejectsUnknownOpcodeArbitraryKeysAndUntrustedPredicateFields() {
        JsonObject rawKey = waitNode("bad", 1);
        rawKey.addProperty("key", "W");
        assertCode(request(capabilities(), rawKey, budget(100, 2, 0, 0)),
                ActionDslException.Code.INVALID_ARGUMENT);

        JsonObject unknown = waitNode("bad", 1);
        unknown.addProperty("op", "raw_key_input");
        assertCode(request(capabilities(), unknown, budget(100, 2, 0, 0)),
                ActionDslException.Code.INVALID_ARGUMENT);

        JsonObject chatCondition = new JsonObject();
        chatCondition.addProperty("field", "chat");
        chatCondition.addProperty("comparison", "eq");
        chatCondition.addProperty("value", true);
        assertCode(request(capabilities(),
                        conditional("bad", chatCondition, array(), array()),
                        budget(100, 2, 0, 0)),
                ActionDslException.Code.INVALID_ARGUMENT);
    }

    @Test
    void enforcesDepthSourceExpandedRepeatAndUniqueIdBounds() {
        JsonObject depthFive = waitNode("leaf", 1);
        for (int depth = 4; depth >= 1; depth--) {
            depthFive = repeat("depth_" + depth, 1, array(depthFive));
        }
        assertCode(request(capabilities(), depthFive, budget(1_000, 20, 0, 0)),
                ActionDslException.Code.PROGRAM_TOO_COMPLEX);

        JsonArray top = new JsonArray();
        for (int index = 0; index < 31; index++) {
            top.add(waitNode("top_" + index, 1));
        }
        JsonArray outerThen = new JsonArray();
        for (int index = 0; index < 15; index++) {
            outerThen.add(waitNode("then_" + index, 1));
        }
        outerThen.add(conditional("inner", numeric("health", "gte", 1),
                array(waitNode("inner_leaf", 1)), array()));
        JsonArray outerElse = new JsonArray();
        for (int index = 0; index < 16; index++) {
            outerElse.add(waitNode("else_" + index, 1));
        }
        top.add(conditional("outer", numeric("health", "gte", 1), outerThen, outerElse));
        assertCode(request(capabilities(), top, budget(30_000, 600, 0, 0)),
                ActionDslException.Code.PROGRAM_TOO_COMPLEX);

        JsonArray sixteen = new JsonArray();
        for (int index = 0; index < 16; index++) {
            sixteen.add(waitNode("expanded_" + index, 1));
        }
        assertCode(request(capabilities(), repeat("expanded", 16, sixteen),
                        budget(30_000, 600, 0, 0)),
                ActionDslException.Code.PROGRAM_TOO_COMPLEX);
        assertCode(request(capabilities(), repeat("repeat_17", 17, array(waitNode("once", 1))),
                        budget(1_000, 20, 0, 0)),
                ActionDslException.Code.INVALID_ARGUMENT);
        assertCode(request(capabilities(), array(waitNode("same", 1), waitNode("same", 1)),
                        budget(1_000, 20, 0, 0)),
                ActionDslException.Code.INVALID_ARGUMENT);
    }

    @Test
    void acceptsExactExpandedBoundaryAndRejectsRequestsOver64KiB() {
        JsonArray body = new JsonArray();
        for (int index = 0; index < 15; index++) {
            body.add(waitNode("body_" + index, 1));
        }
        JsonArray top = new JsonArray();
        for (int index = 0; index < 15; index++) {
            top.add(waitNode("top_" + index, 1));
        }
        top.add(repeat("repeat_16", 16, body));

        ActionDsl.Request parsed = ActionDslParser.parse(
                request(capabilities(), top, budget(30_000, 600, 0, 0)));
        assertThat(ActionDslValidator.validate(parsed).executedNodesUpperBound()).isEqualTo(256);

        String oversized = " ".repeat(ActionDslValidator.MAX_REQUEST_BYTES) + "{}";
        assertThatThrownBy(() -> ActionDslParser.parse(oversized))
                .isInstanceOf(ActionDslException.class)
                .extracting(failure -> ((ActionDslException) failure).code())
                .isEqualTo(ActionDslException.Code.PROGRAM_TOO_COMPLEX);
    }

    @Test
    void enforcesDeclaredLocalCapabilitiesAndEffectiveBudget() {
        assertCode(request(capabilities(), navigate("go"), budget(30_000, 600, 32, 0)),
                ActionDslException.Code.CAPABILITY_DENIED);

        ActionDsl.Request request = ActionDslParser.parse(request(
                capabilities("movement"), navigate("go"), budget(30_000, 600, 5, 90)));
        var costs = (ActionDslCompiler.PrimitiveCostModel) primitive -> Optional.of(
                new ActionDslCompiler.Cost(2_000, 40, 6, 20, 0, 0, 0));
        assertThatThrownBy(() -> ActionDslCompiler.compile(
                        request, costs, Set.of(ActionDsl.Capability.MOVEMENT)))
                .isInstanceOf(ActionDslException.class)
                .extracting(failure -> ((ActionDslException) failure).code())
                .isEqualTo(ActionDslException.Code.PROGRAM_BUDGET_UNPROVABLE);
        assertThatThrownBy(() -> ActionDslCompiler.compile(request, costs, Set.of()))
                .isInstanceOf(ActionDslException.class)
                .extracting(failure -> ((ActionDslException) failure).code())
                .isEqualTo(ActionDslException.Code.CAPABILITY_DENIED);

        ActionDsl.Request interactionBudget = ActionDslParser.parse(request(
                capabilities(), waitNode("wait", 1), budget(1_000, 20, 0, 0, 1, 0, 1)));
        assertThat(interactionBudget.budget().maxInteractions()).isOne();
        assertThat(interactionBudget.budget().maxBlocksPlaced()).isOne();

        assertCode(request(capabilities(), waitNode("wait", 1),
                        budget(1_000, 20, 0, 0, 0, 9, 0)),
                ActionDslException.Code.INVALID_ARGUMENT);
        assertCode(request(capabilities(), waitNode("wait", 1),
                        budget(1_000, 20, 0, 0, 9, 0, 0)),
                ActionDslException.Code.INVALID_ARGUMENT);
    }

    @Test
    void logicalPredicatesEvaluateAllOperandsAndNeverHideUnavailableFields() {
        JsonObject all = new JsonObject();
        all.add("all", array(
                numeric("health", "gte", 1),
                booleanPredicate("on_fire", false),
                inventory("minecraft:oak_log", "gte", 2),
                status("minecraft:fire_resistance", false)));
        ActionDsl.Request request = ActionDslParser.parse(request(
                capabilities(), conditional("gate", all, array(), array()), budget(100, 2, 0, 0)));
        ActionDsl.Predicate predicate = ((ActionDsl.If) request.program().body().getFirst()).condition();
        var snapshot = new TestSnapshot(
                Map.of(ActionDsl.NumericField.HEALTH, 20.0),
                Map.of(ActionDsl.BooleanField.ON_FIRE, false),
                Map.of("minecraft:oak_log", 2),
                Map.of("minecraft:fire_resistance", false));
        assertThat(PredicateEvaluator.evaluate(predicate, snapshot)).isTrue();

        var missingLastOperand = new TestSnapshot(
                Map.of(ActionDsl.NumericField.HEALTH, 0.0),
                Map.of(ActionDsl.BooleanField.ON_FIRE, false),
                Map.of("minecraft:oak_log", 2),
                Map.of());
        assertThatThrownBy(() -> PredicateEvaluator.evaluate(predicate, missingLastOperand))
                .isInstanceOf(ActionDslException.class)
                .extracting(failure -> ((ActionDslException) failure).code())
                .isEqualTo(ActionDslException.Code.PREDICATE_UNAVAILABLE);
    }

    private static JsonObject startActionSchema() throws IOException {
        Path catalogPath = Path.of(
                System.getProperty("mcmcp.projectDir"), "docs", "MCMCP_MCP_Tool_Catalog.json");
        JsonArray tools = JsonParser.parseString(Files.readString(catalogPath))
                .getAsJsonObject().getAsJsonArray("tools");
        for (var tool : tools) {
            JsonObject object = tool.getAsJsonObject();
            if ("agent_start_action".equals(object.get("name").getAsString())) {
                return object.getAsJsonObject("inputSchema");
            }
        }
        throw new AssertionError("agent_start_action catalog entry not found");
    }

    private static JsonObject exampleNamed(String name) throws IOException {
        for (var example : startActionSchema().getAsJsonArray("examples")) {
            JsonObject object = example.getAsJsonObject();
            if (name.equals(object.getAsJsonObject("program").get("name").getAsString())) {
                return object;
            }
        }
        throw new AssertionError("agent_start_action example not found: " + name);
    }

    private static void assertCode(JsonObject source, ActionDslException.Code code) {
        assertThatThrownBy(() -> ActionDslParser.parse(source))
                .isInstanceOf(ActionDslException.class)
                .extracting(failure -> ((ActionDslException) failure).code())
                .isEqualTo(code);
    }

    private static PolicySnapshot snapshot(Map<ActionDsl.NumericField, Double> numbers) {
        return new TestSnapshot(numbers, Map.of(), Map.of(), Map.of());
    }

    private static JsonObject request(JsonArray capabilities, JsonObject node, JsonObject budget) {
        return request(capabilities, array(node), budget);
    }

    private static JsonObject request(JsonArray capabilities, JsonArray body, JsonObject budget) {
        JsonObject program = new JsonObject();
        program.addProperty("dsl_version", 1);
        program.add("capabilities", capabilities);
        program.add("body", body);
        JsonObject request = new JsonObject();
        request.addProperty("schema_version", 1);
        request.add("program", program);
        request.add("budget", budget);
        return request;
    }

    private static JsonObject budget(long duration, long ticks, double distance, double camera) {
        return budget(duration, ticks, distance, camera, 0, 0, 0);
    }

    private static JsonObject budget(
            long duration,
            long ticks,
            double distance,
            double camera,
            long interactions,
            long blocksBroken,
            long blocksPlaced) {
        JsonObject budget = new JsonObject();
        budget.addProperty("max_duration_ms", duration);
        budget.addProperty("max_ticks", ticks);
        budget.addProperty("max_distance_blocks", distance);
        budget.addProperty("max_camera_degrees", camera);
        budget.addProperty("max_interactions", interactions);
        budget.addProperty("max_blocks_broken", blocksBroken);
        budget.addProperty("max_blocks_placed", blocksPlaced);
        return budget;
    }

    private static JsonArray capabilities(String... values) {
        JsonArray result = new JsonArray();
        for (String value : values) result.add(value);
        return result;
    }

    private static JsonArray array(JsonObject... values) {
        JsonArray result = new JsonArray();
        for (JsonObject value : values) result.add(value);
        return result;
    }

    private static JsonObject navigate(String id) {
        JsonObject node = baseNode(id, "navigate_to_known");
        node.add("target", position());
        node.addProperty("tolerance", 0.75);
        return node;
    }

    private static JsonObject face(String id) {
        JsonObject node = baseNode(id, "face_known_position");
        node.add("target", position());
        return node;
    }

    private static JsonObject breakKnownFace(String id) {
        JsonObject node = baseNode(id, "break_known_face");
        node.add("target", position());
        node.addProperty("face", "west");
        node.addProperty("expected_block", "minecraft:oak_log");
        node.addProperty("tool_item", "minecraft:iron_axe");
        return node;
    }

    private static JsonObject tillKnownBlock(String id) {
        JsonObject node = baseNode(id, "till_known_block");
        node.add("target", position());
        node.addProperty("expected_block", "minecraft:dirt");
        node.addProperty("hoe_item", "minecraft:iron_hoe");
        return node;
    }

    private static JsonObject plantKnownWheat(String id) {
        JsonObject node = baseNode(id, "plant_known_wheat");
        node.add("target", position(65));
        node.add("support", position(64));
        node.addProperty("seed_item", "minecraft:wheat_seeds");
        return node;
    }

    private static JsonObject harvestKnownWheat(String id) {
        JsonObject node = baseNode(id, "harvest_known_wheat");
        node.add("target", position(65));
        return node;
    }

    private static JsonObject openKnownFenceGate(String id) {
        JsonObject node = baseNode(id, "open_known_fence_gate");
        node.add("target", position());
        return node;
    }

    private static JsonObject position(int y) {
        JsonObject value = position();
        value.addProperty("y", y);
        return value;
    }

    private static JsonObject waitNode(String id, int ticks) {
        JsonObject node = baseNode(id, "wait_ticks");
        node.addProperty("ticks", ticks);
        return node;
    }

    private static JsonObject waitUntil(String id, int maxTicks) {
        JsonObject condition = new JsonObject();
        condition.addProperty("type", "crop_mature");
        condition.add("target", position(65));
        JsonObject node = baseNode(id, "wait_until");
        node.add("condition", condition);
        node.addProperty("max_ticks", maxTicks);
        return node;
    }

    private static JsonObject conditional(
            String id, JsonObject condition, JsonArray thenBranch, JsonArray elseBranch) {
        JsonObject node = baseNode(id, "if");
        node.add("condition", condition);
        node.add("then", thenBranch);
        node.add("else", elseBranch);
        return node;
    }

    private static JsonObject repeat(String id, int count, JsonArray body) {
        JsonObject node = baseNode(id, "repeat");
        node.addProperty("count", count);
        node.add("body", body);
        return node;
    }

    private static JsonObject numeric(String field, String comparison, double value) {
        JsonObject predicate = new JsonObject();
        predicate.addProperty("field", field);
        predicate.addProperty("comparison", comparison);
        predicate.addProperty("value", value);
        return predicate;
    }

    private static JsonObject booleanPredicate(String field, boolean value) {
        JsonObject predicate = new JsonObject();
        predicate.addProperty("field", field);
        predicate.addProperty("comparison", "eq");
        predicate.addProperty("value", value);
        return predicate;
    }

    private static JsonObject inventory(String item, String comparison, int value) {
        JsonObject predicate = new JsonObject();
        predicate.addProperty("field", "inventory_count");
        predicate.addProperty("item", item);
        predicate.addProperty("comparison", comparison);
        predicate.addProperty("value", value);
        return predicate;
    }

    private static JsonObject status(String effect, boolean value) {
        JsonObject predicate = new JsonObject();
        predicate.addProperty("field", "has_status_effect");
        predicate.addProperty("effect", effect);
        predicate.addProperty("comparison", "eq");
        predicate.addProperty("value", value);
        return predicate;
    }

    private static JsonObject baseNode(String id, String operation) {
        JsonObject node = new JsonObject();
        node.addProperty("id", id);
        node.addProperty("op", operation);
        return node;
    }

    private static JsonObject position() {
        JsonObject position = new JsonObject();
        position.addProperty("dimension", "minecraft:overworld");
        position.addProperty("x", 10);
        position.addProperty("y", 64);
        position.addProperty("z", 10);
        return position;
    }

    private record TestSnapshot(
            Map<ActionDsl.NumericField, Double> numbers,
            Map<ActionDsl.BooleanField, Boolean> booleans,
            Map<String, Integer> inventory,
            Map<String, Boolean> statuses) implements PolicySnapshot {
        private TestSnapshot {
            numbers = Map.copyOf(numbers);
            booleans = Map.copyOf(booleans);
            inventory = Map.copyOf(inventory);
            statuses = Map.copyOf(statuses);
        }

        @Override
        public OptionalDouble numeric(ActionDsl.NumericField field) {
            Double value = numbers.get(field);
            return value == null ? OptionalDouble.empty() : OptionalDouble.of(value);
        }

        @Override
        public Optional<Boolean> bool(ActionDsl.BooleanField field) {
            return Optional.ofNullable(booleans.get(field));
        }

        @Override
        public OptionalInt inventoryCount(String item) {
            Integer value = inventory.get(item);
            return value == null ? OptionalInt.empty() : OptionalInt.of(value);
        }

        @Override
        public Optional<Boolean> hasStatusEffect(String effect) {
            return Optional.ofNullable(statuses.get(effect));
        }
    }
}
