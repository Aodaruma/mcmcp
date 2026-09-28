package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentJobLimits;

import java.util.Map;
import java.util.Set;

/** Bounded source and execution budget for the internal v2 script path. */
record V2ScriptArguments(String source, int work, int iterations, int calls,
                         int maxDurationTicks) {
    static V2ScriptArguments parse(Map<String, Object> arguments) {
        RuntimeArguments.requireAllowedKeys(arguments, "agent_run_script", Set.of(
                "source", "max_work", "max_iterations", "max_calls", "max_duration_ticks"));
        String source = RuntimeArguments.stringArgument(arguments, "source");
        if (source.length() > 32_768) throw new IllegalArgumentException("script source is too long");
        int work = bounded(arguments, "max_work", 100_000, 1, AgentJobLimits.MAX_SCRIPT_WORK);
        int iterations = bounded(arguments, "max_iterations", 10_000, 1, AgentJobLimits.MAX_SCRIPT_ITERATIONS);
        int calls = bounded(arguments, "max_calls", 1_000, 1, AgentJobLimits.MAX_SCRIPT_CALLS);
        int ticks = bounded(arguments, "max_duration_ticks", 24_000, 1, AgentJobLimits.MAX_TICKS);
        return new V2ScriptArguments(source, work, iterations, calls, ticks);
    }

    private static int bounded(Map<String, Object> arguments, String key, int fallback,
                               int minimum, int maximum) {
        int value = arguments.containsKey(key) ? RuntimeArguments.intArgument(arguments, key)
                : fallback;
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(key + " is out of range");
        }
        return value;
    }
}
