package dev.aod.mcmcp.agent.dsl;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aod.mcmcp.brewing.StandardPotionStackSpec;
import dev.aod.mcmcp.redstone.RedstoneSpec;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActionDslTest {
    @Test
    void defaultHardLimitPreservesThePublishedSevenHundredTwentyDegreeCameraBudget() {
        ActionDsl.Request request = ActionDslParser.parse(request(
                capabilities("camera"),
                array(face("look")),
                budget(30_000, 600, 0, 720)));

        var compiled = ActionDslCompiler.compile(
                request,
                primitive -> Optional.of(
                        new ActionDslCompiler.Cost(1_000, 20, 0, 500, 0, 0, 0)),
                Set.of(ActionDsl.Capability.CAMERA));

        assertThat(compiled.effectiveBudget().maxCameraDegrees()).isEqualTo(720);
        assertThat(compiled.worstCaseCost().cameraDegrees()).isEqualTo(500);
    }

    @Test
    void parsesEveryNormativeCatalogExample() throws IOException {
        JsonArray examples = startActionSchema().getAsJsonArray("examples");

        assertThat(examples).hasSize(15);
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
    void parsesSurfaceApproachWithoutAcceptingDerivedNavigationFields() {
        JsonObject node = approachKnownSurface("approach");
        ActionDsl.Request request = ActionDslParser.parse(request(
                capabilities("movement"), node, budget(30_000, 600, 32, 0)));

        assertThat(request.program().body()).singleElement().satisfies(value -> {
            var approach = (ActionDsl.ApproachKnownSurface) value;
            assertThat(approach.target()).isEqualTo(new ActionDsl.Position(
                    "minecraft:overworld", 10, 64, 10));
            assertThat(approach.expectedBlock()).isEqualTo("minecraft:dirt");
        });
        assertThat(ActionDslValidator.validate(request).requiredCapabilities())
                .containsExactly(ActionDsl.Capability.MOVEMENT);

        node.addProperty("navigation_target", "forbidden");
        assertCode(request(
                        capabilities("movement"), node, budget(30_000, 600, 32, 0)),
                ActionDslException.Code.INVALID_ARGUMENT);
    }

    @Test
    void parsesAndIntrinsicallyCompilesOrderedPlaceOnlyBlockPlan() {
        JsonObject node = applyKnownBlockPlan("copy", 2);
        ActionDsl.Request request = ActionDslParser.parse(request(
                capabilities("camera", "block_place"),
                node,
                budget(30_000, 600, 0, 160, 0, 0, 2)));

        var plan = (ActionDsl.ApplyKnownBlockPlan) request.program().body().getFirst();
        assertThat(plan.entries()).extracting(ActionDsl.BlockPlanEntry::id)
                .containsExactly("entry_0", "entry_1");
        assertThat(plan.entries().getFirst().sourceState())
                .isEqualTo(new ActionDsl.BlockStateSpec(
                        "minecraft:oak_log", Map.of("axis", "y")));
        assertThat(plan.entries().get(1).support().dependencyEntryId())
                .contains("entry_0");
        assertThat(ActionDslValidator.validate(request).requiredCapabilities())
                .containsExactlyInAnyOrder(
                        ActionDsl.Capability.CAMERA,
                        ActionDsl.Capability.BLOCK_PLACE);

        var compiled = ActionDslCompiler.compile(
                request,
                ignored -> Optional.empty(),
                Set.of(ActionDsl.Capability.CAMERA, ActionDsl.Capability.BLOCK_PLACE));
        assertThat(compiled.worstCaseCost()).isEqualTo(
                new ActionDslCompiler.Cost(30_000, 600, 0, 160, 0, 0, 2));
        assertThat(compiled.primitiveCostBounds()).containsEntry(
                "copy", new ActionDslCompiler.Cost(30_000, 600, 0, 160, 0, 0, 2));
    }

    @Test
    void blockPlanTransformMatchesExistingMirrorThenClockwiseRotationContract() {
        var transform = new ActionDsl.BlockPlanTransform(
                ActionDsl.BlockPlanRotation.DEGREES_270,
                ActionDsl.BlockPlanMirror.Z);

        assertThat(transform.apply(new ActionDsl.Offset(1, 0, 2)))
                .isEqualTo(new ActionDsl.Offset(-2, 0, -1));
    }

    @Test
    void blockPlanClosesShapeBoundsIdentityTargetsCapabilitiesAndBudget() {
        JsonObject extraNode = applyKnownBlockPlan("copy", 1);
        extraNode.addProperty("derived_target", "forbidden");
        assertCode(request(capabilities("camera", "block_place"), extraNode,
                        budget(15_000, 300, 0, 80, 0, 0, 1)),
                ActionDslException.Code.INVALID_ARGUMENT);

        JsonObject nestedExtra = applyKnownBlockPlan("copy", 1);
        nestedExtra.getAsJsonArray("entries").get(0).getAsJsonObject()
                .addProperty("operation", "replace");
        assertCode(request(capabilities("camera", "block_place"), nestedExtra,
                        budget(15_000, 300, 0, 80, 0, 0, 1)),
                ActionDslException.Code.INVALID_ARGUMENT);

        ActionDsl.Request maximum = ActionDslParser.parse(request(
                capabilities("camera", "block_place"),
                applyKnownBlockPlan("copy", 8),
                budget(120_000, 2_400, 0, 640, 0, 0, 8)));
        assertThat(((ActionDsl.ApplyKnownBlockPlan) maximum.program().body().getFirst()).entries())
                .hasSize(8);
        assertThat(ActionDslCompiler.compile(
                maximum,
                ignored -> Optional.empty(),
                Set.of(ActionDsl.Capability.CAMERA, ActionDsl.Capability.BLOCK_PLACE))
                .worstCaseCost().blocksPlaced()).isEqualTo(8);

        assertCode(request(
                        capabilities("camera", "block_place"),
                        applyKnownBlockPlan("copy", 9),
                        budget(135_000, 2_700, 0, 720, 0, 0, 8)),
                ActionDslException.Code.INVALID_ARGUMENT);

        JsonObject duplicateId = applyKnownBlockPlan("copy", 2);
        duplicateId.getAsJsonArray("entries").get(1).getAsJsonObject()
                .addProperty("id", "entry_0");
        assertCode(request(capabilities("camera", "block_place"), duplicateId,
                        budget(30_000, 600, 0, 160, 0, 0, 2)),
                ActionDslException.Code.INVALID_ARGUMENT);

        JsonObject duplicateTarget = applyKnownBlockPlan("copy", 2);
        duplicateTarget.getAsJsonArray("entries").get(1).getAsJsonObject()
                .add("offset", offset(0, 0, 0));
        assertCode(request(capabilities("camera", "block_place"), duplicateTarget,
                        budget(30_000, 600, 0, 160, 0, 0, 2)),
                ActionDslException.Code.INVALID_ARGUMENT);

        assertCode(request(
                        capabilities("camera"),
                        applyKnownBlockPlan("copy", 1),
                        budget(15_000, 300, 0, 80, 0, 0, 1)),
                ActionDslException.Code.CAPABILITY_DENIED);

        ActionDsl.Request cameraShort = ActionDslParser.parse(request(
                capabilities("camera", "block_place"),
                applyKnownBlockPlan("copy", 2),
                budget(30_000, 600, 0, 159, 0, 0, 2)));
        assertThatThrownBy(() -> ActionDslCompiler.compile(
                cameraShort,
                ignored -> Optional.empty(),
                Set.of(ActionDsl.Capability.CAMERA, ActionDsl.Capability.BLOCK_PLACE)))
                .isInstanceOf(ActionDslException.class)
                .extracting(failure -> ((ActionDslException) failure).code())
                .isEqualTo(ActionDslException.Code.PROGRAM_BUDGET_UNPROVABLE);
    }

    @Test
    void blockPlanRequiresOneSupportWitnessAndAnEarlierMatchingDependency() {
        JsonObject neither = applyKnownBlockPlan("copy", 1);
        JsonObject support = neither.getAsJsonArray("entries").get(0).getAsJsonObject()
                .getAsJsonObject("support");
        support.add("expected_state", JsonNull.INSTANCE);
        assertCode(request(capabilities("camera", "block_place"), neither,
                        budget(15_000, 300, 0, 80, 0, 0, 1)),
                ActionDslException.Code.INVALID_ARGUMENT);

        JsonObject both = applyKnownBlockPlan("copy", 2);
        JsonObject secondSupport = both.getAsJsonArray("entries").get(1).getAsJsonObject()
                .getAsJsonObject("support");
        secondSupport.add("expected_state", blockState("minecraft:stone"));
        assertCode(request(capabilities("camera", "block_place"), both,
                        budget(30_000, 600, 0, 160, 0, 0, 2)),
                ActionDslException.Code.INVALID_ARGUMENT);

        JsonObject futureDependency = applyKnownBlockPlan("copy", 2);
        JsonObject firstSupport = futureDependency.getAsJsonArray("entries").get(0)
                .getAsJsonObject().getAsJsonObject("support");
        firstSupport.add("expected_state", JsonNull.INSTANCE);
        firstSupport.addProperty("dependency_entry_id", "entry_1");
        assertCode(request(capabilities("camera", "block_place"), futureDependency,
                        budget(30_000, 600, 0, 160, 0, 0, 2)),
                ActionDslException.Code.INVALID_ARGUMENT);

        JsonObject mismatchedPosition = applyKnownBlockPlan("copy", 2);
        mismatchedPosition.getAsJsonArray("entries").get(1).getAsJsonObject()
                .getAsJsonObject("support").add("position", position(10, 64, 11));
        assertCode(request(capabilities("camera", "block_place"), mismatchedPosition,
                        budget(30_000, 600, 0, 160, 0, 0, 2)),
                ActionDslException.Code.INVALID_ARGUMENT);
    }

    @Test
    void parsesValidatesAndIntrinsicallyCompilesFixedRedstoneIdentity() {
        ActionDsl.Request request = ActionDslParser.parse(request(
                capabilities("camera", "block_interact", "block_place"),
                applyKnownRedstoneSpec("identity", 90, 5),
                budget(20_750, 415, 0, 720, 2, 0, 2)));

        var redstone = (ActionDsl.ApplyKnownRedstoneSpec) request.program().body().getFirst();
        assertThat(redstone.anchor()).isEqualTo(new ActionDsl.Position(
                "minecraft:overworld", 10, 64, 10));
        assertThat(redstone.rotation()).isEqualTo(90);
        assertThat(redstone.components()).containsExactly(
                new RedstoneSpec.Component(
                        "input", RedstoneSpec.Role.INPUT, "minecraft:lever"),
                new RedstoneSpec.Component(
                        "output", RedstoneSpec.Role.OUTPUT, "minecraft:redstone_lamp"));
        assertThat(redstone.truthTable()).containsExactly(
                new RedstoneSpec.TruthRow(
                        Map.of("input", false), Map.of("output", false)),
                new RedstoneSpec.TruthRow(
                        Map.of("input", true), Map.of("output", true)));
        assertThat(ActionDslValidator.validate(request).requiredCapabilities())
                .containsExactlyInAnyOrder(
                        ActionDsl.Capability.CAMERA,
                        ActionDsl.Capability.BLOCK_INTERACT,
                        ActionDsl.Capability.BLOCK_PLACE);

        var expectedCost = new ActionDslCompiler.Cost(
                20_750, 415, 0, 720, 2, 0, 2);
        assertThat(ActionDslCompiler.compile(
                request, ignored -> Optional.empty(), request.program().capabilities())
                .worstCaseCost()).isEqualTo(expectedCost);
    }

    @Test
    void redstoneIdentityRejectsEveryShapeOutsideTheClosedSlice() {
        for (Consumer<JsonObject> mutation : List.<Consumer<JsonObject>>of(
                node -> node.addProperty("rotation", 45),
                node -> node.getAsJsonObject("timing").addProperty("settle_ticks", 0),
                node -> node.getAsJsonObject("timing").addProperty("settle_ticks", 21),
                node -> node.getAsJsonObject("footprint").addProperty("x", 3),
                node -> node.getAsJsonArray("components").remove(1),
                node -> node.getAsJsonArray("components").add(
                        node.getAsJsonArray("components").get(0).deepCopy()),
                node -> node.getAsJsonArray("components").get(1).getAsJsonObject()
                        .addProperty("block", "minecraft:redstone_wire"),
                node -> node.getAsJsonArray("truth_table").remove(1),
                node -> node.getAsJsonArray("truth_table").set(
                        1, node.getAsJsonArray("truth_table").get(0).deepCopy()))) {
            JsonObject node = applyKnownRedstoneSpec("identity", 0, 1);
            mutation.accept(node);
            assertCode(request(
                            capabilities("camera", "block_interact", "block_place"),
                            node,
                            budget(600_000, 12_000, 0, 720, 2, 0, 2)),
                    ActionDslException.Code.INVALID_ARGUMENT);
        }

        JsonObject extraField = applyKnownRedstoneSpec("identity", 0, 1);
        extraField.addProperty("wire", "forbidden");
        assertCode(request(
                        capabilities("camera", "block_interact", "block_place"),
                        extraField,
                        budget(600_000, 12_000, 0, 720, 2, 0, 2)),
                ActionDslException.Code.INVALID_ARGUMENT);

        assertCode(request(
                        capabilities("camera", "block_interact", "block_place"),
                        repeat("repeat_identity", 2,
                                array(applyKnownRedstoneSpec("identity", 0, 1))),
                        budget(600_000, 12_000, 0, 720, 4, 0, 4)),
                ActionDslException.Code.PROGRAM_BUDGET_UNPROVABLE);
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
    void mutationBatchesAcceptOneThroughEightTargetsAndCompileTheirJointCost() {
        for (int count = 1; count <= ActionDslValidator.MAX_MUTATION_BATCH_TARGETS; count++) {
            for (JsonObject node : new JsonObject[] {
                    tillKnownBatch("till", count),
                    plantKnownWheatBatch("plant", count),
                    harvestKnownWheatBatch("harvest", count)}) {
                ActionDsl.Request parsed = ActionDslParser.parse(request(
                        capabilities("camera", "block_interact", "block_place", "block_break"),
                        node,
                        budget(30_000, 600, 0, 720, 8, 8, 8)));
                ActionDsl.Node batch = parsed.program().body().getFirst();
                int parsedTargets = switch (batch) {
                    case ActionDsl.TillKnownBatch till -> till.targets().size();
                    case ActionDsl.PlantKnownWheatBatch plant -> plant.targets().size();
                    case ActionDsl.HarvestKnownWheatBatch harvest -> harvest.targets().size();
                    default -> throw new AssertionError("not a mutation batch: " + batch);
                };
                assertThat(parsedTargets).isEqualTo(count);
            }
        }

        ActionDsl.Request request = ActionDslParser.parse(request(
                capabilities("camera", "block_interact", "block_place", "block_break"),
                array(
                        tillKnownBatch("till", 2),
                        plantKnownWheatBatch("plant", 2),
                        harvestKnownWheatBatch("harvest", 2)),
                budget(30_000, 600, 0, 720, 2, 2, 2)));
        var compiled = ActionDslCompiler.compile(
                request,
                node -> Optional.of(switch (node) {
                    case ActionDsl.TillKnownBatch ignored ->
                            new ActionDslCompiler.Cost(100, 2, 0, 10, 2, 0, 0);
                    case ActionDsl.PlantKnownWheatBatch ignored ->
                            new ActionDslCompiler.Cost(150, 3, 0, 20, 0, 0, 2);
                    case ActionDsl.HarvestKnownWheatBatch ignored ->
                            new ActionDslCompiler.Cost(200, 4, 0, 30, 0, 2, 0);
                    default -> throw new AssertionError("unexpected primitive: " + node);
                }),
                request.program().capabilities());

        assertThat(compiled.worstCaseCost())
                .isEqualTo(new ActionDslCompiler.Cost(450, 9, 0, 60, 2, 2, 2));
        assertThat(compiled.primitiveCostBounds()).containsOnlyKeys("till", "plant", "harvest");

        assertThatThrownBy(() -> ActionDslCompiler.compile(
                request,
                node -> Optional.of(node instanceof ActionDsl.TillKnownBatch
                        ? new ActionDslCompiler.Cost(100, 2, 0, 10, 1, 0, 0)
                        : node instanceof ActionDsl.PlantKnownWheatBatch
                        ? new ActionDslCompiler.Cost(150, 3, 0, 20, 0, 0, 2)
                        : new ActionDslCompiler.Cost(200, 4, 0, 30, 0, 2, 0)),
                request.program().capabilities()))
                .isInstanceOf(ActionDslException.class)
                .extracting(failure -> ((ActionDslException) failure).code())
                .isEqualTo(ActionDslException.Code.PROGRAM_BUDGET_UNPROVABLE);
    }

    @Test
    void mutationBatchesRejectEmptyOversizedDuplicateAndMisalignedPlantTargets() {
        for (int count : new int[] {0, ActionDslValidator.MAX_MUTATION_BATCH_TARGETS + 1}) {
            for (JsonObject node : new JsonObject[] {
                    tillKnownBatch("till", count),
                    plantKnownWheatBatch("plant", count),
                    harvestKnownWheatBatch("harvest", count)}) {
                assertCode(request(
                                capabilities("camera", "block_interact", "block_place", "block_break"),
                                node,
                                budget(30_000, 600, 0, 720, 8, 8, 8)),
                        ActionDslException.Code.INVALID_ARGUMENT);
            }
        }

        JsonObject duplicateTill = tillKnownBatch("till", 2);
        duplicateTill.getAsJsonArray("targets").set(1, position(10, 64, 10));
        JsonObject duplicateHarvest = harvestKnownWheatBatch("harvest", 2);
        duplicateHarvest.getAsJsonArray("targets").set(1, position(10, 65, 10));
        JsonObject duplicatePlant = plantKnownWheatBatch("plant", 2);
        duplicatePlant.getAsJsonArray("targets").set(
                1, duplicatePlant.getAsJsonArray("targets").get(0).deepCopy());
        for (JsonObject duplicate : new JsonObject[] {
                duplicateTill, duplicatePlant, duplicateHarvest}) {
            assertCode(request(
                            capabilities("camera", "block_interact", "block_place", "block_break"),
                            duplicate,
                            budget(30_000, 600, 0, 720, 8, 8, 8)),
                    ActionDslException.Code.INVALID_ARGUMENT);
        }

        JsonObject misalignedPlant = plantKnownWheatBatch("plant", 1);
        misalignedPlant.getAsJsonArray("targets").get(0).getAsJsonObject()
                .add("support", position(11, 64, 10));
        assertCode(request(
                        capabilities("camera", "block_place"),
                        misalignedPlant,
                        budget(30_000, 600, 0, 720, 0, 0, 1)),
                ActionDslException.Code.INVALID_ARGUMENT);
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
    void parsesValidatesAndCompilesBoundedKnownRecipeCraft() {
        JsonObject craft = craftKnownRecipe("craft", 3);
        ActionDsl.Request request = ActionDslParser.parse(request(
                capabilities("camera", "inventory_transfer"),
                craft,
                budget(30_000, 600, 0, 360, 13, 0, 0)));

        assertThat(request.program().body()).singleElement().satisfies(node -> {
            var parsed = (ActionDsl.CraftKnownRecipe) node;
            assertThat(parsed.recipeRef()).isEqualTo("abcdefghijklmnopqrstuvwx");
            assertThat(parsed.recipeFingerprint()).isEqualTo("sha256:" + "a".repeat(64));
            assertThat(parsed.goalItem()).isEqualTo("minecraft:oak_planks");
            assertThat(parsed.minimumInventoryCount()).isEqualTo(64);
            assertThat(parsed.target()).isEqualTo(new ActionDsl.Position(
                    "minecraft:overworld", 10, 64, 10));
            assertThat(parsed.expectedState()).isEqualTo(
                    new ActionDsl.BlockStateSpec("minecraft:crafting_table", Map.of()));
            assertThat(parsed.maxCrafts()).isEqualTo(3);
        });
        assertThat(ActionDslValidator.validate(request).requiredCapabilities())
                .containsExactlyInAnyOrder(
                        ActionDsl.Capability.CAMERA,
                        ActionDsl.Capability.INVENTORY_TRANSFER);

        var cost = new ActionDslCompiler.Cost(30_000, 600, 0, 120, 13, 0, 0);
        assertThat(ActionDslCompiler.KNOWN_CRAFTING_DURATION_MILLIS).isEqualTo(30_000L);
        assertThat(ActionDslCompiler.KNOWN_CRAFTING_TICKS).isEqualTo(600L);
        assertThat(ActionDslCompiler.compile(
                request, ignored -> Optional.of(cost), request.program().capabilities())
                .worstCaseCost()).isEqualTo(cost);

        craft.addProperty("max_crafts", 4);
        assertCode(request(
                        capabilities("camera", "inventory_transfer"), craft,
                        budget(30_000, 600, 0, 360, 16, 0, 0)),
                ActionDslException.Code.INVALID_ARGUMENT);
        craft = craftKnownRecipe("craft", 3);
        craft.addProperty("recipe_ref", "not-an-opaque-reference");
        assertCode(request(
                        capabilities("camera", "inventory_transfer"), craft,
                        budget(30_000, 600, 0, 360, 13, 0, 0)),
                ActionDslException.Code.INVALID_ARGUMENT);
        craft = craftKnownRecipe("craft", 3);
        craft.getAsJsonObject("station").getAsJsonObject("expected_state")
                .getAsJsonObject("properties").addProperty("invented", "true");
        assertCode(request(
                        capabilities("camera", "inventory_transfer"), craft,
                        budget(30_000, 600, 0, 360, 13, 0, 0)),
                ActionDslException.Code.INVALID_ARGUMENT);
    }

    @Test
    void parsesValidatesAndCompilesOneTerminalKnownSmelt() {
        JsonObject smelt = smeltKnownRecipe("smelt");
        ActionDsl.Request request = ActionDslParser.parse(request(
                capabilities("camera", "inventory_transfer"),
                smelt,
                budget(120_000, 2_400, 0, 540, 6, 0, 0)));

        assertThat(request.program().body()).singleElement().satisfies(node -> {
            var parsed = (ActionDsl.SmeltKnownRecipe) node;
            assertThat(parsed.recipeRef()).isEqualTo("abcdefghijklmnopqrstuvwx");
            assertThat(parsed.goalItem()).isEqualTo("minecraft:iron_ingot");
            assertThat(parsed.stationKind()).isEqualTo("furnace");
            assertThat(parsed.expectedState().block()).isEqualTo("minecraft:furnace");
            assertThat(parsed.fuelItem()).isEqualTo("minecraft:coal");
            assertThat(parsed.maxSmelts()).isOne();
        });
        assertThat(ActionDslValidator.validate(request).requiredCapabilities())
                .containsExactlyInAnyOrder(
                        ActionDsl.Capability.CAMERA,
                        ActionDsl.Capability.INVENTORY_TRANSFER);

        var cost = new ActionDslCompiler.Cost(120_000, 2_400, 0, 120, 6, 0, 0);
        assertThat(ActionDslCompiler.compile(
                request, ignored -> Optional.of(cost), request.program().capabilities())
                .worstCaseCost()).isEqualTo(cost);

        smelt.addProperty("max_smelts", 2);
        assertCode(request(
                        capabilities("camera", "inventory_transfer"), smelt,
                        budget(120_000, 2_400, 0, 540, 6, 0, 0)),
                ActionDslException.Code.INVALID_ARGUMENT);
        smelt = smeltKnownRecipe("smelt");
        smelt.getAsJsonObject("station").addProperty("kind", "smoker");
        assertCode(request(
                        capabilities("camera", "inventory_transfer"), smelt,
                        budget(120_000, 2_400, 0, 540, 6, 0, 0)),
                ActionDslException.Code.INVALID_ARGUMENT);
        assertCode(request(
                        capabilities("camera", "inventory_transfer"),
                        array(smeltKnownRecipe("smelt"), waitNode("after", 1)),
                        budget(120_050, 2_401, 0, 540, 6, 0, 0)),
                ActionDslException.Code.INVALID_ARGUMENT);
    }

    @Test
    void operateKnownMenuIsOneOpaqueTerminalInventoryOperation() {
        JsonObject menu = operateKnownMenu("transfer");
        ActionDsl.Request request = ActionDslParser.parse(request(
                capabilities("inventory_transfer"),
                menu,
                budget(30_000, 600, 0, 0, 1, 0, 0)));

        assertThat(request.program().body()).singleElement().satisfies(node -> {
            var parsed = (ActionDsl.OperateKnownMenu) node;
            assertThat(parsed.operationRef()).isEqualTo("abcdefghijklmnopqrstuvwx");
        });
        assertThat(ActionDslValidator.validate(request).requiredCapabilities())
                .containsExactly(ActionDsl.Capability.INVENTORY_TRANSFER);

        var cost = new ActionDslCompiler.Cost(30_000, 600, 0, 0, 1, 0, 0);
        assertThat(ActionDslCompiler.compile(
                request, ignored -> Optional.of(cost), request.program().capabilities())
                .worstCaseCost()).isEqualTo(cost);

        for (JsonObject insufficient : new JsonObject[] {
                budget(29_999, 600, 0, 0, 1, 0, 0),
                budget(30_000, 599, 0, 0, 1, 0, 0),
                budget(30_000, 600, 0, 0, 0, 0, 0)}) {
            ActionDsl.Request parsed = ActionDslParser.parse(request(
                    capabilities("inventory_transfer"), operateKnownMenu("transfer"), insufficient));
            assertThatThrownBy(() -> ActionDslCompiler.compile(
                    parsed, ignored -> Optional.of(cost), parsed.program().capabilities()))
                    .isInstanceOf(ActionDslException.class)
                    .extracting(failure -> ((ActionDslException) failure).code())
                    .isEqualTo(ActionDslException.Code.PROGRAM_BUDGET_UNPROVABLE);
        }

        var wrongCamera = new ActionDslCompiler.Cost(30_000, 600, 0, 1, 1, 0, 0);
        assertThatThrownBy(() -> ActionDslCompiler.compile(
                request, ignored -> Optional.of(wrongCamera), request.program().capabilities()))
                .isInstanceOf(ActionDslException.class)
                .extracting(failure -> ((ActionDslException) failure).code())
                .isEqualTo(ActionDslException.Code.PROGRAM_BUDGET_UNPROVABLE);

        JsonObject invalidRef = operateKnownMenu("transfer");
        invalidRef.addProperty("operation_ref", "not+base64url============");
        assertCode(request(capabilities("inventory_transfer"), invalidRef,
                        budget(30_000, 600, 0, 0, 1, 0, 0)),
                ActionDslException.Code.INVALID_ARGUMENT);
        JsonObject extra = operateKnownMenu("transfer");
        extra.addProperty("menu_ref", "abcdefghijklmnopqrstuvwx");
        assertCode(request(capabilities("inventory_transfer"), extra,
                        budget(30_000, 600, 0, 0, 1, 0, 0)),
                ActionDslException.Code.INVALID_ARGUMENT);
        assertCode(request(capabilities(), operateKnownMenu("transfer"),
                        budget(30_000, 600, 0, 0, 1, 0, 0)),
                ActionDslException.Code.CAPABILITY_DENIED);
    }

    @Test
    void operateKnownMenuMustBeTheFinalTopLevelNode() {
        JsonObject menu = operateKnownMenu("transfer");
        assertCode(request(
                        capabilities("inventory_transfer"),
                        array(menu, waitNode("after", 1)),
                        budget(30_050, 601, 0, 0, 1, 0, 0)),
                ActionDslException.Code.INVALID_ARGUMENT);
        assertCode(request(
                        capabilities("inventory_transfer"),
                        conditional("choose", numeric("health", "gte", 1), array(menu), array()),
                        budget(30_000, 600, 0, 0, 1, 0, 0)),
                ActionDslException.Code.INVALID_ARGUMENT);
        assertCode(request(
                        capabilities("inventory_transfer"),
                        repeat("twice", 2, array(menu)),
                        budget(60_000, 1_200, 0, 0, 2, 0, 0)),
                ActionDslException.Code.INVALID_ARGUMENT);
    }

    @Test
    void parsesValidatesAndCompilesOneTerminalKnownPotionBatch() {
        JsonObject brew = brewKnownPotionBatch("brew", 3);
        ActionDsl.Request request = ActionDslParser.parse(request(
                capabilities("camera", "inventory_transfer"),
                brew,
                budget(70_000, 1_400, 0, 360, 16, 0, 0)));

        assertThat(request.program().body()).singleElement().satisfies(node -> {
            var parsed = (ActionDsl.BrewKnownPotionBatch) node;
            assertThat(parsed.target()).isEqualTo(new ActionDsl.Position(
                    "minecraft:overworld", 10, 64, 10));
            assertThat(parsed.expectedBlock()).isEqualTo("minecraft:brewing_stand");
            assertThat(parsed.input()).isEqualTo(new StandardPotionStackSpec(
                    "minecraft:potion", "minecraft:water", 3));
            assertThat(parsed.ingredientItem()).isEqualTo("minecraft:nether_wart");
            assertThat(parsed.fuelItem()).isEqualTo("minecraft:blaze_powder");
            assertThat(parsed.expectedOutput()).isEqualTo(new StandardPotionStackSpec(
                    "minecraft:potion", "minecraft:awkward", 3));
        });
        assertThat(ActionDslValidator.validate(request).requiredCapabilities())
                .containsExactlyInAnyOrder(
                        ActionDsl.Capability.CAMERA,
                        ActionDsl.Capability.INVENTORY_TRANSFER);

        var cost = new ActionDslCompiler.Cost(
                70_000, 1_400, 0, 120, 16, 0, 0);
        assertThat(ActionDslCompiler.compile(
                request, ignored -> Optional.of(cost), request.program().capabilities())
                .worstCaseCost()).isEqualTo(cost);

        var wrongTime = new ActionDslCompiler.Cost(
                69_950, 1_399, 0, 120, 16, 0, 0);
        assertThatThrownBy(() -> ActionDslCompiler.compile(
                request, ignored -> Optional.of(wrongTime), request.program().capabilities()))
                .isInstanceOf(ActionDslException.class)
                .extracting(failure -> ((ActionDslException) failure).code())
                .isEqualTo(ActionDslException.Code.PROGRAM_BUDGET_UNPROVABLE);
    }

    @Test
    void brewingIsTopLevelTerminalNonRepeatableAndRecipeClosed() {
        JsonObject brew = brewKnownPotionBatch("brew", 3);
        assertCode(request(
                        capabilities("camera", "inventory_transfer"),
                        array(brew, waitNode("after", 1)),
                        budget(70_050, 1_401, 0, 360, 16, 0, 0)),
                ActionDslException.Code.INVALID_ARGUMENT);
        assertCode(request(
                        capabilities("camera", "inventory_transfer"),
                        repeat("repeat_brew", 2, array(brew)),
                        budget(140_000, 2_800, 0, 720, 16, 0, 0)),
                ActionDslException.Code.INVALID_ARGUMENT);

        JsonObject wrongOutput = brewKnownPotionBatch("brew", 3);
        wrongOutput.getAsJsonObject("expected_output")
                .addProperty("potion", "minecraft:strength");
        assertCode(request(
                        capabilities("camera", "inventory_transfer"), wrongOutput,
                        budget(70_000, 1_400, 0, 360, 16, 0, 0)),
                ActionDslException.Code.INVALID_ARGUMENT);

        JsonObject mismatchedCount = brewKnownPotionBatch("brew", 3);
        mismatchedCount.getAsJsonObject("expected_output").addProperty("count", 2);
        assertCode(request(
                        capabilities("camera", "inventory_transfer"), mismatchedCount,
                        budget(70_000, 1_400, 0, 360, 16, 0, 0)),
                ActionDslException.Code.INVALID_ARGUMENT);

        assertCode(request(
                        capabilities("camera"), brewKnownPotionBatch("brew", 3),
                        budget(70_000, 1_400, 0, 360, 16, 0, 0)),
                ActionDslException.Code.CAPABILITY_DENIED);
    }

    @Test
    void parsesAndCompilesVisibleItemCollectionFromContinuousObservedCoordinates() {
        JsonObject collect = baseNode("collect", "collect_visible_item");
        collect.addProperty("displayed_item", "minecraft:oak_log");
        JsonObject target = new JsonObject();
        target.addProperty("dimension", "minecraft:overworld");
        target.addProperty("x", 10.375D);
        target.addProperty("y", 64.125D);
        target.addProperty("z", -2.75D);
        collect.add("target", target);
        ActionDsl.Request request = ActionDslParser.parse(request(
                capabilities("movement"),
                collect,
                budget(5_000, 100, 8, 0)));

        assertThat(request.program().body()).singleElement().satisfies(node -> {
            assertThat(node).isInstanceOf(ActionDsl.CollectVisibleItem.class);
            var parsed = (ActionDsl.CollectVisibleItem) node;
            assertThat(parsed.displayedItem()).isEqualTo("minecraft:oak_log");
            assertThat(parsed.target()).isEqualTo(new ActionDsl.WorldPosition(
                    "minecraft:overworld", 10.375D, 64.125D, -2.75D));
        });
        assertThat(ActionDslValidator.validate(request).requiredCapabilities())
                .containsExactly(ActionDsl.Capability.MOVEMENT);
        var cost = new ActionDslCompiler.Cost(2_000, 40, 4, 0, 0, 0, 0);
        assertThat(ActionDslCompiler.compile(
                request, ignored -> Optional.of(cost), Set.of(ActionDsl.Capability.MOVEMENT))
                .worstCaseCost()).isEqualTo(cost);

        collect.addProperty("displayed_item", "not an item id");
        assertThatThrownBy(() -> ActionDslParser.parse(request(
                capabilities("movement"), collect, budget(5_000, 100, 8, 0))))
                .isInstanceOf(ActionDslException.class)
                .extracting(failure -> ((ActionDslException) failure).code())
                .isEqualTo(ActionDslException.Code.INVALID_ARGUMENT);
    }

    @Test
    void collectBatchRemainsOneFirstClassListedOrderPrimitive() {
        JsonObject batch = baseNode("drops", "collect_visible_item_batch");
        JsonArray targets = new JsonArray();
        for (int index = 0; index < 2; index++) {
            JsonObject entry = new JsonObject();
            entry.addProperty("displayed_item", "minecraft:wheat");
            JsonObject target = new JsonObject();
            target.addProperty("dimension", "minecraft:overworld");
            target.addProperty("x", 10.25D + index);
            target.addProperty("y", 64.125D);
            target.addProperty("z", -2.75D);
            entry.add("target", target);
            targets.add(entry);
        }
        batch.add("targets", targets);

        ActionDsl.Request request = ActionDslParser.parse(request(
                capabilities("movement"), batch, budget(10_000, 200, 16, 0)));

        assertThat(request.program().body()).singleElement().satisfies(node -> {
            assertThat(node).isInstanceOf(ActionDsl.CollectVisibleItemBatch.class);
            var parsed = (ActionDsl.CollectVisibleItemBatch) node;
            assertThat(parsed.id()).isEqualTo("drops");
            assertThat(parsed.targets()).extracting(ActionDsl.CollectTarget::target)
                    .containsExactly(
                            new ActionDsl.WorldPosition(
                                    "minecraft:overworld", 10.25D, 64.125D, -2.75D),
                            new ActionDsl.WorldPosition(
                                    "minecraft:overworld", 11.25D, 64.125D, -2.75D));
            assertThat(parsed.targets()).extracting(ActionDsl.CollectTarget::displayedItem)
                    .containsExactly("minecraft:wheat", "minecraft:wheat");
        });
        assertThat(ActionDslValidator.validate(request).requiredCapabilities())
                .containsExactly(ActionDsl.Capability.MOVEMENT);
        var batchCost = new ActionDslCompiler.Cost(4_000, 80, 8, 0, 0, 0, 0);
        assertThat(ActionDslCompiler.compile(
                request, ignored -> Optional.of(batchCost),
                Set.of(ActionDsl.Capability.MOVEMENT)).worstCaseCost())
                .isEqualTo(batchCost);

        JsonObject oneTargetBatch = batch.deepCopy();
        oneTargetBatch.getAsJsonArray("targets").remove(1);
        assertCode(request(
                        capabilities("movement"), oneTargetBatch,
                        budget(10_000, 200, 16, 0)),
                ActionDslException.Code.INVALID_ARGUMENT);

        JsonObject duplicateBatch = batch.deepCopy();
        duplicateBatch.getAsJsonArray("targets").add(targets.get(0).deepCopy());
        assertCode(request(
                        capabilities("movement"), duplicateBatch,
                        budget(10_000, 200, 16, 0)),
                ActionDslException.Code.INVALID_ARGUMENT);

        targets.add(targets.get(0).deepCopy());
        targets.add(targets.get(0).deepCopy());
        targets.add(targets.get(0).deepCopy());
        targets.add(targets.get(0).deepCopy());
        targets.add(targets.get(0).deepCopy());
        targets.add(targets.get(0).deepCopy());
        targets.add(targets.get(0).deepCopy());
        assertCode(request(capabilities("movement"), batch, budget(10_000, 200, 16, 0)),
                ActionDslException.Code.INVALID_ARGUMENT);
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
    void validatorAndCompilerDiagnosticsDoNotReflectSubmittedNodeIds() {
        String submittedId = "private_probe_7f31";
        JsonObject duplicate = request(
                capabilities(),
                array(waitNode(submittedId, 1), waitNode(submittedId, 1)),
                budget(1_000, 20, 0, 0));

        assertThatThrownBy(() -> ActionDslParser.parse(duplicate))
                .isInstanceOf(ActionDslException.class)
                .satisfies(failure -> {
                    assertThat(failure.getMessage())
                            .isEqualTo("Program contains duplicate node ids")
                            .doesNotContain(submittedId);
                });

        ActionDsl.Request parsed = ActionDslParser.parse(request(
                capabilities("movement"),
                navigate(submittedId),
                budget(1_000, 20, 1, 0)));
        assertThatThrownBy(() -> ActionDslCompiler.compile(
                parsed,
                ignored -> Optional.empty(),
                Set.of(ActionDsl.Capability.MOVEMENT)))
                .isInstanceOf(ActionDslException.class)
                .satisfies(failure -> {
                    assertThat(failure.getMessage())
                            .isEqualTo("A primitive worst-case cost is unavailable")
                            .doesNotContain(submittedId);
                });
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
                        budget(1_000, 20, 0, 0, 17, 0, 0)),
                ActionDslException.Code.INVALID_ARGUMENT);
    }

    @Test
    void budgetFailureListsEveryExceededCatalogComponentWithoutValues() {
        var cost = new ActionDslCompiler.Cost(101, 3, 1, 1, 1, 1, 1);
        var budget = new ActionDsl.Budget(100, 2, 0, 0, 0, 0, 0);

        assertThatThrownBy(() -> ActionDslCompiler.requireWithinBudget(cost, budget))
                .isInstanceOf(ActionDslException.class)
                .extracting(
                        failure -> ((ActionDslException) failure).code(),
                        Throwable::getMessage)
                .containsExactly(
                        ActionDslException.Code.PROGRAM_BUDGET_UNPROVABLE,
                        "Worst-case cost exceeds effective budget components: "
                                + "budget.max_duration_ms, budget.max_ticks, "
                                + "budget.max_distance_blocks, budget.max_camera_degrees, "
                                + "budget.max_interactions, budget.max_blocks_broken, "
                                + "budget.max_blocks_placed");

        assertThatThrownBy(() -> ActionDslCompiler.requireWithinBudget(
                        new ActionDslCompiler.Cost(0, 0, 0, 1, 0, 0, 0), budget))
                .hasMessage("Worst-case cost exceeds effective budget components: "
                        + "budget.max_camera_degrees")
                .hasMessageNotContaining("1.0")
                .hasMessageNotContaining("target")
                .hasMessageNotContaining("node");
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

    private static JsonObject approachKnownSurface(String id) {
        JsonObject node = baseNode(id, "approach_known_surface");
        node.add("target", position());
        node.addProperty("expected_block", "minecraft:dirt");
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

    private static JsonObject tillKnownBatch(String id, int count) {
        JsonObject node = baseNode(id, "till_known_batch");
        JsonArray targets = new JsonArray();
        for (int index = 0; index < count; index++) {
            targets.add(position(10 + index, 64, 10));
        }
        node.add("targets", targets);
        node.addProperty("expected_block", "minecraft:dirt");
        node.addProperty("hoe_item", "minecraft:iron_hoe");
        return node;
    }

    private static JsonObject plantKnownWheatBatch(String id, int count) {
        JsonObject node = baseNode(id, "plant_known_wheat_batch");
        JsonArray targets = new JsonArray();
        for (int index = 0; index < count; index++) {
            JsonObject plot = new JsonObject();
            plot.add("target", position(10 + index, 65, 10));
            plot.add("support", position(10 + index, 64, 10));
            targets.add(plot);
        }
        node.add("targets", targets);
        node.addProperty("seed_item", "minecraft:wheat_seeds");
        return node;
    }

    private static JsonObject harvestKnownWheatBatch(String id, int count) {
        JsonObject node = baseNode(id, "harvest_known_wheat_batch");
        JsonArray targets = new JsonArray();
        for (int index = 0; index < count; index++) {
            targets.add(position(10 + index, 65, 10));
        }
        node.add("targets", targets);
        return node;
    }

    private static JsonObject applyKnownBlockPlan(String id, int count) {
        JsonObject node = baseNode(id, "apply_known_block_plan");
        node.add("anchor", position(10, 64, 10));
        JsonObject transform = new JsonObject();
        transform.addProperty("rotation", 0);
        transform.addProperty("mirror", "none");
        node.add("transform", transform);
        JsonArray entries = new JsonArray();
        for (int index = 0; index < count; index++) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", "entry_" + index);
            entry.add("offset", offset(0, index, 0));
            JsonObject source = blockState("minecraft:oak_log");
            source.getAsJsonObject("properties").addProperty("axis", "y");
            entry.add("source_state", source);
            entry.addProperty("item", "minecraft:oak_log");
            JsonObject support = new JsonObject();
            support.add("position", position(10, 63 + index, 10));
            support.addProperty("face", "up");
            if (index == 0) {
                support.add("expected_state", blockState("minecraft:stone"));
                support.add("dependency_entry_id", JsonNull.INSTANCE);
            } else {
                support.add("expected_state", JsonNull.INSTANCE);
                support.addProperty("dependency_entry_id", "entry_" + (index - 1));
            }
            entry.add("support", support);
            entries.add(entry);
        }
        node.add("entries", entries);
        return node;
    }

    private static JsonObject applyKnownRedstoneSpec(
            String id, int rotation, int settleTicks) {
        JsonObject node = baseNode(id, "apply_known_redstone_spec");
        node.add("anchor", position(10, 64, 10));
        node.addProperty("rotation", rotation);

        JsonArray components = new JsonArray();
        JsonObject input = new JsonObject();
        input.addProperty("id", "input");
        input.addProperty("role", "input");
        input.addProperty("block", "minecraft:lever");
        components.add(input);
        JsonObject output = new JsonObject();
        output.addProperty("id", "output");
        output.addProperty("role", "output");
        output.addProperty("block", "minecraft:redstone_lamp");
        components.add(output);
        node.add("components", components);

        JsonArray truthTable = new JsonArray();
        truthTable.add(redstoneTruthRow(false));
        truthTable.add(redstoneTruthRow(true));
        node.add("truth_table", truthTable);

        JsonObject footprint = new JsonObject();
        footprint.addProperty("x", 2);
        footprint.addProperty("y", 1);
        footprint.addProperty("z", 1);
        node.add("footprint", footprint);
        JsonObject timing = new JsonObject();
        timing.addProperty("settle_ticks", settleTicks);
        node.add("timing", timing);
        return node;
    }

    private static JsonObject redstoneTruthRow(boolean value) {
        JsonObject row = new JsonObject();
        JsonObject inputs = new JsonObject();
        inputs.addProperty("input", value);
        row.add("inputs", inputs);
        JsonObject outputs = new JsonObject();
        outputs.addProperty("output", value);
        row.add("outputs", outputs);
        return row;
    }

    private static JsonObject brewKnownPotionBatch(String id, int count) {
        JsonObject node = baseNode(id, "brew_known_potion_batch");
        node.add("target", position());
        node.addProperty("expected_block", "minecraft:brewing_stand");
        JsonObject input = new JsonObject();
        input.addProperty("item", "minecraft:potion");
        input.addProperty("potion", "minecraft:water");
        input.addProperty("count", count);
        node.add("input", input);
        node.addProperty("ingredient_item", "minecraft:nether_wart");
        node.addProperty("fuel_item", "minecraft:blaze_powder");
        JsonObject output = new JsonObject();
        output.addProperty("item", "minecraft:potion");
        output.addProperty("potion", "minecraft:awkward");
        output.addProperty("count", count);
        node.add("expected_output", output);
        return node;
    }

    private static JsonObject craftKnownRecipe(String id, int maxCrafts) {
        JsonObject node = baseNode(id, "craft_known_recipe");
        node.addProperty("recipe_ref", "abcdefghijklmnopqrstuvwx");
        node.addProperty("recipe_fingerprint", "sha256:" + "a".repeat(64));
        JsonObject goal = new JsonObject();
        goal.addProperty("item", "minecraft:oak_planks");
        goal.addProperty("stack_policy", "default_components_only");
        goal.addProperty("minimum_inventory_count", 64);
        node.add("goal", goal);
        JsonObject station = new JsonObject();
        station.addProperty("kind", "crafting_table");
        station.add("target", position());
        station.add("expected_state", blockState("minecraft:crafting_table"));
        node.add("station", station);
        node.addProperty("max_crafts", maxCrafts);
        return node;
    }

    private static JsonObject smeltKnownRecipe(String id) {
        JsonObject node = baseNode(id, "smelt_known_recipe");
        node.addProperty("recipe_ref", "abcdefghijklmnopqrstuvwx");
        node.addProperty("recipe_fingerprint", "sha256:" + "a".repeat(64));
        JsonObject goal = new JsonObject();
        goal.addProperty("item", "minecraft:iron_ingot");
        goal.addProperty("stack_policy", "default_components_only");
        goal.addProperty("minimum_inventory_count", 1);
        node.add("goal", goal);
        JsonObject station = new JsonObject();
        station.addProperty("kind", "furnace");
        station.add("target", position());
        JsonObject expectedState = blockState("minecraft:furnace");
        expectedState.getAsJsonObject("properties").addProperty("facing", "north");
        expectedState.getAsJsonObject("properties").addProperty("lit", "false");
        station.add("expected_state", expectedState);
        node.add("station", station);
        JsonObject fuel = new JsonObject();
        fuel.addProperty("item", "minecraft:coal");
        fuel.addProperty("stack_policy", "default_components_only");
        node.add("fuel", fuel);
        node.addProperty("max_smelts", 1);
        return node;
    }

    private static JsonObject operateKnownMenu(String id) {
        JsonObject node = baseNode(id, "operate_known_menu");
        node.addProperty("operation_ref", "abcdefghijklmnopqrstuvwx");
        return node;
    }

    private static JsonObject offset(int x, int y, int z) {
        JsonObject offset = new JsonObject();
        offset.addProperty("x", x);
        offset.addProperty("y", y);
        offset.addProperty("z", z);
        return offset;
    }

    private static JsonObject blockState(String block) {
        JsonObject state = new JsonObject();
        state.addProperty("block", block);
        state.add("properties", new JsonObject());
        return state;
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

    private static JsonObject position(int x, int y, int z) {
        JsonObject value = position();
        value.addProperty("x", x);
        value.addProperty("y", y);
        value.addProperty("z", z);
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
