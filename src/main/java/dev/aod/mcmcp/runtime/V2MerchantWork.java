package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.routine.ExactInventoryTransfer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import static dev.aod.mcmcp.runtime.ContainerSyncSignals.*;

/** Trades one exact payment at a time; never repeats an unconfirmed selection or purchase. */
final class V2MerchantWork implements MinecraftV2MenuDriver.Work {
    private final Minecraft minecraft;
    private final V2TradeArguments request;
    private final V2MerchantReferences references;
    private final UUID session;
    private final UUID merchant;
    private final String dimension;
    private final V2MerchantReferences.Entry reference;
    private final MerchantOfferSignals.Baseline baseline;
    private MerchantOfferSignals.OfferSnapshot offer;
    private long offerRevision;
    private int packetConfirmed;
    private List<Map<String, Object>> offers = List.of();
    private Map<String, Integer> cost = Map.of();
    private Map<StackFingerprint, Integer> before;
    private Map<StackFingerprint, Integer> expected;
    private Stage stage = Stage.OPEN;
    private long dispatchedAt;
    private long dispatchRevision;
    private ExactInventoryTransfer transfer;
    private ExactInventoryTransfer.CursorReturn returning;
    private long returnRevision;
    private int confirmed;
    private boolean uncertain;
    private String failure;

    V2MerchantWork(Minecraft minecraft, WorldSessionTracker.Snapshot session, UUID merchant,
            V2TradeArguments request, V2MerchantReferences references, V2MerchantReferences.Entry reference) {
        this.minecraft = minecraft;
        this.session = session.worldSessionId();
        this.dimension = session.dimension();
        this.merchant = merchant;
        this.request = request;
        this.references = references;
        this.reference = reference;
        baseline = MerchantOfferSignals.global().bindSession(minecraft.level, this.session);
    }

    public V2BlockJobExecution.StepResult tick(AbstractContainerMenu raw, ContainerSnapshot snapshot,
            long tick, BooleanSupplier allowed) {
        if (failure != null) return V2BlockJobExecution.StepResult.FAILED;
        if (raw.getClass() != MerchantMenu.class || raw.slots.size() != 39) return fail("unsupported_merchant_menu");
        var menu = (MerchantMenu) raw;
        var packet = MerchantOfferSignals.global().latestAfter(minecraft.level, baseline).orElse(null);
        var open = ContainerSyncSignals.global().bindAndSnapshot(minecraft.level, session).lastOpenScreen();
        if (packet == null || open == null || packet.containerId() != menu.containerId
                || packet.openPacketRevision() != open.packetLedgerRevision()) return waitForAck(tick);
        if (!V2CraftWork.matchesLive(menu, snapshot)) return waitForAck(tick);
        if (stage == Stage.OPEN) {
            if (!snapshot.carried().empty() || !snapshot.slots().subList(0, 3).stream().allMatch(StackFingerprint::empty))
                return fail("merchant_inputs_not_empty");
            if (packet.offers().size() > 128) return fail("too_many_merchant_offers");
            if (reference == null) {
                var views = MerchantOfferView.from(packet);
                var published = new ArrayList<Map<String, Object>>();
                for (int i = 0; i < views.size(); i++) {
                    var result = new LinkedHashMap<>(views.get(i).toMap());
                    result.put("offer_ref", references.register(session, dimension, merchant, tick, packet.offers().get(i)));
                    result.put("valid_for_ticks", V2MerchantReferences.TTL_TICKS);
                    published.add(Map.copyOf(result));
                }
                offers = List.copyOf(published);
                return V2BlockJobExecution.StepResult.CONFIRMED;
            }
            if (tick > reference.expires() || reference.offer().offerIndex() >= packet.offers().size()) return fail("offer_reference_expired");
            offer = packet.offers().get(reference.offer().offerIndex());
            if (!V2MerchantReferences.sameOffer(reference.offer(), offer, 0)) return fail("merchant_offer_changed");
            if (offer.outOfStock() || request.count() > (long) offer.maxUses() - offer.uses()) return fail("insufficient_trade_stock");
            cost = costs(offer);
            if (cost.isEmpty() || !request.permits(cost)) return fail("trade_cost_limit");
            before = totals(snapshot);
            if (!hasPayments(before, offer, request.count())) return fail("insufficient_trade_materials");
            offerRevision = packet.revision();
            stage = Stage.READY;
        }
        if (packet.revision() != offerRevision) {
            if (offer.offerIndex() >= packet.offers().size()
                    || !V2MerchantReferences.sameOffer(offer, packet.offers().get(offer.offerIndex()), confirmed - packetConfirmed))
                return fail("merchant_offer_changed");
            offer = packet.offers().get(offer.offerIndex());
            offerRevision = packet.revision();
            packetConfirmed = confirmed;
        }
        if (transfer != null) {
            var masked = withoutOutput(snapshot);
            transfer.confirm(masked, ContainerSyncSignals.global().cursorProofRevision(minecraft.level));
            if (transfer.awaitingAcknowledgement()) return transfer.ackTimedOut(tick) ? fail("trade_payment_sync_timeout") : running();
            if (transfer.exhausted()) transfer = null;
            else {
                if (!transfer.matchesConfirmed(masked)) return fail("trade_payment_changed");
                if (allowed.getAsBoolean() && transfer.beginClick(masked, tick)) {
                    dispatchedAt = tick;
                    send(menu, transfer.next().slot(), transfer.next().button(), ContainerInput.PICKUP);
                }
                return running();
            }
        }
        if (!snapshot.carried().empty()) return fail("trade_cursor_changed");
        if (stage == Stage.COLLECTING) {
            reconcile(snapshot);
            if (stage == Stage.COLLECTING) return waitForAck(tick);
        }
        if (confirmed == request.count()) return V2BlockJobExecution.StepResult.CONFIRMED;
        if (!allowed.getAsBoolean()) return fail("trade_dispatch_denied");
        if (stage == Stage.READY) {
            if (!snapshot.slots().subList(0, 3).stream().allMatch(StackFingerprint::empty)
                    || !totals(snapshot).equals(before)) return fail("trade_inventory_changed");
            // Vanilla selects and fills payment slots. The following stage returns any excess.
            stage = Stage.SELECTING;
            uncertain = true;
            dispatchedAt = tick;
            dispatchRevision = snapshot.packetLedgerRevision();
            minecraft.getConnection().send(new ServerboundSelectTradePacket(offer.offerIndex()));
            return running();
        }
        if (snapshot.packetLedgerRevision() <= dispatchRevision) return waitForAck(tick);
        if (!totals(snapshot).equals(before)) return fail("trade_inventory_changed");
        var payments = List.of(offer.costA(), offer.costB());
        for (int slot = 0; slot < 2; slot++) {
            var expectedPayment = payments.get(slot);
            var actual = menu.getSlot(slot).getItem();
            if (actual.isEmpty() != expectedPayment.isEmpty()
                    || !actual.isEmpty() && (!ItemStack.isSameItemSameComponents(actual, expectedPayment)
                        || actual.getCount() < expectedPayment.getCount())) return waitForAck(tick);
            int excess = actual.getCount() - expectedPayment.getCount();
            if (excess > 0) {
                if (!ExactInventoryTransfer.ordinaryPickupItem(actual.getItem().getClass())) return fail("unsupported_trade_payment_item");
                var destinations = new ArrayList<Integer>();
                for (int i = 3; i < menu.slots.size(); i++) {
                    var dest = menu.getSlot(i);
                    if (dest.getClass() == net.minecraft.world.inventory.Slot.class && dest.mayPlace(actual)
                            && (dest.getItem().isEmpty() || ItemStack.isSameItemSameComponents(dest.getItem(), actual))) destinations.add(i);
                }
                var fingerprint = snapshot.slots().get(slot);
                transfer = ExactInventoryTransfer.plan(withoutOutput(snapshot).slots(), List.of(slot), destinations,
                        fingerprint.itemId(), fingerprint.itemAndComponentsHash(), true, excess, 1,
                        actual.getMaxStackSize(), menu.slots.stream().map(s -> s.getMaxStackSize(actual)).toList()).orElse(null);
                if (transfer == null) return fail("trade_payment_return_capacity");
                return running();
            }
        }
        if (!ItemStack.matches(menu.getSlot(2).getItem(), offer.result())) return waitForAck(tick);
        var inventory = new ArrayList<ItemStack>();
        for (int i = 3; i < menu.slots.size(); i++) inventory.add(menu.getSlot(i).getItem().copy());
        if (!V2CraftWork.store(inventory, offer.result())) return fail("trade_output_capacity");
        var inputs = paymentTotals(offer);
        expected = V2CraftWork.expectedProduction(before, inputs,
                StackFingerprint.fromServerPacket(offer.result()).withCount(1), offer.result().getCount(), Map.of());
        // Exact single-payment inputs bound vanilla QUICK_MOVE to one trade, even on a repeated-result menu.
        stage = Stage.COLLECTING;
        dispatchRevision = snapshot.packetLedgerRevision();
        dispatchedAt = tick;
        send(menu, 2, 0, ContainerInput.QUICK_MOVE);
        return running();
    }

    private void reconcile(ContainerSnapshot snapshot) {
        if (stage != Stage.COLLECTING || snapshot.packetLedgerRevision() <= dispatchRevision
                || !snapshot.carried().empty() || !snapshot.slots().subList(0, 3).stream().allMatch(StackFingerprint::empty)
                || !totals(snapshot).equals(expected)) return;
        confirmed++;
        before = expected;
        uncertain = false;
        stage = Stage.READY;
    }

    public void observeBeforeClose(AbstractContainerMenu menu, ContainerSnapshot raw) {
        if (!V2CraftWork.matchesLive(menu, raw)) throw new IllegalStateException("merchant snapshot unavailable for release");
        if (transfer != null) {
            var snapshot = withoutOutput(raw);
            long cursor = ContainerSyncSignals.global().cursorProofRevision(minecraft.level);
            if (returning != null) {
                if (cursor <= returnRevision || !returning.confirmed(snapshot) || !transfer.confirmRelease(snapshot))
                    throw new IllegalStateException("merchant cursor return awaiting confirmation");
                transfer = null;
            } else {
                transfer.confirm(snapshot, cursor);
                if (transfer.awaitingAcknowledgement()) throw new IllegalStateException("merchant payment awaiting confirmation");
                if (snapshot.carried().empty() && transfer.confirmRelease(snapshot)) transfer = null;
                else {
                    returning = transfer.cursorReturn(snapshot).orElseThrow(() -> new IllegalStateException("merchant cursor origin changed"));
                    returnRevision = snapshot.packetLedgerRevision();
                    send(menu, returning.slot(), 0, ContainerInput.PICKUP);
                    throw new IllegalStateException("merchant cursor return awaiting confirmation");
                }
            }
        }
        reconcile(raw);
    }

    static Map<String, Integer> costs(MerchantOfferSignals.OfferSnapshot offer) {
        var result = new LinkedHashMap<String, Integer>();
        for (var item : List.of(offer.costA(), offer.costB())) {
            var stack = StackFingerprint.fromServerPacket(item);
            if (!stack.empty()) result.merge(stack.itemId(), stack.count(), Math::addExact);
        }
        return Map.copyOf(result);
    }
    static Map<StackFingerprint, Integer> paymentTotals(MerchantOfferSignals.OfferSnapshot offer) {
        var totals = new LinkedHashMap<StackFingerprint, Integer>();
        for (var item : List.of(offer.costA(), offer.costB())) add(totals, StackFingerprint.fromServerPacket(item));
        return totals;
    }
    static boolean hasPayments(Map<StackFingerprint, Integer> totals, MerchantOfferSignals.OfferSnapshot offer, int count) {
        return paymentTotals(offer).entrySet().stream().allMatch(entry ->
                (long) entry.getValue() * count <= totals.getOrDefault(entry.getKey(), 0));
    }
    static Map<StackFingerprint, Integer> totals(ContainerSnapshot snapshot) {
        var totals = new LinkedHashMap<StackFingerprint, Integer>();
        for (int i = 0; i < snapshot.slots().size(); i++) if (i != 2) add(totals, snapshot.slots().get(i));
        add(totals, snapshot.carried());
        return totals;
    }
    private static void add(Map<StackFingerprint, Integer> totals, StackFingerprint stack) {
        if (!stack.empty()) totals.merge(stack.withCount(1), stack.count(), Math::addExact);
    }
    private static ContainerSnapshot withoutOutput(ContainerSnapshot snapshot) {
        var slots = new ArrayList<>(snapshot.slots());
        slots.set(2, StackFingerprint.EMPTY);
        return new ContainerSnapshot(snapshot.worldSessionId(), snapshot.containerId(), snapshot.menuTypeId(),
                snapshot.stateId(), slots, snapshot.carried(), snapshot.receivedTick(), snapshot.packetLedgerRevision());
    }
    private void send(AbstractContainerMenu menu, int slot, int button, ContainerInput input) {
        var connection = minecraft.getConnection();
        connection.send(new ServerboundContainerClickPacket(menu.containerId, menu.getStateId(), (short) slot,
                (byte) button, input, new Int2ObjectOpenHashMap<>(),
                HashedStack.create(menu.getCarried(), connection.decoratedHashOpsGenenerator())));
    }
    private V2BlockJobExecution.StepResult waitForAck(long tick) {
        return stage == Stage.OPEN || tick - dispatchedAt <= 100 ? running() : fail("trade_sync_timeout");
    }
    private static V2BlockJobExecution.StepResult running() { return V2BlockJobExecution.StepResult.RUNNING; }
    private V2BlockJobExecution.StepResult fail(String reason) { failure = reason; return V2BlockJobExecution.StepResult.FAILED; }
    public Map<String, Object> result() {
        var result = new LinkedHashMap<String, Object>();
        result.put("operation", request.operation());
        result.put("entity_ref", request.entity().ref());
        result.put("confirmation", reference == null && !offers.isEmpty() ? "server_merchant_offers"
                : confirmed > 0 ? "server_container_packets" : "none");
        result.put("effect_confirmed", confirmed > 0);
        if (reference == null) result.put("offers", offers);
        else {
            result.put("requested_trades", request.count());
            result.put("confirmed_trades", confirmed);
            result.put("confirmed_count", offer == null ? 0 : confirmed * offer.result().getCount());
            var consumed = new LinkedHashMap<String, Integer>();
            cost.forEach((item, count) -> consumed.put(item, count * confirmed));
            result.put("consumed", consumed);
            result.put("unconfirmed", uncertain);
        }
        if (failure != null) result.put("failure", failure);
        return result;
    }
    private enum Stage { OPEN, READY, SELECTING, COLLECTING }
}
