package dev.aod.mcmcp.runtime;
import dev.aod.mcmcp.agent.action.AgentJobLimits;
import java.util.Map;
import java.util.Set;

record V2DismountArguments(int maxTicks) {
    static V2DismountArguments parse(Map<String, Object> args) {
        RuntimeArguments.requireAllowedKeys(args, "agent_interact", Set.of("target", "operation", "max_ticks"));
        if (!"dismount".equals(args.get("operation"))) throw new IllegalArgumentException("vehicle requires dismount");
        int ticks = args.containsKey("max_ticks") ? RuntimeArguments.intArgument(args, "max_ticks") : 100;
        if (ticks < 1 || ticks > AgentJobLimits.MAX_TICKS) throw new IllegalArgumentException("invalid dismount duration");
        return new V2DismountArguments(ticks);
    }
}
