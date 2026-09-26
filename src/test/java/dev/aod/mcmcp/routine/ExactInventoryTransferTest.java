package dev.aod.mcmcp.routine;

import dev.aod.mcmcp.runtime.ContainerSyncSignals.ContainerSnapshot;
import dev.aod.mcmcp.runtime.ContainerSyncSignals.StackFingerprint;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExactInventoryTransferTest {
    private static final String ITEM = "minecraft:black_dye";
    private static final StackFingerprint EMPTY = StackFingerprint.EMPTY;
    private static final UUID SESSION = UUID.randomUUID();

    @Test
    void expandedStorageUsesNormalCursorAndExactQuantitiesInBothDirections() {
        for (int quantity : new int[]{1, 2, 9, 24, 32, 63, 64}) {
            var take = ExactInventoryTransfer.plan(List.of(stack(2560), EMPTY), List.of(0), List.of(1),
                    ITEM, 77, true, quantity, 1, 64, List.of(4096, 64)).orElseThrow();
            assertThat(take.clicks().getLast().slots()).containsExactly(stack(2560 - quantity), stack(quantity));
            assertThat(take.clicks()).allSatisfy(click -> assertThat(click.cursor().count()).isLessThanOrEqualTo(64));
            var store = ExactInventoryTransfer.plan(List.of(stack(64), stack(2560)), List.of(0), List.of(1),
                    ITEM, 77, true, quantity, 1, 64, List.of(64, 4096)).orElseThrow();
            assertThat(store.clicks().getLast().slots()).containsExactly(
                    quantity == 64 ? EMPTY : stack(64 - quantity), stack(2560 + quantity));
        }
    }

    @Test
    void expandedTransferRejectsProtectedSlotsAndUnboundedSearch() {
        assertThat(ExactInventoryTransfer.plan(List.of(stack(2560), EMPTY), List.of(0), List.of(1),
                ITEM, 77, true, 24, 1, 64, List.of(4096, 0))).isEmpty();
        assertThat(ExactInventoryTransfer.plan(List.of(stack(2560), stack(2048)), List.of(0), List.of(1),
                ITEM, 77, true, 24, 1, 64, List.of(4096, 4096))).isEmpty();
        assertThat(ExactInventoryTransfer.plan(List.of(stack(2560), EMPTY), List.of(2), List.of(1),
                ITEM, 77, true, 24, 1, 64, List.of(4096, 64))).isEmpty();
    }

    @Test
    void expandedTransferUsesExplicitRolesAndWaitsForFreshCursorAtEveryStep() {
        var protectedStack = new StackFingerprint("test:upgrade", 1, 98);
        var initial = List.of(protectedStack, EMPTY, stack(2560), protectedStack);
        var plan = ExactInventoryTransfer.plan(initial, List.of(2), List.of(1),
                ITEM, 77, true, 24, 1, 64, List.of(0, 64, 4096, 0)).orElseThrow();
        var current = snapshot(initial, EMPTY, 1);
        long tick = 10;
        while (!plan.exhausted()) {
            assertThat(plan.beginClick(current, tick++)).isTrue();
            var click = plan.next();
            var next = snapshot(click.slots(), click.cursor(), current.packetLedgerRevision() + 1);
            assertThat(plan.confirm(next, current.packetLedgerRevision())).isFalse();
            assertThat(plan.confirm(next, next.packetLedgerRevision())).isTrue();
            assertThat(next.slots().get(0)).isEqualTo(protectedStack);
            assertThat(next.slots().get(3)).isEqualTo(protectedStack);
            current = next;
        }
        assertThat(plan.confirmedCount()).isEqualTo(24);
        assertThat(plan.pending()).isFalse();
        assertThat(plan.reconcileReadback(current)).isTrue();
        assertThat(plan.reconcileReadback(snapshot(List.of(protectedStack, stack(24), stack(2560), protectedStack), EMPTY, 100))).isFalse();
    }

    @Test
    void ordinaryDyesCanSplitButBundlePickupOverridesCannotBePlanned() {
        assertThat(KnownMenuTransfers.ordinaryPickupItem(net.minecraft.world.item.DyeItem.class)).isTrue();
        assertThat(KnownMenuTransfers.ordinaryPickupItem(net.minecraft.world.item.BundleItem.class)).isFalse();
    }

    @Test
    void everyQuantityFromANormalFullStackFitsTheExistingClickEnvelope() {
        for (int quantity = 1; quantity <= 64; quantity++) {
            var initial = List.of(stack(64), EMPTY, new StackFingerprint("minecraft:stone", 8, 91));
            var plan = plan(initial, List.of(0), List.of(1), quantity, 1);
            assertThat(plan.clickCount()).isBetween(2, 14);
            for (var click : plan.clicks()) {
                assertThat(click.slots().get(2)).isEqualTo(initial.get(2));
                assertThat(click.slots().stream().mapToInt(StackFingerprint::count).sum()
                        + click.cursor().count()).isEqualTo(72);
            }
            var end = plan.clicks().getLast();
            assertThat(end.slots()).containsExactly(withCount(64 - quantity), stack(quantity), initial.get(2));
            assertThat(end.cursor()).isEqualTo(EMPTY);
        }
    }

    @Test
    void dyeExamplesMoveTwoAndPreserveTheRemainingStacksInBothDirections() {
        for (int count : new int[]{7, 32, 48, 53, 64}) {
            for (boolean reverse : new boolean[]{false, true}) {
                var initial = reverse ? List.of(stack(3), stack(count)) : List.of(stack(count), stack(3));
                var plan = plan(initial, List.of(reverse ? 1 : 0), List.of(reverse ? 0 : 1), 2, 1);
                var end = plan.clicks().getLast();
                assertThat(end.slots().get(reverse ? 1 : 0).count()).isEqualTo(count - 2);
                assertThat(end.slots().get(reverse ? 0 : 1).count()).isEqualTo(5);
                assertThat(end.cursor()).isEqualTo(EMPTY);
            }
        }
    }

    @Test
    void fixedPlanCanUseMultipleSourcesAndDestinationSlotsWithoutMixingComponents() {
        var different = new StackFingerprint(ITEM, 63, 88);
        var initial = List.of(stack(1), stack(2), stack(63), EMPTY, different);
        var plan = plan(initial, List.of(0, 1), List.of(2, 3, 4), 3, 2);
        assertThat(plan.clicks().getLast().slots()).containsExactly(EMPTY, EMPTY, stack(64), stack(2), different);
        assertThat(ExactInventoryTransfer.plan(initial, List.of(0, 1), List.of(2, 3),
                ITEM, 77, true, 3, 1, 64)).isEmpty();
    }

    @Test
    void unavailableQuantityCapacityOversizedOrClickBudgetRejectsBeforeStarting() {
        assertThat(ExactInventoryTransfer.plan(List.of(stack(1), EMPTY), List.of(0), List.of(1),
                ITEM, 77, true, 2, 1, 64)).isEmpty();
        assertThat(ExactInventoryTransfer.plan(List.of(stack(3), stack(63)), List.of(0), List.of(1),
                ITEM, 77, true, 2, 1, 64)).isEmpty();
        assertThat(ExactInventoryTransfer.plan(List.of(stack(65), EMPTY), List.of(0), List.of(1),
                ITEM, 77, true, 2, 1, 64)).isEmpty();
        assertThat(ExactInventoryTransfer.plan(List.of(stack(2), EMPTY), List.of(0), List.of(1),
                ITEM, 88, true, 2, 1, 64)).isEmpty();
        var many = java.util.stream.IntStream.range(0, 16)
                .mapToObj(i -> i < 8 ? stack(1) : EMPTY).toList();
        assertThat(ExactInventoryTransfer.plan(many,
                java.util.stream.IntStream.range(0, 8).boxed().toList(), List.of(8),
                ITEM, 77, true, 8, 8, 64)).isEmpty();
    }

    @Test
    void everyClickNeedsFreshSlotAndCursorProofAndCannotBeRepeated() {
        var initial = List.of(stack(48), EMPTY);
        var plan = plan(initial, List.of(0), List.of(1), 2, 1);
        var before = snapshot(initial, EMPTY, 1);
        long tick = 10;
        long revision = 1;
        while (!plan.exhausted()) {
            var click = plan.next();
            assertThat(plan.beginClick(before, tick)).isTrue();
            assertThat(plan.beginClick(before, tick + 1)).isFalse();
            assertThat(plan.pending()).isTrue();
            assertThat(plan.confirm(before, revision + 1)).isFalse();
            var after = snapshot(click.slots(), click.cursor(), revision + 1);
            assertThat(plan.confirm(after, revision)).isFalse();
            assertThat(plan.confirm(after, revision + 1)).isTrue();
            assertThat(plan.beginClick(after, tick)).isFalse();
            before = after;
            revision++;
            tick++;
        }
        assertThat(plan.confirmedCount()).isEqualTo(2);
        assertThat(plan.pending()).isFalse();
        assertThat(plan.reconcileReadback(before)).isTrue();
        assertThat(plan.reconcileReadback(snapshot(List.of(stack(48), stack(2)), EMPTY, revision + 1))).isFalse();
    }

    @Test
    void interruptedSplitStaysUnknownEvenBetweenAcknowledgedClicks() {
        var initial = List.of(stack(48), EMPTY);
        var plan = plan(initial, List.of(0), List.of(1), 2, 1);
        assertThat(plan.beginClick(snapshot(initial, EMPTY, 1), 10)).isTrue();
        var first = plan.next();
        var after = snapshot(first.slots(), first.cursor(), 2);
        assertThat(plan.confirm(after, 2)).isTrue();
        assertThat(plan.confirmedCount()).isZero();
        assertThat(plan.pending()).isTrue();
        assertThat(plan.pendingCount()).isEqualTo(2);
        assertThat(plan.reconcileReadback(after)).isFalse();
    }

    @Test
    void interruptedLaterSplitKeepsOnlyTheCompletedPrefixInActionEvidence() {
        var initial = List.of(stack(1), stack(2), EMPTY);
        var plan = plan(initial, List.of(0, 1), List.of(2), 3, 2);
        var target = new BlockTarget("minecraft:overworld", 0, 64, 0);
        var state = new MinecraftPhaseFiveInventoryPort.AttemptState(
                new PhaseFiveRequest("transfer_items", java.util.Map.of(),
                        new PhaseFiveBounds(target.dimension(), target, target, 0, 20, false), 3, "items"),
                new InventoryParameters.TransferParameters(false, ITEM, "default_components_only",
                        3, 3, 2, true, 8, target,
                        new BlockStateFingerprint("minecraft:chest", java.util.Map.of("type", "single")),
                        java.util.Optional.empty(), 3));
        state.beginExactTransfer(plan, 3, 0);
        var current = snapshot(initial, EMPTY, 1);
        long tick = 10;
        while (plan.confirmedCount() == 0) {
            var click = plan.next();
            assertThat(plan.beginClick(current, tick++)).isTrue();
            current = snapshot(click.slots(), click.cursor(), current.packetLedgerRevision() + 1);
            assertThat(plan.confirm(current, current.packetLedgerRevision())).isTrue();
        }
        state.updateTransferPrefix();
        assertThat(state.basis()).containsEntry("confirmed_transfer_count", 1)
                .containsEntry("transfer_in_flight", false);
        var click = plan.next();
        assertThat(plan.beginClick(current, tick)).isTrue();
        current = snapshot(click.slots(), click.cursor(), current.packetLedgerRevision() + 1);
        assertThat(plan.confirm(current, current.packetLedgerRevision())).isTrue();
        state.updateTransferPrefix();
        assertThat(state.basis()).containsEntry("confirmed_transfer_count", 1)
                .containsEntry("confirmed_source_count", 2)
                .containsEntry("confirmed_destination_count", 1)
                .containsEntry("transfer_in_flight", true)
                .containsEntry("pending_stack_count", 2)
                .containsEntry("pending_source_before", 2)
                .containsEntry("pending_destination_before", 1)
                .containsEntry("transfer_readback_observed", false);
    }

    @Test
    void replenishmentAndUnrelatedChangesNeverAuthorizeAnotherClick() {
        var initial = List.of(stack(48), EMPTY, stack(1));
        var plan = plan(initial, List.of(0), List.of(1), 2, 1);
        assertThat(plan.beginClick(snapshot(List.of(stack(49), EMPTY, stack(1)), EMPTY, 2), 10)).isFalse();
        assertThat(plan.beginClick(snapshot(initial, EMPTY, 1), 10)).isTrue();
        var first = plan.next();
        var changed = new java.util.ArrayList<>(first.slots());
        changed.set(2, stack(2));
        assertThat(plan.confirm(snapshot(changed, first.cursor(), 2), 2)).isFalse();
        assertThat(plan.ackTimedOut(69)).isFalse();
        assertThat(plan.ackTimedOut(70)).isTrue();
        assertThat(plan.confirmedCount()).isZero();
    }

    private static ExactInventoryTransfer plan(List<StackFingerprint> initial,
            List<Integer> sources, List<Integer> destinations, int quantity, int maxSources) {
        return ExactInventoryTransfer.plan(initial, sources, destinations, ITEM, 77, true,
                quantity, maxSources, 64).orElseThrow();
    }

    private static ContainerSnapshot snapshot(List<StackFingerprint> slots, StackFingerprint cursor, long revision) {
        return new ContainerSnapshot(SESSION, 7, "minecraft:generic_9x3", (int) revision,
                slots, cursor, revision, revision);
    }

    private static StackFingerprint withCount(int count) { return count == 0 ? EMPTY : stack(count); }
    private static StackFingerprint stack(int count) { return new StackFingerprint(ITEM, count, 77); }
}
