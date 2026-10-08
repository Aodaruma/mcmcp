package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.routine.ExactInventoryTransfer;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Shares the storage transfer planner, including server cursor proof and cancellation recovery. */
final class MinecraftV2InventoryMoveDriver implements V2OperationJobExecution.Driver {
    private final Minecraft minecraft;
    private final V2InventoryMoveArguments request;
    private final PlayerMenuSyncSignals signals = PlayerMenuSyncSignals.global();
    private LocalPlayer player;
    private ClientLevel level;
    private ExactInventoryTransfer transfer;
    private ExactInventoryTransfer.CursorReturn cursorReturn;
    private long returnRevision;
    private String failure;

    MinecraftV2InventoryMoveDriver(Minecraft minecraft, V2InventoryMoveArguments request) {
        this.minecraft = minecraft;
        this.request = request;
    }

    public void begin(long tick, BooleanSupplier allowed) {
        player = minecraft.player;
        level = minecraft.level;
        var snapshot = signals.snapshot(minecraft);
        if (player.containerMenu != player.inventoryMenu || !V2CraftWork.matchesLive(player.inventoryMenu, snapshot)
                || !snapshot.carried().empty()) { failure = "inventory_server_snapshot_required"; return; }
        int source = menuSlot(request.source());
        int destination = menuSlot(request.destination());
        var stack = player.inventoryMenu.getSlot(source).getItem();
        if (stack.isEmpty() || !snapshot.slots().get(source).itemId().equals(request.item())
                || !ExactInventoryTransfer.ordinaryPickupItem(stack.getItem().getClass())
                || !ordinary(source, stack) || !ordinary(destination, stack)) {
            failure = "inventory_move_source_or_slot_changed";
            return;
        }
        transfer = ExactInventoryTransfer.plan(snapshot.slots(), List.of(source), List.of(destination),
                request.item(), snapshot.slots().get(source).itemAndComponentsHash(), true,
                request.count(), 1, stack.getMaxStackSize()).orElse(null);
        if (transfer == null) failure = "inventory_move_capacity_or_count";
    }

    public V2OperationJobExecution.Step tick(long tick, BooleanSupplier allowed) {
        if (failure != null) return V2OperationJobExecution.Step.FAILED;
        if (!context()) return fail("inventory_move_context_changed");
        var snapshot = signals.snapshot(minecraft);
        if (!V2CraftWork.matchesLive(player.inventoryMenu, snapshot)) return waiting(tick);
        transfer.confirm(snapshot, signals.cursorProofRevision(minecraft));
        if (transfer.awaitingAcknowledgement()) return waiting(tick);
        if (transfer.exhausted()) return V2OperationJobExecution.Step.CONFIRMED;
        if (!transfer.matchesConfirmed(snapshot)) return fail("inventory_move_slots_changed");
        if (!allowed.getAsBoolean()) return fail("inventory_move_dispatch_denied");
        if (transfer.beginClick(snapshot, tick)) send(transfer.next().slot(), transfer.next().button());
        return V2OperationJobExecution.Step.RUNNING;
    }

    private V2OperationJobExecution.Step waiting(long tick) {
        return transfer.ackTimedOut(tick) ? fail("inventory_move_sync_timeout") : V2OperationJobExecution.Step.RUNNING;
    }
    private boolean context() {
        return minecraft.player == player && minecraft.level == level && player.containerMenu == player.inventoryMenu;
    }
    private boolean ordinary(int slot, net.minecraft.world.item.ItemStack stack) {
        var target = player.inventoryMenu.getSlot(slot);
        return target.getClass() == Slot.class && target.mayPickup(player) && target.mayPlace(stack)
                && target.getMaxStackSize(stack) >= stack.getMaxStackSize();
    }
    private int menuSlot(int inventorySlot) {
        for (int index = 0; index < player.inventoryMenu.slots.size(); index++) {
            var slot = player.inventoryMenu.getSlot(index);
            if (slot.container == player.getInventory() && slot.getContainerSlot() == inventorySlot) return index;
        }
        throw new IllegalStateException("player slot unavailable");
    }
    private void send(int slot, int button) {
        var menu = player.inventoryMenu;
        var connection = minecraft.getConnection();
        connection.send(new ServerboundContainerClickPacket(menu.containerId, menu.getStateId(),
                (short) slot, (byte) button, ContainerInput.PICKUP, new Int2ObjectOpenHashMap<>(),
                HashedStack.create(menu.getCarried(), connection.decoratedHashOpsGenenerator())));
    }
    public void close() {
        if (transfer == null || minecraft.player != player || minecraft.level != level) return;
        if (!context()) throw new IllegalStateException("inventory move ownership changed");
        var snapshot = signals.snapshot(minecraft);
        if (!V2CraftWork.matchesLive(player.inventoryMenu, snapshot)) throw new IllegalStateException("inventory move release awaiting slots");
        if (cursorReturn != null) {
            if (signals.cursorProofRevision(minecraft) > returnRevision && cursorReturn.confirmed(snapshot)
                    && transfer.confirmRelease(snapshot)) return;
            throw new IllegalStateException("inventory cursor return awaiting confirmation");
        }
        transfer.confirm(snapshot, signals.cursorProofRevision(minecraft));
        if (transfer.awaitingAcknowledgement()) {
            failure = "inventory_move_dispatch_uncertain";
            throw new IllegalStateException("inventory move dispatch uncertain");
        }
        if (snapshot.carried().empty() && transfer.confirmRelease(snapshot)) return;
        var recovery = transfer.cursorReturn(snapshot);
        if (recovery.isEmpty()) throw new IllegalStateException("inventory cursor source changed");
        cursorReturn = recovery.orElseThrow();
        returnRevision = snapshot.packetLedgerRevision();
        send(cursorReturn.slot(), 0);
        throw new IllegalStateException("inventory cursor return awaiting confirmation");
    }
    public Map<String, Object> result() {
        var result = new LinkedHashMap<String, Object>();
        result.put("operation", "move");
        result.put("source_slot", request.source());
        result.put("destination_slot", request.destination());
        result.put("requested_count", request.count());
        result.put("confirmed_count", transfer == null ? 0 : transfer.confirmedCount());
        result.put("unconfirmed", transfer != null && transfer.pending());
        if (failure != null) result.put("failure", failure);
        return result;
    }
    private V2OperationJobExecution.Step fail(String reason) {
        failure = reason;
        return V2OperationJobExecution.Step.FAILED;
    }
}
