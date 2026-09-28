package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentJobLimits;

import dev.aod.mcmcp.agent.input.FiniteInputSequence;
import dev.aod.mcmcp.routine.BoundedInputLease;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Parses the bounded logical-input request before reserving a v2 job. */
final class V2InputSequenceArguments {
    private V2InputSequenceArguments() { }

    static Request parse(Map<String, Object> arguments) {
        RuntimeArguments.requireAllowedKeys(arguments, "agent_input_sequence",
                Set.of("steps", "stop_when", "inputs", "duration_seconds", "max_distance",
                        "item", "refill_wait_seconds"));
        Integer seconds = arguments.containsKey("duration_seconds")
                ? RuntimeArguments.intArgument(arguments, "duration_seconds") : null;
        boolean timed = seconds != null;
        if (timed && (seconds < 1 || seconds > AgentJobLimits.MAX_SECONDS)
                || timed == arguments.containsKey("steps")
                || timed != arguments.containsKey("inputs")) {
            throw new IllegalArgumentException("specify steps or inputs with duration_seconds in 1..86400");
        }
        List<Map<String, Object>> rawSteps = timed
                ? List.of(Map.of("inputs", arguments.get("inputs"), "hold_ticks", seconds * 20))
                : RuntimeArguments.objectListArgument(arguments, "steps", 1, FiniteInputSequence.MAX_STEPS);
        var steps = new ArrayList<FiniteInputSequence.Step>(rawSteps.size());
        for (var step : rawSteps) {
            RuntimeArguments.requireAllowedKeys(step, "step",
                    Set.of("inputs", "hold_ticks", "gap_ticks", "repeat"));
            if (!step.containsKey("inputs") || !step.containsKey("hold_ticks")) {
                throw new IllegalArgumentException("step requires inputs and hold_ticks");
            }
            Object rawInputs = step.get("inputs");
            if (!(rawInputs instanceof List<?> inputs) || inputs.isEmpty() || inputs.size() > 9) {
                throw new IllegalArgumentException("step.inputs must contain 1..9 logical inputs");
            }
            var parsedInputs = EnumSet.noneOf(BoundedInputLease.Input.class);
            for (Object rawInput : inputs) {
                if (!(rawInput instanceof String inputName)
                        || !inputName.matches("[a-z_]{1,16}")) {
                    throw new IllegalArgumentException("invalid logical input");
                }
                final BoundedInputLease.Input input;
                try {
                    input = BoundedInputLease.Input.valueOf(inputName.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException failure) {
                    throw new IllegalArgumentException("unknown logical input", failure);
                }
                if (!parsedInputs.add(input)) {
                    throw new IllegalArgumentException("duplicate logical input");
                }
            }
            if (parsedInputs.contains(BoundedInputLease.Input.FORWARD)
                    && parsedInputs.contains(BoundedInputLease.Input.BACK)
                    || parsedInputs.contains(BoundedInputLease.Input.LEFT)
                    && parsedInputs.contains(BoundedInputLease.Input.RIGHT)) {
                throw new IllegalArgumentException("opposed movement inputs");
            }
            int holdTicks = RuntimeArguments.intArgument(step, "hold_ticks");
            int gapTicks = step.containsKey("gap_ticks")
                    ? RuntimeArguments.intArgument(step, "gap_ticks") : 0;
            int repeat = step.containsKey("repeat")
                    ? RuntimeArguments.intArgument(step, "repeat") : 1;
            steps.add(new FiniteInputSequence.Step(parsedInputs, holdTicks, gapTicks, repeat));
        }
        V2StopCondition stopWhen = null;
        if (arguments.containsKey("stop_when")) {
            stopWhen = V2StopCondition.parse(RuntimeArguments.objectArgument(arguments, "stop_when"));
        }
        double maxDistance = arguments.containsKey("max_distance")
                ? RuntimeArguments.doubleArgument(arguments, "max_distance") : 48.0;
        if (!Double.isFinite(maxDistance) || maxDistance < 0
                || maxDistance > AgentJobLimits.MAX_DISTANCE) {
            throw new IllegalArgumentException("max_distance must be in 0..4096");
        }
        String item = arguments.containsKey("item") ? RuntimeArguments.stringArgument(arguments, "item") : null;
        int refill = arguments.containsKey("refill_wait_seconds")
                ? RuntimeArguments.intArgument(arguments, "refill_wait_seconds") : 30;
        if (refill < 1 || refill > 300 || item == null && arguments.containsKey("refill_wait_seconds")) {
            throw new IllegalArgumentException("refill wait requires item and 1..300 seconds");
        }
        if (item != null) {
            V2InventoryRequest.requireItemId(item);
            if (!timed || steps.size() != 1 || !steps.getFirst().inputs().equals(Set.of(BoundedInputLease.Input.USE))) {
                throw new IllegalArgumentException("material guard requires timed use only");
            }
        }
        return new Request(new FiniteInputSequence(steps), stopWhen, seconds, maxDistance, item, refill);
    }

    record Request(FiniteInputSequence sequence, V2StopCondition stopWhen,
                   Integer durationSeconds, double maxDistance, String item, int refillWaitSeconds) { }
}
