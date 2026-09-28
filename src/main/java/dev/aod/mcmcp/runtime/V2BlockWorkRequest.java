package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.BlockWorkRegion;

/** Shared finite bounds for coordinate-based block jobs. */
interface V2BlockWorkRequest {
    BlockWorkRegion region();
    int maxBlocks();
    int maxTicks();
}
