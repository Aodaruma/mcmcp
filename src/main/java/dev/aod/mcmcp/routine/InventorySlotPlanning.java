package dev.aod.mcmcp.routine;

import dev.aod.mcmcp.observation.ClientRecipeCatalog;
import dev.aod.mcmcp.runtime.ContainerSyncSignals;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** server snapshotからのスロット選択・個数計算・readback判定。clickは発行しない。 */
final class InventorySlotPlanning {
    private InventorySlotPlanning() {}

    private static final int CRAFTING_GRID_LAST_SLOT = 9;

    static MenuLayout layout(AbstractContainerMenu menu, Inventory inventory) {
        var playerSlots = new ArrayList<Integer>();
        var containerSlots = new ArrayList<Integer>();
        for (int index = 0; index < menu.slots.size(); index++) {
            if (menu.slots.get(index).container == inventory) {
                playerSlots.add(index);
            } else {
                containerSlots.add(index);
            }
        }
        return new MenuLayout(playerSlots, containerSlots);
    }

    static boolean liveMenuMatchesSnapshot(
            AbstractContainerMenu menu,
            ContainerSyncSignals.ContainerSnapshot snapshot) {
        if (menu.slots.size() != snapshot.slots().size()) return false;
        for (int index = 0; index < menu.slots.size(); index++) {
            if (!ContainerSyncSignals.StackFingerprint.fromServerPacket(
                    menu.slots.get(index).getItem()).equals(snapshot.slots().get(index))) {
                return false;
            }
        }
        return true;
    }

    static int countPlayerItem(
            List<ContainerSyncSignals.StackFingerprint> stacks,
            AbstractContainerMenu menu,
            Inventory inventory,
            String item,
            int defaultHash) {
        return countExact(stacks, layout(menu, inventory).playerSlots(), item, defaultHash);
    }

    static boolean craftingGridAndResultEmpty(
            List<ContainerSyncSignals.StackFingerprint> stacks) {
        if (stacks.size() <= CRAFTING_GRID_LAST_SLOT) {
            return false;
        }
        for (int slot = CraftingMenu.RESULT_SLOT; slot <= CRAFTING_GRID_LAST_SLOT; slot++) {
            if (!stacks.get(slot).empty()) {
                return false;
            }
        }
        return true;
    }

    static boolean exactlyOneCraftPrepared(
            List<ContainerSyncSignals.StackFingerprint> stacks,
            List<ClientRecipeCatalog.IngredientView> ingredients) {
        if (stacks.size() <= CRAFTING_GRID_LAST_SLOT || ingredients.isEmpty()) {
            return false;
        }
        long expectedUnits = ingredients.stream()
                .mapToLong(ClientRecipeCatalog.IngredientView::countPerCraft)
                .sum();
        long gridUnits = 0L;
        for (int slot = 1; slot <= CRAFTING_GRID_LAST_SLOT; slot++) {
            gridUnits += stacks.get(slot).count();
        }
        return gridUnits == expectedUnits;
    }

    static Optional<Integer> chooseCraftDestinationSlot(
            List<ContainerSyncSignals.StackFingerprint> stacks,
            List<Integer> playerSlots,
            String item,
            int defaultHash,
            int outputCount,
            int maximumStackCount) {
        if (outputCount < 1 || outputCount > maximumStackCount) {
            return Optional.empty();
        }
        Integer empty = null;
        for (int slot : playerSlots) {
            if (slot < 0 || slot >= stacks.size()) {
                throw new IllegalArgumentException("slot is outside the full snapshot");
            }
            var stack = stacks.get(slot);
            if (matchesDefaultStack(stack, item, defaultHash)
                    && stack.count() <= maximumStackCount - outputCount) {
                return Optional.of(slot);
            }
            if (stack.empty() && empty == null) {
                empty = slot;
            }
        }
        return Optional.ofNullable(empty);
    }

    static int countExact(
            List<ContainerSyncSignals.StackFingerprint> stacks,
            List<Integer> slots,
            String item,
            int defaultHash) {
        Objects.requireNonNull(stacks, "stacks");
        Objects.requireNonNull(slots, "slots");
        int count = 0;
        for (int slot : slots) {
            if (slot < 0 || slot >= stacks.size()) {
                throw new IllegalArgumentException("slot is outside the full snapshot");
            }
            var stack = stacks.get(slot);
            if (matchesDefaultStack(stack, item, defaultHash)) {
                count = Math.addExact(count, stack.count());
            }
        }
        return count;
    }

    static Optional<Integer> chooseFullStackSlot(
            List<ContainerSyncSignals.StackFingerprint> stacks,
            List<Integer> sourceSlots,
            String item,
            int defaultHash,
            int maximumCount) {
        if (maximumCount < 1) {
            return Optional.empty();
        }
        for (int slot : sourceSlots) {
            if (slot < 0 || slot >= stacks.size()) {
                throw new IllegalArgumentException("slot is outside the full snapshot");
            }
            var stack = stacks.get(slot);
            if (matchesDefaultStack(stack, item, defaultHash)
                    && stack.count() > 0 && stack.count() <= maximumCount) {
                return Optional.of(slot);
            }
        }
        return Optional.empty();
    }

    static int countTransfer(
            List<ContainerSyncSignals.StackFingerprint> stacks,
            List<Integer> slots,
            String item,
            int defaultHash,
            boolean defaultComponentsOnly) {
        if (defaultComponentsOnly) {
            return countExact(stacks, slots, item, defaultHash);
        }
        int count = 0;
        for (int slot : slots) {
            if (slot < 0 || slot >= stacks.size()) {
                throw new IllegalArgumentException("slot is outside the full snapshot");
            }
            var stack = stacks.get(slot);
            if (!stack.empty() && item.equals(stack.itemId())) {
                count = Math.addExact(count, stack.count());
            }
        }
        return count;
    }

    static Optional<Integer> chooseTransferSlot(
            List<ContainerSyncSignals.StackFingerprint> stacks,
            List<Integer> sourceSlots,
            String item,
            int defaultHash,
            int maximumCount,
            boolean defaultComponentsOnly) {
        if (defaultComponentsOnly) {
            return chooseFullStackSlot(stacks, sourceSlots, item, defaultHash, maximumCount);
        }
        if (maximumCount < 1) {
            return Optional.empty();
        }
        for (int slot : sourceSlots) {
            if (slot < 0 || slot >= stacks.size()) {
                throw new IllegalArgumentException("slot is outside the full snapshot");
            }
            var stack = stacks.get(slot);
            if (!stack.empty() && item.equals(stack.itemId())
                    && stack.count() > 0 && stack.count() <= maximumCount) {
                return Optional.of(slot);
            }
        }
        return Optional.empty();
    }

    static Map<String, Object> availableItemEvidence(
            List<ContainerSyncSignals.StackFingerprint> stacks,
            List<Integer> sourceSlots,
            int maximumItems) {
        Objects.requireNonNull(stacks, "stacks");
        Objects.requireNonNull(sourceSlots, "sourceSlots");
        if (maximumItems < 1) {
            throw new IllegalArgumentException("maximumItems must be positive");
        }
        var counts = new java.util.TreeMap<String, Integer>();
        for (int slot : sourceSlots) {
            if (slot < 0 || slot >= stacks.size()) {
                throw new IllegalArgumentException("slot is outside the full snapshot");
            }
            var stack = stacks.get(slot);
            if (!stack.empty() && stack.count() > 0) {
                counts.merge(stack.itemId(), stack.count(), Math::addExact);
            }
        }
        var items = counts.entrySet().stream()
                .limit(maximumItems)
                .map(entry -> Map.<String, Object>of(
                        "item", entry.getKey(), "count", entry.getValue()))
                .toList();
        return Map.of(
                "available_source_items", items,
                "available_source_items_truncated", counts.size() > maximumItems);
    }

    static boolean matchesDefaultStack(
            ContainerSyncSignals.StackFingerprint stack,
            String item,
            int defaultHash) {
        return !stack.empty()
                && item.equals(stack.itemId())
                && stack.itemAndComponentsHash() == defaultHash;
    }

    static TransferReadback verifyTransferReadback(
            int sourceBefore,
            int destinationBefore,
            int sourceAfter,
            int destinationAfter,
            int dispatchedFullStackCount,
            int minimumDestinationCount) {
        boolean exact = dispatchedFullStackCount > 0
                && sourceBefore - sourceAfter == dispatchedFullStackCount
                && destinationAfter - destinationBefore == dispatchedFullStackCount;
        return new TransferReadback(exact, exact && destinationAfter >= minimumDestinationCount);
    }

    static CraftReadback verifyCraftReadback(
            int inventoryBefore,
            int inventoryAfter,
            int expectedOutputCount,
            int minimumInventoryCount) {
        boolean exact = expectedOutputCount > 0
                && inventoryAfter - inventoryBefore == expectedOutputCount;
        return new CraftReadback(exact, exact && inventoryAfter >= minimumInventoryCount);
    }

    record MenuLayout(List<Integer> playerSlots, List<Integer> containerSlots) {
        MenuLayout {
            playerSlots = List.copyOf(playerSlots);
            containerSlots = List.copyOf(containerSlots);
        }
    }

    record TransferReadback(boolean exactMove, boolean goalVerified) {
    }

    record CraftReadback(boolean exactlyOneCraft, boolean goalVerified) {
    }
}
