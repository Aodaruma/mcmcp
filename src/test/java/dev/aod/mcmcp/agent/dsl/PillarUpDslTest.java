package dev.aod.mcmcp.agent.dsl;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PillarUpDslTest {
    @Test
    void parsesAndCompilesOneExclusivePillarNode() {
        ActionDsl.Request request = ActionDslParser.parse(request(pillar()));

        var compiled = ActionDslCompiler.compile(
                request,
                ignored -> Optional.empty(),
                Set.of(
                        ActionDsl.Capability.MOVEMENT,
                        ActionDsl.Capability.CAMERA,
                        ActionDsl.Capability.BLOCK_PLACE));

        assertThat(request.program().body()).singleElement()
                .isInstanceOf(ActionDsl.PillarUpKnown.class);
        assertThat(compiled.worstCaseCost())
                .isEqualTo(ActionDslCompiler.intrinsicPillarUpCost());
    }

    @Test
    void rejectsSuffixAndNestedPillarForms() {
        JsonObject withSuffix = request(pillar());
        withSuffix.getAsJsonObject("program").getAsJsonArray("body")
                .add(JsonParser.parseString("{\"id\":\"wait\",\"op\":\"wait_ticks\",\"ticks\":2}"));
        assertThatThrownBy(() -> ActionDslParser.parse(withSuffix))
                .isInstanceOf(ActionDslException.class)
                .hasMessageContaining("only top-level Action node");

        JsonObject nested = request(JsonParser.parseString("""
                {"id":"loop","op":"repeat","count":1,"body":[]}
                """).getAsJsonObject());
        nested.getAsJsonObject("program").getAsJsonArray("body").get(0)
                .getAsJsonObject().getAsJsonArray("body").add(pillar());
        assertThatThrownBy(() -> ActionDslParser.parse(nested))
                .isInstanceOf(ActionDslException.class)
                .hasMessageContaining("only top-level Action node");
    }

    private static JsonObject request(JsonObject node) {
        JsonObject root = JsonParser.parseString("""
                {
                  "schema_version":1,
                  "program":{
                    "dsl_version":1,
                    "capabilities":["movement","camera","block_place"],
                    "body":[]
                  },
                  "budget":{
                    "max_duration_ms":15000,
                    "max_ticks":300,
                    "max_distance_blocks":2,
                    "max_camera_degrees":360,
                    "max_interactions":0,
                    "max_blocks_broken":0,
                    "max_blocks_placed":1
                  }
                }
                """).getAsJsonObject();
        root.getAsJsonObject("program").getAsJsonArray("body").add(node);
        return root;
    }

    private static JsonObject pillar() {
        return JsonParser.parseString("""
                {
                  "id":"up",
                  "op":"pillar_up_known",
                  "support":{"dimension":"minecraft:overworld","x":10,"y":64,"z":10},
                  "expected_support":{"block":"minecraft:stone","properties":{}},
                  "source_state":{"block":"minecraft:stone","properties":{}},
                  "item":"minecraft:stone"
                }
                """).getAsJsonObject();
    }
}
