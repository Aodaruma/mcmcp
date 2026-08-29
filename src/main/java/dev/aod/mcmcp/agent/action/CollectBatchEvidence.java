package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.agent.dsl.ActionDsl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded pickup evidence for one listed collect batch.
 *
 * <p>A target can consume at most one newly observed inventory item, and only after the runtime
 * records physical pickup-area contact with that target's fresh policy-visible AABB. Inventory
 * increases seen before contact are deliberately discarded instead of being carried forward.
 */
public final class CollectBatchEvidence {
    private final List<ActionDsl.CollectTarget> targets;
    private final Map<String, Integer> baselineCounts;
    private final Map<String, Integer> lastCounts;
    private final long[] lastContactTicks;
    private final boolean[] credited;

    public CollectBatchEvidence(
            List<ActionDsl.CollectTarget> targets, Map<String, Integer> baselineCounts) {
        this.targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
        this.baselineCounts = new LinkedHashMap<>(
                Objects.requireNonNull(baselineCounts, "baselineCounts"));
        lastCounts = new LinkedHashMap<>(this.baselineCounts);
        lastContactTicks = new long[this.targets.size()];
        Arrays.fill(lastContactTicks, -1L);
        credited = new boolean[this.targets.size()];
        for (ActionDsl.CollectTarget target : this.targets) {
            Integer baseline = this.baselineCounts.get(target.displayedItem());
            if (baseline == null || baseline < 0) {
                throw new IllegalArgumentException(
                        "every collect batch item requires a non-negative inventory baseline");
            }
        }
    }

    public void recordContact(int targetIndex, long clientTick) {
        requireIndex(targetIndex);
        if (clientTick < 0L) {
            throw new IllegalArgumentException("contact tick must be non-negative");
        }
        if (!credited[targetIndex]) {
            lastContactTicks[targetIndex] = clientTick;
        }
    }

    public List<Credit> reconcile(
            Map<String, Integer> currentCounts,
            long clientTick,
            long contactMaxAgeTicks,
            int currentTargetIndex) {
        Objects.requireNonNull(currentCounts, "currentCounts");
        if (clientTick < 0L || contactMaxAgeTicks < 0L) {
            throw new IllegalArgumentException("pickup evidence bounds must be non-negative");
        }
        requireIndex(currentTargetIndex);
        var credits = new ArrayList<Credit>();
        var availableByItem = new LinkedHashMap<String, Integer>();
        for (var item : baselineCounts.keySet()) {
            int previous = lastCounts.get(item);
            Integer currentValue = currentCounts.get(item);
            if (currentValue == null || currentValue < 0) {
                throw new IllegalArgumentException(
                        "every collect batch item requires a non-negative current count");
            }
            int current = currentValue;
            if (current < previous) {
                throw new InventoryDecreasedException();
            }
            availableByItem.put(item, current - previous);
        }
        for (int index = 0; index < targets.size(); index++) {
            String item = targets.get(index).displayedItem();
            int available = availableByItem.get(item);
            if (available <= 0
                    || credited[index]
                    || !contactCurrent(index, clientTick, contactMaxAgeTicks)) {
                continue;
            }
            int previous = lastCounts.get(item);
            int current = currentCounts.get(item);
            credited[index] = true;
            availableByItem.put(item, available - 1);
            credits.add(new Credit(index, index != currentTargetIndex, previous, current));
        }
        for (var item : baselineCounts.keySet()) {
            int current = currentCounts.get(item);
            lastCounts.put(item, current);
        }
        return List.copyOf(credits);
    }

    public boolean credited(int targetIndex) {
        requireIndex(targetIndex);
        return credited[targetIndex];
    }

    public int baselineCount(String item) {
        Integer count = baselineCounts.get(Objects.requireNonNull(item, "item"));
        if (count == null) throw new IllegalArgumentException("item is outside this collect batch");
        return count;
    }

    private boolean contactCurrent(int index, long currentTick, long maximumAge) {
        long contactTick = lastContactTicks[index];
        return contactTick >= 0L
                && contactTick <= currentTick
                && currentTick - contactTick <= maximumAge;
    }

    private void requireIndex(int targetIndex) {
        if (targetIndex < 0 || targetIndex >= targets.size()) {
            throw new IllegalArgumentException("target index is outside the collect batch");
        }
    }

    public record Credit(
            int targetIndex, boolean incidental, int inventoryBefore, int inventoryAfter) {
        public Credit {
            if (targetIndex < 0 || inventoryBefore < 0 || inventoryAfter <= inventoryBefore) {
                throw new IllegalArgumentException("invalid collect batch credit");
            }
        }
    }

    public static final class InventoryDecreasedException extends IllegalStateException {
        public InventoryDecreasedException() {
            super("collect batch inventory count decreased");
        }
    }
}
