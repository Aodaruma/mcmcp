package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.runtime.ContainerSyncSignals.StackFingerprint;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.entity.player.Inventory;

import java.util.Objects;
import java.util.UUID;

/** One main-inventory-to-hotbar SWAP, confirmed only from both inbound slot payloads. */
final class V2InventorySwap implements AutoCloseable {
    private static final int ACK_TIMEOUT_TICKS = 80;

    private final Minecraft minecraft;
    private final ClientLevel level;
    private final UUID session;
    private final long startedTick;
    private final int source;
    private final int destination;
    private final StackFingerprint sourceBefore;
    private final StackFingerprint destinationBefore;
    private final InventorySwapSignals.Ticket ticket;
    private boolean closed;

    private V2InventorySwap(Minecraft minecraft, ClientLevel level, UUID session, long startedTick,
            int source, int destination, StackFingerprint sourceBefore,
            StackFingerprint destinationBefore, InventorySwapSignals.Ticket ticket) {
        this.minecraft = minecraft;
        this.level = level;
        this.session = session;
        this.startedTick = startedTick;
        this.source = source;
        this.destination = destination;
        this.sourceBefore = sourceBefore;
        this.destinationBefore = destinationBefore;
        this.ticket = ticket;
    }

    static V2InventorySwap start(Minecraft minecraft, UUID session, long clientTick,
            int source, int destination) {
        Objects.requireNonNull(minecraft, "minecraft");
        Objects.requireNonNull(session, "session");
        if (!minecraft.isSameThread()) throw new IllegalStateException("SWAP requires client thread");
        var player = Objects.requireNonNull(minecraft.player, "player");
        var level = Objects.requireNonNull(minecraft.level, "level");
        var connection = Objects.requireNonNull(minecraft.getConnection(), "connection");
        var inventory = player.getInventory();
        if (source < Inventory.getSelectionSize() || source >= Inventory.INVENTORY_SIZE
                || destination < 0 || destination >= Inventory.getSelectionSize()
                || player.containerMenu != player.inventoryMenu
                || !player.inventoryMenu.getCarried().isEmpty()
                || inventory.getSelectedSlot() != destination) {
            throw new IllegalArgumentException("inventory SWAP is not safe in the current menu");
        }
        int menuSlot = -1;
        for (int index = InventoryMenu.INV_SLOT_START;
                index < player.inventoryMenu.slots.size(); index++) {
            var slot = player.inventoryMenu.slots.get(index);
            if (slot.container == inventory && slot.getContainerSlot() == source) {
                menuSlot = index;
                break;
            }
        }
        if (menuSlot < 0) throw new IllegalStateException("main inventory slot is unavailable");
        var sourceBefore = StackFingerprint.fromServerPacket(inventory.getItem(source));
        var destinationBefore = StackFingerprint.fromServerPacket(inventory.getItem(destination));
        var ticket = InventorySwapSignals.global().begin(level, session, source, destination,
                sourceBefore, destinationBefore);
        var attempt = new V2InventorySwap(minecraft, level, session, clientTick,
                source, destination, sourceBefore, destinationBefore, ticket);
        try {
            // Register before sending. A send exception is uncertain and is never retried.
            connection.send(new ServerboundContainerClickPacket(player.inventoryMenu.containerId,
                    player.inventoryMenu.getStateId(), (short) menuSlot, (byte) destination,
                    ContainerInput.SWAP, new Int2ObjectOpenHashMap<>(),
                    HashedStack.create(player.inventoryMenu.getCarried(),
                            connection.decoratedHashOpsGenenerator())));
            return attempt;
        } catch (RuntimeException | LinkageError failure) {
            attempt.close();
            throw failure;
        }
    }

    Result poll(UUID currentSession, long clientTick) {
        if (closed || !session.equals(currentSession)
                || minecraft.player == null || minecraft.level != level) return Result.FAILED;
        var player = minecraft.player;
        if (player.containerMenu != player.inventoryMenu
                || !player.inventoryMenu.getCarried().isEmpty()
                || player.getInventory().getSelectedSlot() != destination) return Result.FAILED;
        var status = ticket.result(currentSession);
        if (status == InventorySwapSignals.Result.MISMATCH) return Result.FAILED;
        if (status == InventorySwapSignals.Result.WAITING) {
            return clientTick - startedTick > ACK_TIMEOUT_TICKS
                    ? Result.FAILED : Result.WAITING;
        }
        var inventory = player.getInventory();
        return StackFingerprint.fromServerPacket(inventory.getItem(destination))
                        .equals(sourceBefore)
                && StackFingerprint.fromServerPacket(inventory.getItem(source))
                        .equals(destinationBefore)
                ? Result.CONFIRMED : Result.FAILED;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        InventorySwapSignals.global().close(level, ticket);
    }

    enum Result { WAITING, CONFIRMED, FAILED }
}
