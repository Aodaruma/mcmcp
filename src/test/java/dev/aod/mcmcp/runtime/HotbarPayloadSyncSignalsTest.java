package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.runtime.ContainerSyncSignals.StackFingerprint;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HotbarPayloadSyncSignalsTest {
    @Test
    void allThreeInventoryHooksRecordPayloadBeforeTheLiveReconciliationSummary() throws Exception {
        var node = new ClassNode();
        try (var stream = getClass().getResourceAsStream(
                "/dev/aod/mcmcp/mixin/client/ClientPacketListenerMixin.class")) {
            new ClassReader(java.util.Objects.requireNonNull(stream)).accept(node, 0);
        }
        for (var methodName : List.of("mcmcp$playerInventorySync", "mcmcp$containerSlotSync",
                "mcmcp$containerContentSync")) {
            var calls = new ArrayList<String>();
            var method = node.methods.stream().filter(value -> value.name.equals(methodName))
                    .findFirst().orElseThrow();
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call) calls.add(call.owner + "#" + call.name);
            }
            assertThat(calls).containsSubsequence(
                    "dev/aod/mcmcp/runtime/ContainerSyncSignals$StackFingerprint#fromServerPacket",
                    "dev/aod/mcmcp/runtime/HotbarPayloadSyncSignals#onSlot",
                    "dev/aod/mcmcp/mixin/client/ClientPacketListenerMixin#rememberInventory");
            assertThat(calls).anyMatch(call -> call.startsWith("net/minecraft/network/protocol/game/")
                    && (call.endsWith("#contents") || call.endsWith("#getItem") || call.endsWith("#items")));
            assertThat(calls).noneMatch(call -> call.endsWith("#getSelectedItem"));
        }
    }

    @Test
    void keepsOnlyNinePacketSlotsAndNeverTreatsUnboundOrOtherSlotUpdatesAsAnAck() {
        var channel = new HotbarPayloadSyncSignals.SessionChannel();
        var stack = new StackFingerprint("minecraft:stone", 64, 9);
        channel.record(0, stack, 0);
        var session = new UUID(0, 1);
        assertThat(channel.bind(session).slots()).isEmpty();
        channel.record(0, stack, 1);
        var initial = channel.snapshot();
        channel.record(1, new StackFingerprint("minecraft:stone", 63, 9), 2);
        assertThat(channel.snapshot().revision()).isGreaterThan(initial.revision());
        assertThat(channel.snapshot().slots().get(0)).isEqualTo(initial.slots().get(0));
        for (int slot = -5; slot < 50; slot++) channel.record(slot, stack, 3);
        assertThat(channel.snapshot().slots()).hasSize(9).containsOnlyKeys(0, 1, 2, 3, 4, 5, 6, 7, 8);
        assertThat(initial.slots()).hasSize(1);
        assertThat(initial.slots().get(0).stack().count()).isEqualTo(64);
    }

    @Test
    void payloadComponentsAndObservedZeroSurviveButOldSessionEvidenceDoesNot() {
        var channel = new HotbarPayloadSyncSignals.SessionChannel();
        var first = new UUID(0, 1);
        channel.bind(first);
        channel.record(3, new StackFingerprint("minecraft:stone", 1, 123), 4);
        var before = channel.snapshot();
        channel.record(3, StackFingerprint.EMPTY, 5);
        var emptied = channel.snapshot().slots().get(3);
        assertThat(emptied.stack()).isEqualTo(StackFingerprint.EMPTY);
        assertThat(emptied.revision()).isGreaterThan(before.revision());
        assertThat(before.slots().get(3).stack().itemAndComponentsHash()).isEqualTo(123);
        assertThat(channel.bind(first).slots()).containsKey(3);
        var next = channel.bind(new UUID(0, 2));
        assertThat(next.slots()).isEmpty();
        assertThat(next.revision()).isZero();
    }
}
