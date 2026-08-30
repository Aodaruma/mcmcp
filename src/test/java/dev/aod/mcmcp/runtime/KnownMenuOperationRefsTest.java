package dev.aod.mcmcp.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnownMenuOperationRefsTest {
    private static final String PROFILE_HASH = "sha256:" + "a".repeat(64);

    @BeforeAll
    static void bindStoneComponents() {
        var holder = Items.STONE.builtInRegistryHolder();
        if (!holder.areComponentsBound()) {
            holder.bindComponents(DataComponentMap.builder()
                    .set(DataComponents.MAX_STACK_SIZE, 64)
                    .build());
        }
    }

    @Test
    void exactReferenceIsOpaqueAndSingleUse() {
        var refs = new KnownMenuOperationRefs();
        var context = context(UUID.randomUUID(), new Object(), 4, 9, 41, 7, 100);
        ContainerSyncSignals.StackFingerprint stack = stack();

        assertThatThrownBy(() -> refs.issue(
                context, 2, ContainerSyncSignals.StackFingerprint.EMPTY, ItemStack.EMPTY,
                slots(ContainerSyncSignals.StackFingerprint.EMPTY), 0, 0,
                KnownMenuOperationRefs.TRANSFER_TO_PLAYER, 110))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> refs.issue(
                context, 2, stack, exactStack(), slots(stack), 1, 4, "activate", 110))
                .isInstanceOf(IllegalArgumentException.class);

        List<ContainerSyncSignals.StackFingerprint> slots = slots(stack);
        ItemStack exactStack = exactStack();
        String reference = refs.issue(
                context, 2, stack, exactStack, slots,
                1, 4, KnownMenuOperationRefs.TRANSFER_TO_PLAYER, 110);
        exactStack.setCount(1);

        assertThat(reference).matches("[A-Za-z0-9_-]{24}");
        assertThat(refs.peek(reference, context)).isPresent();
        assertThat(refs.peek(reference, context)).isPresent();
        var operation = refs.resolve(reference, context).orElseThrow();
        assertThat(operation.sourceSlot()).isEqualTo(2);
        assertThat(operation.stack()).isEqualTo(stack);
        assertThat(operation.initialServerSlots()).isEqualTo(slots);
        assertThat(operation.baselineExactComponentPlayerCount()).isEqualTo(1);
        assertThat(operation.expectedExactComponentPlayerCount()).isEqualTo(4);
        assertThat(operation.operationKind())
                .isEqualTo(KnownMenuOperationRefs.TRANSFER_TO_PLAYER);
        assertThat(operation.exactStack().getCount()).isEqualTo(3);
        assertThat(ItemStack.isSameItemSameComponents(
                operation.exactStack(), exactStack())).isTrue();
        ItemStack returned = operation.exactStack();
        returned.setCount(1);
        assertThat(operation.exactStack().getCount()).isEqualTo(3);
        assertThat(refs.resolve(reference, context)).isEmpty();
    }

    @Test
    void resolveRejectsEveryStaleOrDifferentContextAndClearSession() {
        var refs = new KnownMenuOperationRefs();
        UUID session = UUID.randomUUID();
        Object screen = new String("screen");
        var context = context(session, screen, 4, 9, 41, 7, 100);
        String reference = issue(refs, context);

        List<KnownMenuOperationRefs.Context> mismatches = List.of(
                context(UUID.randomUUID(), screen, 4, 9, 41, 7, 100),
                context(session, new String("screen"), 4, 9, 41, 7, 100),
                context(session, screen, 5, 9, 41, 7, 100),
                new KnownMenuOperationRefs.Context(
                        session, screen, 4, "example:other", 9, 41, PROFILE_HASH, 7, 100),
                context(session, screen, 4, 10, 41, 7, 100),
                context(session, screen, 4, 9, 42, 7, 100),
                new KnownMenuOperationRefs.Context(
                        session, screen, 4, "example:machine", 9, 41,
                        "sha256:" + "b".repeat(64), 7, 100),
                context(session, screen, 4, 9, 41, 8, 100));
        mismatches.forEach(value -> assertThat(refs.resolve(reference, value)).isEmpty());
        assertThat(refs.resolve(reference, context)).isPresent();

        String expired = issue(refs, context);
        assertThat(refs.resolve(expired,
                context(session, screen, 4, 9, 41, 7, 111))).isEmpty();

        String cleared = issue(refs, context);
        refs.clearSession(session);
        assertThat(refs.resolve(cleared, context)).isEmpty();

        String clearedGlobally = issue(refs, context);
        refs.clear();
        assertThat(refs.resolve(clearedGlobally, context)).isEmpty();
    }

    @Test
    void seventeenthLeaseEvictsOnlyTheOldest() {
        var refs = new KnownMenuOperationRefs();
        var context = context(UUID.randomUUID(), new Object(), 4, 9, 41, 7, 100);
        var issued = new ArrayList<String>();
        for (int index = 0; index <= KnownMenuOperationRefs.MAX_LEASES; index++) {
            issued.add(issue(refs, context));
        }

        assertThat(refs.resolve(issued.getFirst(), context)).isEmpty();
        assertThat(issued.subList(1, issued.size()))
                .allSatisfy(reference -> assertThat(refs.resolve(reference, context)).isPresent());
    }

    private static KnownMenuOperationRefs.Context context(
            UUID session,
            Object screen,
            int containerId,
            int stateId,
            int slotCount,
            long packetRevision,
            long clientTick) {
        return new KnownMenuOperationRefs.Context(
                session, screen, containerId, "example:machine", stateId,
                slotCount, PROFILE_HASH, packetRevision, clientTick);
    }

    private static String issue(
            KnownMenuOperationRefs refs, KnownMenuOperationRefs.Context context) {
        ContainerSyncSignals.StackFingerprint stack = stack();
        return refs.issue(
                context, 2, stack, exactStack(), slots(stack),
                1, 4, KnownMenuOperationRefs.TRANSFER_TO_PLAYER, 110);
    }

    private static ItemStack exactStack() {
        return new ItemStack(Items.STONE, 3);
    }

    private static ContainerSyncSignals.StackFingerprint stack() {
        return ContainerSyncSignals.StackFingerprint.fromServerPacket(exactStack());
    }

    private static List<ContainerSyncSignals.StackFingerprint> slots(
            ContainerSyncSignals.StackFingerprint stack) {
        var slots = new ArrayList<>(Collections.nCopies(
                41, ContainerSyncSignals.StackFingerprint.EMPTY));
        slots.set(2, stack);
        return List.copyOf(slots);
    }
}
