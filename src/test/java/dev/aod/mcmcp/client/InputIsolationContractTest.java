package dev.aod.mcmcp.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.aod.mcmcp.runtime.AutomationUiSnapshot;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.PreeditEvent;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InputIsolationContractTest {
    @Test
    void onlyActiveEscPressStopsAndStillPassesToVanilla() {
        assertThat(InputIsolationController.keyDecision(
                AutomationUiSnapshot.State.OFF,
                false,
                GLFW.GLFW_KEY_W,
                InputConstants.PRESS))
                .isEqualTo(InputIsolationController.KeyDecision.PASS);

        var escapePress = InputIsolationController.keyDecision(
                AutomationUiSnapshot.State.AGENT,
                true,
                InputConstants.KEY_ESCAPE,
                InputConstants.PRESS);
        assertThat(escapePress)
                .isEqualTo(InputIsolationController.KeyDecision.EMERGENCY_STOP);
        assertThat(escapePress.cancelsVanilla()).isFalse();

        assertThat(InputIsolationController.keyDecision(
                AutomationUiSnapshot.State.AGENT,
                true,
                InputConstants.KEY_ESCAPE,
                InputConstants.RELEASE))
                .isEqualTo(InputIsolationController.KeyDecision.BLOCK);
        assertThat(InputIsolationController.keyDecision(
                AutomationUiSnapshot.State.RECOVERING,
                true,
                GLFW.GLFW_KEY_W,
                InputConstants.PRESS))
                .isEqualTo(InputIsolationController.KeyDecision.BLOCK);
        assertThat(InputIsolationController.KeyDecision.BLOCK.cancelsVanilla()).isTrue();
    }

    @Test
    void readyEscPassesThroughWithoutStopping() {
        var readyEscape = InputIsolationController.keyDecision(
                AutomationUiSnapshot.State.READY,
                false,
                InputConstants.KEY_ESCAPE,
                InputConstants.PRESS);
        assertThat(readyEscape).isEqualTo(InputIsolationController.KeyDecision.PASS);
        assertThat(readyEscape.cancelsVanilla()).isFalse();

        for (var state : List.of(
                AutomationUiSnapshot.State.OFF, AutomationUiSnapshot.State.FAULT)) {
            assertThat(InputIsolationController.keyDecision(
                    state, false, InputConstants.KEY_ESCAPE, InputConstants.PRESS))
                    .isEqualTo(InputIsolationController.KeyDecision.PASS);
        }
    }

    @Test
    void mixinsTargetTheExactPrivatePhysicalInputEntryPoints() throws Exception {
        assertPrivateMethod(KeyboardHandler.class, "keyPress",
                long.class, int.class, KeyEvent.class);
        assertPrivateMethod(KeyboardHandler.class, "charTyped",
                long.class, CharacterEvent.class);
        assertPrivateMethod(KeyboardHandler.class, "preeditCallback",
                long.class, PreeditEvent.class);
        assertPrivateMethod(MouseHandler.class, "turnPlayer", double.class);

        assertRequiredHeadInjection(
                "/dev/aod/mcmcp/mixin/client/KeyboardHandlerMixin.class",
                "mcmcp$interceptKeyPress",
                "keyPress(JILnet/minecraft/client/input/KeyEvent;)V",
                "dev/aod/mcmcp/client/InputIsolationController#interceptKeyPress");
        assertRequiredHeadInjection(
                "/dev/aod/mcmcp/mixin/client/KeyboardHandlerMixin.class",
                "mcmcp$interceptCharacter",
                "charTyped(JLnet/minecraft/client/input/CharacterEvent;)V",
                "dev/aod/mcmcp/client/InputIsolationController#interceptTextInput");
        assertRequiredHeadInjection(
                "/dev/aod/mcmcp/mixin/client/KeyboardHandlerMixin.class",
                "mcmcp$interceptPreedit",
                "preeditCallback(JLnet/minecraft/client/input/PreeditEvent;)V",
                "dev/aod/mcmcp/client/InputIsolationController#interceptTextInput");
        assertRequiredHeadInjection(
                "/dev/aod/mcmcp/mixin/client/MouseHandlerMixin.class",
                "mcmcp$interceptPhysicalTurn",
                "turnPlayer(D)V",
                "dev/aod/mcmcp/client/InputIsolationController#interceptMouseTurn");
    }

    @Test
    void requiredMixinConfigContainsBothInputGuards() throws Exception {
        try (var stream = getClass().getResourceAsStream("/mcmcp.mixins.json")) {
            assertThat(stream).isNotNull();
            var config = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(config)
                    .contains("\"required\": true")
                    .contains("\"client.KeyboardHandlerMixin\"")
                    .contains("\"client.MouseHandlerMixin\"");
        }
    }

    @Test
    void preTickReleasesPhysicalMappingsBeforeRuntimeReassertsOwnedInput() throws Exception {
        var node = classNode("/dev/aod/mcmcp/McmcpMod.class");
        assertThat(invocations(method(node, "onPreTick")))
                .containsSubsequence(
                        "dev/aod/mcmcp/client/InputIsolationController#onClientPreTick",
                        "dev/aod/mcmcp/runtime/McmcpRuntime#onPreTick");
    }

    @Test
    void runtimeRetriesAnUnconfirmedReleaseBeforeAnyActionCanTick() throws Exception {
        var runtime = classNode("/dev/aod/mcmcp/runtime/McmcpRuntime.class");

        assertThat(invocations(method(runtime, "onPreTick")))
                .containsSubsequence(
                        "dev/aod/mcmcp/runtime/McmcpRuntime#retryPendingAgentInputRelease",
                        "dev/aod/mcmcp/runtime/McmcpRuntime#tickAgentAction");
        assertThat(invocations(method(runtime, "retryPendingAgentInputRelease")))
                .contains("dev/aod/mcmcp/runtime/McmcpRuntime#publishAgentTerminal");
    }

    @Test
    void pickupConfirmationRunsAfterHardBudgetsAndBeforeAChangingWitnessCanRejectIt()
            throws Exception {
        var runtime = classNode("/dev/aod/mcmcp/runtime/McmcpRuntime.class");

        assertThat(invocations(method(runtime, "tickAgentAction")))
                .containsSubsequence(
                        "dev/aod/mcmcp/runtime/McmcpRuntime#activeElapsedNanos",
                        "dev/aod/mcmcp/agent/dsl/ActionDsl$Budget#maxTicks",
                        "dev/aod/mcmcp/runtime/McmcpRuntime#pickupInventoryIncreased",
                        "dev/aod/mcmcp/agent/action/AgentActionStore#recordTick",
                        "dev/aod/mcmcp/agent/action/AgentPrimitivePlanner#visibleItemPickupCellCurrent");
    }

    @Test
    void terminalActionFailureKeepsLocalAuthorizationIndependentOfRetryability() throws Exception {
        var runtime = classNode("/dev/aod/mcmcp/runtime/McmcpRuntime.class");

        assertThat(invocations(method(runtime, "failAgentAction")))
                .containsSubsequence(
                        "dev/aod/mcmcp/runtime/McmcpRuntime#releaseAgentControl",
                        "dev/aod/mcmcp/runtime/McmcpRuntime#publishAgentTerminal",
                        "dev/aod/mcmcp/runtime/McmcpRuntime#returnControlReady")
                .doesNotContain("dev/aod/mcmcp/runtime/McmcpRuntime#finishAgentControlReady")
                .doesNotContain("dev/aod/mcmcp/runtime/McmcpRuntime#closeAgentControl");
    }

    @Test
    void everyActiveActionTerminalPathReleasesInputBeforePublishingTerminalState()
            throws Exception {
        var runtime = classNode("/dev/aod/mcmcp/runtime/McmcpRuntime.class");

        assertThat(invocations(method(runtime, "cancelAgentAction")))
                .containsSubsequence(
                        "dev/aod/mcmcp/runtime/McmcpRuntime#releaseAgentControl",
                        "dev/aod/mcmcp/runtime/McmcpRuntime#publishAgentTerminal",
                        "dev/aod/mcmcp/runtime/McmcpRuntime#returnControlReady");
        assertThat(invocations(method(runtime, "advanceAgentProgram")))
                .containsSubsequence(
                        "dev/aod/mcmcp/runtime/McmcpRuntime#releaseAgentControl",
                        "dev/aod/mcmcp/runtime/McmcpRuntime#publishAgentTerminal",
                        "dev/aod/mcmcp/runtime/McmcpRuntime#returnControlReady");
        assertThat(invocations(method(runtime, "stopActiveRoutineForEmergency")))
                .containsSubsequence(
                        "dev/aod/mcmcp/runtime/McmcpRuntime#releaseAgentControl",
                        "dev/aod/mcmcp/runtime/McmcpRuntime#publishAgentTerminal");
        assertThat(invocations(method(runtime, "clearAgentSessionState")))
                .containsSubsequence(
                        "dev/aod/mcmcp/runtime/McmcpRuntime#closeAgentControl",
                        "dev/aod/mcmcp/runtime/McmcpRuntime#publishAgentTerminal")
                .doesNotContain("dev/aod/mcmcp/agent/action/AgentActionStore#clear");
        assertThat(invocations(method(runtime, "publishAgentTerminal")))
                .contains(
                        "dev/aod/mcmcp/agent/action/AgentActionStore#succeed",
                        "dev/aod/mcmcp/agent/action/AgentActionStore#terminateActive",
                        "dev/aod/mcmcp/agent/action/AgentActionStore#cancel");
    }

    @Test
    void everyAgentHoldAndReplanBoundaryChecksTheReleaseResult() throws Exception {
        var runtime = classNode("/dev/aod/mcmcp/runtime/McmcpRuntime.class");
        String checkedRelease =
                "dev/aod/mcmcp/runtime/McmcpRuntime#releaseAgentInputsForHold";

        assertThat(invocations(method(runtime, "onPauseChanged"))).contains(checkedRelease);
        assertThat(invocations(method(runtime, "tickAgentAction"))).contains(checkedRelease);
        assertThat(invocations(method(runtime, "bindAgentPrimitive"))).contains(checkedRelease);
        assertThat(invocations(method(runtime, "retryAgentMutationAim"))).contains(checkedRelease);
        assertThat(invocations(method(runtime, "requestAgentReplan"))).contains(checkedRelease);
        assertThat(invocations(method(runtime, "tickActiveRoutine"))).contains(checkedRelease);
    }

    @Test
    void legacyFunctionKeyControlsAreAbsent() throws Exception {
        var mod = classNode("/dev/aod/mcmcp/McmcpMod.class");
        assertThat(mod.methods.stream().flatMap(method -> invocations(method).stream()).toList())
                .noneMatch(call -> call.contains("McmcpKeyBindings"))
                .noneMatch(call -> call.contains("RegisterKeyMappingsEvent"));

        for (String language : List.of("en_us", "ja_jp")) {
            try (var stream = getClass().getResourceAsStream(
                    "/assets/mcmcp/lang/" + language + ".json")) {
                assertThat(stream).isNotNull();
                var translations = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                assertThat(translations)
                        .doesNotContain("key.mcmcp.emergency_stop")
                        .doesNotContain("key.mcmcp.toggle_lock");
            }
        }
    }

    private static void assertPrivateMethod(
            Class<?> type, String name, Class<?>... parameters) throws Exception {
        assertThat(Modifier.isPrivate(type.getDeclaredMethod(name, parameters).getModifiers()))
                .isTrue();
    }

    private static void assertRequiredHeadInjection(
            String resource,
            String hookName,
            String target,
            String controllerCall) throws Exception {
        var hook = method(classNode(resource), hookName);
        var inject = annotation(hook);
        assertThat(inject.values.toString())
                .contains(target)
                .contains("cancellable, true")
                .contains("require, 1")
                .contains("expect, 1");
        assertThat(atValues(inject)).contains("HEAD");
        assertThat(invocations(hook)).contains(controllerCall);
    }

    private static ClassNode classNode(String resource) throws Exception {
        var node = new ClassNode();
        try (var stream = InputIsolationContractTest.class.getResourceAsStream(resource)) {
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
            if ("at".equals(inject.values.get(index))) {
                return ((List<?>) inject.values.get(index + 1)).stream()
                        .map(AnnotationNode.class::cast)
                        .map(annotation -> annotation.values.toString())
                        .reduce("", (left, right) -> left + right);
            }
        }
        throw new AssertionError("missing @At");
    }
}
