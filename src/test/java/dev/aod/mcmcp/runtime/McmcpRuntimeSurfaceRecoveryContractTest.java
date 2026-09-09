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
    void recoverySummaryIsRecordedAfterCompleteChecksRatherThanOnFogAvailabilityAlone() throws Exception {
        assertThat(calls(ActionAdmission.class, "surfaceRecoveryFailure")).containsSubsequence(
                "ClientFogDistanceSignals#current", "SurfacePreflightRecovery#evaluate",
                "SurfacePreflightRecovery#noteMissing", "Consumer#accept")
                .doesNotContain("SurfacePreflightRecovery#noteRevalidated");
        assertThat(calls(ActionAdmission.class, "prepareAgentAction")).containsSubsequence(
                "SurfacePreflightRecovery#noteRevalidated", "ActionAdmission$PreparedAgentAction#<init>");
        assertThat(calls(ActionAdmission.class, "admissionFenceFailure")).containsSubsequence(
                "KnownBreakSafety#breakProgramPreconditionsCurrent", "ActionAdmission#rendererRecoveryRevalidated");
        assertThat(calls("bindAgentPrimitive")).containsSubsequence(
                "ActionBudgets#fitsRemainingBudget", "ActionAdmission#rendererRecoveryRevalidated");
        assertThat(calls("initialContainerOpenWitness")).containsSubsequence(
                "AgentPrimitivePlanner#requireKnownSurface", "ActionAdmission#rendererRecoveryRevalidated")
                .doesNotContain("MultiPlayerGameMode#useItemOn");
        assertThat(calls("commitAgentAction")).containsSubsequence(
                "AgentActionStore#reserve", "McmcpRuntime#publishRendererRecovery");
        assertThat(calls("publishRendererRecovery")).containsSubsequence(
                "SurfacePreflightRecovery#summary", "AgentActionStore$RendererRecoverySummary#missingStages",
                "AgentActionStore#recordRendererRecovery");
    }

    @Test
    void captureAndCommitKeepRecoveryAheadOfReservation() throws Exception {
        assertThat(calls(ActionAdmission.class, "captureAgentAdmission")).containsSubsequence(
                "SurfacePreflightRecovery#capture", "ActionAdmission#requireSurfaceRecoveryReady",
                "AgentObservations#agentPlanningFrame");
        assertThat(calls("commitAgentAction")).containsSubsequence(
                "ActionAdmission#admissionFenceFailure", "ClientCommandInbox$DeferControl#<init>",
                "McmcpRuntime#requireLiveCall", "LocalArmingState#beginAction", "AgentActionStore#reserve");
    }

    @Test
    void dispatchRepeatsTheFullFenceAndChargesWaitingBeforeTheFirstJit() throws Exception {
        assertThat(calls("tickAgentAction")).containsSubsequence(
                "McmcpRuntime#startAgentExecution", "McmcpRuntime#agentControlCurrent",
                "McmcpRuntime#tickAgentProgram");
        assertThat(calls("startAgentExecution")).containsSubsequence(
                "ActionAdmission#admissionFenceFailure", "SurfacePreflightRecovery#executionStartNanos",
                "AgentActionStore#markRunning", "AgentActionStore#recordAdmissionTicks");
        assertThat(calls("tickAgentProgram")).containsSubsequence(
                "AgentActionStore#recordTick", "McmcpRuntime#bindAgentPrimitive");
        assertThat(calls(ActionAdmission.class, "admissionFenceFailure")).containsSubsequence(
                "ActionAdmission#sameAdmissionSession", "ActionPlanning#playerPose",
                "Predicate#test", "AgentObservations#requireAgentMap",
                "ClientReconciliationSignals#bindAndSnapshot", "ActionPredicates#policySnapshot",
                "ActionPlanning#firstPrimitive", "ActionEvidence#routeDependenciesCurrent",
                "ActionAdmission#surfaceRecoveryFailure", "AgentObservations#agentPlanningFrame");
        assertThat(calls("bindAgentPrimitive")).containsSubsequence(
                "ActionAdmission#surfaceRecoveryFailure", "AgentObservations#requireAgentMap",
                "ClientReconciliationSignals#bindAndSnapshot", "AgentObservations#agentPlanningFrame",
                "ActionAdmission#analyzePrimitive",
                "ActionBudgets#firstRecoveredSurfacePrimitiveRemainingCost",
                "ActionBudgets#fitsRemainingBudget");
        assertThat(calls("initialContainerOpenWitness")).containsSubsequence(
                "ActionAdmission#sameAdmissionSession", "LocalArmingState$Snapshot#controlEpoch",
                "McmcpRuntime#multiplayerPolicyAllows", "AgentObservations#requireAgentMap",
                "ClientReconciliationSignals#bindAndSnapshot", "ActionEvidence#visualBarrierWorldRevision",
                "ActionAdmission#surfaceRecoveryFailure", "AgentObservations#agentPlanningFrame",
                "AgentPrimitivePlanner#requireKnownSurface");
    }

    @Test
    void admissionCallbacksBindToRuntimePolicyAndRecoveryLedger() throws Exception {
        var type = new ClassNode();
        try (var input = McmcpRuntime.class.getResourceAsStream("McmcpRuntime.class")) {
            assertThat(input).isNotNull();
            new ClassReader(input).accept(type, 0);
        }
        var constructor = type.methods.stream().filter(method -> method.name.equals("<init>"))
                .findFirst().orElseThrow();
        var targets = new ArrayList<String>();
        for (var instruction : constructor.instructions) {
            if (instruction instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode dynamic) {
                for (Object argument : dynamic.bsmArgs) {
                    if (argument instanceof org.objectweb.asm.Handle handle) {
                        targets.add(handle.getOwner() + "#" + handle.getName());
                    }
                }
            }
        }
        assertThat(targets).contains(
                "dev/aod/mcmcp/runtime/McmcpRuntime#multiplayerPolicyAllows",
                "dev/aod/mcmcp/runtime/McmcpRuntime#publishRendererRecovery");
    }

    private static List<String> calls(String name) throws Exception {
        return calls(McmcpRuntime.class, name);
    }

    private static List<String> calls(Class<?> owner, String name) throws Exception {
        var type = new ClassNode();
        try (var input = owner.getResourceAsStream(owner.getSimpleName() + ".class")) {
            assertThat(input).isNotNull();
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
