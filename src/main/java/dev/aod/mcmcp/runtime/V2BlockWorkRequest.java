package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.BlockWorkRegion;
import dev.aod.mcmcp.agent.navigation.NavCell;

import java.util.List;

/** Shared finite bounds for coordinate-based block jobs. */
interface V2BlockWorkRequest {
    BlockWorkRegion region();
    int maxBlocks();
    int maxTicks();
    default List<NavCell> cells() { return region().cells(); }
}
