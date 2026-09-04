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
                AutomationUiSnapshot.State.EVALUATING,
                AutomationUiSnapshot.State.CONSENT_PENDING,
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
                        "dev/aod/mcmcp/client/AutomationIndicatorController#drawActiveNotice",
                        "net/minecraft/client/gui/Gui#screen");
    }

    @Test
    void cyanEvaluationBorderIsDistinctFromYellowActionBorder() throws Exception {
        assertThat(AutomationIndicatorController.evaluationBorderVisible(
                AutomationUiSnapshot.State.EVALUATING)).isTrue();
        assertThat(List.of(
                AutomationUiSnapshot.State.OFF,
                AutomationUiSnapshot.State.READY,
                AutomationUiSnapshot.State.CONSENT_PENDING,
                AutomationUiSnapshot.State.AGENT,
                AutomationUiSnapshot.State.RECOVERING,
                AutomationUiSnapshot.State.FAULT))
                .allMatch(state -> !AutomationIndicatorController
                        .evaluationBorderVisible(state));

        var controller = classNode(
                "/dev/aod/mcmcp/client/AutomationIndicatorController.class");
        assertThat(invocations(method(controller, "renderHud")))
                .contains(
                        "dev/aod/mcmcp/client/AutomationIndicatorController"
                                + "#drawEvaluationBorder",
                        "dev/aod/mcmcp/client/AutomationIndicatorController"
                                + "#drawExecutionBorder");
        assertThat(invocations(method(
                classNode("/dev/aod/mcmcp/client/AutomationIndicatorController$IndicatorButton.class"),
                "extractContents")))
                .contains("dev/aod/mcmcp/client/AutomationIndicatorController"
                        + "#drawEvaluationBorder");
    }

    @Test
    void pendingConsentHasItsOwnBorderAndTranslatedGrantPrompt() throws Exception {
        assertThat(AutomationIndicatorController.consentBorderVisible(
                AutomationUiSnapshot.State.CONSENT_PENDING)).isTrue();
        for (String language : List.of("en_us", "ja_jp")) {
            try (var stream = getClass().getResourceAsStream(
                    "/assets/mcmcp/lang/" + language + ".json")) {
                assertThat(stream).isNotNull();
                var translations = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                assertThat(translations)
                        .contains("gui.mcmcp.automation.consent_pending")
                        .contains("gui.mcmcp.automation.tooltip.consent_pending")
                        .contains("gui.mcmcp.entity_attack_consent.title")
                        .contains("gui.mcmcp.entity_attack_consent.message")
                        .contains("gui.mcmcp.entity_attack_consent.grant")
                        .contains("gui.mcmcp.entity_attack_consent.cancel")
                        .doesNotContain("Grant one attack")
                        .doesNotContain("1回の攻撃を許可");
            }
        }
    }

    @Test
    void pendingConsentOpensALocalScreenAndOnlyItsPhysicalDispatchCanGrant()
            throws Exception {
        var controller = classNode(
                "/dev/aod/mcmcp/client/AutomationIndicatorController.class");
        assertThat(invocations(method(controller, "openEntityAttackConsentPrompt")))
                .contains("net/minecraft/client/Minecraft#setScreenAndShow");

        var prompt = classNode(
                "/dev/aod/mcmcp/client/AutomationIndicatorController$EntityAttackConsentScreen.class");
        assertThat(prompt.superName).isEqualTo("net/minecraft/client/gui/screens/ConfirmScreen");
        assertThat(invocations(method(prompt, "dispatchPhysicalPrimaryClick")))
                .contains("dev/aod/mcmcp/client/AutomationIndicatorController"
                        + "$EntityAttackConsentPromptSink#grantFromPhysicalPrimaryClick");
        assertThat(invocations(method(prompt, "keyPressed")))
                .doesNotContain("dev/aod/mcmcp/client/AutomationIndicatorController"
                        + "$EntityAttackConsentPromptSink#grantFromPhysicalPrimaryClick")
                .doesNotContain("net/minecraft/client/gui/components/Button#onPress");
        assertThat(invocations(method(prompt, "removed")))
                .contains("dev/aod/mcmcp/client/AutomationIndicatorController"
                        + "$EntityAttackConsentPromptSink#cancel");

        var runtime = classNode("/dev/aod/mcmcp/runtime/McmcpRuntime.class");
        assertThat(invocations(method(
                runtime, "requestEntityAttackConsentForCanonicalAction")))
                .contains("dev/aod/mcmcp/client/AutomationIndicatorController"
                        + "#openEntityAttackConsentPrompt");
        assertThat(runtime.methods).noneMatch(candidate -> candidate.name.equals(
                "grantEntityAttackConsentFromPhysicalStatusButtonClick"));
    }

    @Test
    void evaluationTranslationsCoverLabelTooltipAndShortNotice() throws Exception {
        for (String language : List.of("en_us", "ja_jp")) {
            try (var stream = getClass().getResourceAsStream(
                    "/assets/mcmcp/lang/" + language + ".json")) {
                assertThat(stream).isNotNull();
                var translations = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                assertThat(translations)
                        .contains("gui.mcmcp.automation.evaluating")
                        .contains("gui.mcmcp.automation.evaluating_notice")
                        .contains("gui.mcmcp.automation.tooltip.evaluating");
            }
        }
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
                                + "#dispatchConsentPromptPrimaryClick",
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
