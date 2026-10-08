package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentJobLimits;

import java.util.Map;
import java.util.Set;

/** Bounded source and execution budget for the internal v2 script path. */
record V2ScriptArguments(String source, int work, int iterations, int calls,
                         int maxDurationTicks, Map<String, dev.aod.mcmcp.agent.script.ScriptResources.Resource> resources) {
    V2ScriptArguments { resources = Map.copyOf(resources); }
    static V2ScriptArguments parse(Map<String, Object> arguments) {
        RuntimeArguments.requireAllowedKeys(arguments, "agent_run_script", Set.of(
                "source", "source_file", "data_files", "max_work", "max_iterations", "max_calls", "max_duration_ticks"));
        if (arguments.containsKey("source") == arguments.containsKey("source_file"))
            throw new IllegalArgumentException("specify source or source_file");
        var resources = new java.util.LinkedHashMap<String, dev.aod.mcmcp.agent.script.ScriptResources.Resource>();
        String source;
        try {
            var root = arguments.containsKey("source_file") || arguments.containsKey("data_files")
                    ? net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath().resolve(
                        dev.aod.mcmcp.client.McmcpClientConfig.scriptResourceRoot()) : java.nio.file.Path.of(".");
            long[] remaining = { arguments.containsKey("source_file") || arguments.containsKey("data_files")
                    ? dev.aod.mcmcp.client.McmcpClientConfig.scriptResourceMaxBytes() : 0 };
            source = arguments.containsKey("source") ? RuntimeArguments.stringArgument(arguments, "source")
                    : dev.aod.mcmcp.agent.script.ScriptResources.read(root, RuntimeArguments.stringArgument(arguments, "source_file"), remaining);
            int[] nodes = {0};
            if (arguments.containsKey("data_files")) {
                var files = RuntimeArguments.objectArgument(arguments, "data_files");
                if (files.size() > 32) throw new IllegalArgumentException("resource count limit");
                for (var entry : files.entrySet()) {
                    if (!entry.getKey().matches("[A-Za-z][A-Za-z0-9_]{0,63}")) throw new IllegalArgumentException("invalid resource name");
                    var descriptor = RuntimeArguments.objectArgument(files, entry.getKey());
                    RuntimeArguments.requireExactKeys(descriptor, "data_file", Set.of("path", "format"));
                    String text = dev.aod.mcmcp.agent.script.ScriptResources.read(root,
                            RuntimeArguments.stringArgument(descriptor, "path"), remaining);
                    resources.put(entry.getKey(), dev.aod.mcmcp.agent.script.ScriptResources.parse(
                            RuntimeArguments.stringArgument(descriptor, "format"), text, nodes));
                }
            }
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("script resource unavailable or invalid UTF-8");
        }
        if (source.length() > 32_768) throw new IllegalArgumentException("script source is too long");
        int work = bounded(arguments, "max_work", 100_000, 1, AgentJobLimits.MAX_SCRIPT_WORK);
        int iterations = bounded(arguments, "max_iterations", 10_000, 1, AgentJobLimits.MAX_SCRIPT_ITERATIONS);
        int calls = bounded(arguments, "max_calls", 1_000, 1, AgentJobLimits.MAX_SCRIPT_CALLS);
        int ticks = bounded(arguments, "max_duration_ticks", 24_000, 1, AgentJobLimits.MAX_TICKS);
        return new V2ScriptArguments(source, work, iterations, calls, ticks, resources);
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
