package dev.aod.mcmcp.routine;

import dev.aod.mcmcp.runtime.ContainerSyncSignals.StackFingerprint;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Objects;

/** Internal ordinary menu operation; callers must prove ownership and whole-stack capacity. */
final class KnownMenuTransfers {
    static final int UPDATE_TIMEOUT_TICKS = 60;

    private KnownMenuTransfers() { }

    static int maximumDestinationCount(ItemStack item, List<Slot> slots) {
        return slots.stream().mapToInt(slot ->
                Math.min(item.getMaxStackSize(), slot.getMaxStackSize(item))).sum();
    }

    static void dispatchServerConfirmedQuickMove(
            Minecraft minecraft, AbstractContainerMenu menu, int sourceSlot) {
        var connection = Objects.requireNonNull(minecraft.getConnection(), "connection");
        if (minecraft.player == null || minecraft.player.containerMenu != menu
                || !menu.getCarried().isEmpty() || sourceSlot < 0 || sourceSlot >= menu.slots.size()
                || !menu.slots.get(sourceSlot).hasItem()) {
            throw new IllegalStateException("known Menu click authority changed");
        }
        // With no local changed-slot prediction, the server sends the ordinary slot updates.
        connection.send(new ServerboundContainerClickPacket(
                menu.containerId, menu.getStateId(), (short) sourceSlot, (byte) 0,
                ContainerInput.QUICK_MOVE, new Int2ObjectOpenHashMap<>(),
                HashedStack.create(menu.getCarried(), connection.decoratedHashOpsGenenerator())));
    }

    /** A whole source stack disappeared and only matching destination stacks gained its units. */
    static boolean exactWholeStackMove(
            List<StackFingerprint> before, List<StackFingerprint> after,
            int sourceSlot, List<Integer> destinationSlots) {
        if (before.size() != after.size() || sourceSlot < 0 || sourceSlot >= before.size()
                || destinationSlots.contains(sourceSlot)) return false;
        var moved = before.get(sourceSlot);
        if (moved.empty() || !after.get(sourceSlot).empty()) return false;
        int gained = 0;
        for (int slot = 0; slot < before.size(); slot++) {
            if (slot == sourceSlot) continue;
            var original = before.get(slot);
            var current = after.get(slot);
            if (!destinationSlots.contains(slot)) {
                if (!original.equals(current)) return false;
                continue;
            }
            if (original.equals(current)) continue;
            if ((!original.empty() && !sameComponents(original, moved))
                    || !sameComponents(current, moved)
                    || current.count() < original.count()) return false;
            gained = Math.addExact(gained, current.count() - original.count());
        }
        return gained == moved.count();
    }

    private static boolean sameComponents(StackFingerprint left, StackFingerprint right) {
        return !left.empty() && left.itemId().equals(right.itemId())
                && left.itemAndComponentsHash() == right.itemAndComponentsHash();
    }
}
