package dev.aod.mcmcp.runtime;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Wiring guard: fixture recovery must remain on capture, reservation and actual dispatch paths. */
class McmcpRuntimeSurfaceRecoveryContractTest {
    @Test
    void captureAndCommitKeepRecoveryAheadOfReservation() throws Exception {
        assertThat(calls("captureAgentAdmission")).containsSubsequence(
                "SurfacePreflightRecovery#capture", "McmcpRuntime#requireSurfaceRecoveryReady",
                "McmcpRuntime#agentPlanningFrame");
        assertThat(calls("commitAgentAction")).containsSubsequence(
                "McmcpRuntime#admissionFenceFailure", "ClientCommandInbox$DeferControl#<init>",
                "McmcpRuntime#requireLiveCall", "LocalArmingState#beginAction", "AgentActionStore#reserve");
    }

    @Test
    void dispatchRepeatsTheFullFenceAndChargesWaitingBeforeTheFirstJit() throws Exception {
        assertThat(calls("tickAgentAction")).containsSubsequence(
                "McmcpRuntime#admissionFenceFailure", "SurfacePreflightRecovery#executionStartNanos",
                "AgentActionStore#markRunning", "AgentActionStore#recordAdmissionTicks",
                "McmcpRuntime#bindAgentPrimitive");
        assertThat(calls("admissionFenceFailure")).containsSubsequence(
                "McmcpRuntime#sameAdmissionSession", "McmcpRuntime#playerPose",
                "McmcpRuntime#multiplayerPolicyAllows", "McmcpRuntime#requireAgentMap",
                "ClientReconciliationSignals#bindAndSnapshot", "McmcpRuntime#policySnapshot",
                "McmcpRuntime#firstPrimitive", "McmcpRuntime#routeDependenciesCurrent",
                "McmcpRuntime#surfaceRecoveryFailure", "McmcpRuntime#agentPlanningFrame");
        assertThat(calls("bindAgentPrimitive")).containsSubsequence(
                "McmcpRuntime#surfaceRecoveryFailure", "McmcpRuntime#requireAgentMap",
                "ClientReconciliationSignals#bindAndSnapshot", "McmcpRuntime#agentPlanningFrame",
                "McmcpRuntime#analyzePrimitive", "McmcpRuntime#fitsRemainingBudget");
        assertThat(calls("initialContainerOpenWitness")).containsSubsequence(
                "McmcpRuntime#sameAdmissionSession", "LocalArmingState$Snapshot#controlEpoch",
                "McmcpRuntime#multiplayerPolicyAllows", "McmcpRuntime#requireAgentMap",
                "ClientReconciliationSignals#bindAndSnapshot", "McmcpRuntime#visualBarrierWorldRevision",
                "McmcpRuntime#surfaceRecoveryFailure", "McmcpRuntime#agentPlanningFrame",
                "AgentPrimitivePlanner#requireKnownSurface");
    }

    private static List<String> calls(String name) throws Exception {
        var type = new ClassNode();
        try (var input = McmcpRuntime.class.getResourceAsStream("/dev/aod/mcmcp/runtime/McmcpRuntime.class")) {
            new ClassReader(input).accept(type, 0);
        }
        var method = type.methods.stream().filter(value -> value.name.equals(name)).findFirst().orElseThrow();
        var calls = new ArrayList<String>();
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                calls.add(call.owner.substring(call.owner.lastIndexOf('/') + 1) + "#" + call.name);
            }
        }
        return calls;
    }
}
