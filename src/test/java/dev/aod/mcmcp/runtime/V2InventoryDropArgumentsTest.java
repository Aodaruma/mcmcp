package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.runtime.ContainerSyncSignals.StackFingerprint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class V2InventoryDropArgumentsTest {
    @Test
    void parsesBoundedDropAndRequiresDisambiguation() {
        var request = (V2InventoryDropArguments) V2InventoryRequest.parse(Map.of(
                "operation", "drop", "item", "example:backpack", "count", 2));
        assertThat(request.maxTicks()).isEqualTo(600);
        var stacks = new ArrayList<StackFingerprint>();
        for (int slot = 0; slot < 36; slot++) stacks.add(StackFingerprint.EMPTY);
        stacks.set(2, new StackFingerprint("example:backpack", 3, 17));
        stacks.set(18, new StackFingerprint("example:backpack", 4, 18));
        assertThatThrownBy(() -> MinecraftV2InventoryDropDriver.select(stacks, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("drop_source_ambiguous");
        var selected = (V2InventoryDropArguments) V2InventoryRequest.parse(Map.of(
                "operation", "drop", "item", "example:backpack", "count", 2,
                "slot", 18));
        assertThat(MinecraftV2InventoryDropDriver.select(stacks, selected).slot()).isEqualTo(18);
        assertThatThrownBy(() -> V2InventoryRequest.parse(Map.of(
                "operation", "drop", "item", "example:backpack", "count", 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> V2InventoryRequest.parse(Map.of(
                "operation", "drop", "item", "example:backpack", "count", 1,
                "slot", 36)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInsufficientCountAndDoesNotTreatArmorAsDropSource() {
        var stacks = new ArrayList<StackFingerprint>();
        for (int slot = 0; slot < 41; slot++) stacks.add(StackFingerprint.EMPTY);
        stacks.set(40, new StackFingerprint("example:backpack", 3, 17));
        var request = new V2InventoryDropArguments("example:backpack", 2, null, 100);
        assertThatThrownBy(() -> MinecraftV2InventoryDropDriver.select(stacks, request))
                .hasMessage("drop_item_unavailable");
        stacks.set(1, new StackFingerprint("example:backpack", 1, 17));
        assertThatThrownBy(() -> MinecraftV2InventoryDropDriver.select(stacks, request))
                .hasMessage("drop_count_unavailable");
    }
}
