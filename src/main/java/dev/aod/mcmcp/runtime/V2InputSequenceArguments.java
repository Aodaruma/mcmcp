package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.input.FiniteInputSequence;
import dev.aod.mcmcp.routine.BoundedInputLease;
import net.minecraft.world.phys.Vec3;

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
                Set.of("steps", "stop_when"));
        var rawSteps = RuntimeArguments.objectListArgument(
                arguments, "steps", 1, FiniteInputSequence.MAX_STEPS);
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
        StopWhen stopWhen = null;
        if (arguments.containsKey("stop_when")) {
            var condition = RuntimeArguments.objectArgument(arguments, "stop_when");
            RuntimeArguments.requireAllowedKeys(condition, "stop_when",
                    Set.of("x", "y", "z", "radius"));
            if (!condition.keySet().containsAll(Set.of("x", "y", "z"))) {
                throw new IllegalArgumentException("stop_when requires x/y/z");
            }
            double radius = condition.containsKey("radius")
                    ? RuntimeArguments.doubleArgument(condition, "radius") : 0.75D;
            if (!Double.isFinite(radius) || radius < 0.1D || radius > 16.0D) {
                throw new IllegalArgumentException("stop_when radius must be in 0.1..16");
            }
            stopWhen = new StopWhen(RuntimeArguments.intArgument(condition, "x"),
                    RuntimeArguments.intArgument(condition, "y"),
                    RuntimeArguments.intArgument(condition, "z"), radius);
        }
        return new Request(new FiniteInputSequence(steps), stopWhen);
    }

    record Request(FiniteInputSequence sequence, StopWhen stopWhen) { }

    record StopWhen(int x, int y, int z, double radius) {
        boolean reached(Vec3 playerPosition) {
            double dx = playerPosition.x - (x + 0.5D);
            double dy = playerPosition.y - y;
            double dz = playerPosition.z - (z + 0.5D);
            return dx * dx + dy * dy + dz * dz <= radius * radius;
        }
    }
}
