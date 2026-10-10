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
    void storageBoundaryUsesWholeStackDestinationsWithinTheSameClickBudget() {
        var initial = boundarySlots();
        var plan = ExactInventoryTransfer.plan(initial, List.of(0, 1, 2, 3, 4, 5, 6), List.of(7, 8),
                ITEM, 77, true, 448, 7, 64, boundaryCapacities(2048), 64).orElseThrow();
        assertThat(plan.clickCount()).isEqualTo(14);
        var current = snapshot(initial, EMPTY, 1);
        for (var click : plan.clicks()) {
            assertThat(plan.beginClick(current, current.packetLedgerRevision())).isTrue();
            assertThat(click.slots().stream().mapToInt(StackFingerprint::count).sum() + click.cursor().count()).isEqualTo(2264);
            current = snapshot(click.slots(), click.cursor(), current.packetLedgerRevision() + 1);
            assertThat(plan.confirm(current, current.packetLedgerRevision())).isTrue();
        }
        assertThat(current.slots().subList(0, 7)).containsOnly(EMPTY);
        assertThat(current.slots().subList(7, 9)).containsExactly(stack(2008), stack(256));
        assertThat(current.carried()).isEqualTo(EMPTY);
        assertThat(plan.confirmedCount()).isEqualTo(448);
        assertThat(plan.reconcileReadback(current)).isTrue();
        assertThat(initial.get(7)).isEqualTo(stack(1816));
    }

    @Test
    void fittingBoundaryTransferStillFillsTheExistingStackFirst() {
        var plan = ExactInventoryTransfer.plan(boundarySlots(), List.of(0, 1, 2, 3, 4, 5, 6), List.of(7, 8),
                ITEM, 77, true, 232, 7, 64, boundaryCapacities(2048), 64).orElseThrow();
        assertThat(plan.clickCount()).isLessThanOrEqualTo(14);
        assertThat(plan.clicks().getLast().slots().subList(7, 9)).containsExactly(stack(2048), EMPTY);
    }

    @Test
    void nearbyCapacityBoundariesKeepQuantityAndBothDestinationLimits() {
        for (int count : new int[]{1791, 1792, 1793, 1816, 1984, 1985, 2047, 2048}) {
            var slots = new java.util.ArrayList<>(boundarySlots());
            slots.set(7, stack(count));
            var plan = ExactInventoryTransfer.plan(slots, List.of(0, 1, 2, 3, 4, 5, 6), List.of(7, 8),
                    ITEM, 77, true, 448, 7, 64, boundaryCapacities(2048), 64).orElseThrow();
            assertThat(plan.clickCount()).isLessThanOrEqualTo(14);
            var last = plan.clicks().getLast();
            assertThat(last.slots().subList(0, 7)).containsOnly(EMPTY);
            assertThat(last.slots().subList(7, 9)).allSatisfy(stack -> assertThat(stack.count()).isBetween(0, 2048));
            assertThat(last.slots().stream().mapToInt(StackFingerprint::count).sum()).isEqualTo(count + 448);
            assertThat(last.cursor()).isEqualTo(EMPTY);
        }
    }

    @Test
    void boundaryFallbackDoesNotRelaxTotalCapacityOrTheClickLimit() {
        for (int secondCapacity : new int[]{0, 215, 216}) {
            assertThat(ExactInventoryTransfer.plan(boundarySlots(), List.of(0, 1, 2, 3, 4, 5, 6), List.of(7, 8),
                    ITEM, 77, true, 448, 7, 64, boundaryCapacities(secondCapacity), 64)).isEmpty();
        }
    }

    @Test
    void cancellationOfBoundaryFallbackReturnsOnlyOwnedCursorAndCountsOnlyProvenMoves() {
        var initial = boundarySlots();
        var plan = ExactInventoryTransfer.plan(initial, List.of(0, 1, 2, 3, 4, 5, 6), List.of(7, 8),
                ITEM, 77, true, 448, 7, 64, boundaryCapacities(2048), 64).orElseThrow();
        var current = snapshot(initial, EMPTY, 1);
        for (int index = 0; index < 7; index++) {
            assertThat(plan.beginClick(current, current.packetLedgerRevision())).isTrue();
            var click = plan.next();
            current = snapshot(click.slots(), click.cursor(), current.packetLedgerRevision() + 1);
            assertThat(plan.confirm(current, current.packetLedgerRevision())).isTrue();
        }
        assertThat(plan.confirmedCount()).isEqualTo(192);
        assertThat(current.carried().count()).isEqualTo(64);
        var recovery = plan.cursorReturn(current).orElseThrow();
        assertThat(recovery.slot()).isEqualTo(3);
        assertThat(plan.confirmRelease(snapshot(recovery.slots(), EMPTY, 9))).isTrue();
        assertThat(plan.confirmedCount()).isEqualTo(192);
        assertThat(recovery.slots().subList(7, 9)).containsExactly(stack(2008), EMPTY);
        assertThat(plan.exhausted()).isTrue();
    }

    private static List<StackFingerprint> boundarySlots() {
        return List.of(stack(64), stack(64), stack(64), stack(64), stack(64), stack(64), stack(64), stack(1816), EMPTY);
    }

    private static List<Integer> boundaryCapacities(int last) {
        return List.of(64, 64, 64, 64, 64, 64, 64, 2048, last);
    }

    @Test
    void cleanupReturnsOnlyFreshExpectedCursorWithoutConfirmingConcurrentSourceChanges() {
        var initial = List.of(stack(668), EMPTY);
        var plan = ExactInventoryTransfer.plan(initial, List.of(0), List.of(1),
                ITEM, 77, true, 2, 1, 64, List.of(2048, 64), 64).orElseThrow();
        assertThat(plan.beginClick(snapshot(initial, EMPTY, 1), 1)).isTrue();
        var expected = plan.next();
        var changed = new java.util.ArrayList<>(expected.slots());
        changed.set(0, changed.get(0).withCount(changed.get(0).count() - 1));
        var server = snapshot(changed, expected.cursor(), 2);
        assertThat(plan.confirm(server, 2)).isFalse();
        assertThat(plan.cursorReturnForRelease(server, 1)).isEmpty();
        var cleanup = plan.cursorReturnForRelease(server, 2).orElseThrow();
        assertThat(cleanup.slot()).isZero();
        assertThat(cleanup.slots().getFirst().count()).isEqualTo(667);
        assertThat(plan.confirmedCount()).isZero();
        assertThat(plan.awaitingAcknowledgement()).isTrue();
        assertThat(plan.cursorReturnForRelease(snapshot(changed, expected.cursor().withCount(1), 3), 3)).isEmpty();
        assertThat(plan.cursorReturnForRelease(snapshot(List.of(stack(2048), EMPTY), expected.cursor(), 3), 3)).isEmpty();
        assertThat(plan.cursorReturnForRelease(snapshot(List.of(new StackFingerprint("minecraft:stone", 1, 99), EMPTY),
                expected.cursor(), 3), 3)).isEmpty();
    }

    @Test
    void fullInventorySwapNeedsNoSpareSlotAndCanRecoverTheSecondCursor() {
        var other = new StackFingerprint("minecraft:stone", 32, 99);
        var initial = List.of(stack(64), other);
        var plan = ExactInventoryTransfer.swap(initial, 0, 1, List.of(64,64)).orElseThrow();
        assertThat(plan.clicks()).hasSize(3);
        var state = snapshot(initial, EMPTY, 1);
        for(int i=0;i<2;i++) {
            assertThat(plan.beginClick(state,10+i)).isTrue();
            var click=plan.next();
            state=snapshot(click.slots(),click.cursor(),2+i);
            assertThat(plan.confirm(state,2+i)).isTrue();
        }
        var recovery=plan.cursorReturn(state).orElseThrow();
        assertThat(recovery.slot()).isZero();
        assertThat(plan.confirmRelease(snapshot(recovery.slots(),EMPTY,4))).isTrue();
        assertThat(recovery.slots()).containsExactly(other,stack(64));
    }

    @Test
    void identicalIntegerHashesDoNotMergeDifferentComponentsOrAcknowledgeWrongPayloads() {
        var type=net.minecraft.core.component.DataComponentType.<String>builder().persistent(com.mojang.serialization.Codec.STRING).build();
        var a=new StackFingerprint(ITEM,1,77,java.util.Map.of(type,"variant-a"));
        var b=new StackFingerprint(ITEM,1,77,java.util.Map.of(type,"variant-b"));
        assertThat(ExactInventoryTransfer.plan(List.of(a,b),List.of(0),List.of(1),ITEM,77,true,1,1,64)).isEmpty();
        var plan=ExactInventoryTransfer.swap(List.of(a,b),0,1,List.of(64,64)).orElseThrow();
        assertThat(plan.beginClick(snapshot(List.of(a,b),EMPTY,1),1)).isTrue();
        assertThat(plan.confirm(snapshot(List.of(EMPTY,b),b,2),2)).isFalse();
        assertThat(plan.confirm(snapshot(List.of(EMPTY,b),a,3),3)).isTrue();
    }

    @Test
    void cancellationAfterPartialDepositCountsUnitsAfterTheCursorReturns() {
        var initial = List.of(stack(64), EMPTY);
        var plan = plan(initial, List.of(0), List.of(1), 1, 1);
        var before = snapshot(initial, EMPTY, 1);
        for (int step = 0; step < 2; step++) {
            assertThat(plan.beginClick(before, 10 + step)).isTrue();
            var click = plan.next();
            before = snapshot(click.slots(), click.cursor(), 2 + step);
            assertThat(plan.confirm(before, 2 + step)).isTrue();
        }
        assertThat(plan.confirmedCount()).isZero();
        var recovery = plan.cursorReturn(before).orElseThrow();
        assertThat(plan.confirmRelease(snapshot(List.of(stack(64), EMPTY), EMPTY, 4))).isFalse();
        var after = snapshot(recovery.slots(), EMPTY, 4);
        assertThat(plan.confirmRelease(after)).isTrue();
        assertThat(plan.confirmedCount()).isEqualTo(1);
        assertThat(plan.pending()).isFalse();
        assertThat(plan.exhausted()).isTrue();
        assertThat(plan.beginClick(after, 20)).isFalse();
    }

    @Test
    void expandedStorageUsesActualPickupCountsAndExactQuantitiesInBothDirections() {
        for (int quantity : new int[]{1, 2, 9, 32, 63, 64}) {
            var take = ExactInventoryTransfer.plan(List.of(stack(2560), EMPTY), List.of(0), List.of(1),
                    ITEM, 77, true, quantity, 1, 64, List.of(4096, 64)).orElseThrow();
            assertThat(take.clicks().getLast().slots()).containsExactly(stack(2560 - quantity), stack(quantity));
            assertThat(take.clicks()).allSatisfy(click -> assertThat(
                    click.slots().stream().mapToInt(StackFingerprint::count).sum() + click.cursor().count())
                    .isEqualTo(2560));
            var store = ExactInventoryTransfer.plan(List.of(stack(64), stack(2560)), List.of(0), List.of(1),
                    ITEM, 77, true, quantity, 1, 64, List.of(64, 4096)).orElseThrow();
            assertThat(store.clicks().getLast().slots()).containsExactly(
                    quantity == 64 ? EMPTY : stack(64 - quantity), stack(2560 + quantity));
        }
    }

    @Test
    void oversizedSourcePickupUsesTheWholeStackAndRejectsAnUnplannableCount() {
        var one = ExactInventoryTransfer.plan(List.of(stack(2560), EMPTY), List.of(0), List.of(1),
                ITEM, 77, true, 1, 1, 64, List.of(4096, 64)).orElseThrow();
        // Vanilla Menu PICKUP takes the entire slot on a primary click, then puts one
        // into the player slot and returns the rest to the expanded storage slot.
        assertThat(one.clicks()).hasSize(3);
        assertThat(one.clicks().getFirst().slots()).containsExactly(EMPTY, EMPTY);
        assertThat(one.clicks().getFirst().cursor()).isEqualTo(stack(2560));
        assertThat(one.clicks().get(1).slots()).containsExactly(EMPTY, stack(1));
        assertThat(one.clicks().get(1).cursor()).isEqualTo(stack(2559));
        assertThat(one.clicks().getLast().slots()).containsExactly(stack(2559), stack(1));
        assertThat(one.clicks().getLast().cursor()).isEqualTo(EMPTY);
        assertThat(ExactInventoryTransfer.plan(List.of(stack(2560), EMPTY), List.of(0), List.of(1),
                ITEM, 77, true, 24, 1, 64, List.of(4096, 64))).isEmpty();
    }

    @Test
    void backpackPickupCapsBeforeSplittingAndKeepsUpgradeSlotsUntouched() {
        var upgrade = new StackFingerprint("test:upgrade", 1, 98);
        for (int quantity : new int[]{1, 24, 32, 63, 64}) {
            var initial = List.of(stack(2048), EMPTY, upgrade);
            var plan = ExactInventoryTransfer.plan(initial, List.of(0), List.of(1),
                    ITEM, 77, true, quantity, 1, 64, List.of(2048, 64, 0), 64).orElseThrow();
            assertThat(plan.clicks()).allSatisfy(click -> {
                assertThat(click.cursor().count()).isBetween(0, 64);
                assertThat(click.slots().get(2)).isEqualTo(upgrade);
            });
            assertThat(plan.clicks().getLast().slots()).containsExactly(stack(2048 - quantity), stack(quantity), upgrade);
        }
        var whole = ExactInventoryTransfer.plan(List.of(stack(2048), EMPTY), List.of(0), List.of(1),
                ITEM, 77, true, 64, 1, 64, List.of(2048, 64), 64).orElseThrow();
        assertThat(whole.clicks()).hasSize(2);
        assertThat(whole.next().cursor()).isEqualTo(stack(64));
        assertThat(whole.next().slots()).containsExactly(stack(1984), EMPTY);
        var half = ExactInventoryTransfer.plan(List.of(stack(2048), EMPTY), List.of(0), List.of(1),
                ITEM, 77, true, 32, 1, 64, List.of(2048, 64), 64).orElseThrow();
        assertThat(half.next().button()).isEqualTo(1);
        assertThat(half.next().cursor()).isEqualTo(stack(32));
        assertThat(half.next().slots()).containsExactly(stack(2016), EMPTY);
    }

    @Test
    void backpackCancellationReturnsOnlyThePacketProvenLimitedCursor() {
        var initial = List.of(stack(2048), EMPTY);
        var plan = ExactInventoryTransfer.plan(initial, List.of(0), List.of(1),
                ITEM, 77, true, 64, 1, 64, List.of(2048, 64), 64).orElseThrow();
        assertThat(plan.beginClick(snapshot(initial, EMPTY, 1), 10)).isTrue();
        assertThat(plan.confirm(snapshot(List.of(EMPTY, EMPTY), stack(2048), 2), 2)).isFalse();
        var held = snapshot(List.of(stack(1984), EMPTY), stack(64), 3);
        assertThat(plan.confirm(held, 3)).isTrue();
        var cleanup = plan.cursorReturn(held).orElseThrow();
        assertThat(cleanup.slots()).containsExactly(stack(2048), EMPTY);
        assertThat(plan.confirmRelease(snapshot(cleanup.slots(), EMPTY, 4))).isTrue();
        assertThat(plan.confirmedCount()).isZero();
        assertThat(plan.pending()).isFalse();
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
                ITEM, 77, true, 23, 1, 64, List.of(0, 64, 4096, 0)).orElseThrow();
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
        assertThat(plan.confirmedCount()).isEqualTo(23);
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

    @Test
    void cursorReturnWaitsForTheSentClickAndPreservesPartialTransfers() {
        var initial = List.of(stack(2560), EMPTY);
        var plan = ExactInventoryTransfer.plan(initial, List.of(0), List.of(1),
                ITEM, 77, true, 1, 1, 64, List.of(4096, 64)).orElseThrow();
        var before = snapshot(initial, EMPTY, 1);
        var pickup = plan.next();
        assertThat(plan.beginClick(before, 10)).isTrue();
        assertThat(plan.cursorReturn(before)).isEmpty();
        var held = snapshot(pickup.slots(), pickup.cursor(), 2);
        assertThat(plan.confirm(held, 1)).isFalse();
        assertThat(plan.cursorReturn(held)).isEmpty();
        assertThat(plan.confirm(held, 2)).isTrue();
        var restoreAll = plan.cursorReturn(held).orElseThrow();
        assertThat(restoreAll.slot()).isZero();
        assertThat(restoreAll.slots()).containsExactly(stack(2560), EMPTY);
        var deposit = plan.next();
        assertThat(plan.beginClick(held, 11)).isTrue();
        var partial = snapshot(deposit.slots(), deposit.cursor(), 3);
        assertThat(plan.confirm(partial, 3)).isTrue();
        var restoreRemainder = plan.cursorReturn(partial).orElseThrow();
        assertThat(restoreRemainder.slots()).containsExactly(stack(2559), stack(1));
        assertThat(restoreRemainder.confirmed(snapshot(restoreRemainder.slots(), EMPTY, 4))).isTrue();
        assertThat(restoreRemainder.confirmed(snapshot(restoreRemainder.slots(), stack(1), 4))).isFalse();
        assertThat(plan.cursorReturn(snapshot(List.of(EMPTY, stack(2)), stack(2559), 4))).isEmpty();
        assertThat(plan.confirmedCount()).isZero();
        assertThat(plan.pending()).isTrue(); // The partial group remains an explicit uncertain effect.
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
