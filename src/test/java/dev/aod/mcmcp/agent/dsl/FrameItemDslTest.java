package dev.aod.mcmcp.agent.dsl;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FrameItemDslTest {
    @Test
    void canonicalFrameOperationsRoundTripAndReserveOneInteraction() {
        for (String op : List.of("remove", "insert")) {
            var source = request(op);
            var parsed = ActionDslParser.parse(source);
            var cost = new ActionDslCompiler.Cost(20_000, 400, 0, 120, 1, 0, 0);
            assertThat(ActionDslCompiler.compile(parsed, node -> Optional.of(cost),
                    parsed.program().capabilities()).worstCaseCost()).isEqualTo(cost);
            var canonical = ActionDslSource.capture(source);
            assertThat(canonical.canonicalJson()).contains(op + "_visible_frame_item");
            assertThat(ActionDslParser.parse(JsonParser.parseString(canonical.canonicalJson()).getAsJsonObject()))
                    .isEqualTo(parsed);
            // Opaque refs must be stripped from a replay template, as for existing opaque operations.
            assertThat(canonical.templateJson()).doesNotContain("abcdefghijklmnopqrstuvwx");
            assertThat(canonical.templateJson()).contains("minecraft:stone");
            assertThat(canonical.referenceRequirements()).singleElement().satisfies(reference -> {
                assertThat(reference.kind()).isEqualTo("frame_display_entity_ref");
                assertThat(reference.path()).isEqualTo("/program/body/0/entity_ref");
                assertThat(reference.sourcePath()).isEqualTo("/records/*/{entity_ref,frame_display}");
                assertThat(reference.coupledPaths()).isEmpty();
            });
            source.getAsJsonObject("budget").addProperty("max_interactions", 0);
            var underfunded = ActionDslParser.parse(source);
            assertThatThrownBy(() -> ActionDslCompiler.compile(underfunded,
                    node -> Optional.of(cost), parsed.program().capabilities())).isInstanceOf(ActionDslException.class);
        }
    }

    @Test
    void frameOperationCannotBeRepeatedOrCombinedWithLaterActions() {
        for (String op : List.of("remove", "insert")) {
            for (String container : List.of("sequence", "repeat", "typed_repeat")) {
                var source = request(op);
                var program = source.getAsJsonObject("program");
                var frame = program.getAsJsonArray("body").get(0).deepCopy();
                var body = new com.google.gson.JsonArray();
                if (container.equals("sequence")) {
                    body.add(frame);
                    body.add(JsonParser.parseString("{\"id\":\"wait\",\"op\":\"wait_ticks\",\"ticks\":1}"));
                } else if (container.equals("repeat")) {
                    var repeat = JsonParser.parseString("{\"id\":\"loop\",\"op\":\"repeat\",\"count\":1,\"body\":[]}").getAsJsonObject();
                    repeat.getAsJsonArray("body").add(frame);
                    body.add(repeat);
                } else {
                    // Typed trees must not bypass the standalone constraint either.
                    var parsed = ActionDslParser.parse(source);
                    var typed = new ActionDsl.Program(1, Optional.empty(), parsed.program().capabilities(),
                            List.of(new ActionDsl.Repeat("loop", 1, parsed.program().body())));
                    assertThatThrownBy(() -> ActionDslValidator.validate(new ActionDsl.Request(1, typed, parsed.budget())))
                            .isInstanceOf(ActionDslException.class);
                    continue;
                }
                program.add("body", body);
                assertThatThrownBy(() -> ActionDslValidator.validate(ActionDslParser.parse(source)))
                        .as("%s %s", op, container).isInstanceOf(ActionDslException.class);
            }
        }
    }

    @Test
    void missingCapabilityAirAndInvalidReferencesAreRejected() {
        for (String op : List.of("remove", "insert")) {
            var source = request(op);
            source.getAsJsonObject("program").add("capabilities", JsonParser.parseString("[\"camera\"]"));
            var missingCapability = source;
            assertThatThrownBy(() -> ActionDslValidator.validate(ActionDslParser.parse(missingCapability)))
                    .isInstanceOf(ActionDslException.class);
            for (String invalid : List.of("minecraft:air", "not a registry id")) {
                source = request(op);
                source.getAsJsonObject("program").getAsJsonArray("body").get(0).getAsJsonObject()
                        .addProperty(op.equals("remove") ? "expected_item" : "item", invalid);
                var rejected = source;
                assertThatThrownBy(() -> ActionDslValidator.validate(ActionDslParser.parse(rejected)))
                        .isInstanceOf(ActionDslException.class);
            }
        }
    }

    private static JsonObject request(String mode) {
        return JsonParser.parseString("""
                {"schema_version":1,"program":{"dsl_version":1,"capabilities":["camera","%s"],"body":[{
                  "id":"frame","op":"%s_visible_frame_item","entity_ref":"abcdefghijklmnopqrstuvwx","%s":"minecraft:stone"}]},
                 "budget":{"max_duration_ms":20000,"max_ticks":400,"max_distance_blocks":0,
                 "max_camera_degrees":360,"max_interactions":1,"max_blocks_broken":0,"max_blocks_placed":0}}
                """.formatted(mode.equals("remove") ? "entity_attack" : "item_use", mode,
                        mode.equals("remove") ? "expected_item" : "item")).getAsJsonObject();
    }
}
