package dev.aodaruma.craftagent.runtime;

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

class SleepSemanticSignalsMixinContractTest {
    @Test
    void systemChatHookRunsAfterHandlingOnTheClientThread() throws Exception {
        var node = classNode();
        var method = method(node, "craftagent$sleepSemanticSignal");
        var inject = annotation(method);

        assertThat(inject.values.toString())
                .contains("handleSystemChat")
                .contains("require, 1")
                .contains("expect, 1");
        assertThat(atValues(inject)).contains("TAIL");

        var calls = invocations(method);
        assertThat(calls)
                .contains(
                        "net/minecraft/network/protocol/game/ClientboundSystemChatPacket#content",
                        "dev/aodaruma/craftagent/runtime/SleepSemanticSignals#onSystemChat")
                .doesNotContain(
                        "net/minecraft/network/chat/Component#getString",
                        "net/minecraft/network/chat/Component#getVisualOrderText");
    }

    @Test
    void requiredMixinConfigIncludesSleepSemanticSignals() throws Exception {
        try (var stream = getClass().getResourceAsStream("/craftagent.mixins.json")) {
            assertThat(stream).isNotNull();
            var config = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(config)
                    .contains("\"required\": true")
                    .contains("\"client.ClientSleepSemanticSignalsMixin\"");
        }
    }

    private static ClassNode classNode() throws Exception {
        var node = new ClassNode();
        try (var stream = SleepSemanticSignalsMixinContractTest.class.getResourceAsStream(
                "/dev/aodaruma/craftagent/mixin/client/ClientSleepSemanticSignalsMixin.class")) {
            assertThat(stream).isNotNull();
            new ClassReader(stream).accept(node, 0);
        }
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream()
                .filter(method -> method.name.equals(name))
                .findFirst().orElseThrow();
    }

    private static AnnotationNode annotation(MethodNode method) {
        return Arrays.asList(method.visibleAnnotations, method.invisibleAnnotations).stream()
                .filter(annotations -> annotations != null)
                .flatMap(java.util.Collection::stream)
                .filter(value -> value.desc.equals(
                        "Lorg/spongepowered/asm/mixin/injection/Inject;"))
                .findFirst().orElseThrow();
    }

    private static ArrayList<String> invocations(MethodNode method) {
        var calls = new ArrayList<String>();
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                calls.add(call.owner + "#" + call.name);
            }
        }
        return calls;
    }

    private static String atValues(AnnotationNode inject) {
        for (int index = 0; index < inject.values.size(); index += 2) {
            if ("at".equals(inject.values.get(index))) {
                return ((java.util.List<?>) inject.values.get(index + 1)).stream()
                        .map(AnnotationNode.class::cast)
                        .map(annotation -> annotation.values.toString())
                        .reduce("", (left, right) -> left + right);
            }
        }
        throw new AssertionError("missing @At");
    }
}
