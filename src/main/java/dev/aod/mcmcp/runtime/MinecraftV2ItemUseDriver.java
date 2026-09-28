package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.client.AgentInputState;
import dev.aod.mcmcp.routine.BoundedInputLease;
import dev.aod.mcmcp.runtime.ContainerSyncSignals.StackFingerprint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;

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
    private V2HeldItemSelection selection;
    private BoundedInputLease use;
    private ClientPredictionSignals.PredictionAttempt prediction;
    private boolean begun;
    private boolean dispatched;
    private boolean clientConsumed;
    private boolean serverProcessed;
    private long dispatchedTick;
    private V2HeldStackEvidence held;
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
        selection = new V2HeldItemSelection(minecraft, session, request.item());
        if (!selection.begin(tick, false)) failure = "item_not_available";
    }

    @Override
    public V2OperationJobExecution.Step tick(long tick, BooleanSupplier outputAllowed) {
        if (failure != null) return V2OperationJobExecution.Step.FAILED;
        if (!outputAllowed.getAsBoolean() || !contextValid()
                || !selection.stillSelected()) {
            failure = "item_context_changed";
            return V2OperationJobExecution.Step.FAILED;
        }
        var state = selection.poll(tick);
        if (state == V2InventorySwap.Result.WAITING) return V2OperationJobExecution.Step.RUNNING;
        if (state != V2InventorySwap.Result.CONFIRMED) {
            failure = "item_swap_not_confirmed";
            return V2OperationJobExecution.Step.FAILED;
        }
        if (!dispatched) {
            if (!selection.matchesRequested() || player.getMainHandItem().isEmpty()) {
                failure = "item_changed_before_use";
                return V2OperationJobExecution.Step.FAILED;
            }
            held = new V2HeldStackEvidence(StackFingerprint.fromServerPacket(player.getMainHandItem()),
                    HotbarPayloadSyncSignals.global().bindAndSnapshot(level, session).revision());
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
                    ? clientConsumed || held.changed() : held.matches(request.resultItem()))) {
            return V2OperationJobExecution.Step.CONFIRMED;
        }
        return V2OperationJobExecution.Step.RUNNING;
    }

    private void captureStack() {
        if (held == null || minecraft.player != player || minecraft.level != level
                || !selection.stillSelected()) return;
        held.observe(HotbarPayloadSyncSignals.global().bindAndSnapshot(level, session)
                .slots().get(selection.slot()), StackFingerprint.fromServerPacket(player.getMainHandItem()));
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
        result.put("effect_confirmed", held != null && held.changed());
        result.put("inventory_layout_may_have_changed", selection != null && selection.layoutMayHaveChanged());
        if (selection != null && selection.slot() >= 0) result.put("slot", selection.slot());
        if (held != null && held.after() != null) result.put("held_after", Map.of(
                "item", held.after().itemId(), "count", held.after().count()));
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
        captureStack();
        if (prediction != null) {
            serverProcessed = prediction.acknowledgement().acknowledged();
            prediction.close();
            prediction = null;
        }
        if (selection != null) selection.close();
    }
}
