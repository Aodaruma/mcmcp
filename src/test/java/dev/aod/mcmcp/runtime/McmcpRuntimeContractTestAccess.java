package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.safety.LocalArmingState;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Test-only access to package-private production payload builders. */
public final class McmcpRuntimeContractTestAccess {
    private McmcpRuntimeContractTestAccess() {
    }

    public static Map<String, Object> readyStatePayload() {
        var lock = new LocalArmingState.Snapshot(
                LocalArmingState.Mode.READY,
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                Set.of(
                        "movement", "camera", "block_break", "block_interact", "block_place",
                        "inventory_transfer", "item_use"),
                null,
                1L);
        return McmcpRuntime.statePayload(lock, false, null, List.of());
    }
}
