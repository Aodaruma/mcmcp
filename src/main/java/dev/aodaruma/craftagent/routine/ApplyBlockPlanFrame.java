package dev.aodaruma.craftagent.routine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Fresh same-tick plan observation used for preflight and final verification. */
public record ApplyBlockPlanFrame(
        long clientTick,
        long observationRevision,
        boolean worldReady,
        boolean clientFocused,
        boolean playerAlive,
        boolean healthSafe,
        boolean visibleThreatClear,
        boolean screenClear,
        boolean inventoryServerSynchronized,
        Map<BlockTarget, ApplyBlockPlanCellObservation> cells,
        Map<String, Integer> inventoryCounts,
        Set<String> hotbarItemIds) {
    public ApplyBlockPlanFrame {
        if (clientTick < 0 || observationRevision < 0) {
            throw new IllegalArgumentException("frame clock must be non-negative");
        }
        Objects.requireNonNull(cells, "cells");
        var cellCopy = new LinkedHashMap<BlockTarget, ApplyBlockPlanCellObservation>();
        cells.forEach((target, cell) -> {
            Objects.requireNonNull(target, "cell target");
            Objects.requireNonNull(cell, "cell observation");
            if (!target.equals(cell.target())) {
                throw new IllegalArgumentException("cell map key must equal its target");
            }
            cellCopy.put(target, cell);
        });
        cells = Collections.unmodifiableMap(cellCopy);

        Objects.requireNonNull(inventoryCounts, "inventoryCounts");
        var inventoryCopy = new LinkedHashMap<String, Integer>();
        inventoryCounts.forEach((item, count) -> {
            Objects.requireNonNull(item, "inventory item");
            Objects.requireNonNull(count, "inventory count");
            if (count < 0) {
                throw new IllegalArgumentException("inventory count must be non-negative");
            }
            inventoryCopy.put(item, count);
        });
        inventoryCounts = Collections.unmodifiableMap(inventoryCopy);
        Objects.requireNonNull(hotbarItemIds, "hotbarItemIds");
        hotbarItemIds = Set.copyOf(hotbarItemIds);
    }

    public boolean universalSafetyClear() {
        return worldReady && clientFocused && playerAlive && healthSafe
                && visibleThreatClear && screenClear;
    }
}
