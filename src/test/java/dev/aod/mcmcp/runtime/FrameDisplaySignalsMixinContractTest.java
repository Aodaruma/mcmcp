package dev.aod.mcmcp.runtime;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class FrameDisplaySignalsMixinContractTest {
    @Test
    void entityDataAndRemovalEvidenceComeOnlyAfterTheInboundClientThreadHandlers() throws Exception {
        var node = readClass("/dev/aod/mcmcp/mixin/client/ClientPacketListenerMixin.class");
        var data = method(node, "mcmcp$frameDisplayPacket");
        var removal = method(node, "mcmcp$removedFrameEntities");
        assertTailHook(data, "handleSetEntityData");
        assertTailHook(removal, "handleRemoveEntities");
        assertThat(calls(data)).contains("dev/aod/mcmcp/runtime/FrameDisplaySyncSignals#onEntityData");
        assertThat(calls(removal)).contains("dev/aod/mcmcp/runtime/FrameDisplaySyncSignals#remove");
        assertThat(calls(method(node, "mcmcp$closeReconciliationLevel")))
                .contains("dev/aod/mcmcp/runtime/FrameDisplaySyncSignals#closeLevel");

        var ledger = readClass("/dev/aod/mcmcp/runtime/FrameDisplaySyncSignals.class");
        var ledgerCalls = ledger.methods.stream().flatMap(value -> calls(value).stream()).toList();
        assertThat(ledgerCalls).doesNotContain(
                "net/minecraft/world/entity/decoration/ItemFrame#getItem",
                "net/minecraft/world/entity/decoration/ItemFrame#getRotation",
                "net/minecraft/world/entity/decoration/ItemFrame#onSyncedDataUpdated");
    }

    @Test
    void requiredAccessorContainsOnlyTheTwoRenderedPacketFields() throws Exception {
        try (var stream = getClass().getResourceAsStream("/mcmcp.mixins.json")) {
            assertThat(stream).isNotNull();
            assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                    .contains("\"required\": true", "\"client.ItemFrameDataAccessor\"");
        }
        var accessor = readClass("/dev/aod/mcmcp/mixin/client/ItemFrameDataAccessor.class");
        assertThat(accessor.methods.stream().map(method -> method.name))
                .containsExactlyInAnyOrder("mcmcp$itemData", "mcmcp$rotationData");
    }

    private static void assertTailHook(MethodNode method, String target) {
        AnnotationNode inject = Arrays.asList(method.visibleAnnotations, method.invisibleAnnotations).stream()
                .filter(values -> values != null).flatMap(java.util.Collection::stream)
                .filter(value -> value.desc.endsWith("/Inject;")).findFirst().orElseThrow();
        assertThat(inject.values.toString()).contains(target, "require, 1", "expect, 1");
        for (int i = 0; i < inject.values.size(); i += 2) {
            if ("at".equals(inject.values.get(i))) {
                var at = (java.util.List<?>) inject.values.get(i + 1);
                assertThat(((AnnotationNode) at.getFirst()).values.toString()).contains("TAIL");
                return;
            }
        }
        throw new AssertionError("missing injection point");
    }

    private static ClassNode readClass(String path) throws Exception {
        var node = new ClassNode();
        try (var stream = FrameDisplaySignalsMixinContractTest.class.getResourceAsStream(path)) {
            assertThat(stream).isNotNull();
            new ClassReader(stream).accept(node, 0);
        }
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow();
    }

    private static ArrayList<String> calls(MethodNode method) {
        var calls = new ArrayList<String>();
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) calls.add(call.owner + "#" + call.name);
        }
        return calls;
    }
}
