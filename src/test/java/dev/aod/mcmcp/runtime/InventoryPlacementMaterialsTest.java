package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.observation.DeliveredPolicyEvidenceStore;
import dev.aod.mcmcp.mcp.McpRuntimePort;
import java.util.List;
import java.util.Map;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class InventoryPlacementMaterialsTest {
    private static final net.minecraft.world.item.Item BLACK_WOOL = item("minecraft:black_wool");
    private static final net.minecraft.world.item.Item RED_CONCRETE = item("minecraft:red_concrete");

    @org.junit.jupiter.api.BeforeAll
    static void bindIsolatedTestItemDefaults() {
        for (var item : List.of(BLACK_WOOL, RED_CONCRETE, Items.SNOW_BLOCK, Items.GLASS,
                Items.OAK_PLANKS, Items.SAND, Items.CHEST, Items.TNT, Items.OAK_LOG,
                Items.OAK_STAIRS, Items.OAK_SLAB, Items.GLASS_PANE, Items.TORCH,
                Items.OAK_DOOR, Items.WATER_BUCKET, Items.REDSTONE_BLOCK)) {
            var holder = item.builtInRegistryHolder();
            if (!holder.areComponentsBound()) holder.bindComponents(
                    net.minecraft.core.component.DataComponentMap.builder()
                            .set(DataComponents.MAX_STACK_SIZE, 64).build());
        }
    }

    private static net.minecraft.world.item.Item item(String id) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM
                .get(net.minecraft.resources.Identifier.parse(id)).orElseThrow().value();
    }
    @Test
    void publishesOwnedBlackWoolWithoutASurfaceAndActivatesOnlyAfterDelivery() {
        var store = new DeliveredPolicyEvidenceStore();
        var named = new ItemStack(BLACK_WOOL, 12);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("custom"));
        var reply = InventoryPlacementMaterials.prepare(Map.of(), List.of(
                new ItemStack(BLACK_WOOL, 64), new ItemStack(BLACK_WOOL, 3), named), store);
        var entries = (List<?>) reply.data().get("placement_materials");
        assertThat(entries).hasSize(1);
        var entry = (Map<?, ?>) entries.getFirst();
        assertThat(entry.get("item")).isEqualTo("minecraft:black_wool");
        assertThat(entry.get("count")).isEqualTo(67);
        assertThat(entry.get("state")).isEqualTo(Map.of("block", "minecraft:black_wool", "properties", Map.of()));
        String ref = (String) entry.get("placement_state_ref");
        assertThat(store.resolvePlacementState(ref)).isEmpty();
        var receipt = (McpRuntimePort.ObservationDeliveryReceipt) reply.deliveryReceipt();
        assertThat(store.confirmDelivery(receipt.receiptId())).isTrue();
        assertThat(store.resolvePlacementState(ref)).contains(
                InventoryPlacementMaterials.identify(new ItemStack(BLACK_WOOL)).orElseThrow());
        store.clear();
        assertThat(store.resolvePlacementState(ref)).isEmpty();
        assertThat(store.confirmDelivery(receipt.receiptId())).isFalse();
    }

    @Test
    void omitsNonstandardOverstackedDirectionalPartialAndDynamicItems() {
        for (var item : List.of(BLACK_WOOL, Items.SNOW_BLOCK, RED_CONCRETE,
                Items.GLASS, Items.OAK_PLANKS)) {
            assertThat(InventoryPlacementMaterials.identify(new ItemStack(item))).isPresent();
        }
        for (var item : List.of(Items.SAND, Items.CHEST, Items.TNT, Items.OAK_LOG,
                Items.OAK_STAIRS, Items.OAK_SLAB, Items.GLASS_PANE, Items.TORCH,
                Items.OAK_DOOR, Items.WATER_BUCKET, Items.REDSTONE_BLOCK)) {
            assertThat(InventoryPlacementMaterials.identify(new ItemStack(item))).isEmpty();
        }
        assertThat(InventoryPlacementMaterials.identify(ItemStack.EMPTY)).isEmpty();
        assertThat(InventoryPlacementMaterials.identify(new ItemStack(BLACK_WOOL, 65))).isEmpty();
        var modified = new ItemStack(BLACK_WOOL);
        modified.set(DataComponents.CUSTOM_NAME, Component.literal("custom"));
        assertThat(InventoryPlacementMaterials.identify(modified)).isEmpty();
        var empty = InventoryPlacementMaterials.prepare(Map.of(), List.of(modified), new DeliveredPolicyEvidenceStore());
        assertThat(empty.data().get("placement_materials")).isEqualTo(List.of());
        assertThat(empty.deliveryReceipt()).isNull();
    }
}
