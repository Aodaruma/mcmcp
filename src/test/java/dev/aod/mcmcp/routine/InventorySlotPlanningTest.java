package dev.aod.mcmcp.routine;

import dev.aod.mcmcp.observation.ClientRecipeCatalog;
import dev.aod.mcmcp.runtime.ContainerSyncSignals.StackFingerprint;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class InventorySlotPlanningTest {
    private static final int DEFAULT_HASH = 41;

    @Test
    void fullStackSelectionIgnoresCustomComponentsAndRespectsRemainingCap() {
        var stacks = List.of(
                stack("minecraft:stone", 32, 99),
                stack("minecraft:stone", 16, DEFAULT_HASH),
                stack("minecraft:stone", 8, DEFAULT_HASH));

        assertThat(InventorySlotPlanning.chooseFullStackSlot(
                stacks, List.of(0, 1, 2), "minecraft:stone", DEFAULT_HASH, 12))
                .contains(2);
        assertThat(InventorySlotPlanning.chooseFullStackSlot(
                stacks, List.of(0, 1), "minecraft:stone", DEFAULT_HASH, 12))
                .isEmpty();
    }

    @Test
    void packetSnapshotCountIncludesOnlyExactDefaultComponentStacks() {
        var stacks = List.of(
                stack("minecraft:stone", 12, DEFAULT_HASH),
                stack("minecraft:stone", 4, 99),
                stack("minecraft:dirt", 7, DEFAULT_HASH),
                StackFingerprint.EMPTY);

        assertThat(InventorySlotPlanning.countExact(
                stacks, List.of(0, 1, 2, 3), "minecraft:stone", DEFAULT_HASH))
                .isEqualTo(12);
    }

    @Test
    void unavailableTransferReportsBoundedSourceItemChoices() {
        var stacks = List.of(
                stack("minecraft:wheat_seeds", 32, DEFAULT_HASH),
                stack("minecraft:diamond_hoe", 1, 99),
                stack("minecraft:wheat_seeds", 16, DEFAULT_HASH),
                StackFingerprint.EMPTY);

        assertThat(InventorySlotPlanning.availableItemEvidence(
                stacks, List.of(0, 1, 2, 3), 16))
                .containsEntry("available_source_items_truncated", false)
                .containsEntry("available_source_items", List.of(
                        java.util.Map.of("item", "minecraft:diamond_hoe", "count", 1),
                        java.util.Map.of("item", "minecraft:wheat_seeds", "count", 48)));
        assertThat(InventorySlotPlanning.availableItemEvidence(
                stacks, List.of(0, 1, 2, 3), 1))
                .containsEntry("available_source_items_truncated", true);
    }

    @Test
    void completeInspectionAggregatesAll54SlotsWithoutPlayerInventoryOrComponentDetails() {
        var stacks = new java.util.ArrayList<StackFingerprint>();
        for (int index = 0; index < 54; index++) {
            stacks.add(stack("minecraft:item_%02d".formatted(index), 64, index));
        }
        stacks.add(stack("minecraft:diamond", 64, 999)); // Player slot must not leak into contents.
        var sourceSlots = java.util.stream.IntStream.range(0, 54).boxed().toList();
        var evidence = InventorySlotPlanning.availableItemEvidence(stacks, sourceSlots, 54);
        assertThat(evidence).containsEntry("available_source_items_truncated", false);
        assertThat((List<?>) evidence.get("available_source_items")).hasSize(54)
                .allSatisfy(item -> assertThat(item).isNotEqualTo(
                        java.util.Map.of("item", "minecraft:diamond", "count", 64)));
        var sameId = java.util.stream.IntStream.range(0, 54)
                .mapToObj(index -> stack("minecraft:stone", 64, index)).toList();
        assertThat(InventorySlotPlanning.availableItemEvidence(sameId, sourceSlots, 54))
                .containsEntry("available_source_items", List.of(
                        java.util.Map.of("item", "minecraft:stone", "count", 3456)));
    }

    @Test
    void transferReadbackRequiresEqualFullStackDecreaseAndIncrease() {
        var confirmed = InventorySlotPlanning.verifyTransferReadback(
                40, 3, 24, 19, 16, 18);
        assertThat(confirmed.exactMove()).isTrue();
        assertThat(confirmed.goalVerified()).isTrue();

        assertThat(InventorySlotPlanning.verifyTransferReadback(
                40, 3, 24, 18, 16, 18).exactMove()).isFalse();
        assertThat(InventorySlotPlanning.verifyTransferReadback(
                40, 3, 25, 19, 16, 18).exactMove()).isFalse();
    }

    @Test
    void replenishedSourceIsNotAConfirmedTransferEvenWhenDestinationGainedTheWholeStack() {
        var replenished = InventorySlotPlanning.verifyTransferReadback(
                64, 0, 64, 64, 64, 1);
        assertThat(replenished.exactMove()).isFalse();
        assertThat(replenished.goalVerified()).isFalse();
        var conserved = InventorySlotPlanning.verifyTransferReadback(
                64, 0, 0, 64, 64, 1);
        assertThat(conserved.exactMove()).isTrue();
        assertThat(conserved.goalVerified()).isTrue();
    }

    @Test
    void itemIdTransferCanBindAndMoveADamagedToolStack() {
        var stacks = List.of(
                stack("minecraft:diamond_hoe", 1, 99),
                stack("minecraft:diamond_hoe", 1, DEFAULT_HASH),
                stack("minecraft:wheat_seeds", 16, DEFAULT_HASH));

        assertThat(InventorySlotPlanning.countTransfer(
                stacks, List.of(0, 1, 2), "minecraft:diamond_hoe", 0, false))
                .isEqualTo(2);
        assertThat(InventorySlotPlanning.chooseTransferSlot(
                stacks, List.of(0, 1, 2), "minecraft:diamond_hoe", 0, 1, false))
                .contains(0);
        assertThat(InventorySlotPlanning.chooseTransferSlot(
                stacks, List.of(0, 1, 2), "minecraft:diamond_hoe", DEFAULT_HASH, 1, true))
                .contains(1);
    }

    @Test
    void craftReadbackRejectsNoOpAndMultipleCraftDelta() {
        assertThat(InventorySlotPlanning.verifyCraftReadback(
                2, 6, 4, 6))
                .isEqualTo(new InventorySlotPlanning.CraftReadback(true, true));
        assertThat(InventorySlotPlanning.verifyCraftReadback(
                2, 2, 4, 6).exactlyOneCraft()).isFalse();
        assertThat(InventorySlotPlanning.verifyCraftReadback(
                2, 10, 4, 6).exactlyOneCraft()).isFalse();
    }

    @Test
    void craftPreparationAndReadbackRequireConservedEmptyGridAndOneIngredientSet() {
        var slots = emptySlots(46);
        assertThat(InventorySlotPlanning.craftingGridAndResultEmpty(slots)).isTrue();

        slots.set(0, stack("minecraft:stick", 4, DEFAULT_HASH));
        slots.set(1, stack("minecraft:oak_planks", 1, DEFAULT_HASH));
        slots.set(2, stack("minecraft:oak_planks", 1, DEFAULT_HASH));
        assertThat(InventorySlotPlanning.exactlyOneCraftPrepared(
                slots, recipe("crafting_table", true).ingredients())).isTrue();

        slots.set(1, stack("minecraft:oak_planks", 2, DEFAULT_HASH));
        assertThat(InventorySlotPlanning.exactlyOneCraftPrepared(
                slots, recipe("crafting_table", true).ingredients())).isFalse();
        assertThat(InventorySlotPlanning.craftingGridAndResultEmpty(slots)).isFalse();
    }

    @Test
    void craftOutputUsesOnlyCompatibleCapacityOrAnEmptyPlayerSlot() {
        var slots = emptySlots(46);
        slots.set(10, stack("minecraft:stick", 60, DEFAULT_HASH));

        assertThat(InventorySlotPlanning.chooseCraftDestinationSlot(
                slots, List.of(10, 11), "minecraft:stick", DEFAULT_HASH, 4, 64))
                .contains(10);
        assertThat(InventorySlotPlanning.chooseCraftDestinationSlot(
                slots, List.of(10, 11), "minecraft:stick", DEFAULT_HASH, 5, 64))
                .contains(11);

    }

    private static StackFingerprint stack(String item, int count, int hash) {
        return new StackFingerprint(item, count, hash);
    }

    private static ArrayList<StackFingerprint> emptySlots(int size) {
        return new ArrayList<>(java.util.Collections.nCopies(size, StackFingerprint.EMPTY));
    }

    private static ClientRecipeCatalog.RecipeView recipe(
            String requiredScreen, boolean supported) {
        return new ClientRecipeCatalog.RecipeView(
                "recipe-ref", "fingerprint", "shaped", requiredScreen, supported,
                supported ? null : "unsupported",
                new ClientRecipeCatalog.Result(true, List.of(
                        new ClientRecipeCatalog.ResultAlternative(
                                "minecraft:stick", 4, "stack-fingerprint"))),
                List.of(
                        new ClientRecipeCatalog.IngredientView(
                                0, 1, List.of("minecraft:oak_planks")),
                        new ClientRecipeCatalog.IngredientView(
                                1, 1, List.of("minecraft:oak_planks"))),
                new ClientRecipeCatalog.Shape(1, 2));
    }
}
