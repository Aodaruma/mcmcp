package dev.aod.mcmcp.agent.dsl;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aod.mcmcp.routine.KnownContainerPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContainerTransferDslTest {
    @Test
    void allCopperChestIdsAreAcceptedForEachContainerOpcodeAndOtherContainersStayClosed() {
        var base = ActionDslParser.parse(request("take"));
        var target = new ActionDsl.Position("minecraft:overworld", 1, 64, 2);
        for (String blockId : KnownContainerPolicy.copperChestBlockIds()) {
            for (ActionDsl.Node node : List.of(
                    new ActionDsl.InspectKnownContainer("inspect", target, blockId),
                    new ActionDsl.TakeKnownContainerStack(
                            "take", target, blockId, "minecraft:torch",
                            "default_components_only", 64),
                    new ActionDsl.StoreKnownContainerStack(
                            "store", target, blockId, "minecraft:torch",
                            "default_components_only", 64))) {
                var program = new ActionDsl.Program(
                        1, Optional.empty(), base.program().capabilities(), List.of(node));
                assertThat(ActionDslValidator.validate(
                        new ActionDsl.Request(1, program, base.budget()))).isNotNull();
            }
        }

        for (String blockId : List.of(
                "minecraft:trapped_chest",
                "minecraft:ender_chest",
                "minecraft:white_shulker_box",
                "example:modded_chest")) {
            var node = new ActionDsl.InspectKnownContainer("inspect", target, blockId);
            var program = new ActionDsl.Program(
                    1, Optional.empty(), base.program().capabilities(), List.of(node));
            assertThatThrownBy(() -> ActionDslValidator.validate(
                    new ActionDsl.Request(1, program, base.budget())))
                    .isInstanceOf(ActionDslException.class);
        }
    }

    @Test
    void oldRequestsAndConstructorsKeepOneStackWhileExplicitLimitsRoundTrip() {
        for (String op : List.of("take", "store")) {
            var source = request(op);
            var parsed = ActionDslParser.parse(source);
            var target = new ActionDsl.Position("minecraft:overworld", 1, 64, 2);
            ActionDsl.Node legacy = op.equals("take")
                    ? new ActionDsl.TakeKnownContainerStack("move", target, "minecraft:chest",
                            "minecraft:torch", "default_components_only", 64)
                    : new ActionDsl.StoreKnownContainerStack("move", target, "minecraft:chest",
                            "minecraft:torch", "default_components_only", 64);
            assertThat(parsed.program().body()).containsExactly(legacy);
            var node = source.getAsJsonObject("program").getAsJsonArray("body").get(0).getAsJsonObject();
            node.addProperty("max_stacks", 14);
            var expanded = ActionDslParser.parse(source).program().body().getFirst();
            if (expanded instanceof ActionDsl.TakeKnownContainerStack take) {
                assertThat(take.maxStacks()).isEqualTo(14);
                assertThat(take.maxTransferCount()).isEqualTo(896);
            } else {
                var store = (ActionDsl.StoreKnownContainerStack) expanded;
                assertThat(store.maxStacks()).isEqualTo(14);
                assertThat(store.maxTransferCount()).isEqualTo(896);
            }
            node.addProperty("max_transfer_count", 67);
            parsed = ActionDslParser.parse(source);
            ActionDslValidator.validate(parsed);
            var captured = ActionDslSource.capture(source);
            assertThat(captured.canonicalJson()).contains("\"max_stacks\":14", "\"max_transfer_count\":67");
            assertThat(ActionDslParser.parse(JsonParser.parseString(captured.canonicalJson()).getAsJsonObject()))
                    .isEqualTo(parsed);
            assertThat(ActionDslParser.parse(JsonParser.parseString(captured.templateJson()).getAsJsonObject()))
                    .isEqualTo(parsed);
        }
    }

    @Test
    void malformedOptionalBoundsCannotBecomeDefaultsOrOverflowTheDependentDefault() {
        for (String op : List.of("take", "store")) {
            for (String field : List.of("max_stacks", "max_transfer_count")) {
                for (String value : List.of("null", "0", "-1", "1.5", "2147483647",
                        field.equals("max_stacks") ? "15" : "897")) {
                    var source = request(op);
                    source.getAsJsonObject("program").getAsJsonArray("body").get(0)
                            .getAsJsonObject().add(field, JsonParser.parseString(value));
                    assertThatThrownBy(() -> ActionDslValidator.validate(ActionDslParser.parse(source)))
                            .as("%s %s=%s", op, field, value).isInstanceOf(ActionDslException.class);
                }
            }
        }
    }

    @Test
    void absoluteContainerGoalCanReach3456ButPlayerGoalStillStopsAt2304() {
        for (String op : List.of("take", "store")) {
            var source = request(op);
            var node = source.getAsJsonObject("program").getAsJsonArray("body").get(0).getAsJsonObject();
            String field = op.equals("take") ? "minimum_inventory_count" : "minimum_container_count";
            int maximum = op.equals("take") ? 2_304 : 3_456;
            node.addProperty(field, maximum);
            ActionDslValidator.validate(ActionDslParser.parse(source));
            node.addProperty(field, maximum + 1);
            assertThatThrownBy(() -> ActionDslValidator.validate(ActionDslParser.parse(source)))
                    .isInstanceOf(ActionDslException.class);
        }
    }

    @Test
    void compilerChargesAllFourteenClicksAndBothOpensWithoutWideningTheGlobalBudget() {
        for (String op : List.of("take", "store")) {
            var source = request(op);
            source.getAsJsonObject("program").getAsJsonArray("body").get(0)
                    .getAsJsonObject().addProperty("max_stacks", 14);
            var parsed = ActionDslParser.parse(source);
            var bound = new ActionDslCompiler.Cost(69_000, 1_380, 0, 120, 16, 0, 0);
            assertThat(ActionDslCompiler.compile(parsed, ignored -> Optional.of(bound),
                    parsed.program().capabilities()).worstCaseCost()).isEqualTo(bound);
            for (var invalid : List.of(
                    new ActionDslCompiler.Cost(69_000, 1_380, 0, 120, 3, 0, 0),
                    new ActionDslCompiler.Cost(30_000, 600, 0, 120, 16, 0, 0))) {
                assertThatThrownBy(() -> ActionDslCompiler.compile(parsed, ignored -> Optional.of(invalid),
                        parsed.program().capabilities())).isInstanceOf(ActionDslException.class);
            }
            source.getAsJsonObject("budget").addProperty("max_interactions", 15);
            var underfunded = ActionDslParser.parse(source);
            assertThatThrownBy(() -> ActionDslCompiler.compile(underfunded, ignored -> Optional.of(bound),
                    underfunded.program().capabilities())).isInstanceOf(ActionDslException.class);
            source.getAsJsonObject("budget").addProperty("max_interactions", 17);
            assertThatThrownBy(() -> ActionDslValidator.validate(ActionDslParser.parse(source)))
                    .isInstanceOf(ActionDslException.class);
        }
        assertThat(ActionDslCompiler.knownContainerTransferTicks(1)).isEqualTo(600);
        assertThat(ActionDslCompiler.knownContainerTransferOperationTicks(1)).isEqualTo(400);
        assertThat(ActionDslCompiler.knownContainerTransferOperationTicks(14)).isEqualTo(1_180);
    }

    @Test
    void programmaticNodesCannotBypassTransferBounds() {
        var base = ActionDslParser.parse(request("take"));
        var target = new ActionDsl.Position("minecraft:overworld", 1, 64, 2);
        for (ActionDsl.Node node : List.of(
                new ActionDsl.TakeKnownContainerStack("move", target, "minecraft:chest",
                        "minecraft:torch", "default_components_only", 64, Optional.empty(), 15, 64),
                new ActionDsl.StoreKnownContainerStack("move", target, "minecraft:chest",
                        "minecraft:torch", "default_components_only", 64, Optional.empty(), 14, 897))) {
            var program = new ActionDsl.Program(1, Optional.empty(), base.program().capabilities(), List.of(node));
            assertThatThrownBy(() -> ActionDslValidator.validate(new ActionDsl.Request(1, program, base.budget())))
                    .isInstanceOf(ActionDslException.class);
        }
    }

    private static JsonObject request(String op) {
        String goal = op.equals("take") ? "minimum_inventory_count" : "minimum_container_count";
        return JsonParser.parseString("""
                {"schema_version":1,"program":{"dsl_version":1,
                 "capabilities":["camera","inventory_transfer"],"body":[{
                  "id":"move","op":"%s_known_container_stack",
                  "target":{"dimension":"minecraft:overworld","x":1,"y":64,"z":2},
                  "expected_block":"minecraft:chest","item":"minecraft:torch",
                  "stack_policy":"default_components_only","%s":64}]},
                 "budget":{"max_duration_ms":69000,"max_ticks":1380,
                  "max_distance_blocks":0,"max_camera_degrees":360,"max_interactions":16,
                  "max_blocks_broken":0,"max_blocks_placed":0}}
                """.formatted(op, goal)).getAsJsonObject();
    }
}
