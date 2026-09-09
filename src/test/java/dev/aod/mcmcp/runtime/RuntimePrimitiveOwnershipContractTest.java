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
        assertThat(calls(McmcpRuntime.class, "applyMenuPrimitiveOutcome"))
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

    private static List<String> calls(Class<?> owner, String methodName) throws Exception {
        var type = new ClassNode();
        try (var input = owner.getResourceAsStream(owner.getSimpleName() + ".class")) {
            assertThat(input).isNotNull();
            new ClassReader(input).accept(type, 0);
        }
        MethodNode method = type.methods.stream()
                .filter(candidate -> candidate.name.equals(methodName)).findFirst().orElseThrow();
        var calls = new ArrayList<String>();
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                calls.add(call.owner.substring(call.owner.lastIndexOf('/') + 1) + "#" + call.name);
            }
        }
        return List.copyOf(calls);
    }
}
