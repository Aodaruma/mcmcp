package dev.aod.mcmcp.runtime;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import dev.aod.mcmcp.runtime.ContainerSyncSignals.ContainerSnapshot;
import dev.aod.mcmcp.runtime.ContainerSyncSignals.StackFingerprint;
import static org.assertj.core.api.Assertions.assertThat;

class InventorySyncDiagnosticTest {
    private static final StackFingerprint EMPTY = StackFingerprint.EMPTY;
    private static final StackFingerprint WOOD = new StackFingerprint("minecraft:jungle_log", 9, 123);

    @Test
    void distinguishesAbsentSnapshotFromLayoutAndCursorMismatch() {
        assertThat(InventorySyncDiagnostic.compare(0, List.of(WOOD), EMPTY, null))
                .containsEntry("reason", "snapshot_missing");
        assertThat(InventorySyncDiagnostic.compare(0, List.of(WOOD, EMPTY), EMPTY, snapshot(List.of(WOOD))))
                .containsEntry("reason", "slot_count_mismatch");
        assertThat(InventorySyncDiagnostic.compare(3, List.of(WOOD), EMPTY, snapshot(List.of(WOOD))))
                .containsEntry("reason", "menu_identity_mismatch");
        assertThat(InventorySyncDiagnostic.compare(0, List.of(WOOD), WOOD, snapshot(List.of(WOOD))))
                .containsEntry("reason", "cursor_mismatch").containsEntry("cursor_matches", false);
    }

    @Test
    void identifiesQuantityAndComponentDifferencesWithoutExposingComponents() {
        var changed = new StackFingerprint(WOOD.itemId(), WOOD.count(), 456);
        var server = snapshot(List.of(WOOD, WOOD));
        var result = InventorySyncDiagnostic.compare(0, List.of(WOOD.withCount(8), changed), EMPTY, server);
        assertThat(result).containsEntry("reason", "slot_mismatch").containsEntry("mismatch_count", 2);
        assertThat(result.get("mismatches").toString()).contains("menu_slot=0", "menu_slot=1",
                "live_count=8", "server_count=9", "item_and_components_match=false");
        assertThat(result.toString()).doesNotContain("123", "456");
        assertThat(server.slots()).containsExactly(WOOD, WOOD);
    }

    @Test
    void boundsDetailsAndPreservesTheTotal() {
        var result = InventorySyncDiagnostic.compare(0, java.util.Collections.nCopies(46, EMPTY), EMPTY,
                snapshot(java.util.Collections.nCopies(46, WOOD)));
        assertThat(result).containsEntry("mismatch_count", 46).containsEntry("mismatches_truncated", true);
        assertThat((List<?>) result.get("mismatches")).hasSize(8);
    }

    @Test
    void matchingDoesNotHideNonemptyCursorOrLoseEvidenceRevision() {
        var result = InventorySyncDiagnostic.compare(0, List.of(WOOD), EMPTY, snapshot(List.of(WOOD)));
        assertThat(result).containsEntry("reason", "matched").containsEntry("mismatch_count", 0)
                .containsEntry("snapshot_revision", 7L).containsEntry("snapshot_tick", 10L);
        var held = new ContainerSnapshot(UUID.randomUUID(), 0, "mcmcp:player_inventory", 1,
                List.of(WOOD), WOOD, 10, 7);
        assertThat(InventorySyncDiagnostic.compare(0, List.of(WOOD), WOOD, held))
                .containsEntry("reason", "matched").containsEntry("live_cursor_empty", false)
                .containsEntry("server_cursor_empty", false);
    }

    private static ContainerSnapshot snapshot(List<StackFingerprint> slots) {
        return new ContainerSnapshot(UUID.randomUUID(), 0, "mcmcp:player_inventory", 1, slots, EMPTY, 10, 7);
    }
}
