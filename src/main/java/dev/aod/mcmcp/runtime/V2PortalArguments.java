package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.safety.PortalEntryGeometry;
import net.minecraft.core.BlockPos;
import java.util.Map;
import java.util.Set;

/** Explicit nearby natural End-portal entry; ordinary move/input never acquire this intent. */
record V2PortalArguments(PortalEntryGeometry geometry, String sourceDimension,
                         String expectedDimension, int maxTicks) {
    static V2PortalArguments parse(Map<String, Object> args, String sourceDimension) {
        RuntimeArguments.requireAllowedKeys(args, "agent_interact portal", Set.of(
                "target", "block", "x", "y", "z", "expected_dimension", "max_ticks"));
        if (!"portal".equals(args.get("target")) || !"minecraft:end_portal".equals(args.get("block")))
            throw new IllegalArgumentException("portal requires block=minecraft:end_portal");
        String expected = RuntimeArguments.stringArgument(args, "expected_dimension");
        if (!("minecraft:overworld".equals(sourceDimension) && "minecraft:the_end".equals(expected))
                && !("minecraft:the_end".equals(sourceDimension) && "minecraft:overworld".equals(expected)))
            throw new IllegalArgumentException("portal requires an explicit Overworld/End transition");
        int ticks = args.containsKey("max_ticks") ? RuntimeArguments.intArgument(args, "max_ticks") : 200;
        if (ticks < 20 || ticks > 200) throw new IllegalArgumentException("portal max_ticks must be in 20..200");
        int x = RuntimeArguments.intArgument(args, "x"), y = RuntimeArguments.intArgument(args, "y"),
                z = RuntimeArguments.intArgument(args, "z");
        if (x < -30_000_000 || x > 30_000_000 || z < -30_000_000 || z > 30_000_000
                || y < -2048 || y > 2048) throw new IllegalArgumentException("portal position is out of bounds");
        return new V2PortalArguments(new PortalEntryGeometry(new BlockPos(x, y, z)), sourceDimension, expected, ticks);
    }
}
