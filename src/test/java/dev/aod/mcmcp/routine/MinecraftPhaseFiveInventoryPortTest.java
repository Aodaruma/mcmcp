package dev.aod.mcmcp.routine;

import dev.aod.mcmcp.runtime.ContainerSyncSignals.StackFingerprint;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.ArrayList;
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
    void aimingChecksSafetyBeforeTurningAndReadbackCanRecoverAStaleCrosshair() throws Exception {
        var node = new ClassNode();
        try (var stream = getClass().getResourceAsStream(
                "/dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort.class")) {
            assertThat(stream).isNotNull();
            new ClassReader(stream).accept(node, 0);
        }

        assertThat(invocations(node, "maintainAim"))
                .containsSubsequence(
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort#preflight",
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort$ViewLease#turnToward");
        assertThat(invocations(node, "targetReadyForReopen"))
                .contains("net/minecraft/client/player/LocalPlayer#isWithinBlockInteractionRange")
                .doesNotContain(
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort#exactHit");
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
}
