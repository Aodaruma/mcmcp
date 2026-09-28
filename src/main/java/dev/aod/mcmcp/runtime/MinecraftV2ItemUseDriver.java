package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.client.AgentInputState;
import dev.aod.mcmcp.routine.BoundedInputLease;
import dev.aod.mcmcp.runtime.ContainerSyncSignals.StackFingerprint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** One ordinary held-item use. A server ACK proves processing, not an item-specific world effect. */
final class MinecraftV2ItemUseDriver implements V2OperationJobExecution.Driver {
    private static final Duration LEASE_HORIZON = Duration.ofMillis(500);
    private final Minecraft minecraft;
    private final UUID session;
    private final V2ItemUseArguments request;
    private LocalPlayer player;
    private ClientLevel level;
    private V2InventorySwap staging;
    private BoundedInputLease use;
    private ClientPredictionSignals.PredictionAttempt prediction;
    private int originalSlot = -1;
    private int selectedSlot = -1;
    private boolean begun;
    private boolean dispatched;
    private boolean clientConsumed;
    private boolean serverProcessed;
    private boolean layoutMayHaveChanged;
    private long dispatchedTick;
    private long beforeRevision;
    private StackFingerprint before;
    private StackFingerprint confirmedAfter;
    private String failure;

    MinecraftV2ItemUseDriver(Minecraft minecraft, UUID session, V2ItemUseArguments request) {
        this.minecraft = Objects.requireNonNull(minecraft);
        this.session = Objects.requireNonNull(session);
        this.request = Objects.requireNonNull(request);
    }

    @Override
    public void begin(long tick, BooleanSupplier outputAllowed) {
        if (begun) throw new IllegalStateException("item use already begun");
        begun = true;
        player = minecraft.player;
        level = minecraft.level;
        if (!minecraft.isSameThread() || !outputAllowed.getAsBoolean() || !contextValid()
                || player.isUsingItem()) {
            failure = "item_context_unavailable";
            return;
        }
        var inventory = player.getInventory();
        originalSlot = inventory.getSelectedSlot();
        int source = originalSlot;
        if (request.item() != null && !matchesItem(source)) {
            source = -1;
            for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
                if (matchesItem(slot)) { source = slot; break; }
            }
        }
        if (source < 0 || inventory.getItem(source).isEmpty()) {
            failure = "item_not_available";
            return;
        }
        selectedSlot = source < 9 ? source : originalSlot;
        if (source >= 9) {
            layoutMayHaveChanged = true;
            staging = V2InventorySwap.start(minecraft, session, tick, source, selectedSlot);
        } else if (selectedSlot != originalSlot) {
            inventory.setSelectedSlot(selectedSlot);
            minecraft.getConnection().send(new ServerboundSetCarriedItemPacket(selectedSlot));
        }
    }

    @Override
    public V2OperationJobExecution.Step tick(long tick, BooleanSupplier outputAllowed) {
        if (failure != null) return V2OperationJobExecution.Step.FAILED;
        if (!outputAllowed.getAsBoolean() || !contextValid()
                || player.getInventory().getSelectedSlot() != selectedSlot) {
            failure = "item_context_changed";
            return V2OperationJobExecution.Step.FAILED;
        }
        if (staging != null) {
            var state = staging.poll(session, tick);
            if (state == V2InventorySwap.Result.WAITING) return V2OperationJobExecution.Step.RUNNING;
            staging.close();
            staging = null;
            if (state != V2InventorySwap.Result.CONFIRMED) {
                failure = "item_swap_not_confirmed";
                return V2OperationJobExecution.Step.FAILED;
            }
        }
        if (!dispatched) {
            if (!matchesItem(selectedSlot) || player.getMainHandItem().isEmpty()) {
                failure = "item_changed_before_use";
                return V2OperationJobExecution.Step.FAILED;
            }
            before = StackFingerprint.fromServerPacket(player.getMainHandItem());
            beforeRevision = HotbarPayloadSyncSignals.global().bindAndSnapshot(level, session).revision();
            prediction = ClientPredictionSignals.global().begin(level, player.blockPosition(), tick);
            int sequence = prediction.sequenceBeforePrediction();
            dispatched = true; // A throwing native call is uncertain; never repeat it.
            dispatchedTick = tick;
            var result = minecraft.gameMode.useItem(player, InteractionHand.MAIN_HAND);
            clientConsumed = result.consumesAction();
            if (prediction.captureIssuedPredictions() != sequence + 1) {
                failure = "item_prediction_unavailable";
                return V2OperationJobExecution.Step.FAILED;
            }
            if (player.isUsingItem()) {
                use = BoundedInputLease.acquire(AgentInputState.global(),
                        Set.of(BoundedInputLease.Input.USE), System.nanoTime(), LEASE_HORIZON);
                AgentInputState.global().suppressUseRestart();
            }
            return V2OperationJobExecution.Step.RUNNING;
        }

        var ack = prediction.acknowledgement();
        serverProcessed = ack.acknowledged();
        captureStack();
        if (use != null) {
            if (!player.isUsingItem() || tick - dispatchedTick >= request.holdTicks()) {
                releaseUse();
            } else if (!use.heartbeat(System.nanoTime(), LEASE_HORIZON)) {
                failure = "item_input_expired";
                return V2OperationJobExecution.Step.FAILED;
            }
        }
        if (serverProcessed && use == null
                && (request.resultItem() == null
                    ? clientConsumed || confirmedAfter != null && !confirmedAfter.equals(before)
                    : confirmedAfter != null && request.resultItem().equals(confirmedAfter.itemId()))) {
            return V2OperationJobExecution.Step.CONFIRMED;
        }
        return V2OperationJobExecution.Step.RUNNING;
    }

    private void captureStack() {
        if (!dispatched || minecraft.player != player || minecraft.level != level
                || player.getInventory().getSelectedSlot() != selectedSlot) return;
        var evidence = HotbarPayloadSyncSignals.global().bindAndSnapshot(level, session)
                .slots().get(selectedSlot);
        if (evidence != null && evidence.revision() > beforeRevision
                && evidence.stack().equals(StackFingerprint.fromServerPacket(player.getMainHandItem()))) {
            confirmedAfter = evidence.stack();
        }
    }

    private boolean matchesItem(int slot) {
        return request.item() == null || request.item().equals(
                StackFingerprint.fromServerPacket(player.getInventory().getItem(slot)).itemId());
    }

    private boolean contextValid() {
        return player != null && level != null && minecraft.player == player && minecraft.level == level
                && minecraft.gameMode != null && minecraft.getConnection() != null
                && player.containerMenu == player.inventoryMenu && player.inventoryMenu.getCarried().isEmpty();
    }

    @Override
    public Map<String, Object> result() {
        var result = new LinkedHashMap<String, Object>();
        result.put("target", "item");
        result.put("dispatched", dispatched);
        result.put("server_processed", serverProcessed);
        result.put("client_consumed", clientConsumed);
        result.put("effect_confirmed", confirmedAfter != null && !confirmedAfter.equals(before));
        result.put("inventory_layout_may_have_changed", layoutMayHaveChanged);
        if (selectedSlot >= 0) result.put("slot", selectedSlot);
        if (confirmedAfter != null) result.put("held_after", Map.of(
                "item", confirmedAfter.itemId(), "count", confirmedAfter.count()));
        if (failure != null) result.put("failure", failure);
        return result;
    }

    private void releaseUse() {
        if (use == null) return;
        use.close();
        use = null;
        if (minecraft.player == player && minecraft.level == level && player.isUsingItem()) {
            minecraft.gameMode.releaseUsingItem(player);
        }
    }

    @Override
    public void close() {
        releaseUse();
        if (staging != null) { staging.close(); staging = null; }
        captureStack();
        if (prediction != null) {
            serverProcessed = prediction.acknowledgement().acknowledged();
            prediction.close();
            prediction = null;
        }
        if (minecraft.player == player && minecraft.level == level && player != null
                && selectedSlot >= 0 && originalSlot >= 0
                && player.getInventory().getSelectedSlot() == selectedSlot) {
            player.getInventory().setSelectedSlot(originalSlot);
        }
    }
}
