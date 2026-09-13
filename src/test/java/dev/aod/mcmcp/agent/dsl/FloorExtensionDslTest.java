package dev.aod.mcmcp.agent.dsl;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aod.mcmcp.routine.MinecraftFloorExtensionAttempt;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class FloorExtensionDslTest {
    @Test void fixedBudgetAndCardinalDirections() {
        for (String direction : new String[]{"north", "south", "west", "east"}) {
            var request = ActionDslParser.parse(request(direction));
            var compiled = ActionDslCompiler.compile(request, ignored -> Optional.empty(),
                    Set.of(ActionDsl.Capability.MOVEMENT, ActionDsl.Capability.CAMERA, ActionDsl.Capability.BLOCK_PLACE));
            assertThat(compiled.worstCaseCost()).isEqualTo(ActionDslCompiler.intrinsicFloorExtensionCost());
            assertThat(compiled.worstCaseCost().blocksPlaced()).isEqualTo(1);
        }
    }
    @Test void verticalAndNestedOrCombinedFormsAreRejected() {
        assertThatThrownBy(() -> ActionDslParser.parse(request("up"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ActionDslParser.parse(request("down"))).isInstanceOf(IllegalArgumentException.class);
        var combined = request("east");
        combined.getAsJsonObject("program").getAsJsonArray("body").add(JsonParser.parseString("{\"id\":\"wait\",\"op\":\"wait_ticks\",\"ticks\":1}"));
        assertThatThrownBy(() -> ActionDslParser.parse(combined)).isInstanceOf(ActionDslException.class);
        var nested = request("east");
        var body = nested.getAsJsonObject("program").getAsJsonArray("body");
        var node = body.remove(0);
        var repeat = JsonParser.parseString("{\"id\":\"loop\",\"op\":\"repeat\",\"count\":1,\"body\":[]}").getAsJsonObject();
        repeat.getAsJsonArray("body").add(node); body.add(repeat);
        assertThatThrownBy(() -> ActionDslParser.parse(nested)).isInstanceOf(ActionDslException.class);
    }
    @Test void noFootingBeyondEdgeWithoutServerConfirmation() {
        assertThat(MinecraftFloorExtensionAttempt.withinCorridor(0.7, 0.1, false)).isTrue();
        assertThat(MinecraftFloorExtensionAttempt.withinCorridor(0.8, 0, false)).isFalse();
        assertThat(MinecraftFloorExtensionAttempt.withinCorridor(1.0, 0, true)).isTrue();
        assertThat(MinecraftFloorExtensionAttempt.withinCorridor(1.2, 0, true)).isFalse();
        assertThat(MinecraftFloorExtensionAttempt.withinCorridor(0.6, 0.3, false)).isFalse();
        assertThat(MinecraftFloorExtensionAttempt.withinCorridor(Double.NaN, 0, true)).isFalse();
    }
    private static JsonObject request(String direction) {
        return JsonParser.parseString("""
                {"schema_version":1,"program":{"dsl_version":1,"capabilities":["movement","camera","block_place"],"body":[
                {"id":"floor","op":"extend_known_floor","support":{"dimension":"minecraft:overworld","x":0,"y":64,"z":0},
                 "expected_support":{"block":"minecraft:stone","properties":{}},"direction":"%s",
                 "placement_state_ref":"psr_0123456789abcdef0123456789abcdef"}]},
                 "budget":{"max_duration_ms":20000,"max_ticks":400,"max_distance_blocks":2,"max_camera_degrees":720,
                 "max_interactions":0,"max_blocks_broken":0,"max_blocks_placed":1}}
                """.formatted(direction)).getAsJsonObject();
    }
}
