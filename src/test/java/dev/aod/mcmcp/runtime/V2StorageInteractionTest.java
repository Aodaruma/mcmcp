package dev.aod.mcmcp.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class V2StorageInteractionTest {
    @Test void blockInteractionsShareExistingInventoryValidation() {
        var args = new LinkedHashMap<String, Object>(Map.of("target", "block", "operation", "inspect_storage",
                "x", 87, "y", 49, "z", 1, "block", "sophisticatedbackpacks:netherite_backpack", "advance", false));
        var inspect = V2InventoryContainerArguments.parseInteraction(args);
        assertThat(inspect.inspect()).isTrue();
        assertThat(inspect.advance()).isFalse();
        args.put("operation", "transfer_storage"); args.put("direction", "take"); args.put("item", "minecraft:white_concrete"); args.put("count", 64);
        var transfer = V2InventoryContainerArguments.parseInteraction(args);
        args.put("target", "container"); args.put("operation", "transfer");
        assertThat(transfer).isEqualTo(V2InventoryContainerArguments.parse(args));
        args.put("target", "block"); args.put("operation", "transfer_storage"); args.put("count", 897);
        assertThatThrownBy(() -> V2InventoryContainerArguments.parseInteraction(args)).isInstanceOf(IllegalArgumentException.class);
        args.put("count", 1); args.put("packet", "custom");
        assertThatThrownBy(() -> V2InventoryContainerArguments.parseInteraction(args)).isInstanceOf(IllegalArgumentException.class);
    }
}
