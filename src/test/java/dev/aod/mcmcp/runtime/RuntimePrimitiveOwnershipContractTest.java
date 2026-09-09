package dev.aod.mcmcp.runtime;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 分割後も効果回収と入力解放がActionのterminal公開より先に行われることを検査する。 */
class RuntimePrimitiveOwnershipContractTest {
    @Test
    void cleanupStillDrainsMenuEffectsBeforeFishingCleanupAndTerminalPublication() throws Exception {
        assertThat(calls(McmcpRuntime.class, "closeAgentPrimitiveExecutor"))
                .containsSubsequence("MenuPrimitiveExecution#close", "FishingPrimitiveExecution#close");
        assertThat(calls(MenuPrimitiveExecution.class, "close"))
                .containsSubsequence(
                        "KnownContainerAttempt#close",
                        "KnownContainerAttempt#drainReleaseInteractionDelta",
                        "KnownContainerAttempt#drainEffectDeltas",
                        "MenuPrimitiveExecution#recordContainerEffects",
                        "KnownBrewingAttempt#close",
                        "KnownBrewingAttempt#drainReleaseInteractionDelta",
                        "KnownConstructionAttempt#close",
                        "KnownConstructionAttempt#drainEffectDeltas",
                        "MenuPrimitiveExecution#recordConstructionEffects",
                        "KnownPillarUpAttempt#close",
                        "KnownRedstoneIdentityAttempt#close");
        assertThat(calls(McmcpRuntime.class, "failAgentAction"))
                .containsSubsequence("McmcpRuntime#releaseAgentControl", "McmcpRuntime#publishAgentTerminal");
    }

    @Test
    void inspectAndTransferEvidencePrecedePrimitiveCompletion() throws Exception {
        assertThat(calls(MenuPrimitiveExecution.class, "tickAgentContainer"))
                .containsSubsequence(
                        "KnownContainerAttempt#tick",
                        "MenuPrimitiveExecution#recordContainerEffects",
                        "AgentActionStore#recordInteraction",
                        "KnownContainerAttempt#inspectionContents",
                        "AgentActionStore#recordContainerInspection",
                        "PrimitiveOutcome#succeeded")
                .doesNotContain("AgentActionStore#completeNode");
        assertThat(calls(McmcpRuntime.class, "applyPrimitiveOutcome"))
                .containsSubsequence("PrimitiveOutcome#failure", "McmcpRuntime#failAgentAction",
                        "AgentActionStore#completeNode", "McmcpRuntime#advanceAgentProgram");
    }

    @Test
    void fishingRetainsItsConsumedSessionAndUnknownEffectCleanup() throws Exception {
        assertThat(calls(FishingPrimitiveExecution.class, "tickAgentFishing"))
                .containsSubsequence("FishingSessionRefs#consume",
                        "PlayerInventoryEvidence#ownedFishingHook", "MultiPlayerGameMode#useItem",
                        "AgentActionStore#recordInteraction");
        assertThat(calls(FishingPrimitiveExecution.class, "releaseFishingAttempt"))
                .containsSubsequence("FishingPrimitiveExecution#recordUnknownFishingEffect",
                        "FishingSessionRefs#clear", "LocalArmingState#lock")
                .doesNotContain("FishingSessionRefs#consume", "FishingSessionRefs#issue");
        assertThat(calls(KillZoneExecution.class, "closePendingEffect"))
                .containsSubsequence("KillZoneExecution#armorStandHitConfirmed", "AgentActionStore#recordEffect")
                .doesNotContain("MultiPlayerGameMode#attack");
    }

    @Test
    void eachNewOwnerDrainsItsEvidenceBeforeRootInputRelease() throws Exception {
        assertThat(calls(McmcpRuntime.class, "closeAgentPrimitiveExecutor"))
                .containsSubsequence("BoundedInputExecution#close", "MovementExecution#close",
                        "KnownBreakExecution#close", "CobblestoneExecution#close",
                        "BlockMutationExecution#close", "FrameItemExecution#close",
                        "MenuPrimitiveExecution#close", "FishingPrimitiveExecution#close");
        assertThat(calls(KnownBreakExecution.class, "close"))
                .containsSubsequence("KnownBlockBreakAttempt#close", "KnownBlockBreakAttempt#drainEffectDeltas",
                        "KnownBreakExecution#recordBreakEffects");
        assertThat(calls(CobblestoneExecution.class, "close"))
                .containsSubsequence("StationaryBreakOperation#snapshot",
                        "CobblestoneExecution#recordCobblestoneGeneratorCheckpoints",
                        "CobblestoneExecution#recordUnconfirmedCobblestoneGeneratorDispatch",
                        "StationaryBreakOperation#close");
        assertThat(calls(FrameItemExecution.class, "close"))
                .containsSubsequence("FrameItemAttempt#close", "FrameItemExecution#recordFrameItemUsage");
        assertThat(calls(FrameItemExecution.class, "recordFrameItemUsage"))
                .containsSubsequence("FrameItemAttempt#drainInteractionDelta", "AgentActionStore#recordInteraction",
                        "FrameItemAttempt#drainEffectDeltas", "AgentActionStore#recordEffect");
        assertThat(calls(McmcpRuntime.class, "releaseAgentControl"))
                .containsSubsequence("McmcpRuntime#advanceStatefulAgentCleanupOncePerClientTick",
                        "McmcpRuntime#boundedActionInputRelease");
        assertThat(calls(McmcpRuntime.class, "releaseAllAndConfirmNoInputOwner"))
                .containsSubsequence("InputReleaseController#releaseAll", "InputReleaseController#inputOwnerNone");
    }

    @Test
    void newOwnersKeepPrivateStateWithoutTheRuntimeOrAnExecutionContext() {
        for (Class<?> owner : List.of(KnownBreakExecution.class, CobblestoneExecution.class,
                BlockMutationExecution.class, FrameItemExecution.class, BoundedInputExecution.class,
                MovementExecution.class, WaitExecution.class)) {
            assertThat(owner.getDeclaredFields()).allSatisfy(field -> {
                assertThat(java.lang.reflect.Modifier.isPrivate(field.getModifiers()))
                        .as(owner.getSimpleName() + "." + field.getName()).isTrue();
                assertThat(field.getType()).isNotEqualTo(McmcpRuntime.class);
                assertThat(field.getType().getSimpleName()).isNotEqualTo("AgentExecution");
            });
        }
    }

    @Test
    void dispatchStagesPreserveBudgetChargingAndRevalidationOrder() throws Exception {
        assertThat(calls(McmcpRuntime.class, "tickAgentAction"))
                .containsSubsequence("McmcpRuntime#startAgentExecution", "McmcpRuntime#agentControlCurrent",
                        "McmcpRuntime#recordAgentMotion", "McmcpRuntime#tickAgentBoundedInputHold",
                        "McmcpRuntime#tickAgentRecovery", "McmcpRuntime#recoveryAllowsAgentTick",
                        "McmcpRuntime#tickKillZoneBudget", "McmcpRuntime#tickAgentProgram")
                .doesNotContain("AgentActionStore#recordTick");
        assertThat(calls(McmcpRuntime.class, "tickAgentProgram"))
                .containsSubsequence("McmcpRuntime#activeElapsedNanos", "ActionDsl$Budget#maxTicks",
                        "PlayerInventoryEvidence#pickupInventoryIncreased", "AgentActionStore#recordTick",
                        "McmcpRuntime#bindAgentPrimitive", "McmcpRuntime#dispatchSemanticPrimitive",
                        "McmcpRuntime#tickAgentMovement");
        assertThat(calls(McmcpRuntime.class, "tickAgentMovement"))
                .containsSubsequence("McmcpRuntime#beginAgentPrimitive", "McmcpRuntime#activeElapsedNanos",
                        "MovementExecution#tick", "McmcpRuntime#recordAgentMotion",
                        "McmcpRuntime#activeElapsedNanos")
                .doesNotContain("AgentActionStore#recordTick");
        assertThat(calls(McmcpRuntime.class, "tickAgentBlockMutation"))
                .containsSubsequence("BlockMutationExecution#bindTarget", "McmcpRuntime#releaseAgentInputsForHold",
                        "BlockMutationExecution#waitForReproof", "BlockMutationExecution#tick");
        assertThat(calls(BlockMutationExecution.class, "bindTarget"))
                .containsSubsequence("ActionAdmission#analyzePrimitive", "ActionBudgets#mutationBatchRequiredRemainder",
                        "ActionBudgets#fitsMutationBatchRemainder");
        assertThat(calls(BlockMutationExecution.class, "tick"))
                .containsSubsequence("ConstructionRequests#blockMutationRequest", "KnownBlockMutationAttempt#<init>",
                        "KnownBlockMutationAttempt#tick", "BlockMutationExecution#armBatchTillSettlingAllowance");
        assertThat(calls(KnownBreakExecution.class, "tick"))
                .containsSubsequence("KnownBreakSafety#breakTargetStateMatches", "AgentObservations#agentPlanningFrame",
                        "AgentPrimitivePlanner#knownSurface", "MinecraftStationaryBreakPort#captureExpectedSource",
                        "KnownBlockBreakAttempt#<init>", "KnownBlockBreakAttempt#tick");
    }

    private static List<String> calls(Class<?> owner, String methodName) throws Exception {
        var type = new ClassNode();
        try (var input = owner.getResourceAsStream(owner.getSimpleName() + ".class")) {
            assertThat(input).isNotNull();
            new ClassReader(input).accept(type, 0);
        }
        MethodNode method = type.methods.stream()
                .filter(candidate -> candidate.name.equals(methodName))
                .max(java.util.Comparator.comparingInt(candidate ->
                        org.objectweb.asm.Type.getArgumentTypes(candidate.desc).length)).orElseThrow();
        var calls = new ArrayList<String>();
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                calls.add(call.owner.substring(call.owner.lastIndexOf('/') + 1) + "#" + call.name);
            }
        }
        return List.copyOf(calls);
    }
}
