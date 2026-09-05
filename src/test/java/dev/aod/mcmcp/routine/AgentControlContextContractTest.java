package dev.aod.mcmcp.routine;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentControlContextContractTest {
    @Test
    void actionAdaptersDoNotTreatOsFocusOrMouseGrabAsAgentSafetyFacts() throws Exception {
        for (var resource : List.of(
                "/dev/aod/mcmcp/routine/MinecraftSemanticActionPort.class",
                "/dev/aod/mcmcp/routine/MinecraftApplyBlockPlanPort.class",
                "/dev/aod/mcmcp/routine/MinecraftStationaryBreakPort.class",
                "/dev/aod/mcmcp/routine/MinecraftPhaseFiveWorldPort.class")) {
            var calls = invocations(classNode(resource));

            assertThat(calls)
                    .as(resource)
                    .noneMatch(call -> call.endsWith("#isWindowActive"))
                    .noneMatch(call -> call.endsWith("#isMouseGrabbed"))
                    .anyMatch(call -> call.endsWith("#isPaused"))
                    .anyMatch(call -> call.endsWith("#isUsingItem"))
                    .contains("dev/aod/mcmcp/client/AgentScreenPolicy#allowsWorldInput")
                    .anyMatch(call -> call.endsWith("#getPlayerMode"));
        }
    }

    @Test
    void boundedInputAndMenuPreparationUseTheSameChatPolicy() throws Exception {
        for (var resource : List.of(
                "/dev/aod/mcmcp/runtime/McmcpRuntime.class",
                "/dev/aod/mcmcp/client/AgentUseInputChannel.class",
                "/dev/aod/mcmcp/routine/MinecraftPillarUpPort.class",
                "/dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort.class",
                "/dev/aod/mcmcp/routine/MinecraftKnownFurnacePort.class",
                "/dev/aod/mcmcp/routine/MinecraftKnownBrewingPort.class")) {
            assertThat(invocations(classNode(resource))).as(resource)
                    .contains("dev/aod/mcmcp/client/AgentScreenPolicy#allowsWorldInput");
        }
        var runtime = classNode("/dev/aod/mcmcp/runtime/McmcpRuntime.class");
        var boundedSafety = runtime.methods.stream()
                .filter(method -> method.name.equals("boundedInputUnsafeReason"))
                .findFirst().orElseThrow();
        assertThat(java.util.Arrays.stream(boundedSafety.instructions.toArray())
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .map(call -> call.owner + "#" + call.name).toList())
                .contains("dev/aod/mcmcp/client/AgentScreenPolicy#allowsWorldInput");
    }

    private static ClassNode classNode(String resource) throws Exception {
        var node = new ClassNode();
        try (var stream = AgentControlContextContractTest.class.getResourceAsStream(resource)) {
            assertThat(stream).isNotNull();
            new ClassReader(stream).accept(node, 0);
        }
        return node;
    }

    private static List<String> invocations(ClassNode node) {
        var calls = new ArrayList<String>();
        for (var method : node.methods) {
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call) {
                    calls.add(call.owner + "#" + call.name);
                }
            }
        }
        return List.copyOf(calls);
    }
}
