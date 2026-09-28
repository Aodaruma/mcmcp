package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.navigation.NavCell;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class V2InventoryStorageArgumentsTest {
    private static final NavCell CELL = new NavCell("minecraft:overworld", 0, 65, 0);
    private static final String REFERENCE = "abcdefghijklmnopqrstuvwx";

    @Test
    void storageUsesOneSelectedSlotAndAnInternalOpeningReference() {
        var input = new LinkedHashMap<String, Object>(Map.of("operation", "inspect",
                "target", "storage", "storage_slot", 38, "storage_item", "example:bag", "item", "example:ore"));
        var inspect = (V2InventoryStorageArguments) V2InventoryRequest.parse(input);
        assertThat(inspect.storageSlot()).isEqualTo(38);
        assertThat(inspect.operation(REFERENCE, CELL).parameters()).isEqualTo(
                Map.of("operation_ref", REFERENCE, "operation", "inspect"));
        for (String direction : List.of("take", "store")) {
            input.putAll(Map.of("operation", "transfer", "direction", direction, "count", 3));
            var transfer = (V2InventoryStorageArguments) V2InventoryRequest.parse(input);
            assertThat(transfer.operation(REFERENCE, CELL).parameters()).isEqualTo(Map.of(
                    "operation_ref", REFERENCE, "operation", direction, "item", "example:ore", "transfer_count", 3));
            assertThat(transfer.operation(REFERENCE, CELL).expectedUnits()).isEqualTo(3);
        }
    }

    @Test
    void invalidStorageSelectionAndQuantityCannotReachTheProvider() {
        var input = new LinkedHashMap<String, Object>(Map.of("operation", "transfer", "target", "storage",
                "storage_slot", 0, "direction", "take", "count", 3, "item", "example:ore"));
        for (var invalid : Map.<String, Object>of("storage_slot", 41, "direction", "both", "count", 0,
                "max_ticks", 1_728_001, "storage_item", "bad", "operation_ref", REFERENCE, "x", 0).entrySet()) {
            var modified = new LinkedHashMap<>(input);
            modified.put(invalid.getKey(), invalid.getValue());
            assertThatThrownBy(() -> V2InventoryRequest.parse(modified)).isInstanceOf(IllegalArgumentException.class);
        }
        for (String missing : List.of("storage_slot", "item", "count", "direction")) {
            var modified = new LinkedHashMap<>(input);
            modified.remove(missing);
            assertThatThrownBy(() -> V2InventoryRequest.parse(modified)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void inspectionFiltersContentsWithoutMixingStorageAndPlayerSlotsOrLeakingTheReference() {
        var first = Map.<String, Object>of("slot", 2, "item", "example:ore", "count", 2560);
        var second = Map.<String, Object>of("slot", 9, "item", "minecraft:stone", "count", 10);
        var inspection = Map.<String, Object>of("operation_ref", REFERENCE, "contents", List.of(first, second));
        assertThat(MinecraftV2StorageDriver.filteredContents(inspection, "example:ore")).containsExactly(first);
        assertThat(MinecraftV2StorageDriver.filteredContents(inspection, null)).containsExactly(first, second);
        assertThatThrownBy(() -> MinecraftV2StorageDriver.filteredContents(
                Map.of("contents", List.of(Map.of("slot", 0, "item", "minecraft:stone"))), null))
                .isInstanceOf(IllegalStateException.class);
    }
}
