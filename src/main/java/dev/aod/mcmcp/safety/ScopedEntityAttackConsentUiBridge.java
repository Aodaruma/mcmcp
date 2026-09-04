package dev.aod.mcmcp.safety;

import java.util.Objects;
import java.util.UUID;

/**
 * Narrow capability-minting bridge for the dedicated local confirmation Screen.
 *
 * <p>No MCP, Action, chat, packet, or generic runtime boolean is accepted here. The caller must
 * already have proved a physical primary click on that Screen's Grant button.</p>
 */
public final class ScopedEntityAttackConsentUiBridge {
    private ScopedEntityAttackConsentUiBridge() {
    }

    public static boolean grantFromPhysicalPromptClick(
            ScopedEntityAttackConsentStore store,
            UUID worldSessionId,
            long clientTick) {
        Objects.requireNonNull(store, "store");
        return store.grantFromPhysicalUiClick(
                new ScopedEntityAttackConsentStore.LocalUiGrantCapability(),
                Objects.requireNonNull(worldSessionId, "worldSessionId"),
                clientTick);
    }
}
