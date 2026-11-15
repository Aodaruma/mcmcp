package dev.aod.mcmcp.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.aod.mcmcp.runtime.ContainerSyncSignals.ContainerSnapshot;
import dev.aod.mcmcp.runtime.ContainerSyncSignals.StackFingerprint;

/** Read-only comparison; it never promotes client state into server evidence. */
final class InventorySyncDiagnostic {
    private static final int MAX_MISMATCHES = 8;

    private InventorySyncDiagnostic() { }

    static Map<String, Object> compare(int menuId, List<StackFingerprint> live,
            StackFingerprint cursor, ContainerSnapshot server) {
        var result = new LinkedHashMap<String, Object>();
        result.put("live_slot_count", live.size());
        result.put("live_cursor_empty", cursor.empty());
        if (server == null) {
            result.put("reason", "snapshot_missing");
            return result;
        }
        result.put("snapshot_slot_count", server.slots().size());
        result.put("snapshot_tick", server.receivedTick());
        result.put("snapshot_revision", server.packetLedgerRevision());
        result.put("server_cursor_empty", server.carried().empty());
        result.put("cursor_matches", cursor.equals(server.carried()));
        if (menuId != server.containerId()) {
            result.put("reason", "menu_identity_mismatch");
        } else if (live.size() != server.slots().size()) {
            result.put("reason", "slot_count_mismatch");
        } else {
            var mismatches = new ArrayList<Map<String, Object>>();
            int count = 0;
            for (int slot = 0; slot < live.size(); slot++) {
                var actual = live.get(slot);
                var expected = server.slots().get(slot);
                if (actual.equals(expected)) continue;
                count++;
                if (mismatches.size() == MAX_MISMATCHES) continue;
                mismatches.add(Map.of("menu_slot", slot,
                        "live_item", actual.itemId(), "live_count", actual.count(),
                        "server_item", expected.itemId(), "server_count", expected.count(),
                        "item_and_components_match", actual.sameItem(expected)));
            }
            result.put("mismatch_count", count);
            result.put("mismatches", List.copyOf(mismatches));
            result.put("mismatches_truncated", count > mismatches.size());
            result.put("reason", count > 0 ? "slot_mismatch"
                    : !cursor.equals(server.carried()) ? "cursor_mismatch" : "matched");
        }
        return result;
    }
}
