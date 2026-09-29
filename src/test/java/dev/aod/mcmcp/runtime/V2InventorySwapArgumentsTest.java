package dev.aod.mcmcp.runtime;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class V2InventorySwapArgumentsTest {
    @Test
    void acceptsOnlyExactMainToHotbarExchange() {
        var request = (V2InventorySwapArguments) V2InventoryRequest.parse(Map.of(
                "operation", "swap", "source_slot", 35, "hotbar_slot", 8,
                "item", "example:tool"));
        assertThat(request.maxTicks()).isEqualTo(100);
        assertThat(request.sourceSlot()).isEqualTo(35);
        assertThat(request.hotbarSlot()).isEqualTo(8);
        assertThatThrownBy(() -> V2InventoryRequest.parse(Map.of(
                "operation", "swap", "source_slot", 8, "hotbar_slot", 0,
                "item", "example:tool")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> V2InventoryRequest.parse(Map.of(
                "operation", "swap", "source_slot", 9, "hotbar_slot", 9,
                "item", "example:tool")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> V2InventoryRequest.parse(Map.of(
                "operation", "swap", "source_slot", 9, "hotbar_slot", 0,
                "item", "example:tool", "count", 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
