package dev.aod.mcmcp.routine;

import dev.aod.mcmcp.runtime.ContainerSyncSignals;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MinecraftKnownMenuPortTest {
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
        assertThat(MinecraftKnownMenuPort.immutableSlotsUnchanged(
                expected, validAfter, 0, List.of(0, 1), List.of(5))).isTrue();

        var changedStorage = new ArrayList<>(validAfter);
        changedStorage.set(1, ContainerSyncSignals.StackFingerprint.EMPTY);
        assertThat(MinecraftKnownMenuPort.immutableSlotsUnchanged(
                expected, changedStorage, 0, List.of(0, 1), List.of(5))).isFalse();

        var changedUpgrade = new ArrayList<>(validAfter);
        changedUpgrade.set(5, ContainerSyncSignals.StackFingerprint.EMPTY);
        assertThat(MinecraftKnownMenuPort.immutableSlotsUnchanged(
                expected, changedUpgrade, 0, List.of(0, 1), List.of(5))).isFalse();
    }

    private static ContainerSyncSignals.StackFingerprint stack(
            String item, int count, int componentsHash) {
        return new ContainerSyncSignals.StackFingerprint(item, count, componentsHash);
    }
}
