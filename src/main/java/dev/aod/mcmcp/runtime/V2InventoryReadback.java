package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.runtime.ContainerSyncSignals.StackFingerprint;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Compact slot-level player inventory result; empty slots are counted, not listed. */
final class V2InventoryReadback {
    private V2InventoryReadback() { }

    static Map<String, Object> capture(List<StackFingerprint> stacks,
            int selectedSlot, V2InventoryInspectArguments request) {
        Objects.requireNonNull(stacks, "stacks");
        Objects.requireNonNull(request, "request");
        if (stacks.size() < 36 || stacks.size() > 64
                || selectedSlot < 0 || selectedSlot >= 9) {
            throw new IllegalArgumentException("invalid player inventory snapshot");
        }
        var slots = new ArrayList<Map<String, Object>>();
        int empty = 0;
        int matchingCount = 0;
        for (int slot = 0; slot < stacks.size(); slot++) {
            var stack = Objects.requireNonNull(stacks.get(slot), "stack");
            if (stack.empty()) {
                empty++;
            } else if (request.itemFilter() == null
                    || request.itemFilter().equals(stack.itemId())) {
                slots.add(Map.of("slot", slot, "item", stack.itemId(),
                        "count", stack.count()));
                matchingCount = Math.addExact(matchingCount, stack.count());
            }
        }
        return Map.of("operation", "inspect", "selected_slot", selectedSlot,
                "slot_count", stacks.size(), "empty_slots", empty,
                "matching_count", matchingCount, "slots", List.copyOf(slots));
    }
}
