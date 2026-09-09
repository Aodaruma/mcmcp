package dev.aod.mcmcp.routine;

import dev.aod.mcmcp.runtime.ContainerSyncSignals;
import java.util.ArrayList;
import java.util.List;

/** Fixed initial sources and per-click server baselines, bounded by the caller's stack/count caps. */
final class InventoryTransferBatch {
    private final List<PlannedTransferStack> plan;
    private final List<Integer> destinationSlots;
    private List<ContainerSyncSignals.StackFingerprint> confirmedSlots;
    private List<ContainerSyncSignals.StackFingerprint> clickBaseline;
    private long clickRevision;
    private long confirmedRevision = -1L;
    private long lastDispatchTick = -1L;
    private int confirmedMoves;
    private int confirmedCount;

    InventoryTransferBatch(
            List<ContainerSyncSignals.StackFingerprint> initial,
            List<Integer> sourceSlots, List<Integer> destinationSlots,
            String item, int defaultHash, boolean defaultComponentsOnly,
            int maximumStacks, int maximumCount, int neededCount) {
        if (maximumStacks < 1 || maximumStacks > 14 || maximumCount < 1 || maximumCount > 896) {
            throw new IllegalArgumentException("transfer batch limits are outside the contract");
        }
        this.confirmedSlots = List.copyOf(initial);
        this.destinationSlots = List.copyOf(destinationSlots);
        var selected = new ArrayList<PlannedTransferStack>();
        int plannedCount = 0;
        for (int slot : sourceSlots) {
            if (selected.size() >= maximumStacks || plannedCount >= neededCount) break;
            var stack = initial.get(slot);
            if (stack.empty() || !item.equals(stack.itemId())
                    || (defaultComponentsOnly && stack.itemAndComponentsHash() != defaultHash)
                    || stack.count() > maximumCount - plannedCount) continue;
            selected.add(new PlannedTransferStack(slot, stack));
            plannedCount = Math.addExact(plannedCount, stack.count());
        }
        this.plan = List.copyOf(selected);
    }

    PlannedTransferStack next() { return plan.get(confirmedMoves); }
    boolean exhausted() { return confirmedMoves >= plan.size(); }
    boolean inFlight() { return clickBaseline != null; }

    boolean beginClick(ContainerSyncSignals.ContainerSnapshot snapshot, long tick) {
        if (inFlight() || exhausted() || tick <= lastDispatchTick
                || !snapshot.carried().empty()
                || snapshot.packetLedgerRevision() < confirmedRevision
                || !confirmedSlots.equals(snapshot.slots())
                || !next().stack().equals(snapshot.slots().get(next().slot()))) return false;
        clickBaseline = List.copyOf(snapshot.slots());
        clickRevision = snapshot.packetLedgerRevision();
        lastDispatchTick = tick;
        return true;
    }

    boolean confirm(ContainerSyncSignals.ContainerSnapshot snapshot) {
        if (!inFlight() || snapshot.packetLedgerRevision() <= clickRevision
                || !snapshot.carried().empty()
                || !KnownMenuTransfers.exactWholeStackMove(
                        clickBaseline, snapshot.slots(), next().slot(), destinationSlots)) return false;
        confirmedCount = Math.addExact(confirmedCount, next().stack().count());
        confirmedMoves++;
        confirmedSlots = List.copyOf(snapshot.slots());
        confirmedRevision = snapshot.packetLedgerRevision();
        clickBaseline = null;
        return true;
    }

    boolean ackTimedOut(long tick) {
        return inFlight() && tick - lastDispatchTick >= KnownMenuTransfers.UPDATE_TIMEOUT_TICKS;
    }

    boolean reconcileReadback(ContainerSyncSignals.ContainerSnapshot snapshot) {
        if (inFlight()) confirm(snapshot);
        return !inFlight() && confirmedMoves > 0 && snapshot.carried().empty()
                && confirmedSlots.equals(snapshot.slots());
    }

    int confirmedCount() { return confirmedCount; }
    int confirmedMoves() { return confirmedMoves; }
    List<ContainerSyncSignals.StackFingerprint> confirmedSlots() { return confirmedSlots; }

    record PlannedTransferStack(int slot, ContainerSyncSignals.StackFingerprint stack) { }
}
