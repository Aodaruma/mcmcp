package dev.aod.mcmcp.routine;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseOwnershipContractTest {
    @Test
    void stationaryBreakForgetsItsAttemptOnlyAfterBothOwnedResourcesClose()
            throws Exception {
        var calls = invocations(method(classNode(
                "/dev/aod/mcmcp/routine/MinecraftStationaryBreakPort.class"),
                "releaseAttack"));

        assertThat(calls).containsSubsequence(
                "java/util/Map#get(Ljava/lang/Object;)Ljava/lang/Object;",
                "dev/aod/mcmcp/routine/MinecraftStationaryBreakPort$ActiveAttempt#lease()Ldev/aod/mcmcp/routine/AttackInputLease;",
                "dev/aod/mcmcp/routine/AttackInputLease#close()V",
                "dev/aod/mcmcp/routine/MinecraftStationaryBreakPort$ActiveAttempt#prediction()Ldev/aod/mcmcp/runtime/ClientPredictionSignals$PredictionAttempt;",
                "dev/aod/mcmcp/runtime/ClientPredictionSignals$PredictionAttempt#close()V",
                "java/util/Map#remove(Ljava/lang/Object;Ljava/lang/Object;)Z");
    }

    @Test
    void phaseFiveRouterForgetsItsRouteOnlyAfterDelegateReleaseSucceeds()
            throws Exception {
        var calls = invocations(method(classNode(
                "/dev/aod/mcmcp/routine/PhaseFivePortRouter.class"),
                "release"));

        assertThat(calls).containsSubsequence(
                "java/util/Map#get(Ljava/lang/Object;)Ljava/lang/Object;",
                "dev/aod/mcmcp/routine/PhaseFivePort#release(Ldev/aod/mcmcp/routine/PhaseFiveAttempt;)V",
                "java/util/Map#remove(Ljava/lang/Object;Ljava/lang/Object;)Z");
    }

    private static ClassNode classNode(String resource) throws Exception {
        var node = new ClassNode();
        try (var stream = ReleaseOwnershipContractTest.class.getResourceAsStream(resource)) {
            assertThat(stream).isNotNull();
            new ClassReader(stream).accept(node, 0);
        }
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream()
                .filter(method -> method.name.equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static List<String> invocations(MethodNode method) {
        var calls = new ArrayList<String>();
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                calls.add(call.owner + "#" + call.name + call.desc);
            }
        }
        return List.copyOf(calls);
    }
}
