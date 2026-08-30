package dev.aod.mcmcp.runtime;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScreenOwnershipContractTest {
    @Test
    void requiredMixinHooksExactPostHandoffOpenAndInboundFullContentMethods() throws Exception {
        var node = classNode(
                "/dev/aod/mcmcp/mixin/client/ClientContainerSignalsMixin.class");

        var open = method(node, "mcmcp$expectedContainerOpen");
        var openInject = annotation("Lorg/spongepowered/asm/mixin/injection/Inject;", open);
        assertThat(openInject.values.toString())
                .contains("handleOpenScreen")
                .contains("require, 1")
                .contains("expect, 1");
        assertThat(atValues(openInject)).contains("MenuScreens;create");

        var full = annotation("Lorg/spongepowered/asm/mixin/injection/Inject;",
                method(node, "mcmcp$fullContainerContent"));
        assertThat(full.values.toString())
                .contains("handleContainerContent")
                .contains("require, 1")
                .contains("expect, 1");
        assertThat(atValues(full)).contains("TAIL");

        var merchant = annotation("Lorg/spongepowered/asm/mixin/injection/Inject;",
                method(node, "mcmcp$merchantOffers"));
        assertThat(merchant.values.toString())
                .contains("handleMerchantOffers")
                .contains("require, 1")
                .contains("expect, 1");
        assertThat(atValues(merchant)).contains("TAIL");

        var close = annotation("Lorg/spongepowered/asm/mixin/injection/Inject;",
                method(node, "mcmcp$serverContainerClose"));
        assertThat(close.values.toString())
                .contains("handleContainerClose")
                .contains("require, 1")
                .contains("expect, 1");
        assertThat(atValues(close)).contains("clientSideCloseContainer");
    }

    @Test
    void requiredClientMixinConfigIncludesContainerSignals() throws Exception {
        try (var stream = getClass().getResourceAsStream("/mcmcp.mixins.json")) {
            assertThat(stream).isNotNull();
            String config = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(config).contains("\"required\": true");
            assertThat(config).contains("\"client.ClientContainerSignalsMixin\"");
        }
    }

    @Test
    void packetBridgeFingerprintsPayloadsAndNeverSamplesPredictedMenuItemValues() throws Exception {
        var node = classNode(
                "/dev/aod/mcmcp/mixin/client/ClientContainerSignalsMixin.class");
        var calls = new ArrayList<String>();
        for (var method : node.methods) {
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call) {
                    calls.add(call.owner + "#" + call.name);
                }
            }
        }

        assertThat(calls)
                .contains("dev/aod/mcmcp/runtime/ContainerSyncSignals$StackFingerprint"
                        + "#fromServerPacket")
                .doesNotContain(
                        "net/minecraft/world/inventory/AbstractContainerMenu#getItems",
                        "net/minecraft/world/inventory/AbstractContainerMenu#getCarried",
                        "net/minecraft/world/inventory/Slot#getItem");
    }

    @Test
    void screenEventsStopOnOwnershipFailureAndGateOnlyTheExpectedOpening() throws Exception {
        var node = classNode("/dev/aod/mcmcp/McmcpMod.class");
        var ownership = classNode(
                "/dev/aod/mcmcp/runtime/ScreenOwnershipSignals.class");

        assertThat(invocations(method(node, "onScreenOpening")))
                .containsSubsequence(
                        "dev/aod/mcmcp/runtime/ScreenOwnershipSignals#allowScreenOpening",
                        "dev/aod/mcmcp/runtime/McmcpRuntime#onScreenOwnershipFailure");
        assertThat(invocations(method(node, "onScreenClosing")))
                .contains("dev/aod/mcmcp/runtime/ScreenOwnershipSignals#onScreenClosing");
        assertThat(invocations(method(ownership, "allowScreenOpening")))
                .contains("dev/aod/mcmcp/runtime/ScreenOwnershipSignals$Core#failIfActive");
        assertThat(node.methods.stream().map(method -> method.name).toList())
                .doesNotContain("onKeyInput", "onMouseButtonInput", "onMouseScrollInput");
    }

    @Test
    void ownershipCleanupContainsNoSyntheticClickDispatchPath() throws Exception {
        var node = classNode(
                "/dev/aod/mcmcp/runtime/ScreenOwnershipSignals.class");
        var calls = node.methods.stream()
                .flatMap(method -> invocations(method).stream())
                .toList();

        assertThat(calls).noneMatch(call ->
                call.contains("handleContainerInput")
                        || call.contains("ServerboundContainerClickPacket")
                        || call.contains("#clicked"));
    }

    private static ClassNode classNode(String resource) throws Exception {
        var node = new ClassNode();
        try (var stream = ScreenOwnershipContractTest.class.getResourceAsStream(resource)) {
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

    private static AnnotationNode annotation(String descriptor, MethodNode method) {
        for (var annotations : java.util.Arrays.asList(
                method.visibleAnnotations, method.invisibleAnnotations)) {
            if (annotations == null) {
                continue;
            }
            var match = annotations.stream().filter(value -> value.desc.equals(descriptor))
                    .findFirst();
            if (match.isPresent()) {
                return match.orElseThrow();
            }
        }
        throw new AssertionError("missing annotation " + descriptor);
    }

    private static List<String> invocations(MethodNode method) {
        var calls = new ArrayList<String>();
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                calls.add(call.owner + "#" + call.name);
            }
        }
        return List.copyOf(calls);
    }

    private static String atValues(AnnotationNode inject) {
        for (int index = 0; index < inject.values.size(); index += 2) {
            if (!"at".equals(inject.values.get(index))) {
                continue;
            }
            var values = (List<?>) inject.values.get(index + 1);
            return values.stream()
                    .map(AnnotationNode.class::cast)
                    .map(annotation -> annotation.values.toString())
                    .reduce("", (left, right) -> left + right);
        }
        throw new AssertionError("missing @At");
    }
}
