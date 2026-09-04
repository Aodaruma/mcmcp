package dev.aod.mcmcp.agent.dsl;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActionDslSourceTest {
    @Test
    void canonicalizesObjectCapabilityAndNumberRepresentations() {
        var first = ActionDslSource.capture(JsonParser.parseString("""
                {"schema_version":1,"program":{"dsl_version":1,
                 "capabilities":["movement","camera"],
                 "body":[{"op":"face_known_position","target":{
                   "z":3,"dimension":"minecraft:overworld","y":2,"x":1},"id":"look"}]},
                 "budget":{"max_ticks":10,"max_duration_ms":500,
                   "max_distance_blocks":0.0,"max_camera_degrees":90.00,
                   "max_interactions":0,"max_blocks_broken":0,"max_blocks_placed":0}}
                """).getAsJsonObject());
        var second = ActionDslSource.capture(JsonParser.parseString("""
                {"budget":{"max_blocks_placed":0,"max_blocks_broken":0,
                 "max_interactions":0,"max_camera_degrees":90,"max_distance_blocks":0,
                 "max_duration_ms":500.0,"max_ticks":10.0},
                 "program":{"body":[{"id":"look","target":{"x":1,"y":2,"z":3,
                   "dimension":"minecraft:overworld"},"op":"face_known_position"}],
                   "capabilities":["camera","movement"],"dsl_version":1.0},
                 "schema_version":1.0}
                """).getAsJsonObject());

        assertThat(first.canonicalJson()).isEqualTo(second.canonicalJson());
        assertThat(first.sha256()).isEqualTo(second.sha256())
                .matches("sha256:[0-9a-f]{64}");
        assertThat(first.canonicalJson())
                .contains("\"capabilities\":[\"camera\",\"movement\"]")
                .doesNotContain("90.00", "1.0");
        assertThat(first.templateJson()).isEqualTo(first.canonicalJson());
        assertThat(first.templatePayload()).containsEntry(
                "ready_for_agent_start_action", true);
    }

    @Test
    void redactsOpaqueReferencesAndTheirCoupledFingerprintFromTheCloneTemplate() {
        var source = ActionDslSource.capture(JsonParser.parseString("""
                {"schema_version":1,"program":{"dsl_version":1,
                 "capabilities":["camera","inventory_transfer"],"body":[{
                   "id":"craft","op":"craft_known_recipe",
                   "recipe_ref":"abcdefghijklmnopqrstuvwx",
                   "recipe_fingerprint":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                   "goal":{"item":"minecraft:stick","stack_policy":"default_components_only",
                     "minimum_inventory_count":4},
                   "station":{"kind":"crafting_table","target":{"dimension":"minecraft:overworld",
                     "x":1,"y":2,"z":3},"expected_state":{"block":"minecraft:crafting_table",
                     "properties":{}}},"max_crafts":1}]},
                 "budget":{"max_duration_ms":30000,"max_ticks":600,
                   "max_distance_blocks":0,"max_camera_degrees":360,
                   "max_interactions":5,"max_blocks_broken":0,"max_blocks_placed":0}}
                """).getAsJsonObject());

        assertThat(source.canonicalJson())
                .contains("abcdefghijklmnopqrstuvwx")
                .contains("sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        assertThat(source.templateJson())
                .contains("\"recipe_ref\":null", "\"recipe_fingerprint\":null")
                .doesNotContain("abcdefghijklmnopqrstuvwx")
                .doesNotContain("sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        assertThat(source.templatePayload())
                .containsEntry("ready_for_agent_start_action", false)
                .containsEntry("blocked_by", "FRESH_REFERENCE_REQUIRED");
        assertThat(source.sourcePayload()).containsEntry("replayable", false);
        assertThat(source.referenceRequirements()).singleElement().satisfies(requirement -> {
            assertThat(requirement.path()).isEqualTo("/program/body/0/recipe_ref");
            assertThat(requirement.coupledPaths())
                    .containsExactly("/program/body/0/recipe_fingerprint");
        });
        assertThat(source.referenceRequirementPayload()).singleElement().satisfies(requirement ->
                assertThat(requirement).containsEntry("status", "refresh_required"));
    }

    @Test
    void rejectsUnknownFieldsBeforeTheyCanBeReflected() {
        var source = JsonParser.parseString("""
                {"schema_version":1,"secret":"must-not-echo",
                 "program":{"dsl_version":1,"capabilities":[],"body":[
                   {"id":"hold","op":"wait_ticks","ticks":1}]},
                 "budget":{"max_duration_ms":50,"max_ticks":1,"max_distance_blocks":0,
                   "max_camera_degrees":0,"max_interactions":0,"max_blocks_broken":0,
                   "max_blocks_placed":0}}
                """).getAsJsonObject();

        assertThatThrownBy(() -> ActionDslSource.capture(source))
                .isInstanceOf(ActionDslException.class)
                .hasMessageContaining("unexpected keys");
    }
}
