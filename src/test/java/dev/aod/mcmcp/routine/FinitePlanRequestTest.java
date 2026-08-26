package dev.aod.mcmcp.routine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FinitePlanRequestTest {
    @Test
    void parsesOnlyTypedBoundedOperationsIntoAnImmutablePlan() {
        var actionArguments = new LinkedHashMap<String, Object>();
        actionArguments.put("parameters", Map.of(
                "container", Map.of(
                        "dimension", "minecraft:overworld", "x", 2, "y", 64, "z", 3)));
        actionArguments.put("bounds", Map.of());
        var plan = FinitePlanRequest.parse(Map.of(
                "plan_id", "wheat-stack",
                "max_ticks", 12_000,
                "steps", List.of(
                        Map.of(
                                "id", "take-seeds",
                                "op", "action",
                                "kind", "transfer_items",
                                "arguments", actionArguments),
                        Map.of(
                                "id", "harvest-cycle",
                                "op", "repeat_until",
                                "until", inventoryCondition(),
                                "max_iterations", 64,
                                "max_ticks", 12_000,
                                "steps", List.of(
                                        Map.of(
                                                "id", "wait-mature",
                                                "op", "wait_until",
                                                "condition", blockCondition(),
                                                "max_ticks", 6_000))))));

        actionArguments.put("unexpected", true);

        assertThat(plan.planId()).isEqualTo("wheat-stack");
        assertThat(plan.steps()).hasSize(2);
        assertThat(new FinitePlanRequest(
                "two-hour-plan", FinitePlanRequest.MAX_TICKS,
                List.of(new FinitePlanRequest.Assert(
                        "done", new FinitePlanRequest.InventoryAtLeast(
                                "minecraft:wheat", 64)))).maxTicks())
                .isEqualTo(144_000);
        var action = (FinitePlanRequest.Action) plan.steps().getFirst();
        assertThat(action.kind()).isEqualTo(FinitePlanRequest.RoutineKind.TRANSFER_ITEMS);
        assertThat(action.arguments()).doesNotContainKey("unexpected");
        assertThatThrownBy(() -> action.arguments().put("mutate", true))
                .isInstanceOf(UnsupportedOperationException.class);

        var repeat = (FinitePlanRequest.RepeatUntil) plan.steps().get(1);
        assertThat(repeat.maxIterations()).isEqualTo(64);
        assertThat(repeat.until()).isEqualTo(
                new FinitePlanRequest.InventoryAtLeast("minecraft:wheat", 64));
        assertThat(repeat.steps().getFirst())
                .isInstanceOf(FinitePlanRequest.WaitUntil.class);

        assertThatThrownBy(() -> FinitePlanRequest.parse(planWithAction("arbitrary_input")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported routine kind");
        assertThatThrownBy(() -> FinitePlanRequest.parse(planWithExtraActionEnvelopeField()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("action arguments must contain exactly");
        assertThatThrownBy(() -> FinitePlanRequest.parse(unboundedRepeat()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must contain exactly");
        assertThatThrownBy(() -> FinitePlanRequest.parse(tooManySteps()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than 256 declared steps");
        assertThatThrownBy(() -> FinitePlanRequest.parse(repeatBoundaryOverflow()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expanded plan exceeds 12000 steps");
    }

    private static Map<String, Object> inventoryCondition() {
        return Map.of(
                "kind", "inventory_at_least",
                "item", "minecraft:wheat",
                "minimum_count", 64);
    }

    private static Map<String, Object> blockCondition() {
        return Map.of(
                "kind", "block_matches",
                "target", Map.of(
                        "dimension", "minecraft:overworld", "x", 2, "y", 64, "z", 3),
                "expected_state", Map.of(
                        "block", "minecraft:wheat",
                        "properties", Map.of("age", "7")));
    }

    private static Map<String, Object> planWithAction(String kind) {
        return Map.of(
                "plan_id", "invalid-action",
                "max_ticks", 20,
                "steps", List.of(Map.of(
                        "id", "bad-action",
                        "op", "action",
                        "kind", kind,
                        "arguments", Map.of())));
    }

    private static Map<String, Object> unboundedRepeat() {
        return Map.of(
                "plan_id", "unbounded-repeat",
                "max_ticks", 20,
                "steps", List.of(Map.of(
                        "id", "bad-repeat",
                        "op", "repeat_until",
                        "until", inventoryCondition(),
                        "max_iterations", 2,
                        "steps", List.of(Map.of(
                                "id", "check",
                                "op", "assert",
                                "condition", inventoryCondition())))));
    }

    private static Map<String, Object> planWithExtraActionEnvelopeField() {
        return Map.of(
                "plan_id", "extra-action-field",
                "max_ticks", 20,
                "steps", List.of(Map.of(
                        "id", "move",
                        "op", "action",
                        "kind", "navigate_to",
                        "arguments", Map.of(
                                "parameters", Map.of(),
                                "bounds", Map.of(),
                                "completion_intent", "finish_goal"))));
    }

    private static Map<String, Object> tooManySteps() {
        var steps = new ArrayList<Map<String, Object>>();
        for (int index = 0; index <= FinitePlanRequest.MAX_DECLARED_STEPS; index++) {
            steps.add(Map.of(
                    "id", "check-" + index,
                    "op", "assert",
                    "condition", inventoryCondition()));
        }
        return Map.of("plan_id", "too-many", "max_ticks", 20, "steps", steps);
    }

    private static Map<String, Object> repeatBoundaryOverflow() {
        var body = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < 95; index++) {
            body.add(Map.of(
                    "id", "body-" + index,
                    "op", "assert",
                    "condition", inventoryCondition()));
        }
        return Map.of(
                "plan_id", "repeat-boundary",
                "max_ticks", 72_000,
                "steps", List.of(Map.of(
                        "id", "repeat",
                        "op", "repeat_until",
                        "until", inventoryCondition(),
                        "max_iterations", 125,
                        "max_ticks", 72_000,
                        "steps", body)));
    }
}
