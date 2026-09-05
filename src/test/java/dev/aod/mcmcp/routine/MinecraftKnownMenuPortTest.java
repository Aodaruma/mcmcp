package dev.aod.mcmcp.routine;

import dev.aod.mcmcp.runtime.ContainerSyncSignals;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MinecraftKnownMenuPortTest {
    @Test
    void absoluteGoalCeilingUsesActualSingleDoubleAndNonstackableSlotCapacity() {
        var holder = Items.DIRT.builtInRegistryHolder();
        if (!holder.areComponentsBound()) {
            holder.bindComponents(DataComponentMap.builder()
                    .set(DataComponents.MAX_STACK_SIZE, 64).build());
        }
        var item = new ItemStack(Items.DIRT, 1);
        item.set(DataComponents.MAX_STACK_SIZE, 64);
        var inventory = new SimpleContainer(54);
        var slots = java.util.stream.IntStream.range(0, 54)
                .mapToObj(i -> new Slot(inventory, i, 0, 0)).toList();
        assertThat(KnownMenuTransfers.maximumDestinationCount(item, slots.subList(0, 27)))
                .isEqualTo(1_728).isLessThan(2_465);
        assertThat(KnownMenuTransfers.maximumDestinationCount(item, slots))
                .isEqualTo(3_456).isGreaterThanOrEqualTo(2_465);
        item.set(DataComponents.MAX_STACK_SIZE, 1);
        assertThat(KnownMenuTransfers.maximumDestinationCount(item, slots)).isEqualTo(54);
        assertThat(KnownMenuTransfers.maximumDestinationCount(item, slots.subList(0, 27))).isEqualTo(27);
    }

    @Test
    void normalServerConfirmedQuickMoveUsesNoClientPredictionAndIsSharedByBothAdapters()
            throws Exception {
        var shared = classNode(KnownMenuTransfers.class);
        assertThat(invocations(shared, "dispatchServerConfirmedQuickMove"))
                .contains("net/minecraft/network/protocol/game/ServerboundContainerClickPacket#<init>")
                .noneMatch(call -> call.endsWith("#handleContainerInput"));
        var inputFields = new ArrayList<String>();
        for (var method : shared.methods) {
            for (var instruction : method.instructions) {
                if (instruction instanceof FieldInsnNode field
                        && field.owner.equals("net/minecraft/world/inventory/ContainerInput")) {
                    inputFields.add(field.name);
                }
            }
        }
        assertThat(inputFields).containsExactly("QUICK_MOVE");
        assertThat(invocations(classNode(MinecraftKnownMenuPort.class),
                "dispatchServerConfirmedQuickMove"))
                .containsSubsequence(
                        "dev/aod/mcmcp/runtime/KnownMenuProfileSupport$Context#canTransferEntireStack",
                        "dev/aod/mcmcp/routine/KnownMenuTransfers#dispatchServerConfirmedQuickMove");
        assertThat(invocations(classNode(MinecraftPhaseFiveInventoryPort.class), "dispatchTransferClick"))
                .containsSubsequence(
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort#prepareOwnedDispatch",
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort$TransferBatch#beginClick",
                        "dev/aod/mcmcp/routine/KnownMenuTransfers#dispatchServerConfirmedQuickMove");
    }

    @Test
    void sharedWholeStackProofSupportsBothDirectionsAndRejectsEveryUnselectedChange() {
        var before = List.of(stack("minecraft:stone", 64, 7),
                stack("minecraft:diamond_hoe", 1, 99),
                stack("minecraft:stone", 32, 7), ContainerSyncSignals.StackFingerprint.EMPTY,
                stack("minecraft:apple", 3, 11));
        var after = new ArrayList<>(before);
        after.set(0, ContainerSyncSignals.StackFingerprint.EMPTY);
        after.set(2, stack("minecraft:stone", 64, 7));
        after.set(3, stack("minecraft:stone", 32, 7));
        assertThat(KnownMenuTransfers.exactWholeStackMove(before, after, 0, List.of(2, 3, 4)))
                .isTrue();
        var changedSource = new ArrayList<>(after);
        changedSource.set(1, stack("minecraft:diamond_hoe", 1, 100));
        assertThat(KnownMenuTransfers.exactWholeStackMove(before, changedSource, 0, List.of(2, 3, 4)))
                .isFalse();
        var changedDestination = new ArrayList<>(after);
        changedDestination.set(4, stack("minecraft:apple", 3, 12));
        assertThat(KnownMenuTransfers.exactWholeStackMove(before, changedDestination, 0, List.of(2, 3, 4)))
                .isFalse();
        var wrongComponents = new ArrayList<>(after);
        wrongComponents.set(3, stack("minecraft:stone", 32, 8));
        assertThat(KnownMenuTransfers.exactWholeStackMove(before, wrongComponents, 0, List.of(2, 3, 4)))
                .isFalse();
        var partial = new ArrayList<>(after);
        partial.set(3, stack("minecraft:stone", 31, 7));
        assertThat(KnownMenuTransfers.exactWholeStackMove(before, partial, 0, List.of(2, 3, 4)))
                .isFalse();

        var storing = List.of(ContainerSyncSignals.StackFingerprint.EMPTY,
                stack("minecraft:diamond_hoe", 1, 99), stack("minecraft:stone", 16, 7));
        var stored = List.of(storing.get(2), storing.get(1), ContainerSyncSignals.StackFingerprint.EMPTY);
        assertThat(KnownMenuTransfers.exactWholeStackMove(storing, stored, 2, List.of(0, 1)))
                .isTrue();
    }
    @Test
    void exactTransferRequiresEveryStorageAndProtectedSlotExceptSourceToRemainUnchanged() {
        var before = new ArrayList<>(Collections.nCopies(
                6, ContainerSyncSignals.StackFingerprint.EMPTY));
        before.set(0, stack("example:opaque_item", 3, 42));
        before.set(1, stack("minecraft:stone", 1, 7));
        before.set(5, stack("example:upgrade", 1, 99));
        var expected = List.copyOf(before);

        var validAfter = new ArrayList<>(before);
        validAfter.set(0, ContainerSyncSignals.StackFingerprint.EMPTY);
        validAfter.set(2, stack("example:opaque_item", 3, 42));
        assertThat(KnownMenuTransfers.exactWholeStackMove(
                expected, validAfter, 0, List.of(2, 3, 4))).isTrue();

        var changedStorage = new ArrayList<>(validAfter);
        changedStorage.set(1, ContainerSyncSignals.StackFingerprint.EMPTY);
        assertThat(KnownMenuTransfers.exactWholeStackMove(
                expected, changedStorage, 0, List.of(2, 3, 4))).isFalse();

        var changedUpgrade = new ArrayList<>(validAfter);
        changedUpgrade.set(5, ContainerSyncSignals.StackFingerprint.EMPTY);
        assertThat(KnownMenuTransfers.exactWholeStackMove(
                expected, changedUpgrade, 0, List.of(2, 3, 4))).isFalse();
    }

    private static ContainerSyncSignals.StackFingerprint stack(
            String item, int count, int componentsHash) {
        return new ContainerSyncSignals.StackFingerprint(item, count, componentsHash);
    }

    private static ClassNode classNode(Class<?> type) throws Exception {
        try (var input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            var node = new ClassNode();
            new ClassReader(java.util.Objects.requireNonNull(input)).accept(node, 0);
            return node;
        }
    }

    private static List<String> invocations(ClassNode node, String methodName) {
        var calls = new ArrayList<String>();
        for (var method : node.methods) {
            if (!method.name.equals(methodName)) continue;
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call) calls.add(call.owner + "#" + call.name);
            }
        }
        return calls;
    }
}
