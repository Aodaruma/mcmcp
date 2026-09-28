package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.runtime.ContainerSyncSignals.StackFingerprint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Exchanges one exact main-inventory stack with a hotbar slot and waits for both server slots. */
final class MinecraftV2InventorySwapDriver implements V2InventoryJobExecution.Driver {
    private final Minecraft minecraft;
    private final UUID session;
    private final V2InventorySwapArguments request;
    private ClientLevel level;
    private LocalPlayer player;
    private V2InventorySwap swap;
    private int originalSelected = -1;
    private boolean selectionChanged;
    private boolean layoutMayHaveChanged;
    private boolean confirmed;
    private String failure;
    private StackFingerprint sourceBefore;
    private StackFingerprint hotbarBefore;
    private StackFingerprint sourceAfter;
    private StackFingerprint hotbarAfter;

    MinecraftV2InventorySwapDriver(Minecraft minecraft, UUID session,
            V2InventorySwapArguments request) {
        this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
        this.session = Objects.requireNonNull(session, "session");
        this.request = Objects.requireNonNull(request, "request");
    }

    @Override
    public void begin(long clientTick, BooleanSupplier outputAllowed) {
        if (player != null) throw new IllegalStateException("swap already begun");
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
        sourceBefore = StackFingerprint.fromServerPacket(inventory.getItem(request.sourceSlot()));
        hotbarBefore = StackFingerprint.fromServerPacket(inventory.getItem(request.hotbarSlot()));
        if (sourceBefore.empty() || !request.itemId().equals(sourceBefore.itemId())) {
            fail("swap_source_changed");
            return;
        }
        originalSelected = inventory.getSelectedSlot();
        if (originalSelected != request.hotbarSlot()) {
            selectionChanged = true;
            inventory.setSelectedSlot(request.hotbarSlot());
            try {
                minecraft.getConnection().send(
                        new ServerboundSetCarriedItemPacket(request.hotbarSlot()));
            } catch (RuntimeException | LinkageError sendFailure) {
                fail("selection_send_uncertain");
                return;
            }
        }
        layoutMayHaveChanged = true;
        try {
            swap = V2InventorySwap.start(minecraft, session, clientTick,
                    request.sourceSlot(), request.hotbarSlot());
        } catch (RuntimeException | LinkageError sendFailure) {
            fail("swap_send_uncertain");
        }
    }

    @Override
    public V2InventoryJobExecution.Step tick(long clientTick,
            BooleanSupplier outputAllowed) {
        if (failure != null || swap == null) return V2InventoryJobExecution.Step.FAILED;
        if (!outputAllowed.getAsBoolean() || minecraft.player != player
                || minecraft.level != level
                || player.containerMenu != player.inventoryMenu
                || !player.inventoryMenu.getCarried().isEmpty()
                || player.getInventory().getSelectedSlot() != request.hotbarSlot()) {
            fail("inventory_context_changed");
            return V2InventoryJobExecution.Step.FAILED;
        }
        return switch (swap.poll(session, clientTick)) {
            case WAITING -> V2InventoryJobExecution.Step.RUNNING;
            case FAILED -> {
                fail("swap_not_confirmed");
                yield V2InventoryJobExecution.Step.FAILED;
            }
            case CONFIRMED -> {
                var inventory = player.getInventory();
                sourceAfter = StackFingerprint.fromServerPacket(
                        inventory.getItem(request.sourceSlot()));
                hotbarAfter = StackFingerprint.fromServerPacket(
                        inventory.getItem(request.hotbarSlot()));
                confirmed = true;
                layoutMayHaveChanged = false;
                yield V2InventoryJobExecution.Step.CONFIRMED;
            }
        };
    }

    @Override
    public Map<String, Object> result() {
        var result = new LinkedHashMap<String, Object>();
        result.put("operation", "swap");
        result.put("source_slot", request.sourceSlot());
        result.put("hotbar_slot", request.hotbarSlot());
        result.put("item", request.itemId());
        result.put("confirmed", confirmed);
        if (sourceBefore != null) result.put("source_before", stack(sourceBefore));
        if (hotbarBefore != null) result.put("hotbar_before", stack(hotbarBefore));
        if (sourceAfter != null) result.put("source_after", stack(sourceAfter));
        if (hotbarAfter != null) result.put("hotbar_after", stack(hotbarAfter));
        if (layoutMayHaveChanged) result.put("inventory_layout_may_have_changed", true);
        if (failure != null) result.put("failure", failure);
        return result;
    }

    @Override
    public void close() {
        if (swap != null) {
            swap.close();
            swap = null;
        }
        if (selectionChanged && player != null && minecraft.player == player
                && minecraft.level == level
                && player.getInventory().getSelectedSlot() == request.hotbarSlot()) {
            if (minecraft.getConnection() != null) {
                minecraft.getConnection().send(
                        new ServerboundSetCarriedItemPacket(originalSelected));
            }
            player.getInventory().setSelectedSlot(originalSelected);
        }
        selectionChanged = false;
    }

    private static Map<String, Object> stack(StackFingerprint value) {
        return Map.of("item", value.itemId(), "count", value.count());
    }

    private void fail(String reason) { failure = reason; }
}
