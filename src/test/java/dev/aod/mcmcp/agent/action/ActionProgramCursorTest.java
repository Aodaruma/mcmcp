package dev.aod.mcmcp.agent.action;

import com.google.gson.JsonParser;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.dsl.ActionDslParser;
import dev.aod.mcmcp.agent.dsl.PolicySnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

class ActionProgramCursorTest {
    @Test
    void expandsFixedRepeatAndEvaluatesIfOnceWhenEntered() {
        var request = ActionDslParser.parse(JsonParser.parseString("""
                {
                  "schema_version":1,
                  "program":{"dsl_version":1,"capabilities":[],"body":[
                    {"id":"twice","op":"repeat","count":2,"body":[
                      {"id":"gate","op":"if",
                       "condition":{"field":"health","comparison":"gte","value":10},
                       "then":[{"id":"healthy","op":"wait_ticks","ticks":1}],
                       "else":[{"id":"hurt","op":"wait_ticks","ticks":2}]}
                    ]}
                  ]},
                  "budget":{"max_duration_ms":200,"max_ticks":4,"max_distance_blocks":0,
                    "max_camera_degrees":0,"max_interactions":0,"max_blocks_broken":0,
                    "max_blocks_placed":0}
                }
                """).getAsJsonObject());
        var cursor = new ActionProgramCursor(request.program());
        var ids = new ArrayList<String>();

        var first = cursor.next(snapshot(20));
        ids.addAll(first.completedControlNodeIds());
        ids.add(first.primitive().id());
        var second = cursor.next(snapshot(5));
        ids.addAll(second.completedControlNodeIds());
        ids.add(second.primitive().id());
        var finished = cursor.next(snapshot(5));

        assertThat(ids).containsExactly("twice", "gate", "healthy", "gate", "hurt");
        assertThat(finished.finished()).isTrue();
    }

    private static PolicySnapshot snapshot(double health) {
        return new PolicySnapshot() {
            @Override public OptionalDouble numeric(ActionDsl.NumericField field) {
                return field == ActionDsl.NumericField.HEALTH
                        ? OptionalDouble.of(health) : OptionalDouble.empty();
            }
            @Override public Optional<Boolean> bool(ActionDsl.BooleanField field) {
                return Optional.empty();
            }
            @Override public OptionalInt inventoryCount(String item) {
                return OptionalInt.empty();
            }
            @Override public Optional<Boolean> hasStatusEffect(String effect) {
                return Optional.empty();
            }
        };
    }
}
