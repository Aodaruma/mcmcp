package dev.aod.mcmcp.routine;

import dev.aod.mcmcp.runtime.ContainerSyncSignals.StackFingerprint;
import dev.aod.mcmcp.runtime.ContainerSyncSignals.ContainerSnapshot;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryTransferBatchTest {
    private static final int DEFAULT_HASH = 41;

    @Test
    void batchMovesAtMostFourteenInitialWholeStacksAfterFreshCompleteServerDeltas() {
        var slots = emptySlots(32);
        for (int i = 0; i < 16; i++) slots.set(i, stack("minecraft:stone", 64, DEFAULT_HASH));
        var sources = java.util.stream.IntStream.range(0, 16).boxed().toList();
        var destinations = java.util.stream.IntStream.range(16, 32).boxed().toList();
        var batch = new InventoryTransferBatch(slots, sources, destinations,
                "minecraft:stone", DEFAULT_HASH, true, 14, 896, 1_024);
        var state = transferState(false, 1_024, 896, 14);
        state.beginTransferBatch(batch, 1_024, 0);
        long revision = 1;
        for (int i = 0; i < 14; i++) {
            long tick = i * 2L + 1;
            assertThat(batch.next().slot()).isEqualTo(i);
            assertThat(batch.beginClick(transferSnapshot(slots, revision), tick)).isTrue();
            assertThat(batch.beginClick(transferSnapshot(slots, revision), tick + 1)).isFalse();
            var partial = new ArrayList<>(slots);
            partial.set(i, StackFingerprint.EMPTY);
            assertThat(batch.confirm(transferSnapshot(partial, ++revision))).isFalse();
            slots.set(i, StackFingerprint.EMPTY);
            slots.set(16 + i, stack("minecraft:stone", 64, DEFAULT_HASH));
            // Matching slots without a newer server revision are not an acknowledgement.
            assertThat(batch.confirm(transferSnapshot(slots, revision - 1))).isFalse();
            assertThat(batch.confirm(transferSnapshot(slots, ++revision))).isTrue();
            assertThat(batch.beginClick(transferSnapshot(slots, revision), tick)).isFalse();
            state.updateTransferPrefix();
        }
        assertThat(batch.exhausted()).isTrue();
        assertThat(batch.beginClick(transferSnapshot(slots, revision), 99)).isFalse();
        assertThat(batch.reconcileReadback(transferSnapshot(slots, ++revision))).isTrue();
        assertThat(slots.get(14).count()).isEqualTo(64);
        assertThat(slots.get(15).count()).isEqualTo(64);
        assertThat(state.basis()).containsEntry("source_before", 1_024)
                .containsEntry("destination_before", 0)
                .containsEntry("confirmed_transfer_count", 896)
                .containsEntry("confirmed_stack_moves", 14)
                .containsEntry("confirmed_source_count", 128)
                .containsEntry("confirmed_destination_count", 896)
                .containsEntry("transfer_in_flight", false)
                .containsEntry("transfer_readback_observed", false)
                .doesNotContainKeys("source_after", "destination_after", "pending_stack_count");
    }

    @Test
    void batchPlanRespectsCountPolicyAndAbsoluteGoalWithoutAddingNewSources() {
        var slots = new ArrayList<>(List.of(
                stack("minecraft:stone", 64, DEFAULT_HASH),
                stack("minecraft:stone", 32, DEFAULT_HASH),
                stack("minecraft:stone", 16, DEFAULT_HASH),
                stack("minecraft:stone", 16, 99), StackFingerprint.EMPTY,
                StackFingerprint.EMPTY, StackFingerprint.EMPTY));
        var batch = new InventoryTransferBatch(slots,
                List.of(0, 1, 2, 3, 4), List.of(5, 6),
                "minecraft:stone", DEFAULT_HASH, true, 3, 80, 100);
        assertThat(batch.beginClick(transferSnapshot(slots, 1), 1)).isTrue();
        slots.set(0, StackFingerprint.EMPTY);
        slots.set(5, stack("minecraft:stone", 64, DEFAULT_HASH));
        assertThat(batch.confirm(transferSnapshot(slots, 2))).isTrue();
        assertThat(batch.next().slot()).isEqualTo(2); // 32 does not fit the remaining whole-stack cap.
        var replenished = new ArrayList<>(slots);
        replenished.set(0, stack("minecraft:stone", 16, DEFAULT_HASH));
        assertThat(batch.beginClick(transferSnapshot(replenished, 3), 2)).isFalse();
        assertThat(batch.beginClick(transferSnapshot(slots, 4), 2)).isTrue();
        slots.set(2, StackFingerprint.EMPTY);
        slots.set(6, stack("minecraft:stone", 16, DEFAULT_HASH));
        assertThat(batch.confirm(transferSnapshot(slots, 5))).isTrue();
        assertThat(batch.exhausted()).isTrue();

        var goalOne = new InventoryTransferBatch(
                List.of(stack("minecraft:stone", 64, 99), stack("minecraft:stone", 16, DEFAULT_HASH),
                        StackFingerprint.EMPTY), List.of(0, 1), List.of(2),
                "minecraft:stone", DEFAULT_HASH, false, 14, 896, 1);
        var initial = List.of(stack("minecraft:stone", 64, 99),
                stack("minecraft:stone", 16, DEFAULT_HASH), StackFingerprint.EMPTY);
        assertThat(goalOne.beginClick(transferSnapshot(initial, 1), 1)).isTrue();
        assertThat(goalOne.confirm(transferSnapshot(List.of(StackFingerprint.EMPTY,
                initial.get(1), initial.get(0)), 2))).isTrue();
        assertThat(goalOne.exhausted()).isTrue(); // minimum is an absolute goal, not a batch size.
    }

    @Test
    void doubleChestPlanUsesMatchingWholeStacksAcrossAllFiftyFourSourceSlots() {
        var slots = emptySlots(90);
        slots.set(0, stack("minecraft:dripstone_block", 47, DEFAULT_HASH));
        slots.set(53, stack("minecraft:dripstone_block", 27, DEFAULT_HASH));
        var sourceSlots = java.util.stream.IntStream.range(0, 54).boxed().toList();
        var destinationSlots = java.util.stream.IntStream.range(54, 90).boxed().toList();
        var batch = new InventoryTransferBatch(
                slots, sourceSlots, destinationSlots,
                "minecraft:dripstone_block", DEFAULT_HASH, true, 8, 74, 74);
        var state = transferState(false, 74, 74, 8);
        state.beginTransferBatch(batch, 74, 0);

        assertThat(batch.next().slot()).isZero();
        assertThat(batch.beginClick(transferSnapshot(slots, 1), 1)).isTrue();
        slots.set(0, StackFingerprint.EMPTY);
        slots.set(54, stack("minecraft:dripstone_block", 47, DEFAULT_HASH));
        assertThat(batch.confirm(transferSnapshot(slots, 2))).isTrue();
        state.updateTransferPrefix();

        assertThat(batch.next().slot()).isEqualTo(53);
        assertThat(batch.beginClick(transferSnapshot(slots, 2), 2)).isTrue();
        slots.set(53, StackFingerprint.EMPTY);
        slots.set(54, stack("minecraft:dripstone_block", 64, DEFAULT_HASH));
        slots.set(55, stack("minecraft:dripstone_block", 10, DEFAULT_HASH));
        assertThat(batch.confirm(transferSnapshot(slots, 3))).isTrue();
        state.updateTransferPrefix();

        assertThat(batch.exhausted()).isTrue();
        assertThat(batch.reconcileReadback(transferSnapshot(slots, 4))).isTrue();
        state.recordTransferReadback(0, 74);
        assertThat(state.basis()).containsEntry("source_before", 74)
                .containsEntry("destination_before", 0)
                .containsEntry("confirmed_transfer_count", 74)
                .containsEntry("confirmed_stack_moves", 2)
                .containsEntry("confirmed_source_count", 0)
                .containsEntry("confirmed_destination_count", 74)
                .containsEntry("transfer_in_flight", false);
    }

    @Test
    void wholeStackCeilingCanLeaveAConfirmedPrefixShortOfTheAbsoluteGoal() {
        var slots = emptySlots(90);
        slots.set(0, stack("minecraft:dripstone_block", 47, DEFAULT_HASH));
        slots.set(53, stack("minecraft:dripstone_block", 64, DEFAULT_HASH));
        var batch = new InventoryTransferBatch(
                slots,
                java.util.stream.IntStream.range(0, 54).boxed().toList(),
                java.util.stream.IntStream.range(54, 90).boxed().toList(),
                "minecraft:dripstone_block", DEFAULT_HASH, true, 8, 74, 74);
        var state = transferState(false, 74, 74, 8);
        state.beginTransferBatch(batch, 111, 0);

        assertThat(batch.next().slot()).isZero();
        assertThat(batch.beginClick(transferSnapshot(slots, 1), 1)).isTrue();
        slots.set(0, StackFingerprint.EMPTY);
        slots.set(54, stack("minecraft:dripstone_block", 47, DEFAULT_HASH));
        assertThat(batch.confirm(transferSnapshot(slots, 2))).isTrue();
        state.updateTransferPrefix();

        assertThat(batch.exhausted()).isTrue();
        assertThat(batch.reconcileReadback(transferSnapshot(slots, 3))).isTrue();
        state.recordTransferReadback(64, 47);
        assertThat(state.basis()).containsEntry("confirmed_transfer_count", 47)
                .containsEntry("confirmed_stack_moves", 1)
                .containsEntry("confirmed_source_count", 64)
                .containsEntry("confirmed_destination_count", 47)
                .containsEntry("transfer_in_flight", false);
    }

    @Test
    void prefixAndTheOneUnconfirmedClickRemainSeparateWithoutBlindRetry() {
        var slots = new ArrayList<>(List.of(stack("minecraft:stone", 64, DEFAULT_HASH),
                stack("minecraft:stone", 64, DEFAULT_HASH), StackFingerprint.EMPTY,
                StackFingerprint.EMPTY));
        var batch = new InventoryTransferBatch(slots,
                List.of(0, 1), List.of(2, 3), "minecraft:stone", DEFAULT_HASH, true, 2, 128, 128);
        var state = transferState(false, 128, 128, 2);
        state.beginTransferBatch(batch, 128, 0);
        assertThat(batch.beginClick(transferSnapshot(slots, 1), 1)).isTrue();
        slots.set(0, StackFingerprint.EMPTY);
        slots.set(2, stack("minecraft:stone", 64, DEFAULT_HASH));
        assertThat(batch.confirm(transferSnapshot(slots, 2))).isTrue();
        state.updateTransferPrefix();
        assertThat(batch.beginClick(transferSnapshot(slots, 2), 3)).isTrue();
        assertThat(batch.ackTimedOut(62)).isFalse();
        assertThat(batch.ackTimedOut(63)).isTrue();
        assertThat(batch.beginClick(transferSnapshot(slots, 3), 64)).isFalse();
        assertThat(batch.reconcileReadback(transferSnapshot(slots, 4))).isFalse();
        state.recordTransferReadback(64, 64);
        assertThat(state.basis()).containsEntry("source_before", 128)
                .containsEntry("destination_before", 0)
                .containsEntry("confirmed_transfer_count", 64)
                .containsEntry("confirmed_stack_moves", 1)
                .containsEntry("confirmed_source_count", 64)
                .containsEntry("confirmed_destination_count", 64)
                .containsEntry("transfer_in_flight", true)
                .containsEntry("pending_source_before", 64)
                .containsEntry("pending_destination_before", 64)
                .containsEntry("pending_stack_count", 64)
                .containsEntry("transfer_readback_observed", true)
                .containsEntry("source_after", 64).containsEntry("destination_after", 64);
    }

    @Test
    void finalFullReadbackCanResolveOneUnacknowledgedClickButRefillIsNeverSuccess() {
        var initial = List.of(stack("minecraft:torch", 64, DEFAULT_HASH), StackFingerprint.EMPTY);
        var batch = new InventoryTransferBatch(initial,
                List.of(0), List.of(1), "minecraft:torch", DEFAULT_HASH, true, 1, 64, 1);
        assertThat(batch.beginClick(transferSnapshot(initial, 1), 1)).isTrue();
        var refilled = List.of(initial.get(0), initial.get(0));
        assertThat(batch.reconcileReadback(transferSnapshot(refilled, 2))).isFalse();
        assertThat(batch.inFlight()).isTrue();
        var exact = List.of(StackFingerprint.EMPTY, initial.get(0));
        assertThat(batch.reconcileReadback(transferSnapshot(exact, 3))).isTrue();
        assertThat(batch.inFlight()).isFalse();
        assertThat(batch.exhausted()).isTrue();
        // A later inventory change must still invalidate final batch completion.
        assertThat(batch.reconcileReadback(transferSnapshot(refilled, 4))).isFalse();
    }

    private static StackFingerprint stack(String item, int count, int hash) {
        return new StackFingerprint(item, count, hash);
    }

    private static ContainerSnapshot transferSnapshot(List<StackFingerprint> slots, long revision) {
        return new ContainerSnapshot(new java.util.UUID(0, 1), 1, "minecraft:generic_9x3", 0,
                slots, StackFingerprint.EMPTY, revision, revision);
    }

    private static MinecraftPhaseFiveInventoryPort.AttemptState transferState(
            boolean store, int minimum, int maximumCount, int maximumStacks) {
        var target = new BlockTarget("minecraft:overworld", 0, 64, 0);
        return new MinecraftPhaseFiveInventoryPort.AttemptState(inventoryRequest(target, null),
                new InventoryParameters.TransferParameters(
                        store, "minecraft:stone", "default_components_only", minimum,
                        maximumCount, maximumStacks, true, 8.0D, target,
                        new BlockStateFingerprint("minecraft:chest", Map.of("type", "left"))));
    }

    private static ArrayList<StackFingerprint> emptySlots(int size) {
        return new ArrayList<>(java.util.Collections.nCopies(size, StackFingerprint.EMPTY));
    }

    private static PhaseFiveRequest inventoryRequest(
            BlockTarget target, Map<String, Object> aimPoint) {
        return new PhaseFiveRequest(
                "transfer_items",
                aimPoint == null ? Map.of() : Map.of("aim_point", aimPoint),
                new PhaseFiveBounds(
                        target.dimension(), target, target, 0, 20, false),
                0,
                "items");
    }
}
