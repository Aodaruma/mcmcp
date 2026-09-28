package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.input.FiniteInputSequence;
import dev.aod.mcmcp.routine.BoundedInputLease;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** A short world click expressed through the same finite input owner as raw sequences. */
final class V2ClickArguments {
    private V2ClickArguments() { }

    static FiniteInputSequence parse(Map<String, Object> arguments) {
        RuntimeArguments.requireAllowedKeys(arguments, "agent_click",
                Set.of("button", "count", "hold_ticks", "gap_ticks"));
        String button = RuntimeArguments.stringArgument(arguments, "button");
        BoundedInputLease.Input input = switch (button) {
            case "left" -> BoundedInputLease.Input.ATTACK;
            case "right" -> BoundedInputLease.Input.USE;
            case "middle" -> BoundedInputLease.Input.PICK;
            default -> throw new IllegalArgumentException("button must be left, right or middle");
        };
        int count = arguments.containsKey("count")
                ? RuntimeArguments.intArgument(arguments, "count") : 1;
        int holdTicks = arguments.containsKey("hold_ticks")
                ? RuntimeArguments.intArgument(arguments, "hold_ticks") : 1;
        int gapTicks = arguments.containsKey("gap_ticks")
                ? RuntimeArguments.intArgument(arguments, "gap_ticks") : 4;
        if (count < 1 || count > 32 || holdTicks < 1 || holdTicks > 20
                || gapTicks < 0 || gapTicks > 40 || count > 1 && gapTicks == 0) {
            throw new IllegalArgumentException("invalid click count or tick bounds");
        }
        return new FiniteInputSequence(List.of(new FiniteInputSequence.Step(
                Set.of(input), holdTicks, gapTicks, count)));
    }
}
