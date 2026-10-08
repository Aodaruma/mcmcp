package dev.aod.mcmcp.runtime;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Replans only after each packet-confirmed transfer; never drops an item or uses a spare slot. */
final class MinecraftV2InventorySortDriver implements V2OperationJobExecution.Driver {
    private final Minecraft minecraft;
    private final V2InventorySortArguments request;
    private MinecraftV2InventoryMoveDriver active;
    private List<ContainerSyncSignals.StackFingerprint> expected;
    private int confirmed;
    private String failure;

    MinecraftV2InventorySortDriver(Minecraft minecraft, V2InventorySortArguments request) {
        this.minecraft = minecraft;
        this.request = request;
    }
    public void begin(long tick, BooleanSupplier allowed) {
        var snapshot = PlayerMenuSyncSignals.global().snapshot(minecraft);
        if (minecraft.player.containerMenu != minecraft.player.inventoryMenu
                || !V2CraftWork.matchesLive(minecraft.player.inventoryMenu, snapshot) || !snapshot.carried().empty()) {
            failure = "inventory_server_snapshot_required";
        } else expected = snapshot.slots();
    }
    public V2OperationJobExecution.Step tick(long tick, BooleanSupplier allowed) {
        if (failure != null) return V2OperationJobExecution.Step.FAILED;
        if (active != null) {
            var step = active.tick(tick, allowed);
            if (step == V2OperationJobExecution.Step.FAILED) { failure = "inventory_sort_transfer_failed"; return step; }
            if (step == V2OperationJobExecution.Step.CONFIRMED) {
                active.close();
                active = null;
                confirmed++;
                expected = PlayerMenuSyncSignals.global().snapshot(minecraft).slots();
            }
            return V2OperationJobExecution.Step.RUNNING;
        }
        var snapshot = PlayerMenuSyncSignals.global().snapshot(minecraft);
        if (!V2CraftWork.matchesLive(minecraft.player.inventoryMenu, snapshot)
                || !snapshot.carried().empty() || !snapshot.slots().equals(expected)) {
            failure = "inventory_sort_slots_changed";
            return V2OperationJobExecution.Step.FAILED;
        }
        var inventory = minecraft.player.getInventory();
        var slots = request.slots();
        // Fill earlier equal-component stacks before sorting; every transfer reduces fragmentation.
        for (int i = 0; i < slots.size(); i++) {
            var into = inventory.getItem(slots.get(i));
            if (into.isEmpty() || into.getCount() == into.getMaxStackSize()) continue;
            for (int j = i + 1; j < slots.size(); j++) {
                var from = inventory.getItem(slots.get(j));
                if (!from.isEmpty() && ItemStack.isSameItemSameComponents(from, into)) {
                    return start(slots.get(j), slots.get(i),
                            Math.min(from.getCount(), into.getMaxStackSize() - into.getCount()), false, tick, allowed);
                }
            }
        }
        if (!request.consolidateOnly()) {
            for (int i = 0; i < slots.size(); i++) {
                int best = i;
                for (int j = i + 1; j < slots.size(); j++) {
                    if (compare(inventory.getItem(slots.get(j)), inventory.getItem(slots.get(best))) < 0) best = j;
                }
                if (best != i) return start(slots.get(best), slots.get(i),
                        inventory.getItem(slots.get(best)).getCount(), true, tick, allowed);
            }
        }
        return V2OperationJobExecution.Step.CONFIRMED;
    }
    static int compare(ItemStack a, ItemStack b) {
        var left = ContainerSyncSignals.StackFingerprint.fromServerPacket(a);
        var right = ContainerSyncSignals.StackFingerprint.fromServerPacket(b);
        if (left.empty() || right.empty()) return Boolean.compare(left.empty(), right.empty());
        int item = left.itemId().compareTo(right.itemId());
        return item != 0 ? item : Integer.compare(left.itemAndComponentsHash(), right.itemAndComponentsHash());
    }
    private V2OperationJobExecution.Step start(int from, int to, int count, boolean swap, long tick, BooleanSupplier allowed) {
        var stack = minecraft.player.getInventory().getItem(from);
        active = new MinecraftV2InventoryMoveDriver(minecraft,
                new V2InventoryMoveArguments(from, to, ContainerSyncSignals.StackFingerprint.fromServerPacket(stack).itemId(),
                        count, request.maxTicks()), swap);
        active.begin(tick, allowed);
        return V2OperationJobExecution.Step.RUNNING;
    }
    public void close() { if (active != null) active.close(); }
    public Map<String, Object> result() {
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("operation", request.consolidateOnly() ? "consolidate" : "sort");
        result.put("slots", request.slots());
        result.put("confirmed_transfers", confirmed);
        if (active != null) result.put("current_transfer", active.result());
        if (failure != null) result.put("failure", failure);
        return result;
    }
}
