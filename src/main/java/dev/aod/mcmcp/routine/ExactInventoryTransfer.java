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
public final class ExactInventoryTransfer {
    public static boolean ordinaryPickupItem(Class<?> itemType) {
        return KnownMenuTransfers.ordinaryPickupItem(itemType);
    }
    static final int MAX_CLICKS = 14;
    private static final int MAX_SEARCH_STATES = 10_000;
    private final List<Click> clicks;
    private final List<Integer> capacities;
    private final List<StackFingerprint> initialSlots;
    private final List<Integer> sourceSlots;
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
    private int cursorOrigin = -1;

    private ExactInventoryTransfer(List<StackFingerprint> initial, List<Click> clicks, List<Integer> capacities,
            List<Integer> sources) {
        this.confirmedSlots = List.copyOf(initial);
        this.initialSlots = List.copyOf(initial);
        this.sourceSlots = List.copyOf(sources);
        this.clicks = List.copyOf(clicks);
        this.capacities = List.copyOf(capacities);
    }

    /** Plans everything before any click; capacity/count/click failure never causes a partial start. */
    public static Optional<ExactInventoryTransfer> swap(List<StackFingerprint> initial, int source,
            int destination, List<Integer> capacities) {
        if (source < 0 || destination < 0 || source == destination || source >= initial.size()
                || destination >= initial.size() || capacities.size() != initial.size()) return Optional.empty();
        var from = initial.get(source);
        var to = initial.get(destination);
        if (from.empty() || from.count() > capacities.get(destination)
                || to.count() > capacities.get(source) || !to.empty() && sameItem(from, to)) return Optional.empty();
        var slots = new ArrayList<>(initial);
        var clicks = new ArrayList<Click>();
        slots.set(source, StackFingerprint.EMPTY);
        clicks.add(new Click(source, 0, List.copyOf(slots), from, 0, false));
        slots.set(destination, from);
        clicks.add(new Click(destination, 0, List.copyOf(slots), to, 0, to.empty()));
        if (!to.empty()) {
            slots.set(source, to);
            clicks.add(new Click(source, 0, List.copyOf(slots), StackFingerprint.EMPTY, 0, true));
        }
        return Optional.of(new ExactInventoryTransfer(initial, clicks, capacities, List.of(source, destination)));
    }

    public static Optional<ExactInventoryTransfer> plan(
            List<StackFingerprint> initial, List<Integer> sources, List<Integer> destinations,
            String item, int defaultHash, boolean defaultOnly,
            int quantity, int maxSources, int stackLimit) {
        return plan(initial, sources, destinations, item, defaultHash, defaultOnly,
                quantity, maxSources, stackLimit,
                Collections.nCopies(initial.size(), stackLimit));
    }

    /** Slot capacity and cursor pickup limit are separate, including expanded storage stacks. */
    public static Optional<ExactInventoryTransfer> plan(
            List<StackFingerprint> initial, List<Integer> sources, List<Integer> destinations,
            String item, int defaultHash, boolean defaultOnly,
            int quantity, int maxSources, int stackLimit, List<Integer> capacities) {
        return plan(initial, sources, destinations, item, defaultHash, defaultOnly,
                quantity, maxSources, stackLimit, capacities, Integer.MAX_VALUE);
    }

    /** A verified menu provider may cap the amount picked up before applying a secondary split. */
    public static Optional<ExactInventoryTransfer> plan(
            List<StackFingerprint> initial, List<Integer> sources, List<Integer> destinations,
            String item, int defaultHash, boolean defaultOnly,
            int quantity, int maxSources, int stackLimit, List<Integer> capacities, int pickupLimit) {
        if (quantity < 1 || quantity > 896 || maxSources < 1 || maxSources > 14
                || stackLimit < 1 || stackLimit > 64
                || pickupLimit < stackLimit
                || capacities.size() != initial.size()
                || capacities.stream().anyMatch(limit -> limit == null || limit < 0)
                || sources.isEmpty() || destinations.isEmpty()
                || sources.stream().anyMatch(slot -> slot < 0 || slot >= initial.size())
                || destinations.stream().anyMatch(slot -> slot < 0 || slot >= initial.size())
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
            if (original.count() > capacities.get(source) || ++usedSources > maxSources) return Optional.empty();
            for (int destination : destinations) {
                var from = slots.get(source);
                var to = slots.get(destination);
                if (from.empty() || remaining == 0) break;
                if (!to.empty() && !sameItem(from, to)) continue;
                int sourceLimit = capacities.get(source);
                int destinationLimit = capacities.get(destination);
                // One side must be an ordinary player slot. This also bounds the search space.
                if (Math.min(sourceLimit, destinationLimit) > stackLimit
                        || to.count() > destinationLimit) return Optional.empty();
                int moved = Math.min(remaining, Math.min(from.count(), destinationLimit - to.count()));
                if (moved == 0) continue;
                var path = split(from.count(), to.count(), moved, sourceLimit, destinationLimit,
                        MAX_CLICKS - clicks.size(), pickupLimit);
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
            if (remaining == 0) return Optional.of(new ExactInventoryTransfer(initial, clicks, capacities, sources));
        }
        return Optional.empty();
    }

    /** Simulate ordinary Menu PICKUP transitions with a finite search bound. */
    private static Optional<List<Transition>> split(
            int source, int destination, int quantity,
            int sourceLimit, int destinationLimit, int maxClicks, int pickupLimit) {
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
                    int limit = fromSource ? sourceLimit : destinationLimit;
                    int pickup = Math.min(slot, pickupLimit);
                    int amount = cursor == 0
                            ? (button == 0 ? pickup : Math.ceilDiv(pickup, 2))
                            : Math.min(button == 0 ? cursor : 1, limit - slot);
                    if (amount == 0) continue;
                    int afterSlot = cursor == 0 ? slot - amount : slot + amount;
                    int afterCursor = cursor == 0 ? amount : cursor - amount;
                    var after = new Counts(fromSource ? afterSlot : before.source(),
                            fromSource ? before.destination() : afterSlot, afterCursor);
                    if (visited.add(after)) {
                        if (visited.size() > MAX_SEARCH_STATES) return Optional.empty();
                        queue.addLast(new Search(after, current,
                                new Transition(fromSource, button, after), current.depth() + 1));
                    }
                }
            }
        }
        return Optional.empty();
    }

    public boolean beginClick(ContainerSnapshot snapshot, long tick) {
        if (inFlight || exhausted() || tick <= dispatchTick
                || snapshot.packetLedgerRevision() < confirmedRevision
                || !matchesConfirmed(snapshot)) return false;
        inFlight = true;
        groupStarted = true;
        dispatchRevision = snapshot.packetLedgerRevision();
        dispatchTick = tick;
        return true;
    }

    public boolean confirm(ContainerSnapshot snapshot, long cursorProofRevision) {
        if (!inFlight || snapshot.packetLedgerRevision() <= dispatchRevision
                || cursorProofRevision <= dispatchRevision
                || !next().slots().equals(snapshot.slots())
                || !next().cursor().equals(snapshot.carried())) return false;
        var click = next();
        if (confirmedCursor.empty() && !click.cursor().empty()) cursorOrigin = click.slot();
        if (click.cursor().empty()) cursorOrigin = -1;
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

    public boolean matchesConfirmed(ContainerSnapshot snapshot) {
        return confirmedSlots.equals(snapshot.slots()) && confirmedCursor.equals(snapshot.carried());
    }

    /** Return only the proven cursor remainder to the slot that supplied it, without resuming work. */
    public Optional<CursorReturn> cursorReturn(ContainerSnapshot snapshot) {
        if (inFlight || confirmedCursor.empty() || cursorOrigin < 0 || !matchesConfirmed(snapshot)) {
            return Optional.empty();
        }
        return plannedCursorReturn();
    }

    private Optional<CursorReturn> plannedCursorReturn() {
        if (confirmedCursor.empty() || cursorOrigin < 0) return Optional.empty();
        var source = confirmedSlots.get(cursorOrigin);
        if (!source.empty() && !sameItem(source, confirmedCursor)) return Optional.empty();
        long count = (long) source.count() + confirmedCursor.count();
        if (count > capacities.get(cursorOrigin)) return Optional.empty();
        var after = new ArrayList<>(confirmedSlots);
        after.set(cursorOrigin, withCount(confirmedCursor, (int) count));
        return Optional.of(new CursorReturn(cursorOrigin, List.copyOf(after)));
    }

    public boolean awaitingAcknowledgement() { return inFlight; }

    /** Stops at a proven empty cursor, retaining even a partly completed split group. */
    public boolean confirmRelease(ContainerSnapshot snapshot) {
        if (inFlight || !snapshot.carried().empty()) return false;
        boolean returned = plannedCursorReturn().filter(value -> value.confirmed(snapshot)).isPresent();
        if (!matchesConfirmed(snapshot) && !returned) return false;
        confirmedCount = sourceSlots.stream().mapToInt(slot -> initialSlots.get(slot).count() - snapshot.slots().get(slot).count()).sum();
        confirmedSlots = snapshot.slots();
        confirmedCursor = StackFingerprint.EMPTY;
        confirmedRevision = snapshot.packetLedgerRevision();
        cursorOrigin = -1;
        groupStarted = false;
        next = clicks.size();
        return true;
    }

    public record CursorReturn(int slot, List<StackFingerprint> slots) {
        public boolean confirmed(ContainerSnapshot snapshot) {
            return snapshot.carried().empty() && slots.equals(snapshot.slots());
        }
    }

    boolean reconcileReadback(ContainerSnapshot snapshot) {
        return !pending() && confirmedCount > 0 && snapshot.carried().empty()
                && matchesConfirmed(snapshot);
    }

    public boolean ackTimedOut(long tick) {
        return inFlight && tick - dispatchTick >= KnownMenuTransfers.UPDATE_TIMEOUT_TICKS;
    }

    public Click next() { return clicks.get(next); }
    public boolean exhausted() { return next == clicks.size(); }
    public boolean pending() { return inFlight || groupStarted; }
    int pendingCount() { return pending() ? next().quantity() : 0; }
    public int confirmedCount() { return confirmedCount; }
    int confirmedGroups() { return confirmedGroups; }
    int clickCount() { return clicks.size(); }
    public long dispatchRevision() { return dispatchRevision; }
    List<Click> clicks() { return clicks; }

    private static boolean sameItem(StackFingerprint left, StackFingerprint right) {
        return left.sameItem(right);
    }

    private static StackFingerprint withCount(StackFingerprint stack, int count) {
        return stack.withCount(count);
    }

    public record Click(int slot, int button, List<StackFingerprint> slots, StackFingerprint cursor,
                 int quantity, boolean groupEnd) { }
    private record Counts(int source, int destination, int cursor) { }
    private record Transition(boolean source, int button, Counts after) { }
    private record Search(Counts counts, Search parent, Transition transition, int depth) { }
}
