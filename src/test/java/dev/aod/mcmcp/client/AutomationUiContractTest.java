package dev.aod.mcmcp.client;

import dev.aod.mcmcp.runtime.AutomationUiSnapshot;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AutomationUiContractTest {
    @Test
    void yellowExecutionBorderOnlyCoversAgentOwnedModes() throws Exception {
        assertThat(AutomationIndicatorController.executionBorderVisible(
                AutomationUiSnapshot.State.AGENT)).isTrue();
        assertThat(AutomationIndicatorController.executionBorderVisible(
                AutomationUiSnapshot.State.RECOVERING)).isTrue();
        assertThat(List.of(
                AutomationUiSnapshot.State.OFF,
                AutomationUiSnapshot.State.READY,
                AutomationUiSnapshot.State.FAULT))
                .allMatch(state -> !AutomationIndicatorController.executionBorderVisible(state));

        assertThat(invocations(method(
                classNode("/dev/aod/mcmcp/client/AutomationIndicatorController.class"),
                "renderHud")))
                .contains("dev/aod/mcmcp/client/AutomationIndicatorController#drawExecutionBorder");
        assertThat(invocations(method(
                classNode("/dev/aod/mcmcp/client/AutomationIndicatorController$IndicatorButton.class"),
                "extractContents")))
                .contains("dev/aod/mcmcp/client/AutomationIndicatorController#drawExecutionBorder");
    }

    @Test
    void screenButtonLabelsStayCompactAndPutActionsInTooltips() throws Exception {
        for (String language : List.of("en_us", "ja_jp")) {
            try (var stream = getClass().getResourceAsStream(
                    "/assets/mcmcp/lang/" + language + ".json")) {
                assertThat(stream).isNotNull();
                var translations = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                assertThat(translations).doesNotContain("[").doesNotContain("]");
            }
        }
    }

    @Test
    void hudUsesAboveAllLayerAndBothConfiguredOffsets() throws Exception {
        var controller = classNode(
                "/dev/aod/mcmcp/client/AutomationIndicatorController.class");
        assertThat(invocations(method(controller, "onRegisterGuiLayers")))
                .contains("net/neoforged/neoforge/client/event/RegisterGuiLayersEvent"
                        + "#registerAboveAll");
        assertThat(invocations(method(controller, "renderHud")))
                .contains(
                        "dev/aod/mcmcp/client/McmcpClientConfig#hudOffsetX",
                        "dev/aod/mcmcp/client/McmcpClientConfig#hudOffsetY")
                .containsSubsequence(
                        "dev/aod/mcmcp/client/AutomationIndicatorController#drawAgentNotice",
                        "net/minecraft/client/gui/Gui#screen");
    }

    @Test
    void everyScreenGetsTheStatusButtonWithoutAWorldGuard() throws Exception {
        var controller = classNode(
                "/dev/aod/mcmcp/client/AutomationIndicatorController.class");
        var init = method(controller, "onScreenInit");
        assertThat(invocations(init))
                .contains(
                        "net/neoforged/neoforge/client/event/ScreenEvent$Init$Post#getScreen",
                        "net/neoforged/neoforge/client/event/ScreenEvent$Init$Post#addListener");
        assertThat(init.instructions)
                .noneMatch(instruction -> instruction instanceof JumpInsnNode);
        assertThat(init.instructions)
                .anyMatch(instruction -> instruction instanceof TypeInsnNode type
                        && type.desc.equals("net/minecraft/client/gui/screens/ChatScreen"));
    }

    @Test
    void clientConfigIsRegisteredByTheModContainer() throws Exception {
        var mod = classNode("/dev/aod/mcmcp/McmcpMod.class");
        assertThat(invocations(method(mod, "<init>")))
                .contains("net/neoforged/fml/ModContainer#registerConfig");
    }

    @Test
    void isolatedMouseInputCanOnlyDispatchTheMcmcpButtonAndIsThenCanceled()
            throws Exception {
        var controller = classNode(
                "/dev/aod/mcmcp/client/InputIsolationController.class");
        assertThat(invocations(method(controller, "onMouseButton")))
                .contains(
                        "dev/aod/mcmcp/client/AutomationIndicatorController"
                                + "#dispatchPrimaryClick",
                        "net/neoforged/neoforge/client/event/InputEvent$MouseButton$Pre"
                                + "#setCanceled")
                .noneMatch(call -> call.endsWith("#onScreenOwnershipFailure"));
        assertThat(invocations(method(controller, "onMouseScroll")))
                .contains("net/neoforged/neoforge/client/event/InputEvent$MouseScrollingEvent"
                        + "#setCanceled");
        assertThat(invocations(method(controller, "onScreenMouseDragged")))
                .contains("net/neoforged/neoforge/client/event/ScreenEvent$MouseDragged$Pre"
                        + "#setCanceled");
        assertThat(invocations(method(controller, "onScreenMouseScrolled")))
                .contains("net/neoforged/neoforge/client/event/ScreenEvent$MouseScrolled$Pre"
                        + "#setCanceled");
    }

    private static ClassNode classNode(String resource) throws Exception {
        var node = new ClassNode();
        try (var stream = AutomationUiContractTest.class.getResourceAsStream(resource)) {
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

    private static List<String> invocations(MethodNode method) {
        var calls = new ArrayList<String>();
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                calls.add(call.owner + "#" + call.name);
            }
        }
        return List.copyOf(calls);
    }
}
