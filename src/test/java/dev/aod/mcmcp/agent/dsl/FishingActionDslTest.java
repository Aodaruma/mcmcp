package dev.aod.mcmcp.agent.dsl;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FishingActionDslTest {
    @Test
    void parsesClosedCastAndSoundWaitAndRedactsSessionRef() {
        ActionDsl.Request cast = ActionDslParser.parse(JsonParser.parseString("""
                {"schema_version":1,"program":{"dsl_version":1,
                 "capabilities":["camera","item_use"],"body":[{
                   "id":"cast","op":"cast_known_fishing_rod","hand":"main_hand",
                   "rod_item":"minecraft:fishing_rod",
                   "target":{"dimension":"minecraft:overworld","x":1,"y":62,"z":1},
                   "face":"up","expected_state":{"block":"minecraft:water","properties":{"level":"0"}}
                 }]},"budget":{"max_duration_ms":15000,"max_ticks":300,
                   "max_distance_blocks":0,"max_camera_degrees":180,"max_interactions":2,
                   "max_blocks_broken":0,"max_blocks_placed":0}}
                """).getAsJsonObject());
        assertThat(ActionDslValidator.validate(cast).requiredCapabilities())
                .containsExactlyInAnyOrder(
                        ActionDsl.Capability.CAMERA, ActionDsl.Capability.ITEM_USE);
        assertThat(ActionDslCompiler.compile(
                cast,
                ignored -> Optional.of(new ActionDslCompiler.Cost(
                        15_000, 300, 0, 180, 2, 0, 0)),
                cast.program().capabilities()).worstCaseCost().interactions()).isEqualTo(2);

        var reelSource = JsonParser.parseString("""
                {"schema_version":1,"program":{"dsl_version":1,
                 "capabilities":["item_use"],"body":[{
                   "id":"reel","op":"reel_known_fishing_session",
                   "fishing_session_ref":"f_0123456789abcdef012345","hand":"main_hand",
                   "rod_item":"minecraft:fishing_rod"}]},
                 "budget":{"max_duration_ms":4000,"max_ticks":80,"max_distance_blocks":0,
                   "max_camera_degrees":0,"max_interactions":2,"max_blocks_broken":0,
                   "max_blocks_placed":0}}
                """).getAsJsonObject();
        ActionDsl.Request reel = ActionDslParser.parse(reelSource);
        assertThat(ActionDslCompiler.compile(
                reel, ignored -> Optional.empty(), reel.program().capabilities())
                .worstCaseCost()).isEqualTo(ActionDslCompiler.intrinsicKnownFishingReelCost());
        ActionDslSource source = ActionDslSource.capture(reelSource);
        assertThat(source.templateJson()).contains("\"fishing_session_ref\":null");
        assertThat(source.referenceRequirements()).singleElement()
                .extracting(ActionDslSource.ReferenceRequirement::kind)
                .isEqualTo("fishing_session_ref");
    }

    @Test
    void rejectsGenericSoundAndNonSourceWater() {
        assertThatThrownBy(() -> ActionDslParser.parse(JsonParser.parseString("""
                {"schema_version":1,"program":{"dsl_version":1,"capabilities":[],"body":[{
                  "id":"wait","op":"wait_until","condition":{"type":"sound_clue",
                  "sound_event":"minecraft:block.note_block.harp","since_tick":10,
                  "bounds":{"dimension":"minecraft:overworld","min":{"x":0,"y":0,"z":0},
                  "max":{"x":1,"y":1,"z":1}}},"max_ticks":20}]},
                  "budget":{"max_duration_ms":1000,"max_ticks":20,"max_distance_blocks":0,
                  "max_camera_degrees":0,"max_interactions":0,"max_blocks_broken":0,
                  "max_blocks_placed":0}}
                """).getAsJsonObject())).isInstanceOf(ActionDslException.class);

        assertThatThrownBy(() -> ActionDslParser.parse(JsonParser.parseString("""
                {"schema_version":1,"program":{"dsl_version":1,"capabilities":[],"body":[{
                  "id":"wait","op":"wait_until","condition":{"type":"sound_clue",
                  "sound_event":"minecraft:entity.fishing_bobber.splash","since_tick":10,
                  "bounds":{"dimension":"minecraft:overworld","min":{"x":0,"y":0,"z":0},
                  "max":{"x":16,"y":16,"z":16}}},"max_ticks":901}]},
                  "budget":{"max_duration_ms":45050,"max_ticks":901,"max_distance_blocks":0,
                  "max_camera_degrees":0,"max_interactions":0,"max_blocks_broken":0,
                  "max_blocks_placed":0}}
                """).getAsJsonObject()))
                .isInstanceOf(ActionDslException.class)
                .hasMessageContaining("max_ticks");
    }
}
