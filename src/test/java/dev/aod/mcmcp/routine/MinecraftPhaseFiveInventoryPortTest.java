package dev.aod.mcmcp.routine;

import dev.aod.mcmcp.observation.ClientRecipeCatalog;
import dev.aod.mcmcp.runtime.ContainerSyncSignals.StackFingerprint;
import dev.aod.mcmcp.runtime.ContainerSyncSignals.ContainerSnapshot;
import dev.aod.mcmcp.runtime.ScreenOwnershipSignals;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MinecraftPhaseFiveInventoryPortTest {
    private static final int DEFAULT_HASH = 41;

    @Test
    void rendererWaitGatesOnlyUnsentInitialOpenAndNeverAckReadbackOrCleanup() throws Exception {
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        var current = new java.util.concurrent.atomic.AtomicReference<>(
                MinecraftPhaseFiveInventoryPort.InitialOpenWitness.RENDERER_EVIDENCE_MISSING);
        java.util.function.Supplier<MinecraftPhaseFiveInventoryPort.InitialOpenWitness> witness = () -> {
            checks.incrementAndGet();
            return current.get();
        };
        for (int tick = 0; tick < 4; tick++) {
            assertThat(MinecraftPhaseFiveInventoryPort.initialOpenWitness(
                    MinecraftPhaseFiveInventoryPort.Stage.AIMING_INITIAL, witness))
                    .isEqualTo(MinecraftPhaseFiveInventoryPort.InitialOpenWitness.RENDERER_EVIDENCE_MISSING);
        }
        current.set(MinecraftPhaseFiveInventoryPort.InitialOpenWitness.READY);
        assertThat(MinecraftPhaseFiveInventoryPort.initialOpenWitness(
                MinecraftPhaseFiveInventoryPort.Stage.AIMING_INITIAL, witness))
                .isEqualTo(MinecraftPhaseFiveInventoryPort.InitialOpenWitness.READY);
        current.set(MinecraftPhaseFiveInventoryPort.InitialOpenWitness.DELIVERY_EXPIRED);
        for (var stage : MinecraftPhaseFiveInventoryPort.Stage.values()) {
            if (stage == MinecraftPhaseFiveInventoryPort.Stage.AIMING_INITIAL) continue;
            assertThat(MinecraftPhaseFiveInventoryPort.initialOpenWitness(stage, witness))
                    .isEqualTo(MinecraftPhaseFiveInventoryPort.InitialOpenWitness.READY);
        }
        assertThat(checks).hasValue(5);
        assertThat(MinecraftPhaseFiveInventoryPort.initialOpenWitness(
                MinecraftPhaseFiveInventoryPort.Stage.AIMING_INITIAL, witness))
                .isEqualTo(MinecraftPhaseFiveInventoryPort.InitialOpenWitness.DELIVERY_EXPIRED);

        var node = classNode();
        assertThat(invocations(node, "dispatchExpectedOpen")).containsSubsequence(
                "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort#ongoingFailure",
                "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort#initialOpenWitness",
                "dev/aod/mcmcp/runtime/ScreenOwnershipSignals#beginExpectedOpen",
                "dev/aod/mcmcp/runtime/ClientPredictionSignals#begin",
                "net/minecraft/client/multiplayer/MultiPlayerGameMode#useItemOn");
        assertThat(invocations(node, "maintain")).containsSubsequence(
                "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort#ongoingFailure",
                "dev/aod/mcmcp/routine/PhaseFiveAttempt#hardDeadlineClientTick",
                "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort#maintainAim");
    }

    @Test
    void expiredReleaseRetriesOnlyWhenObservableCleanupEvidenceChanges() {
        var waiting = List.of("same-menu", ScreenOwnershipSignals.Phase.FAILED, 42L,
                ScreenOwnershipSignals.CausalBarrierStatus.WAITING_ACK);
        assertThat(MinecraftPhaseFiveInventoryPort.releaseBoundaryChanged(null, waiting)).isTrue();
        assertThat(MinecraftPhaseFiveInventoryPort.releaseBoundaryChanged(waiting, List.copyOf(waiting)))
                .isFalse();
        assertThat(MinecraftPhaseFiveInventoryPort.releaseBoundaryChanged(waiting,
                List.of("same-menu", ScreenOwnershipSignals.Phase.FAILED, 43L,
                        ScreenOwnershipSignals.CausalBarrierStatus.WAITING_ACK))).isTrue();
        assertThat(MinecraftPhaseFiveInventoryPort.releaseBoundaryChanged(waiting,
                List.of("same-menu", ScreenOwnershipSignals.Phase.FAILED, 42L,
                        ScreenOwnershipSignals.CausalBarrierStatus.ACKNOWLEDGED))).isTrue();
        assertThat(MinecraftPhaseFiveInventoryPort.releaseBoundaryChanged(waiting,
                List.of("closed", ScreenOwnershipSignals.Phase.IDLE, 42L,
                        ScreenOwnershipSignals.CausalBarrierStatus.WAITING_ACK))).isTrue();
    }

    @Test
    void retiringFailedScreenPreservesOnlyExactOwnerServerEmptyCursorProof() {
        var owner = java.util.UUID.randomUUID();
        assertThat(MinecraftPhaseFiveInventoryPort.releaseCursorProofMatches(owner, owner, true, true))
                .isTrue();
        assertThat(MinecraftPhaseFiveInventoryPort.releaseCursorProofMatches(owner, owner, false, true))
                .isFalse();
        assertThat(MinecraftPhaseFiveInventoryPort.releaseCursorProofMatches(owner, owner, true, false))
                .isFalse();
        assertThat(MinecraftPhaseFiveInventoryPort.releaseCursorProofMatches(
                owner, java.util.UUID.randomUUID(), true, true)).isFalse();
        assertThat(MinecraftPhaseFiveInventoryPort.releaseCursorProofMatches(owner, null, true, true))
                .isFalse();
        assertThat(MinecraftPhaseFiveInventoryPort.releaseCursorProofMatches(null, null, true, true))
                .isFalse();
    }

    @Test
    void fullStackSelectionIgnoresCustomComponentsAndRespectsRemainingCap() {
        var stacks = List.of(
                stack("minecraft:stone", 32, 99),
                stack("minecraft:stone", 16, DEFAULT_HASH),
                stack("minecraft:stone", 8, DEFAULT_HASH));

        assertThat(MinecraftPhaseFiveInventoryPort.chooseFullStackSlot(
                stacks, List.of(0, 1, 2), "minecraft:stone", DEFAULT_HASH, 12))
                .contains(2);
        assertThat(MinecraftPhaseFiveInventoryPort.chooseFullStackSlot(
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

        assertThat(MinecraftPhaseFiveInventoryPort.countExact(
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

        assertThat(MinecraftPhaseFiveInventoryPort.availableItemEvidence(
                stacks, List.of(0, 1, 2, 3), 16))
                .containsEntry("available_source_items_truncated", false)
                .containsEntry("available_source_items", List.of(
                        java.util.Map.of("item", "minecraft:diamond_hoe", "count", 1),
                        java.util.Map.of("item", "minecraft:wheat_seeds", "count", 48)));
        assertThat(MinecraftPhaseFiveInventoryPort.availableItemEvidence(
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
        var evidence = MinecraftPhaseFiveInventoryPort.availableItemEvidence(stacks, sourceSlots, 54);
        assertThat(evidence).containsEntry("available_source_items_truncated", false);
        assertThat((List<?>) evidence.get("available_source_items")).hasSize(54)
                .allSatisfy(item -> assertThat(item).isNotEqualTo(
                        java.util.Map.of("item", "minecraft:diamond", "count", 64)));
        var sameId = java.util.stream.IntStream.range(0, 54)
                .mapToObj(index -> stack("minecraft:stone", 64, index)).toList();
        assertThat(MinecraftPhaseFiveInventoryPort.availableItemEvidence(sameId, sourceSlots, 54))
                .containsEntry("available_source_items", List.of(
                        java.util.Map.of("item", "minecraft:stone", "count", 3456)));
    }

    @Test
    void transferReadbackRequiresEqualFullStackDecreaseAndIncrease() {
        var confirmed = MinecraftPhaseFiveInventoryPort.verifyTransferReadback(
                40, 3, 24, 19, 16, 18);
        assertThat(confirmed.exactMove()).isTrue();
        assertThat(confirmed.goalVerified()).isTrue();

        assertThat(MinecraftPhaseFiveInventoryPort.verifyTransferReadback(
                40, 3, 24, 18, 16, 18).exactMove()).isFalse();
        assertThat(MinecraftPhaseFiveInventoryPort.verifyTransferReadback(
                40, 3, 25, 19, 16, 18).exactMove()).isFalse();
    }

    @Test
    void batchMovesAtMostFourteenInitialWholeStacksAfterFreshCompleteServerDeltas() {
        var slots = emptySlots(32);
        for (int i = 0; i < 16; i++) slots.set(i, stack("minecraft:stone", 64, DEFAULT_HASH));
        var sources = java.util.stream.IntStream.range(0, 16).boxed().toList();
        var destinations = java.util.stream.IntStream.range(16, 32).boxed().toList();
        var batch = new MinecraftPhaseFiveInventoryPort.TransferBatch(slots, sources, destinations,
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
    void fourteenStackTakeWithOneEmptyHotbarKeepsAStableHandForTheNextContainer() {
        var hotbar = new ArrayList<ItemStack>();
        for (int slot = 0; slot < 8; slot++) {
            hotbar.add(testItemStack(Blocks.COBBLESTONE.asItem(), 64));
        }
        hotbar.add(ItemStack.EMPTY);

        var opening = MinecraftPhaseFiveInventoryPort.chooseOpenHand(
                hotbar, ItemStack.EMPTY, 3).orElseThrow();
        assertThat(opening.hand()).isEqualTo(InteractionHand.OFF_HAND);
        assertThat(opening.selectedSlot()).isEqualTo(3);

        var menuSlots = emptySlots(28);
        for (int slot = 0; slot < 14; slot++) {
            menuSlots.set(slot, stack("minecraft:cobblestone", 64, DEFAULT_HASH));
        }
        var batch = new MinecraftPhaseFiveInventoryPort.TransferBatch(
                menuSlots,
                java.util.stream.IntStream.range(0, 14).boxed().toList(),
                java.util.stream.IntStream.range(14, 28).boxed().toList(),
                "minecraft:cobblestone", DEFAULT_HASH, true, 14, 896, 896);
        long revision = 1L;
        for (int slot = 0; slot < 14; slot++) {
            assertThat(batch.beginClick(transferSnapshot(menuSlots, revision), 1L + slot * 2L))
                    .isTrue();
            menuSlots.set(slot, StackFingerprint.EMPTY);
            menuSlots.set(14 + slot, stack("minecraft:cobblestone", 64, DEFAULT_HASH));
            assertThat(batch.confirm(transferSnapshot(menuSlots, ++revision))).isTrue();
        }
        assertThat(batch.exhausted()).isTrue();

        // Vanilla may fill the only empty hotbar slot during the maximum inbound batch.
        hotbar.set(8, testItemStack(Blocks.COBBLESTONE.asItem(), 64));
        var nextContainer = MinecraftPhaseFiveInventoryPort.chooseOpenHand(
                hotbar, ItemStack.EMPTY, 3).orElseThrow();
        assertThat(nextContainer.hand()).isEqualTo(InteractionHand.OFF_HAND);
        assertThat(nextContainer.selectedSlot()).isEqualTo(3);
    }

    @Test
    void batchPlanRespectsCountPolicyAndAbsoluteGoalWithoutAddingNewSources() {
        var slots = new ArrayList<>(List.of(
                stack("minecraft:stone", 64, DEFAULT_HASH),
                stack("minecraft:stone", 32, DEFAULT_HASH),
                stack("minecraft:stone", 16, DEFAULT_HASH),
                stack("minecraft:stone", 16, 99), StackFingerprint.EMPTY,
                StackFingerprint.EMPTY, StackFingerprint.EMPTY));
        var batch = new MinecraftPhaseFiveInventoryPort.TransferBatch(slots,
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

        var goalOne = new MinecraftPhaseFiveInventoryPort.TransferBatch(
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
    void prefixAndTheOneUnconfirmedClickRemainSeparateWithoutBlindRetry() {
        var slots = new ArrayList<>(List.of(stack("minecraft:stone", 64, DEFAULT_HASH),
                stack("minecraft:stone", 64, DEFAULT_HASH), StackFingerprint.EMPTY,
                StackFingerprint.EMPTY));
        var batch = new MinecraftPhaseFiveInventoryPort.TransferBatch(slots,
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
        var batch = new MinecraftPhaseFiveInventoryPort.TransferBatch(initial,
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

    @Test
    void transferParametersKeepDirectionalGoalAndSeparateFiniteBatchLimits() {
        assertThat(transferState(true, 3_456, 896, 14)).isNotNull();
        assertThat(transferState(false, 2_304, 64, 1)).isNotNull();
        assertThatThrownBy(() -> transferState(true, 3_457, 64, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transferState(false, 2_305, 64, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transferState(true, 1, 897, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transferState(true, 1, 64, 15))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void replenishedSourceIsNotAConfirmedTransferEvenWhenDestinationGainedTheWholeStack() {
        var replenished = MinecraftPhaseFiveInventoryPort.verifyTransferReadback(
                64, 0, 64, 64, 64, 1);
        assertThat(replenished.exactMove()).isFalse();
        assertThat(replenished.goalVerified()).isFalse();
        var conserved = MinecraftPhaseFiveInventoryPort.verifyTransferReadback(
                64, 0, 0, 64, 64, 1);
        assertThat(conserved.exactMove()).isTrue();
        assertThat(conserved.goalVerified()).isTrue();
    }

    @Test
    void transferEvidenceDistinguishesUnreadZeroFromObservedZeroAndResetsBetweenClicks() {
        var target = new BlockTarget("minecraft:overworld", 147, 66, -300);
        var parameters = new MinecraftPhaseFiveInventoryPort.TransferParameters(
                true, "minecraft:torch", "default_components_only", 1, 128, 2,
                true, 8.0D, target, new BlockStateFingerprint("minecraft:barrel", Map.of()));
        var state = new MinecraftPhaseFiveInventoryPort.AttemptState(
                inventoryRequest(target, null), parameters);

        assertThat(state.basis()).containsEntry("source_before", 0)
                .containsEntry("destination_before", 0)
                .containsEntry("transfer_readback_observed", false)
                .doesNotContainKeys("source_after", "destination_after");
        state.prepareTransfer(64, 0, 64);
        assertThat(state.basis()).containsEntry("source_before", 64)
                .containsEntry("destination_before", 0)
                .containsEntry("transfer_readback_observed", false)
                .doesNotContainKeys("source_after", "destination_after");

        state.recordTransferReadback(0, 64);
        assertThat(state.basis()).containsEntry("transfer_readback_observed", true)
                .containsEntry("source_after", 0).containsEntry("destination_after", 64);
        state.prepareTransfer(64, 64, 64);
        assertThat(state.basis()).containsEntry("source_before", 64)
                .containsEntry("destination_before", 64)
                .containsEntry("transfer_readback_observed", false)
                .doesNotContainKeys("source_after", "destination_after");
        state.recordTransferReadback(64, 128);
        assertThat(state.basis()).containsEntry("transfer_readback_observed", true)
                .containsEntry("source_after", 64).containsEntry("destination_after", 128);
    }

    @Test
    void refilledTorchHandDoesNotRejectOwnedReadbackButStillRejectsTheNextBlockUse()
            throws Exception {
        // An empty opening hand may be filled by normal full-content sync or an auto-refill mod.
        // The isolated NeoForge JUnit loader does not bind Vanilla item defaults.
        var holder = Items.TORCH.builtInRegistryHolder();
        if (!holder.areComponentsBound()) holder.bindComponents(DataComponentMap.EMPTY);
        boolean safeRefilledHand = MinecraftKnownBrewingPort.safeNormalUseStack(
                new ItemStack(Items.TORCH, 64));
        assertThat(safeRefilledHand).isFalse();
        for (var stage : List.of(MinecraftPhaseFiveInventoryPort.Stage.OPENING_INITIAL,
                MinecraftPhaseFiveInventoryPort.Stage.OPENING_READBACK)) {
            assertThat(MinecraftPhaseFiveInventoryPort.safeOpenHandFailure(stage, () -> {
                throw new AssertionError("a completed block use must not inspect the changed hand");
            }))
                    .isNull();
        }
        for (var stage : List.of(MinecraftPhaseFiveInventoryPort.Stage.AIMING_INITIAL,
                MinecraftPhaseFiveInventoryPort.Stage.AIMING_READBACK)) {
            assertThat(MinecraftPhaseFiveInventoryPort.safeOpenHandFailure(stage, () -> safeRefilledHand)
                    .code()).isEqualTo("INVENTORY_SAFE_OPEN_HAND_CHANGED");
            assertThat(MinecraftPhaseFiveInventoryPort.safeOpenHandFailure(stage, () -> true)).isNull();
        }

        var node = classNode();
        assertThat(invocations(node, "ongoingFailure")).containsSubsequence(
                "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort$ViewLease#undisturbed",
                "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort#screenContextMatches",
                "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort#safeOpenHandFailure");
        assertThat(invocations(node, "dispatchExpectedOpen")).containsSubsequence(
                "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort$OpenHandPlan#ready",
                "net/minecraft/client/multiplayer/MultiPlayerGameMode#useItemOn");
        assertThat(invocations(node, "acceptTransferSnapshot")).containsSubsequence(
                "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort$AttemptState#recordTransferReadback",
                "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort#verifyTransferReadback");
        assertThat(invocations(node, "dispatchTransferClick")).containsSubsequence(
                "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort#prepareOwnedDispatch",
                "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort#freshEmptyServerCursorProof",
                "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort$TransferBatch#beginClick",
                "dev/aod/mcmcp/routine/KnownMenuTransfers#dispatchServerConfirmedQuickMove");
    }

    @Test
    void itemIdTransferCanBindAndMoveADamagedToolStack() {
        var stacks = List.of(
                stack("minecraft:diamond_hoe", 1, 99),
                stack("minecraft:diamond_hoe", 1, DEFAULT_HASH),
                stack("minecraft:wheat_seeds", 16, DEFAULT_HASH));

        assertThat(MinecraftPhaseFiveInventoryPort.countTransfer(
                stacks, List.of(0, 1, 2), "minecraft:diamond_hoe", 0, false))
                .isEqualTo(2);
        assertThat(MinecraftPhaseFiveInventoryPort.chooseTransferSlot(
                stacks, List.of(0, 1, 2), "minecraft:diamond_hoe", 0, 1, false))
                .contains(0);
        assertThat(MinecraftPhaseFiveInventoryPort.chooseTransferSlot(
                stacks, List.of(0, 1, 2), "minecraft:diamond_hoe", DEFAULT_HASH, 1, true))
                .contains(1);
    }

    @Test
    void craftReadbackRejectsNoOpAndMultipleCraftDelta() {
        assertThat(MinecraftPhaseFiveInventoryPort.verifyCraftReadback(
                2, 6, 4, 6))
                .isEqualTo(new MinecraftPhaseFiveInventoryPort.CraftReadback(true, true));
        assertThat(MinecraftPhaseFiveInventoryPort.verifyCraftReadback(
                2, 2, 4, 6).exactlyOneCraft()).isFalse();
        assertThat(MinecraftPhaseFiveInventoryPort.verifyCraftReadback(
                2, 10, 4, 6).exactlyOneCraft()).isFalse();
    }

    @Test
    void craftingTableAcceptsSmallerGridRecipesButRejectsIncompatibleDisplays() {
        assertThat(MinecraftPhaseFiveInventoryPort.craftingTableRecipeSupported(
                recipe("crafting_table", true))).isTrue();
        assertThat(MinecraftPhaseFiveInventoryPort.craftingTableRecipeSupported(
                recipe("inventory_2x2", true))).isTrue();
        assertThat(MinecraftPhaseFiveInventoryPort.craftingTableRecipeSupported(
                recipe("unsupported", true))).isFalse();
        assertThat(MinecraftPhaseFiveInventoryPort.craftingTableRecipeSupported(
                recipe("crafting_table", false))).isFalse();
    }

    @Test
    void craftPreparationAndReadbackRequireConservedEmptyGridAndOneIngredientSet() {
        var slots = emptySlots(46);
        assertThat(MinecraftPhaseFiveInventoryPort.craftingGridAndResultEmpty(slots)).isTrue();

        slots.set(0, stack("minecraft:stick", 4, DEFAULT_HASH));
        slots.set(1, stack("minecraft:oak_planks", 1, DEFAULT_HASH));
        slots.set(2, stack("minecraft:oak_planks", 1, DEFAULT_HASH));
        assertThat(MinecraftPhaseFiveInventoryPort.exactlyOneCraftPrepared(
                slots, recipe("crafting_table", true).ingredients())).isTrue();

        slots.set(1, stack("minecraft:oak_planks", 2, DEFAULT_HASH));
        assertThat(MinecraftPhaseFiveInventoryPort.exactlyOneCraftPrepared(
                slots, recipe("crafting_table", true).ingredients())).isFalse();
        assertThat(MinecraftPhaseFiveInventoryPort.craftingGridAndResultEmpty(slots)).isFalse();
    }

    @Test
    void craftOutputUsesOnlyCompatibleCapacityOrAnEmptyPlayerSlot() {
        var slots = emptySlots(46);
        slots.set(10, stack("minecraft:stick", 60, DEFAULT_HASH));

        assertThat(MinecraftPhaseFiveInventoryPort.chooseCraftDestinationSlot(
                slots, List.of(10, 11), "minecraft:stick", DEFAULT_HASH, 4, 64))
                .contains(10);
        assertThat(MinecraftPhaseFiveInventoryPort.chooseCraftDestinationSlot(
                slots, List.of(10, 11), "minecraft:stick", DEFAULT_HASH, 5, 64))
                .contains(11);

    }

    @Test
    void transferAllowsTheSameVanillaContainerWhileBarrelOpenStateEvolves() {
        var closedBarrel = Blocks.BARREL.defaultBlockState()
                .setValue(BlockStateProperties.OPEN, false);
        var openBarrel = closedBarrel.setValue(BlockStateProperties.OPEN, true);
        var expected = MinecraftPhaseFiveInventoryPort.fingerprintLiveState(closedBarrel);

        assertThat(MinecraftPhaseFiveInventoryPort.sameTransferContainerIdentity(
                expected, openBarrel)).isTrue();
        assertThat(MinecraftPhaseFiveInventoryPort.sameTransferContainerIdentity(
                expected, Blocks.CHEST.defaultBlockState())).isFalse();
        assertThat(MinecraftPhaseFiveInventoryPort.sameTransferContainerIdentity(
                expected, openBarrel.setValue(BlockStateProperties.FACING, Direction.EAST)))
                .isFalse();

        var chest = Blocks.CHEST.defaultBlockState();
        var expectedChest = MinecraftPhaseFiveInventoryPort.fingerprintLiveState(chest);
        assertThat(MinecraftPhaseFiveInventoryPort.sameTransferContainerIdentity(
                expectedChest, chest)).isTrue();
        assertThat(MinecraftPhaseFiveInventoryPort.sameTransferContainerIdentity(
                expectedChest, chest.setValue(BlockStateProperties.HORIZONTAL_FACING,
                        Direction.EAST))).isFalse();

        var doubleChest = chest.setValue(ChestBlock.TYPE, ChestType.LEFT);
        var expectedDouble = MinecraftPhaseFiveInventoryPort.fingerprintLiveState(doubleChest);
        assertThat(MinecraftPhaseFiveInventoryPort.sameTransferContainerIdentity(
                expectedDouble, doubleChest)).isTrue();
        assertThat(MinecraftPhaseFiveInventoryPort.sameTransferContainerIdentity(
                expectedDouble, doubleChest.setValue(ChestBlock.TYPE, ChestType.RIGHT))).isFalse();
        assertThat(MinecraftPhaseFiveInventoryPort.transferMenuType(expectedChest))
                .isEqualTo(MinecraftPhaseFiveInventoryPort.SINGLE_CONTAINER_MENU);
        assertThat(MinecraftPhaseFiveInventoryPort.transferMenuType(expectedDouble))
                .isEqualTo(MinecraftPhaseFiveInventoryPort.DOUBLE_CONTAINER_MENU);
    }

    @Test
    void craftingAloneRetainsTheStationHeadingAndUsesTheRuntimeCameraLimit() throws Exception {
        var target = new BlockTarget("minecraft:overworld", 1, 64, 2);
        var parameters = new MinecraftPhaseFiveInventoryPort.CraftParameters(
                "recipe-ref", "fingerprint", "minecraft:stick", 1, 1, target,
                new BlockStateFingerprint("minecraft:crafting_table", Map.of()));
        assertThat(parameters.restoreViewOnRelease()).isFalse();
        var transfer = new MinecraftPhaseFiveInventoryPort.TransferParameters(
                false, "minecraft:stone", "default_components_only", 0, 1, 1,
                false, 8.0D, target,
                new BlockStateFingerprint("minecraft:barrel", Map.of()));
        assertThat(transfer.restoreViewOnRelease()).isTrue();

        var node = classNode();
        assertThat(invocations(node, "begin"))
                .containsSubsequence(
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#configuredCameraDegreesPerTick",
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort$ViewLease#acquire",
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort#selectOpenHand");
        assertThat(invocations(node, "configuredCameraDegreesPerTick"))
                .contains("java/util/function/DoubleSupplier#getAsDouble");
    }

    @Test
    void aimingChecksSafetyBeforeTurningAndReadbackCanRecoverAStaleCrosshair() throws Exception {
        var node = classNode();

        assertThat(invocations(node, "maintainAim"))
                .containsSubsequence(
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort#ongoingFailure",
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort$ViewLease#turnToward");
        assertThat(invocations(node, "targetReadyForReopen"))
                .contains("net/minecraft/client/player/LocalPlayer#isWithinBlockInteractionRange")
                .doesNotContain(
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort#exactHit");
    }

    @Test
    void internalVisibleAimIsRevalidatedAndReplacesTheOccludableBlockCenter() {
        var target = new BlockTarget("minecraft:overworld", -11, 56, 3);
        var visibleUpHit = Map.<String, Object>of(
                "dimension", target.dimension(),
                "x", -10.5D,
                "y", 57.0D,
                "z", 3.5D);

        assertThat(MinecraftPhaseFiveInventoryPort.inventoryAimPoint(
                inventoryRequest(target, visibleUpHit), target))
                .isEqualTo(new Vec3(-10.5D, 57.0D, 3.5D));
        assertThat(MinecraftPhaseFiveInventoryPort.inventoryAimPoint(
                inventoryRequest(target, null), target))
                .isEqualTo(new Vec3(-10.5D, 56.5D, 3.5D));

        assertThatThrownBy(() -> MinecraftPhaseFiveInventoryPort.inventoryAimPoint(
                inventoryRequest(target, Map.of(
                        "dimension", "minecraft:the_nether",
                        "x", -10.5D, "y", 57.0D, "z", 3.5D)), target))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MinecraftPhaseFiveInventoryPort.inventoryAimPoint(
                inventoryRequest(target, Map.of(
                        "dimension", target.dimension(),
                        "x", -9.5D, "y", 57.0D, "z", 3.5D)), target))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MinecraftPhaseFiveInventoryPort.inventoryAimPoint(
                inventoryRequest(target, Map.of(
                        "dimension", target.dimension(),
                        "x", Double.NaN, "y", 57.0D, "z", 3.5D)), target))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ownedContainerCleanupUsesTheCanonicalScreenCloseLifecycle() throws Exception {
        var node = classNode();

        assertThat(invocations(node, "closeOwnedMenuClient"))
                .containsSubsequence(
                        "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen"
                                + "#onClose",
                        "dev/aod/mcmcp/runtime/ScreenOwnershipSignals#onScreenClosing")
                .doesNotContain("net/minecraft/client/player/LocalPlayer#closeContainer");
        assertThat(invocations(node, "closeForReadback"))
                .contains("dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                        + "#closeOwnedMenuClient");
        assertThat(invocations(node, "releaseOwnedMenu"))
                .contains("dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                        + "#closeOwnedMenuClient");
        var brewing = classNode("/dev/aod/mcmcp/routine/MinecraftKnownBrewingPort.class");
        assertThat(invocations(brewing, "closeOwnedMenuClient"))
                .containsSubsequence(
                        "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen"
                                + "#onClose",
                        "dev/aod/mcmcp/runtime/ScreenOwnershipSignals#onScreenClosing");
    }

    @Test
    void cursorInvariantQuickMovesKeepTheInitialEmptyProofForAuthoritativeReadback()
            throws Exception {
        var node = classNode();

        assertThat(invocations(node, "prepareOwnedDispatch"))
                .contains("dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort#packetRevision")
                .doesNotContain("dev/aod/mcmcp/runtime/ScreenOwnershipSignals"
                        + "#invalidateServerCursorProof");
        assertThat(invocations(node, "dispatchContainerClick"))
                .containsSubsequence(
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#prepareOwnedDispatch",
                        "net/minecraft/client/multiplayer/MultiPlayerGameMode"
                                + "#handleContainerInput");
        assertThat(invocations(node, "dispatchRecipePlacement"))
                .contains(
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort#packetRevision",
                        "net/minecraft/client/multiplayer/MultiPlayerGameMode"
                                + "#handlePlaceRecipe")
                .doesNotContain(
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#prepareOwnedDispatch",
                        "dev/aod/mcmcp/runtime/ScreenOwnershipSignals"
                                + "#invalidateServerCursorProof");
        assertThat(invocations(node, "maintainCraftResult"))
                .contains("dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                        + "#dispatchContainerClick")
                .noneMatch(call -> call.endsWith("#handleContainerInput"));
        assertThat(invocations(node, "maintainClickAck"))
                .containsSubsequence(
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#freshServerCursorSnapshot",
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#closeForReadback")
                .doesNotContain("dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                        + "#countTransfer");
        assertThat(invocations(node, "acceptCraftSnapshot"))
                .contains(
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#craftingGridAndResultEmpty",
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#dispatchRecipePlacement");
        assertThat(invocations(node, "maintainCraftResult"))
                .contains("dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                        + "#freshServerCursorSnapshot");
        assertThat(invocations(node, "acceptTransferSnapshot"))
                .containsSubsequence(
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#liveMenuMatchesSnapshot",
                        "dev/aod/mcmcp/runtime/KnownMenuProfileSupport"
                                + "#hasFullDestinationCapacity",
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#dispatchTransferClick")
                .noneMatch(call -> call.endsWith("#handleContainerInput"));
        assertThat(node.methods.stream()
                .filter(method -> method.instructions.iterator().hasNext())
                .filter(method -> invocations(node, method.name).stream()
                        .anyMatch(call -> call.endsWith("#handleContainerInput")))
                .map(method -> method.name))
                .containsExactly("dispatchContainerClick");
        assertThat(node.methods.stream()
                .filter(method -> method.instructions.iterator().hasNext())
                .filter(method -> invocations(node, method.name).stream()
                        .anyMatch(call -> call.endsWith("#handlePlaceRecipe")))
                .map(method -> method.name))
                .containsExactly("dispatchRecipePlacement");
        assertThat(invocations(node, "closeForReadback"))
                .containsSubsequence(
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#freshEmptyServerCursorProof",
                        "dev/aod/mcmcp/runtime/ScreenOwnershipSignals#cancelRoutine",
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#closeOwnedMenuClient");
        assertThat(containerInputs(node, "maintainCraftResult"))
                .containsExactly("QUICK_MOVE");
    }

    @Test
    void optionalRoutingLabelIsRecheckedAtAdmissionAndImmediatelyBeforeEachClick()
            throws Exception {
        var node = classNode();

        assertThat(invocations(node, "preflight"))
                .contains("dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                        + "#routingLabelFailure");
        assertThat(invocations(node, "dispatchExpectedOpen"))
                .containsSubsequence(
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#routingLabelFailure",
                        "net/minecraft/client/multiplayer/MultiPlayerGameMode#useItemOn");
        assertThat(invocations(node, "prepareOwnedDispatch"))
                .contains("dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                        + "#routingLabelFailure");
        assertThat(invocations(node, "routingLabelFailure"))
                .contains(
                        "dev/aod/mcmcp/observation/MinecraftObservationService"
                                + "#resolveCurrentlyVisibleEntity",
                        "dev/aod/mcmcp/observation/ContainerLabelResolver#resolve");
    }

    @Test
    void expectedOpenRetainsCausalAuthorityUntilPredictionAckOrOwnedCleanup()
            throws Exception {
        var node = classNode();

        assertThat(invocations(node, "dispatchExpectedOpen"))
                .contains(
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#ongoingFailure",
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort$OpenHandPlan"
                                + "#ready",
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort$OpenHandPlan"
                                + "#hand",
                        "dev/aod/mcmcp/runtime/ClientPredictionSignals#begin",
                        "dev/aod/mcmcp/runtime/ClientPredictionSignals$PredictionAttempt"
                                + "#sequenceBeforePrediction",
                        "net/minecraft/client/multiplayer/MultiPlayerGameMode#useItemOn",
                        "dev/aod/mcmcp/runtime/ClientPredictionSignals$PredictionAttempt"
                                + "#captureIssuedPredictions",
                        "dev/aod/mcmcp/runtime/ScreenOwnershipSignals"
                                + "#cancelRoutineAfterPredictedUse");
        assertThat(invocations(node, "cancelScreenAuthority"))
                .contains("dev/aod/mcmcp/runtime/ScreenOwnershipSignals"
                        + "#cancelRoutineAfterPredictedUse");
        assertThat(invocations(node, "maintainTerminalRelease"))
                .contains(
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#cancelScreenAuthority",
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#closeOpenPrediction");
    }

    @Test
    void safetyAndSlotOwnershipAreRecheckedAndReleasedBeforeTerminal() throws Exception {
        var node = classNode();
        assertThat(invocations(node, "observe"))
                .containsSubsequence(
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort$AttemptState"
                                + "#terminal",
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#ongoingFailure");
        assertThat(invocations(node, "preflight"))
                .contains(
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#basicPlayerSafety",
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#chooseOpenHand");
        assertThat(invocations(node, "ongoingFailure"))
                .contains(
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#basicPlayerSafety",
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#screenContextMatches",
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort$ViewLease"
                                + "#undisturbed");
        assertThat(invocations(node, "basicPlayerSafety"))
                .contains(
                        "net/minecraft/client/Minecraft#isPaused",
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#visibleThreatClear");
        assertThat(invocations(node, "chooseOpenHand"))
                .contains("net/minecraft/client/player/LocalPlayer#getOffhandItem");
        var brewing = classNode("/dev/aod/mcmcp/routine/MinecraftKnownBrewingPort.class");
        assertThat(invocations(brewing, "chooseOpenHand"))
                .doesNotContain("net/minecraft/client/player/LocalPlayer#getOffhandItem");
        assertThat(invocations(node, "maintain"))
                .contains("dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                        + "#selectOpenHand");
        assertThat(invocations(node, "maintainAim"))
                .doesNotContain("dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                        + "#selectOpenHand");
        assertThat(invocations(node, "confirmReleaseIfClear"))
                .contains("dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort$AttemptState"
                        + "#closeView");

        var lease = classNode(
                "/dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort$ViewLease.class");
        assertThat(invocations(lease, "close"))
                .contains("net/minecraft/world/entity/player/Inventory#setSelectedSlot");
        assertThat(invocations(lease, "undisturbed"))
                .contains("net/minecraft/world/entity/player/Inventory#getSelectedSlot");
    }

    @Test
    void unownedScreenDoesNotBlockSlotReleaseButOwnedScreenStillRequiresFullClearance() {
        assertThat(MinecraftPhaseFiveInventoryPort.releaseScreenContextClear(
                false, ScreenOwnershipSignals.Phase.IDLE, false)).isTrue();

        assertThat(MinecraftPhaseFiveInventoryPort.releaseScreenContextClear(
                true, ScreenOwnershipSignals.Phase.IDLE, true)).isTrue();
        assertThat(MinecraftPhaseFiveInventoryPort.releaseScreenContextClear(
                true, ScreenOwnershipSignals.Phase.IDLE, false)).isFalse();
        assertThat(MinecraftPhaseFiveInventoryPort.releaseScreenContextClear(
                false, ScreenOwnershipSignals.Phase.CLOSING, false)).isFalse();
    }

    private static List<String> containerInputs(ClassNode node, String methodName) {
        var inputs = new ArrayList<String>();
        node.methods.stream()
                .filter(method -> method.name.equals(methodName))
                .findFirst().orElseThrow()
                .instructions.forEach(instruction -> {
                    if (instruction instanceof FieldInsnNode field
                            && field.owner.equals("net/minecraft/world/inventory/ContainerInput")) {
                        inputs.add(field.name);
                    }
                });
        return inputs;
    }

    private ClassNode classNode() throws Exception {
        return classNode("/dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort.class");
    }

    private ClassNode classNode(String resource) throws Exception {
        var node = new ClassNode();
        try (var stream = getClass().getResourceAsStream(resource)) {
            assertThat(stream).isNotNull();
            new ClassReader(stream).accept(node, 0);
        }
        return node;
    }

    private static List<String> invocations(ClassNode node, String methodName) {
        var calls = new ArrayList<String>();
        node.methods.stream()
                .filter(method -> method.name.equals(methodName))
                .findFirst().orElseThrow()
                .instructions.forEach(instruction -> {
                    if (instruction instanceof MethodInsnNode call) {
                        calls.add(call.owner + "#" + call.name);
                    }
                });
        return calls;
    }

    private static StackFingerprint stack(String item, int count, int hash) {
        return new StackFingerprint(item, count, hash);
    }

    private static ItemStack testItemStack(Item item, int count) {
        return new ItemStack(Holder.direct(item, DataComponentMap.EMPTY), count);
    }

    private static ContainerSnapshot transferSnapshot(List<StackFingerprint> slots, long revision) {
        return new ContainerSnapshot(new java.util.UUID(0, 1), 1, "minecraft:generic_9x3", 0,
                slots, StackFingerprint.EMPTY, revision, revision);
    }

    private static MinecraftPhaseFiveInventoryPort.AttemptState transferState(
            boolean store, int minimum, int maximumCount, int maximumStacks) {
        var target = new BlockTarget("minecraft:overworld", 0, 64, 0);
        return new MinecraftPhaseFiveInventoryPort.AttemptState(inventoryRequest(target, null),
                new MinecraftPhaseFiveInventoryPort.TransferParameters(
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
