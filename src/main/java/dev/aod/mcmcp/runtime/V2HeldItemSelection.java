package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.runtime.ContainerSyncSignals.StackFingerprint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.entity.player.Inventory;

import java.util.UUID;

/** Selects a held item, sharing the server-confirmed main-inventory swap path. */
final class V2HeldItemSelection implements AutoCloseable {
    private final Minecraft minecraft;
    private final UUID session;
    private final String item;
    private final LocalPlayer player;
    private final ClientLevel level;
    private final int originalSlot;
    private V2InventorySwap staging;
    private int selectedSlot = -1;
    private boolean begun;
    private boolean layoutMayHaveChanged;

    V2HeldItemSelection(Minecraft minecraft, UUID session, String item) {
        this.minecraft = minecraft;
        this.session = session;
        this.item = item;
        player = minecraft.player;
        level = minecraft.level;
        originalSlot = player.getInventory().getSelectedSlot();
    }

    boolean begin(long tick, boolean allowEmpty) {
        if (begun) throw new IllegalStateException("item selection already begun");
        begun = true;
        var inventory = player.getInventory();
        int source = originalSlot;
        if (!matches(source)) {
            source = -1;
            for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
                if (matches(slot)) { source = slot; break; }
            }
        }
        if (source < 0 || !allowEmpty && inventory.getItem(source).isEmpty()) return false;
        selectedSlot = source < 9 ? source : originalSlot;
        if (source >= 9) {
            layoutMayHaveChanged = true;
            staging = V2InventorySwap.start(minecraft, session, tick, source, selectedSlot);
        } else if (selectedSlot != originalSlot) {
            inventory.setSelectedSlot(selectedSlot);
            minecraft.getConnection().send(new ServerboundSetCarriedItemPacket(selectedSlot));
        }
        return true;
    }

    V2InventorySwap.Result poll(long tick) {
        if (!stillSelected()) return V2InventorySwap.Result.FAILED;
        if (staging == null) return V2InventorySwap.Result.CONFIRMED;
        var result = staging.poll(session, tick);
        if (result != V2InventorySwap.Result.WAITING) {
            staging.close();
            staging = null;
        }
        return result;
    }

    boolean matchesRequested() { return selectedSlot >= 0 && matches(selectedSlot); }
    boolean stillSelected() {
        return selectedSlot >= 0 && minecraft.player == player && minecraft.level == level
                && player.getInventory().getSelectedSlot() == selectedSlot;
    }
    int slot() { return selectedSlot; }
    boolean layoutMayHaveChanged() { return layoutMayHaveChanged; }

    private boolean matches(int slot) {
        return item == null || item.equals(
                StackFingerprint.fromServerPacket(player.getInventory().getItem(slot)).itemId());
    }

    @Override
    public void close() {
        if (staging != null) { staging.close(); staging = null; }
        if (stillSelected()) player.getInventory().setSelectedSlot(originalSlot);
    }
}
