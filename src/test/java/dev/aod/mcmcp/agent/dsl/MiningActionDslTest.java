package dev.aod.mcmcp.agent.dsl;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MiningActionDslTest {
    private static final Set<ActionDsl.Capability> CAPS = Set.of(
            ActionDsl.Capability.MOVEMENT, ActionDsl.Capability.CAMERA, ActionDsl.Capability.BLOCK_BREAK);

    @Test
    void straightAndBranchesHaveClosedDefaultsAndSingleNodeManifest() {
        var straight = (ActionDsl.ExcavateTunnel) ActionDslParser.parse(source()).program().body().getFirst();
        assertThat(straight.pattern()).isEqualTo(ActionDsl.MiningPattern.STRAIGHT);
        assertThat(straight.lengthBlocks()).isEqualTo(16);
        assertThat(straight.branchLengthBlocks()).isZero();
        assertThat(straight.branchSpacingBlocks()).isZero();
        var input = source();
        node(input).addProperty("pattern", "branches");
        var branches = (ActionDsl.ExcavateTunnel) ActionDslParser.parse(input).program().body().getFirst();
        assertThat(branches.branchLengthBlocks()).isEqualTo(6);
        assertThat(branches.branchSpacingBlocks()).isEqualTo(3);
        assertThat(ActionDslOperationManifest.forNode(branches).requiredCapabilities())
                .containsExactlyInAnyOrder("movement", "camera", "block_break");
    }

    @Test
    void largestShapeUsesOneDerivedReservationAndCannotBeUnderreportedByCostModel() {
        var operation = operation(160, ActionDsl.MiningPattern.BRANCHES, 7, 3);
        var expected = new ActionDslCompiler.Cost(35_285_000, 705_700, 2467.5, 736520, 0, 1804, 0);
        assertThat(ActionDslCompiler.intrinsicExcavateTunnelCost(operation)).isEqualTo(expected);
        assertThat(ActionDslCompiler.MAX_TUNNEL_COST).isEqualTo(expected);
        var request = request(operation, budget(expected));
        var compiled = ActionDslCompiler.compile(request, ignored -> {
            throw new AssertionError("A supplied cost must not replace the fixed tunnel reservation");
        }, CAPS);
        assertThat(compiled.worstCaseCost()).isEqualTo(expected);
        assertThat(compiled.executedNodesUpperBound()).isEqualTo(1);
        assertThat(compiled.primitiveCostBounds()).containsEntry("mine", expected);
    }

    @Test
    void allFiveNonzeroCostComponentsMustFitAndCapabilitiesRemainRequired() {
        var operation = operation(16, ActionDsl.MiningPattern.STRAIGHT, 0, 0);
        var cost = ActionDslCompiler.intrinsicExcavateTunnelCost(operation);
        for (int component = 0; component < 5; component++) {
            var budget = new ActionDsl.Budget(cost.durationMillis() - (component == 0 ? 1 : 0),
                    cost.ticks() - (component == 1 ? 1 : 0),
                    cost.distanceBlocks() - (component == 2 ? 0.1 : 0),
                    cost.cameraDegrees() - (component == 3 ? 0.1 : 0),
                    0, cost.blocksBroken() - (component == 4 ? 1 : 0), 0);
            assertThatThrownBy(() -> ActionDslCompiler.compile(request(operation, budget), ignored -> Optional.empty(), CAPS))
                    .isInstanceOf(ActionDslException.class).hasMessageContaining("budget");
        }
        var missing = new ActionDsl.Program(1, Optional.empty(), Set.of(ActionDsl.Capability.BLOCK_BREAK), List.of(operation));
        assertThatThrownBy(() -> ActionDslValidator.validate(new ActionDsl.Request(1, missing, budget(cost))))
                .isInstanceOf(ActionDslException.class).hasMessageContaining("capabilities");
    }

    @Test
    void expandedHardLimitsDoNotAuthorizeOrdinaryOperations() {
        var ordinary = new ActionDsl.Program(1, Optional.empty(), Set.of(), List.of(new ActionDsl.WaitTicks("wait", 2)));
        for (var oversized : List.of(
                new ActionDsl.Budget(1000, 20, 32.1, 0, 0, 0, 0),
                new ActionDsl.Budget(1000, 20, 0, 721, 0, 0, 0),
                new ActionDsl.Budget(1000, 20, 0, 0, 0, 9, 0),
                new ActionDsl.Budget(1000, 20, 0, 0, 17, 0, 0),
                new ActionDsl.Budget(750050, 15001, 0, 0, 0, 0, 0))) {
            assertThatThrownBy(() -> ActionDslValidator.validate(new ActionDsl.Request(1, ordinary, oversized)))
                    .isInstanceOf(ActionDslException.class);
        }
        assertThatCode(() -> ActionDslValidator.validate(new ActionDsl.Request(1, ordinary,
                new ActionDsl.Budget(750000, 15000, 32, 720, 16, 8, 8)))).doesNotThrowAnyException();
    }

    @Test
    void miningCannotBeComposedOrNestedToExpandItsFootprint() {
        var operation = operation(1, ActionDsl.MiningPattern.STRAIGHT, 0, 0);
        var smallBudget = new ActionDsl.Budget(1000, 20, 0, 0, 0, 0, 0);
        for (var body : List.of(
                List.<ActionDsl.Node>of(operation, new ActionDsl.WaitTicks("wait", 2)),
                List.<ActionDsl.Node>of(new ActionDsl.Repeat("loop", 2, List.of(operation))))) {
            var program = new ActionDsl.Program(1, Optional.empty(), CAPS, body);
            assertThatThrownBy(() -> ActionDslValidator.validate(new ActionDsl.Request(1, program, smallBudget)))
                    .isInstanceOf(ActionDslException.class).hasMessageContaining("only top-level");
        }
    }

    @Test
    void invalidPatternsGeometryToolsAndStateFailBeforeExecution() {
        for (String fields : List.of(
                "\"length_blocks\":0", "\"length_blocks\":161", "\"face\":\"up\"",
                "\"pattern\":\"private-value\"", "\"branch_length_blocks\":6",
                "\"pattern\":\"branches\",\"branch_length_blocks\":8",
                "\"pattern\":\"branches\",\"branch_spacing_blocks\":2",
                "\"pattern\":\"branches\",\"branch_spacing_blocks\":17",
                "\"tool_item\":\"minecraft:wooden_pickaxe\"",
                "\"expected_state\":{\"block\":\"minecraft:gravel\",\"properties\":{}}",
                "\"expected_state\":{\"block\":\"minecraft:deepslate\",\"properties\":{}}")) {
            var input = source();
            JsonParser.parseString("{" + fields + "}").getAsJsonObject().entrySet()
                    .forEach(entry -> node(input).add(entry.getKey(), entry.getValue()));
            assertThatThrownBy(() -> ActionDslParser.parse(input)).isInstanceOf(ActionDslException.class);
        }
        var beyondWorld = operation(160, ActionDsl.MiningPattern.STRAIGHT, 0, 0);
        beyondWorld = new ActionDsl.ExcavateTunnel("mine",
                new ActionDsl.Position("minecraft:overworld", 0, 64, 30_000_000),
                ActionDsl.BlockFace.NORTH, beyondWorld.expectedState(), beyondWorld.toolItem(),
                160, beyondWorld.pattern(), 0, 0);
        var request = request(beyondWorld, budget(ActionDslCompiler.MAX_TUNNEL_COST));
        assertThatThrownBy(() -> ActionDslValidator.validate(request)).isInstanceOf(ActionDslException.class);
    }

    @Test
    void canonicalSourceKeepsExplicitGeometryWithoutCreatingOpaqueAuthority() {
        var input = source();
        node(input).addProperty("pattern", "branches");
        var source = ActionDslSource.capture(input);
        assertThat(source.canonicalJson()).contains("\"pattern\":\"branches\"", "\"length_blocks\":16");
        assertThat(source.containsOpaqueReferences()).isFalse();
        assertThat(source.templateJson()).isEqualTo(source.canonicalJson());
        assertThat(source.referenceRequirements()).isEmpty();
    }

    private static ActionDsl.ExcavateTunnel operation(int length, ActionDsl.MiningPattern pattern, int branchLength, int spacing) {
        return new ActionDsl.ExcavateTunnel("mine", new ActionDsl.Position("minecraft:overworld", 0, 64, 0),
                ActionDsl.BlockFace.NORTH, new ActionDsl.BlockStateSpec("minecraft:stone", java.util.Map.of()),
                "minecraft:diamond_pickaxe", length, pattern, branchLength, spacing);
    }

    private static ActionDsl.Request request(ActionDsl.Node node, ActionDsl.Budget budget) {
        return new ActionDsl.Request(1, new ActionDsl.Program(1, Optional.empty(), CAPS, List.of(node)), budget);
    }

    private static ActionDsl.Budget budget(ActionDslCompiler.Cost cost) {
        return new ActionDsl.Budget(cost.durationMillis(), cost.ticks(), cost.distanceBlocks(),
                cost.cameraDegrees(), cost.interactions(), cost.blocksBroken(), cost.blocksPlaced());
    }

    private static JsonObject node(JsonObject source) {
        return source.getAsJsonObject("program").getAsJsonArray("body").get(0).getAsJsonObject();
    }

    private static JsonObject source() {
        return JsonParser.parseString("""
                {"schema_version":1,"program":{"dsl_version":1,"capabilities":["movement","camera","block_break"],
                  "body":[{"id":"mine","op":"excavate_tunnel","target":{"dimension":"minecraft:overworld","x":0,"y":64,"z":0},
                    "face":"north","expected_state":{"block":"minecraft:stone","properties":{}},
                    "tool_item":"minecraft:diamond_pickaxe","length_blocks":16}]},
                 "budget":{"max_duration_ms":35285000,"max_ticks":705700,"max_distance_blocks":2467.5,
                   "max_camera_degrees":736520,"max_interactions":0,"max_blocks_broken":1804,"max_blocks_placed":0}}
                """).getAsJsonObject();
    }
}
