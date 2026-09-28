package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentJobStore;

import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Common cancellation and release boundary for interaction and inventory implementations. */
interface V2JobExecution {
    AgentJobStore.Snapshot tick(UUID session, long clientTick, long nowNanos,
            boolean safe, BooleanSupplier outputAllowed);
    AgentJobStore.Snapshot cancel();
    AgentJobStore.Snapshot stop(String reason);
    default boolean allowsScreenChange() { return false; }
}
