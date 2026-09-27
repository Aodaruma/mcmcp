package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.dsl.ActionDsl;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/** 現在のplayer inventory・釣り・pickupの局所証拠の読み取り。 */
final class PlayerInventoryEvidence {
    private PlayerInventoryEvidence() {}

    static int exactPlayerCount(
            KnownMenuProfileSupport.Context context, ItemStack expected) {
        int count = 0;
        for (int slot : context.playerSlots()) {
            ItemStack actual = context.menu().slots.get(slot).getItem();
            if (ItemStack.isSameItemSameComponents(actual, expected)) {
                count = Math.addExact(count, actual.getCount());
            }
        }
        return count;
    }

    static int inventoryItemCount(
            net.minecraft.client.player.LocalPlayer player, String item) {
        int count = 0;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (!stack.isEmpty()
                    && item.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())) {
                count = Math.addExact(count, stack.getCount());
            }
        }
        return count;
    }

    static Map<String, Integer> collectBatchInventoryCounts(
            net.minecraft.client.player.LocalPlayer player,
            ActionDsl.CollectVisibleItemBatch batch) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(batch, "batch");
        var counts = new LinkedHashMap<String, Integer>();
        for (ActionDsl.CollectTarget target : batch.targets()) {
            counts.computeIfAbsent(
                    target.displayedItem(), item -> inventoryItemCount(player, item));
        }
        return counts;
    }

    static boolean pickupInventoryIncreased(int before, int current) {
        if (before < 0 || current < 0) {
            throw new IllegalArgumentException("pickup inventory counts must be non-negative");
        }
        return current > before;
    }

    static InteractionHand fishingHand(String hand) {
        return "main_hand".equals(hand) ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
    }

    static boolean exactFishingRodHeld(
            net.minecraft.client.player.LocalPlayer player, String hand, String rodItem) {
        ItemStack stack = player.getItemInHand(fishingHand(hand));
        return !stack.isEmpty()
                && rodItem.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
    }

    static int fishingRodDamage(
            net.minecraft.client.player.LocalPlayer player, String hand, String rodItem) {
        ItemStack stack = player.getItemInHand(fishingHand(hand));
        return !stack.isEmpty()
                && rodItem.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
                ? stack.getDamageValue() : -1;
    }

    static boolean ownedFishingHook(
            net.minecraft.client.player.LocalPlayer player, FishingHook hook, UUID expectedId) {
        return hook != null && !hook.isRemoved() && hook.getOwner() == player
                && (expectedId == null || expectedId.equals(hook.getUUID()));
    }

    static Map<String, Integer> inventoryCounts(
            net.minecraft.client.player.LocalPlayer player) {
        var counts = new LinkedHashMap<String, Integer>();
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) {
                counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                        stack.getCount(), Integer::sum);
            }
        }
        return Map.copyOf(counts);
    }

    static int totalInventoryCount(Map<String, Integer> counts) {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    static boolean playerPickupAreaIntersects(
            AABB playerBounds, dev.aod.mcmcp.agent.observation.ObservationValues.Aabb itemBounds) {
        Objects.requireNonNull(playerBounds, "playerBounds");
        Objects.requireNonNull(itemBounds, "itemBounds");
        AABB pickupArea = playerBounds.inflate(1.0D, 0.5D, 1.0D);
        return pickupArea.intersects(new AABB(
                itemBounds.minX(), itemBounds.minY(), itemBounds.minZ(),
                itemBounds.maxX(), itemBounds.maxY(), itemBounds.maxZ()));
    }
}
