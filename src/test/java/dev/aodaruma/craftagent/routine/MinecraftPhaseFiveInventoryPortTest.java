package dev.aodaruma.craftagent.routine;

import dev.aodaruma.craftagent.runtime.ContainerSyncSignals.StackFingerprint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MinecraftPhaseFiveInventoryPortTest {
    private static final int DEFAULT_HASH = 41;

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
    void craftReadbackRejectsNoOpAndMultipleCraftDelta() {
        assertThat(MinecraftPhaseFiveInventoryPort.verifyCraftReadback(
                2, 6, 4, 6))
                .isEqualTo(new MinecraftPhaseFiveInventoryPort.CraftReadback(true, true));
        assertThat(MinecraftPhaseFiveInventoryPort.verifyCraftReadback(
                2, 2, 4, 6).exactlyOneCraft()).isFalse();
        assertThat(MinecraftPhaseFiveInventoryPort.verifyCraftReadback(
                2, 10, 4, 6).exactlyOneCraft()).isFalse();
    }

    private static StackFingerprint stack(String item, int count, int hash) {
        return new StackFingerprint(item, count, hash);
    }
}
