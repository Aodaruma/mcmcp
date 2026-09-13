package dev.aod.mcmcp.agent.dsl;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HoldBoundedInputsDslTest {
    @Test
    void faceMatchingDefaultsToStrictAndOnlyAttackCanOptOut() {
        for (boolean attack : List.of(false, true)) {
            var json = repeating(attack, 20);
            var node = json.getAsJsonObject("program").getAsJsonArray("body").get(0).getAsJsonObject();
            var guard = node.getAsJsonObject("target_guard");
            assertThat(((ActionDsl.HoldBoundedInputs) parse(json.toString()).program().body().getFirst())
                    .targetGuard().orElseThrow().matchFace()).isTrue();
            guard.addProperty("match_face", true);
            ActionDslValidator.validate(parse(json.toString()));
            guard.addProperty("match_face", false);
            if (!attack) {
                assertInvalid(json.toString());
                continue;
            }
            var request = parse(json.toString());
            ActionDslValidator.validate(request);
            assertThat(((ActionDsl.HoldBoundedInputs) request.program().body().getFirst())
                    .targetGuard().orElseThrow().matchFace()).isFalse();
            node.addProperty("repeat_target", false);
            json.getAsJsonObject("budget").addProperty("max_interactions", 1);
            json.getAsJsonObject("budget").addProperty("max_blocks_broken", 1);
            ActionDslValidator.validate(parse(json.toString()));
            node.add("inputs", JsonParser.parseString("[\"sneak\",\"attack\"]"));
            json.getAsJsonObject("program").add("capabilities", JsonParser.parseString("[\"block_break\",\"movement\"]"));
            ActionDslValidator.validate(parse(json.toString()));
        }
    }

    @Test
    void faceOptOutDoesNotAllowNullFlagsMissingEvidenceOrMovementGuards() {
        var json = repeating(true, 20);
        var node = json.getAsJsonObject("program").getAsJsonArray("body").get(0).getAsJsonObject();
        var guard = node.getAsJsonObject("target_guard");
        for (String invalid : List.of("null", "0", "\"false\"", "{}")) {
            guard.add("match_face", JsonParser.parseString(invalid));
            assertInvalid(json.toString());
        }
        guard.addProperty("match_face", false);
        guard.remove("face");
        assertInvalid(json.toString());
        guard.addProperty("face", "up");
        guard.add("expected_state", com.google.gson.JsonNull.INSTANCE);
        assertInvalid(json.toString());
        guard.addProperty("expected_block", "minecraft:snow");
        node.add("inputs", JsonParser.parseString("[\"sneak\"]"));
        assertInvalid(json.toString());
    }

    @Test
    void strictDefaultRemainsCompatibleAndRepeatCostsReserveEveryStart() {
        var strict = (ActionDsl.HoldBoundedInputs) parse(guarded("[\"use\"]", 20))
                .program().body().getFirst();
        assertThat(strict.repeatTarget()).isFalse();
        for (boolean attack : List.of(false, true)) {
            var json = repeating(attack, 1_728_000);
            var request = parse(json.toString());
            var capability = attack ? ActionDsl.Capability.BLOCK_BREAK : ActionDsl.Capability.ITEM_USE;
            var cost = ActionDslCompiler.compile(request, ignored -> Optional.empty(), Set.of(capability))
                    .worstCaseCost();
            assertThat(cost).isEqualTo(new ActionDslCompiler.Cost(
                    86_400_000, 1_728_000, 0, 0, 1_728_000, attack ? 1_728_000 : 0, 0));
        }
    }

    @Test
    void repetitionNeedsFiniteTimeAndTargetedInputsAndRejectsTheRemovedCountField() {
        var json = repeating(false, 20);
        var node = json.getAsJsonObject("program").getAsJsonArray("body").get(0).getAsJsonObject();
        for (int limit : List.of(0, 64, 65)) {
            node.addProperty("max_repetitions", limit);
            assertInvalid(json.toString());
        }
        node.remove("max_repetitions");
        node.remove("duration_ticks");
        assertInvalid(json.toString());
        node.addProperty("duration_ticks", 1_728_001);
        assertInvalid(json.toString());
        assertInvalid(movement("[\"sneak\"]", 20).replace(
                "\"duration_ticks\":20", "\"duration_ticks\":20,\"repeat_target\":true"));
    }

    @Test
    void insufficientRepeatBudgetIsRejectedBeforeExecution() {
        var json = repeating(true, 8);
        json.getAsJsonObject("budget").addProperty("max_interactions", 7);
        assertThatThrownBy(() -> ActionDslCompiler.compile(parse(json.toString()),
                ignored -> Optional.empty(), Set.of(ActionDsl.Capability.BLOCK_BREAK)))
                .isInstanceOf(ActionDslException.class);
        json.getAsJsonObject("budget").addProperty("max_interactions", 8);
        json.getAsJsonObject("budget").addProperty("max_blocks_broken", 7);
        assertThatThrownBy(() -> ActionDslCompiler.compile(parse(json.toString()),
                ignored -> Optional.empty(), Set.of(ActionDsl.Capability.BLOCK_BREAK)))
                .isInstanceOf(ActionDslException.class);
    }

    @Test
    void expandedCountBudgetsDoNotApplyToOtherActionsOrBypassALocalHardLimit() {
        var json = repeating(true, 4096);
        var request = parse(json.toString());
        var hardLimit = new ActionDsl.Budget(204_800, 4096, 0, 0, 64, 64, 0);
        assertThatThrownBy(() -> ActionDslCompiler.compile(request, ignored -> Optional.empty(),
                Set.of(ActionDsl.Capability.BLOCK_BREAK), hardLimit))
                .isInstanceOf(ActionDslException.class);
        json.getAsJsonObject("program").getAsJsonArray("body").get(0).getAsJsonObject()
                .remove("repeat_target");
        assertInvalid(json.toString());
        json.getAsJsonObject("program").add("body", JsonParser.parseString(
                "[{\"id\":\"wait\",\"op\":\"wait_ticks\",\"ticks\":20}]"));
        assertInvalid(json.toString());
    }

    private static JsonObject repeating(boolean attack, int ticks) {
        var json = JsonParser.parseString(guarded(attack ? "[\"attack\"]" : "[\"use\"]", ticks))
                .getAsJsonObject();
        var program = json.getAsJsonObject("program");
        program.add("capabilities", JsonParser.parseString(attack ? "[\"block_break\"]" : "[\"item_use\"]"));
        var node = program.getAsJsonArray("body").get(0).getAsJsonObject();
        node.addProperty("repeat_target", true);
        var budget = json.getAsJsonObject("budget");
        budget.addProperty("max_interactions", ticks);
        budget.addProperty("max_blocks_broken", attack ? ticks : 0);
        budget.addProperty("max_distance_blocks", 0);
        return json;
    }

    @Test
    void nullStateRequiresExplicitBlockAndStillRejectsOtherBlocks() {
        var request = parse(guarded("[\"use\"]", 20).replace(
                "\"expected_state\":{\"block\":\"minecraft:water\",\"properties\":{\"level\":\"0\"}}",
                "\"expected_state\":null,\"expected_block\":\"minecraft:snow\""));
        ActionDslValidator.validate(request);
        var guard = ((ActionDsl.HoldBoundedInputs) request.program().body().getFirst())
                .targetGuard().orElseThrow();
        assertThat(guard.expectedState()).isNull();
        assertThat(guard.matches("minecraft:snow", Map.of("layers", "1"))).isTrue();
        assertThat(guard.matches("minecraft:stone", Map.of())).isFalse();
        assertThat(guard.matches("minecraft:air", Map.of())).isFalse();
    }

    @Test
    void completeStateStillChecksEveryProperty() {
        var hold = (ActionDsl.HoldBoundedInputs) parse(guarded("[\"use\"]", 20))
                .program().body().getFirst();
        var guard = hold.targetGuard().orElseThrow();
        assertThat(guard.matches("minecraft:water", Map.of("level", "0"))).isTrue();
        assertThat(guard.matches("minecraft:water", Map.of("level", "1"))).isFalse();
        assertThat(guard.matches("minecraft:water", Map.of())).isFalse();
    }

    @Test
    void rejectsMissingIdentityAirAndConflictingState() {
        String original = guarded("[\"use\"]", 20);
        String state = "\"expected_state\":{\"block\":\"minecraft:water\",\"properties\":{\"level\":\"0\"}}";
        assertInvalid(original.replace(state, "\"expected_state\":null"));
        assertInvalid(original.replace(state, "\"expected_state\":null,\"expected_block\":\"minecraft:air\""));
        assertInvalid(original.replace(state, state + ",\"expected_block\":\"minecraft:snow\""));
    }

    @Test
    void parsesAndCompilesGuardedUseForAtMostTwentyFourHours() {
        ActionDsl.Request request = parse(guarded("[\"use\"]", 1_728_000));
        var hold = (ActionDsl.HoldBoundedInputs) request.program().body().getFirst();

        assertThat(hold.inputs()).containsExactly(ActionDsl.BoundedInput.USE);
        assertThat(hold.selectedItem()).contains("minecraft:fishing_rod");
        assertThat(hold.targetGuard()).isPresent();
        assertThat(ActionDslValidator.validate(request).requiredCapabilities())
                .containsExactly(ActionDsl.Capability.ITEM_USE);
        assertThat(ActionDslCompiler.compile(
                request, ignored -> Optional.empty(), Set.of(ActionDsl.Capability.ITEM_USE))
                .worstCaseCost())
                .isEqualTo(new ActionDslCompiler.Cost(
                        86_400_000, 1_728_000, 0, 0, 1, 0, 0));
    }

    @Test
    void movementUnionIsDerivedFromInputsWithoutAcceptingRawKeys() {
        ActionDsl.Request request = parse(movement("[\"forward\",\"sneak\"]", 20));

        assertThat(ActionDslValidator.validate(request).requiredCapabilities())
                .containsExactly(ActionDsl.Capability.MOVEMENT);
        assertThat(ActionDslCompiler.compile(
                request, ignored -> Optional.empty(), Set.of(ActionDsl.Capability.MOVEMENT))
                .worstCaseCost())
                .isEqualTo(new ActionDslCompiler.Cost(1_000, 20, 32, 0, 0, 0, 0));

        assertInvalid(movement("[\"key.keyboard.w\"]", 20));
    }

    @Test
    void attackOrUseRequiresExactGuardAndSelectedItem() {
        JsonObject missing = JsonParser.parseString(guarded("[\"attack\"]", 20))
                .getAsJsonObject();
        JsonObject node = missing.getAsJsonObject("program").getAsJsonArray("body")
                .get(0).getAsJsonObject();
        node.remove("target_guard");
        assertInvalid(missing.toString());

        JsonObject movementWithGuard = JsonParser.parseString(guarded("[\"sneak\"]", 20))
                .getAsJsonObject();
        movementWithGuard.getAsJsonObject("program").add("capabilities",
                JsonParser.parseString("[\"movement\"]"));
        assertInvalid(movementWithGuard.toString());
    }

    @Test
    void rejectsConflictingDuplicateOrMovingGuardedInputsAndNesting() {
        assertInvalid(movement("[\"forward\",\"back\"]", 20));
        assertInvalid(movement("[\"left\",\"right\"]", 20));
        assertInvalid(movement("[\"jump\",\"jump\"]", 20));
        assertInvalid(guarded("[\"attack\",\"use\"]", 20));
        assertInvalid(guarded("[\"use\",\"jump\"]", 20));

        JsonObject nested = JsonParser.parseString(movement("[\"sneak\"]", 20))
                .getAsJsonObject();
        nested.getAsJsonObject("program").getAsJsonArray("body").add(
                JsonParser.parseString("{\"id\":\"later\",\"op\":\"wait_ticks\",\"ticks\":1}"));
        assertInvalid(nested.toString());
    }

    private static ActionDsl.Request parse(String json) {
        return ActionDslParser.parse(JsonParser.parseString(json).getAsJsonObject());
    }

    private static void assertInvalid(String json) {
        assertThatThrownBy(() -> ActionDslValidator.validate(parse(json)))
                .isInstanceOf(ActionDslException.class)
                .extracting(failure -> ((ActionDslException) failure).code())
                .isEqualTo(ActionDslException.Code.INVALID_ARGUMENT);
    }

    private static String movement(String inputs, long ticks) {
        return request("[\"movement\"]", inputs, ticks, "");
    }

    private static String guarded(String inputs, long ticks) {
        return request("[\"item_use\"]", inputs, ticks, """
                ,"target_guard":{"target":{"dimension":"minecraft:overworld","x":1,"y":64,"z":2},
                  "face":"up","expected_state":{"block":"minecraft:water","properties":{"level":"0"}}},
                 "selected_item":"minecraft:fishing_rod"
                """);
    }

    private static String request(String capabilities, String inputs, long ticks, String extra) {
        return """
                {"schema_version":1,"program":{"dsl_version":1,
                 "capabilities":%s,"body":[{"id":"hold","op":"hold_bounded_inputs",
                  "inputs":%s,"duration_ticks":%d%s}]},
                 "budget":{"max_duration_ms":%d,"max_ticks":%d,
                  "max_distance_blocks":32,"max_camera_degrees":0,"max_interactions":1,
                  "max_blocks_broken":1,"max_blocks_placed":0}}
                """.formatted(capabilities, inputs, ticks, extra, ticks * 50L, ticks);
    }
}
