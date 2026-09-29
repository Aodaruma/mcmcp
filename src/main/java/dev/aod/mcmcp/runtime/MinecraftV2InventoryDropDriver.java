package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.runtime.ContainerSyncSignals.StackFingerprint;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Drops one confirmed stack quantity, never retrying an uncertain packet. */
final class MinecraftV2InventoryDropDriver implements V2OperationJobExecution.Driver {
    private static final int ACK_TIMEOUT_TICKS = 80;

    private final Minecraft minecraft;
    private final UUID session;
    private final V2InventoryDropArguments request;
    private final HotbarPayloadSyncSignals sync = HotbarPayloadSyncSignals.global();
    private ClientLevel level;
    private LocalPlayer player;
    private V2InventorySwap staging;
    private Stage stage = Stage.NEW;
    private int originalSelectedSlot = -1;
    private int selectedSlot = -1;
    private int sourceSlot = -1;
    private int confirmedCount;
    private int inFlightCount;
    private String failure;
    private long dispatchedTick;
    private long beforeRevision;
    private StackFingerprint expected;
    private boolean layoutMayHaveChanged;

    MinecraftV2InventoryDropDriver(Minecraft minecraft, UUID session,
            V2InventoryDropArguments request) {
        this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
        this.session = Objects.requireNonNull(session, "session");
        this.request = Objects.requireNonNull(request, "request");
    }

    @Override
    public void begin(long clientTick, BooleanSupplier outputAllowed) {
        if (stage != Stage.NEW) throw new IllegalStateException("drop already begun");
        if (!minecraft.isSameThread() || !outputAllowed.getAsBoolean()) {
            fail("dispatch_denied");
            return;
        }
        player = minecraft.player;
        level = minecraft.level;
        if (player == null || level == null || minecraft.getConnection() == null
                || player.containerMenu != player.inventoryMenu
                || !player.inventoryMenu.getCarried().isEmpty()) {
            fail("inventory_menu_unavailable");
            return;
        }
        var inventory = player.getInventory();
        originalSelectedSlot = inventory.getSelectedSlot();
        var stacks = new ArrayList<StackFingerprint>(Inventory.INVENTORY_SIZE);
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            stacks.add(StackFingerprint.fromServerPacket(inventory.getItem(slot)));
        }
        final Selection source;
        try {
            source = select(stacks, request);
        } catch (IllegalArgumentException invalid) {
            fail(invalid.getMessage());
            return;
        }
        sourceSlot = source.slot();
        if (source.slot() >= Inventory.getSelectionSize()) {
            selectedSlot = originalSelectedSlot;
            layoutMayHaveChanged = true;
            try {
                staging = V2InventorySwap.start(minecraft, session, clientTick,
                        source.slot(), selectedSlot);
                stage = Stage.STAGING;
            } catch (RuntimeException | LinkageError sendFailure) {
                fail("swap_send_uncertain");
            }
            return;
        }
        selectedSlot = source.slot();
        if (selectedSlot != originalSelectedSlot) {
            inventory.setSelectedSlot(selectedSlot);
            try {
                // TCP ordering ensures the selected slot reaches the server before THROW.
                minecraft.getConnection().send(new ServerboundSetCarriedItemPacket(selectedSlot));
            } catch (RuntimeException | LinkageError sendFailure) {
                fail("selection_send_uncertain");
                return;
            }
        }
        stage = Stage.READY;
    }

    @Override
    public V2OperationJobExecution.Step tick(long clientTick, BooleanSupplier outputAllowed) {
        if (stage == Stage.FAILED || stage == Stage.NEW) return V2OperationJobExecution.Step.FAILED;
        if (stage == Stage.DONE) return V2OperationJobExecution.Step.CONFIRMED;
        if (!outputAllowed.getAsBoolean() || minecraft.player != player || minecraft.level != level
                || player.containerMenu != player.inventoryMenu
                || !player.inventoryMenu.getCarried().isEmpty()
                || player.getInventory().getSelectedSlot() != selectedSlot) {
            fail("inventory_context_changed");
            return V2OperationJobExecution.Step.FAILED;
        }
        if (stage == Stage.STAGING) {
            var result = staging.poll(session, clientTick);
            if (result == V2InventorySwap.Result.WAITING) return V2OperationJobExecution.Step.RUNNING;
            staging.close();
            staging = null;
            if (result != V2InventorySwap.Result.CONFIRMED) {
                fail("swap_not_confirmed");
                return V2OperationJobExecution.Step.FAILED;
            }
            stage = Stage.READY;
        }
        if (stage == Stage.AWAITING_ACK) {
            var evidence = sync.bindAndSnapshot(level, session).slots().get(selectedSlot);
            if (evidence != null && evidence.revision() > beforeRevision
                    && evidence.stack().equals(expected)) {
                if (!StackFingerprint.fromServerPacket(
                        player.getInventory().getItem(selectedSlot)).equals(expected)) {
                    fail("drop_local_server_mismatch");
                    return V2OperationJobExecution.Step.FAILED;
                }
                confirmedCount += inFlightCount;
                inFlightCount = 0;
                expected = null;
                if (confirmedCount == request.count()) {
                    stage = Stage.DONE;
                    return V2OperationJobExecution.Step.CONFIRMED;
                }
                stage = Stage.READY;
            } else if (clientTick - dispatchedTick > ACK_TIMEOUT_TICKS) {
                fail("drop_ack_timeout");
                return V2OperationJobExecution.Step.FAILED;
            } else {
                return V2OperationJobExecution.Step.RUNNING;
            }
        }
        return dispatch(clientTick, outputAllowed);
    }

    private V2OperationJobExecution.Step dispatch(long clientTick,
            BooleanSupplier outputAllowed) {
        var before = StackFingerprint.fromServerPacket(
                player.getInventory().getItem(selectedSlot));
        int remaining = request.count() - confirmedCount;
        if (!request.itemId().equals(before.itemId()) || before.count() < remaining
                || !outputAllowed.getAsBoolean()) {
            fail("drop_source_changed");
            return V2OperationJobExecution.Step.FAILED;
        }
        boolean entireStack = before.count() == remaining;
        int menuSlot = player.inventoryMenu.findSlot(player.getInventory(), selectedSlot).orElse(-1);
        if (menuSlot < 0 || minecraft.getConnection() == null) {
            fail("inventory_menu_unavailable");
            return V2OperationJobExecution.Step.FAILED;
        }
        inFlightCount = entireStack ? remaining : 1;
        expected = entireStack ? StackFingerprint.EMPTY
                : new StackFingerprint(before.itemId(), before.count() - 1,
                        before.itemAndComponentsHash());
        beforeRevision = sync.bindAndSnapshot(level, session).revision();
        dispatchedTick = clientTick;
        stage = Stage.AWAITING_ACK; // A throwing send is uncertain and must not be retried.
        try {
            // LocalPlayer.drop predicts the decrement and ServerPlayer.drop suppresses
            // the matching slot update. A normal inventory THROW without client prediction
            // lets the server send the authoritative slot payload we require below.
            var connection = minecraft.getConnection();
            connection.send(new ServerboundContainerClickPacket(player.inventoryMenu.containerId,
                    player.inventoryMenu.getStateId(), (short) menuSlot, (byte) (entireStack ? 1 : 0),
                    ContainerInput.THROW, new Int2ObjectOpenHashMap<>(),
                    HashedStack.create(player.inventoryMenu.getCarried(),
                            connection.decoratedHashOpsGenenerator())));
        } catch (RuntimeException | LinkageError sendFailure) {
            fail("drop_send_uncertain");
            return V2OperationJobExecution.Step.FAILED;
        }
        return V2OperationJobExecution.Step.RUNNING;
    }

    @Override
    public Map<String, Object> result() {
        var result = new LinkedHashMap<String, Object>();
        result.put("operation", "drop");
        result.put("item", request.itemId());
        result.put("requested_count", request.count());
        result.put("confirmed_count", confirmedCount);
        result.put("unconfirmed_count", inFlightCount);
        if (selectedSlot >= 0) result.put("selected_slot", selectedSlot);
        if (sourceSlot >= 0) result.put("source_slot", sourceSlot);
        if (layoutMayHaveChanged) result.put("inventory_layout_may_have_changed", true);
        if (failure != null) result.put("failure", failure);
        return result;
    }

    @Override
    public void close() {
        if (staging != null) {
            staging.close();
            staging = null;
        }
        if (player != null && minecraft.player == player && minecraft.level == level
                && selectedSlot >= 0 && originalSelectedSlot >= 0
                && player.getInventory().getSelectedSlot() == selectedSlot) {
            player.getInventory().setSelectedSlot(originalSelectedSlot);
        }
    }

    private void fail(String reason) {
        failure = reason;
        stage = Stage.FAILED;
    }

    static Selection select(List<StackFingerprint> stacks, V2InventoryDropArguments request) {
        int found = -1;
        int end = Math.min(stacks.size(), Inventory.INVENTORY_SIZE);
        for (int slot = 0; slot < end; slot++) {
            if (request.slot() != null && slot != request.slot()) continue;
            var stack = stacks.get(slot);
            if (!request.itemId().equals(stack.itemId())) continue;
            if (found >= 0) throw new IllegalArgumentException("drop_source_ambiguous");
            found = slot;
        }
        if (found < 0) throw new IllegalArgumentException("drop_item_unavailable");
        var stack = stacks.get(found);
        if (stack.count() < request.count()) {
            throw new IllegalArgumentException("drop_count_unavailable");
        }
        return new Selection(found, stack);
    }

    record Selection(int slot, StackFingerprint stack) { }
    private enum Stage { NEW, STAGING, READY, AWAITING_ACK, DONE, FAILED }
}
