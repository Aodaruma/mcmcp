package dev.aod.mcmcp.runtime;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Server payloads for the unregistered player menu, kept separate from owned block menus. */
public final class PlayerMenuSyncSignals {
    private static final PlayerMenuSyncSignals INSTANCE = new PlayerMenuSyncSignals();
    private final ContainerSyncSignals.SessionChannel channel = new ContainerSyncSignals.SessionChannel();
    private ClientLevel level;
    private LocalPlayer player;
    private int lastFullPacketSlots = -1;
    private long lastFullPacketTick = -1;

    public static PlayerMenuSyncSignals global() { return INSTANCE; }

    private boolean bind(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null || !minecraft.isSameThread()) return false;
        if (level != minecraft.level || player != minecraft.player) {
            level = minecraft.level;
            player = minecraft.player;
            channel.bindAndSnapshot(UUID.randomUUID());
            lastFullPacketSlots = -1;
            lastFullPacketTick = -1;
        }
        return true;
    }

    public void full(Minecraft minecraft, ClientboundContainerSetContentPacket packet) {
        if (!bind(minecraft)) return;
        if (packet.containerId() != player.inventoryMenu.containerId) {
            var menu = player.containerMenu;
            if (packet.containerId() == menu.containerId && packet.items().size() == menu.slots.size()) {
                for (int index = 0; index < menu.slots.size(); index++) {
                    var slot = menu.getSlot(index);
                    if (slot.container == player.getInventory()) playerSlot(minecraft, slot.getContainerSlot(), packet.items().get(index));
                }
            }
            return;
        }
        lastFullPacketSlots = packet.items().size();
        lastFullPacketTick = tick();
        if (packet.items().size() != player.inventoryMenu.slots.size()) return;
        channel.fullContent(0, "mcmcp:player_inventory", packet.stateId(),
                packet.items().stream().map(ContainerSyncSignals.StackFingerprint::fromServerPacket).toList(),
                ContainerSyncSignals.StackFingerprint.fromServerPacket(packet.carriedItem()), tick());
    }

    public void slot(Minecraft minecraft, ClientboundContainerSetSlotPacket packet) {
        if (!bind(minecraft)) return;
        var menu = player.containerMenu;
        if (packet.getContainerId() == player.inventoryMenu.containerId) {
            channel.slot(0, "mcmcp:player_inventory", packet.getStateId(), packet.getSlot(),
                    ContainerSyncSignals.StackFingerprint.fromServerPacket(packet.getItem()), tick());
        } else if (packet.getContainerId() == menu.containerId
                && packet.getSlot() >= 0 && packet.getSlot() < menu.slots.size()) {
            var slot = menu.getSlot(packet.getSlot());
            if (slot.container == player.getInventory()) playerSlot(minecraft, slot.getContainerSlot(), packet.getItem());
        }
    }

    public void playerSlot(Minecraft minecraft, int slot, ItemStack stack) {
        if (!bind(minecraft)) return;
        var indices = new ArrayList<Integer>();
        for (int index = 0; index < player.inventoryMenu.slots.size(); index++) {
            var entry = player.inventoryMenu.getSlot(index);
            if (entry.container == player.getInventory() && entry.getContainerSlot() == slot) indices.add(index);
        }
        channel.playerInventorySlot(0, "mcmcp:player_inventory", List.copyOf(indices),
                ContainerSyncSignals.StackFingerprint.fromServerPacket(stack), tick());
    }

    public void carried(Minecraft minecraft, ItemStack stack) {
        if (bind(minecraft) && player.containerMenu == player.inventoryMenu) {
            channel.carried(0, "mcmcp:player_inventory",
                    ContainerSyncSignals.StackFingerprint.fromServerPacket(stack), tick());
        }
    }

    ContainerSyncSignals.ContainerSnapshot snapshot(Minecraft minecraft) {
        if (!bind(minecraft)) return null;
        return channel.snapshot().container();
    }

    long cursorProofRevision(Minecraft minecraft) { return bind(minecraft) ? channel.cursorProofRevision() : -1; }

    java.util.Map<String, Object> diagnostic(Minecraft minecraft) {
        if (!bind(minecraft)) return java.util.Map.of("reason", "player_unavailable");
        var menu = player.inventoryMenu;
        var result = InventorySyncDiagnostic.compare(menu.containerId,
                menu.slots.stream().map(slot -> ContainerSyncSignals.StackFingerprint.fromServerPacket(slot.getItem())).toList(),
                ContainerSyncSignals.StackFingerprint.fromServerPacket(menu.getCarried()), channel.snapshot().container());
        result.put("active_menu_is_player", player.containerMenu == menu);
        result.put("last_full_packet_slots", lastFullPacketSlots);
        result.put("last_full_packet_tick", lastFullPacketTick);
        result.put("cursor_proof_revision", channel.cursorProofRevision());
        return java.util.Map.copyOf(result);
    }

    public void clear() { level = null; player = null; }
    private static long tick() { return ScreenOwnershipSignals.global().currentTick(); }
}
