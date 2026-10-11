package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentJobLimits;
import dev.aod.mcmcp.agent.script.BlueprintPlan;
import dev.aod.mcmcp.agent.script.ScriptResources;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** File admission and immutable identity binding for the dedicated blueprint tool. */
record V2BuildArguments(boolean execute, UUID worldSessionId, BlueprintPlan plan, int durationTicks) {
    static V2BuildArguments parse(Map<String, Object> arguments) {
        return parse(arguments, net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath().resolve(
                dev.aod.mcmcp.client.McmcpClientConfig.scriptResourceRoot()),
                dev.aod.mcmcp.client.McmcpClientConfig.scriptResourceMaxBytes());
    }

    static V2BuildArguments parse(Map<String, Object> arguments, Path root, long maxBytes) {
        RuntimeArguments.requireAllowedKeys(arguments, "agent_build", Set.of("operation", "blueprint_file", "format",
                "dimension", "origin", "plan_sha256", "world_session_id", "start_index", "max_blocks",
                "step_max_ticks", "step_max_distance", "advance", "max_duration_ticks"));
        String operation = arguments.containsKey("operation") ? RuntimeArguments.stringArgument(arguments, "operation") : "preview";
        if (!Set.of("preview", "execute").contains(operation)) throw new IllegalArgumentException("invalid build operation");
        String format = arguments.containsKey("format") ? RuntimeArguments.stringArgument(arguments, "format") : "json";
        String dimension = RuntimeArguments.stringArgument(arguments, "dimension");
        List<Integer> origin = List.of(0, 0, 0);
        if (arguments.containsKey("origin")) {
            var position = RuntimeArguments.objectArgument(arguments, "origin");
            RuntimeArguments.requireExactKeys(position, "origin", Set.of("x", "y", "z"));
            origin = List.of(RuntimeArguments.intArgument(position, "x"), RuntimeArguments.intArgument(position, "y"),
                    RuntimeArguments.intArgument(position, "z"));
        }
        int start = bounded(arguments, "start_index", 0, 0, BlueprintPlan.MAX_ENTRIES);
        int maximum = bounded(arguments, "max_blocks", 4096, 1, BlueprintPlan.MAX_BATCH);
        int stepTicks = bounded(arguments, "step_max_ticks", 3200, 1, AgentJobLimits.MAX_TICKS);
        int duration = bounded(arguments, "max_duration_ticks", 24_000, 1, AgentJobLimits.MAX_TICKS);
        double distance = arguments.containsKey("step_max_distance") ? RuntimeArguments.doubleArgument(arguments, "step_max_distance") : 64;
        boolean advance = !arguments.containsKey("advance") || RuntimeArguments.booleanArgument(arguments, "advance");
        boolean execute = operation.equals("execute");
        UUID world = arguments.containsKey("world_session_id") ? UUID.fromString(RuntimeArguments.stringArgument(arguments, "world_session_id")) : null;
        String expected = arguments.containsKey("plan_sha256") ? RuntimeArguments.stringArgument(arguments, "plan_sha256") : null;
        if (expected != null && !expected.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid plan_sha256");
        if (execute && (world == null || expected == null)) throw new IllegalArgumentException("execute requires plan_sha256 and world_session_id");
        final BlueprintPlan plan;
        try {
            String text = ScriptResources.read(root, RuntimeArguments.stringArgument(arguments, "blueprint_file"), new long[]{maxBytes});
            plan = BlueprintPlan.compile(ScriptResources.parse(format, text, new int[]{0}), dimension, origin,
                    start, maximum, stepTicks, distance, advance);
        } catch (IOException failure) {
            throw new IllegalArgumentException("blueprint resource unavailable or invalid UTF-8");
        }
        if (expected != null && !expected.equals(plan.sha256())) throw new IllegalArgumentException("blueprint plan changed");
        if (execute && plan.commands() == 0) throw new IllegalArgumentException("blueprint batch has no work");
        return new V2BuildArguments(execute, world, plan, duration);
    }

    V2ScriptArguments script() {
        return new V2ScriptArguments(BlueprintPlan.SOURCE, 1000 + plan.commands() * 160,
                plan.commands(), plan.commands(), durationTicks, plan.resources());
    }

    private static int bounded(Map<String, Object> values, String key, int fallback, int min, int max) {
        int n = values.containsKey(key) ? RuntimeArguments.intArgument(values, key) : fallback;
        if (n < min || n > max) throw new IllegalArgumentException(key + " out of range");
        return n;
    }
}
