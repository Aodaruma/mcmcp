package dev.aod.mcmcp.routine;

import dev.aod.mcmcp.runtime.ContainerSyncSignals.ContainerSnapshot;
import dev.aod.mcmcp.runtime.ContainerSyncSignals.StackFingerprint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/** A fixed, bounded sequence of ordinary PICKUP clicks with packet-proven cursor transitions. */
final class ExactInventoryTransfer {
    static final int MAX_CLICKS = 14;
    private final List<Click> clicks;
    private List<StackFingerprint> confirmedSlots;
    private StackFingerprint confirmedCursor = StackFingerprint.EMPTY;
    private int next;
    private int confirmedCount;
    private int confirmedGroups;
    private boolean inFlight;
    private boolean groupStarted;
    private long dispatchRevision;
    private long confirmedRevision = -1;
    private long dispatchTick = -1;

    private ExactInventoryTransfer(List<StackFingerprint> initial, List<Click> clicks) {
        this.confirmedSlots = List.copyOf(initial);
        this.clicks = List.copyOf(clicks);
    }

    /** Plans everything before any click; capacity/count/click failure never causes a partial start. */
    static Optional<ExactInventoryTransfer> plan(
            List<StackFingerprint> initial, List<Integer> sources, List<Integer> destinations,
            String item, int defaultHash, boolean defaultOnly,
            int quantity, int maxSources, int stackLimit) {
        if (quantity < 1 || quantity > 896 || maxSources < 1 || maxSources > 14
                || stackLimit < 1 || stackLimit > 64
                || sources.isEmpty() || destinations.isEmpty()
                || new HashSet<>(sources).size() != sources.size()
                || new HashSet<>(destinations).size() != destinations.size()
                || sources.stream().anyMatch(destinations::contains)) return Optional.empty();
        var slots = new ArrayList<>(initial);
        var clicks = new ArrayList<Click>();
        int remaining = quantity;
        int usedSources = 0;
        for (int source : sources) {
            var original = initial.get(source);
            if (original.empty() || !original.itemId().equals(item)
                    || (defaultOnly && original.itemAndComponentsHash() != defaultHash)) continue;
            if (original.count() > stackLimit || ++usedSources > maxSources) return Optional.empty();
            for (int destination : destinations) {
                var from = slots.get(source);
                var to = slots.get(destination);
                if (from.empty() || remaining == 0) break;
                if (!to.empty() && !sameItem(from, to)) continue;
                if (to.count() > stackLimit) return Optional.empty();
                int moved = Math.min(remaining, Math.min(from.count(), stackLimit - to.count()));
                if (moved == 0) continue;
                var path = split(from.count(), to.count(), moved, stackLimit,
                        MAX_CLICKS - clicks.size());
                if (path.isEmpty()) return Optional.empty();
                var transitions = path.orElseThrow();
                for (int i = 0; i < transitions.size(); i++) {
                    var transition = transitions.get(i);
                    slots.set(source, withCount(from, transition.after().source()));
                    slots.set(destination, withCount(from, transition.after().destination()));
                    clicks.add(new Click(transition.source() ? source : destination,
                            transition.button(), List.copyOf(slots),
                            withCount(from, transition.after().cursor()), moved,
                            i == transitions.size() - 1));
                }
                remaining -= moved;
            }
            if (remaining == 0) return Optional.of(new ExactInventoryTransfer(initial, clicks));
        }
        return Optional.empty();
    }

    /** At most 65*65 states: both normal slots contain the same item/components. */
    private static Optional<List<Transition>> split(
            int source, int destination, int quantity, int limit, int maxClicks) {
        var start = new Counts(source, destination, 0);
        var goal = new Counts(source - quantity, destination + quantity, 0);
        var queue = new ArrayDeque<Search>();
        var visited = new HashSet<Counts>();
        queue.add(new Search(start, null, null, 0));
        visited.add(start);
        while (!queue.isEmpty()) {
            var current = queue.removeFirst();
            if (current.counts().equals(goal)) {
                var path = new ArrayList<Transition>();
                for (var node = current; node.parent() != null; node = node.parent()) {
                    path.add(node.transition());
                }
                Collections.reverse(path);
                return Optional.of(List.copyOf(path));
            }
            if (current.depth() >= maxClicks) continue;
            for (boolean fromSource : new boolean[]{true, false}) {
                for (int button = 0; button <= 1; button++) {
                    var before = current.counts();
                    int slot = fromSource ? before.source() : before.destination();
                    int cursor = before.cursor();
                    int amount = cursor == 0
                            ? (button == 0 ? slot : Math.ceilDiv(slot, 2))
                            : Math.min(button == 0 ? cursor : 1, limit - slot);
                    if (amount == 0) continue;
                    int afterSlot = cursor == 0 ? slot - amount : slot + amount;
                    int afterCursor = cursor == 0 ? amount : cursor - amount;
                    var after = new Counts(fromSource ? afterSlot : before.source(),
                            fromSource ? before.destination() : afterSlot, afterCursor);
                    if (visited.add(after)) queue.addLast(new Search(after, current,
                            new Transition(fromSource, button, after), current.depth() + 1));
                }
            }
        }
        return Optional.empty();
    }

    boolean beginClick(ContainerSnapshot snapshot, long tick) {
        if (inFlight || exhausted() || tick <= dispatchTick
                || snapshot.packetLedgerRevision() < confirmedRevision
                || !matchesConfirmed(snapshot)) return false;
        inFlight = true;
        groupStarted = true;
        dispatchRevision = snapshot.packetLedgerRevision();
        dispatchTick = tick;
        return true;
    }

    boolean confirm(ContainerSnapshot snapshot, long cursorProofRevision) {
        if (!inFlight || snapshot.packetLedgerRevision() <= dispatchRevision
                || cursorProofRevision <= dispatchRevision
                || !next().slots().equals(snapshot.slots())
                || !next().cursor().equals(snapshot.carried())) return false;
        var click = next();
        confirmedSlots = snapshot.slots();
        confirmedCursor = snapshot.carried();
        confirmedRevision = snapshot.packetLedgerRevision();
        if (click.groupEnd()) {
            confirmedCount += click.quantity();
            confirmedGroups++;
            groupStarted = false;
        }
        next++;
        inFlight = false;
        return true;
    }

    boolean matchesConfirmed(ContainerSnapshot snapshot) {
        return confirmedSlots.equals(snapshot.slots()) && confirmedCursor.equals(snapshot.carried());
    }

    boolean reconcileReadback(ContainerSnapshot snapshot) {
        return !pending() && confirmedCount > 0 && snapshot.carried().empty()
                && matchesConfirmed(snapshot);
    }

    boolean ackTimedOut(long tick) {
        return inFlight && tick - dispatchTick >= KnownMenuTransfers.UPDATE_TIMEOUT_TICKS;
    }

    Click next() { return clicks.get(next); }
    boolean exhausted() { return next == clicks.size(); }
    boolean pending() { return inFlight || groupStarted; }
    int pendingCount() { return pending() ? next().quantity() : 0; }
    int confirmedCount() { return confirmedCount; }
    int confirmedGroups() { return confirmedGroups; }
    int clickCount() { return clicks.size(); }
    List<Click> clicks() { return clicks; }

    private static boolean sameItem(StackFingerprint left, StackFingerprint right) {
        return left.itemId().equals(right.itemId())
                && left.itemAndComponentsHash() == right.itemAndComponentsHash();
    }

    private static StackFingerprint withCount(StackFingerprint stack, int count) {
        return count == 0 ? StackFingerprint.EMPTY
                : new StackFingerprint(stack.itemId(), count, stack.itemAndComponentsHash());
    }

    record Click(int slot, int button, List<StackFingerprint> slots, StackFingerprint cursor,
                 int quantity, boolean groupEnd) { }
    private record Counts(int source, int destination, int cursor) { }
    private record Transition(boolean source, int button, Counts after) { }
    private record Search(Counts counts, Search parent, Transition transition, int depth) { }
}
