package dev.aod.mcmcp.routine;

import dev.aod.mcmcp.runtime.ContainerSyncSignals.StackFingerprint;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MinecraftFrameItemPortTest {
    @Test
    void insertRequiresTheSelectedPayloadToLoseExactlyOneWithComponentsPreserved() {
        var before = List.of(stack(64, 7), stack(20, 7), new StackFingerprint("minecraft:apple", 2, 8));
        assertThat(MinecraftFrameItemPort.exactInventoryConsumption(
                before, List.of(stack(63, 7), before.get(1), before.get(2)), 0)).isTrue();
        assertThat(MinecraftFrameItemPort.exactInventoryConsumption(
                before, List.of(stack(63, 9), before.get(1), before.get(2)), 0)).isFalse();
        assertThat(MinecraftFrameItemPort.exactInventoryConsumption(
                before, List.of(stack(63, 7), stack(19, 7), before.get(2)), 0)).isFalse();
        assertThat(MinecraftFrameItemPort.exactInventoryConsumption(before, before, 0)).isFalse();
        assertThat(MinecraftFrameItemPort.exactConsumedStack(stack(1, 7), StackFingerprint.EMPTY)).isTrue();
        assertThat(MinecraftFrameItemPort.exactConsumedStack(stack(64, 7), stack(62, 7))).isFalse();
        assertThat(MinecraftFrameItemPort.exactConsumedStack(stack(64, 7), stack(64, 7))).isFalse();
    }

    @Test
    void removeSelectsOnlyAnEmptyHotbarSlotAndInsertRefusesAmbiguousComponents() {
        var holder = Items.DIRT.builtInRegistryHolder();
        if (!holder.areComponentsBound()) holder.bindComponents(DataComponentMap.builder()
                .set(DataComponents.MAX_STACK_SIZE, 64).build());
        var plain = new ItemStack(Items.DIRT, 64);
        var second = plain.copyWithCount(2);
        var named = plain.copy();
        named.set(DataComponents.CUSTOM_NAME, Component.literal("kept name"));
        assertThat(MinecraftFrameItemPort.chooseHotbar(List.of(plain, ItemStack.EMPTY),
                FrameItemPort.Mode.REMOVE, "minecraft:stone")).isEqualTo(1);
        assertThat(MinecraftFrameItemPort.chooseHotbar(List.of(plain, second),
                FrameItemPort.Mode.REMOVE, "minecraft:stone")).isEqualTo(-1);
        assertThat(MinecraftFrameItemPort.chooseHotbar(List.of(plain, second),
                FrameItemPort.Mode.INSERT, "minecraft:dirt")).isZero();
        assertThat(MinecraftFrameItemPort.chooseHotbar(List.of(plain, named),
                FrameItemPort.Mode.INSERT, "minecraft:dirt")).isEqualTo(-2);
        var outsideHotbar = new ArrayList<>(java.util.Collections.nCopies(9, ItemStack.EMPTY));
        outsideHotbar.add(plain);
        assertThat(MinecraftFrameItemPort.chooseHotbar(outsideHotbar,
                FrameItemPort.Mode.INSERT, "minecraft:dirt")).isEqualTo(-1);
    }

    @Test
    void onlyNormalExactEntityDispatchIsWiredBehindFreshFrontAndHandGates() throws Exception {
        var node = new ClassNode();
        try (var stream = getClass().getResourceAsStream(
                "/dev/aod/mcmcp/routine/MinecraftFrameItemPort.class")) {
            new ClassReader(java.util.Objects.requireNonNull(stream)).accept(node, 0);
        }
        assertThat(calls(node, "dispatch")).containsSubsequence(
                "dev/aod/mcmcp/routine/MinecraftFrameItemPort#safetyFailure",
                "dev/aod/mcmcp/routine/MinecraftFrameItemPort#displayFailure",
                "dev/aod/mcmcp/routine/MinecraftFrameItemPort#handReady",
                "dev/aod/mcmcp/routine/MinecraftFrameItemPort#exactCrosshair",
                "net/minecraft/client/multiplayer/MultiPlayerGameMode#attack",
                "net/minecraft/client/multiplayer/MultiPlayerGameMode#interact")
                .noneMatch(call -> call.endsWith("#useItem") || call.endsWith("#useItemOn")
                        || call.endsWith("#handleContainerInput") || call.endsWith("#send"));
        assertThat(calls(node, "displayFailure")).contains(
                "dev/aod/mcmcp/agent/observation/OmnidirectionalObserver#currentFrameDisplay",
                "net/minecraft/client/player/LocalPlayer#isWithinEntityInteractionRange",
                "net/minecraft/client/player/LocalPlayer#isWithinAttackRange");
        assertThat(calls(node, "sample")).contains(
                "dev/aod/mcmcp/runtime/HotbarPayloadSyncSignals#bindAndSnapshot",
                "dev/aod/mcmcp/routine/MinecraftFrameItemPort#exactConsumedStack",
                "dev/aod/mcmcp/routine/MinecraftFrameItemPort#exactInventoryConsumption");
    }

    private static StackFingerprint stack(int count, int hash) {
        return new StackFingerprint("minecraft:stone", count, hash);
    }

    private static List<String> calls(ClassNode node, String name) {
        var calls = new ArrayList<String>();
        for (var method : node.methods) {
            if (!method.name.equals(name)) continue;
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call) calls.add(call.owner + "#" + call.name);
            }
        }
        return calls;
    }
}
