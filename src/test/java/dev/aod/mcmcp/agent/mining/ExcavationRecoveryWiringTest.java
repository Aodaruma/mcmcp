package dev.aod.mcmcp.agent.mining;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExcavationRecoveryWiringTest {
    @Test void normalTerminalAndOuterCleanupUseTheSameOnceOnlyEngineEvidence() throws Exception {
        String runtime = "dev/aod/mcmcp/runtime/McmcpRuntime";
        var owner = readClass(runtime);
        var tunnel = readClass(runtime + "$TunnelExecution");
        String publish = runtime + "$TunnelExecution.recordRendererRecoverySummary";
        assertThat(calls(owner, "tickAgentTunnel")).contains(publish);
        assertThat(calls(owner, "closeAgentPrimitiveExecutor")).contains(publish);
        assertThat(calls(tunnel, "recordRendererRecoverySummary"))
                .contains("dev/aod/mcmcp/agent/mining/ExcavationEngine.drainRendererRecoveryEvidence");
        assertThat(calls(tunnel, "lambda$recordRendererRecoverySummary$"))
                .contains("dev/aod/mcmcp/agent/action/AgentActionStore.recordNodeEvidence");
    }

    private static ClassNode readClass(String name) throws Exception {
        var result = new ClassNode();
        try (var stream = ExcavationRecoveryWiringTest.class.getClassLoader()
                .getResourceAsStream(name + ".class")) {
            assertThat(stream).isNotNull();
            new ClassReader(stream).accept(result, 0);
        }
        return result;
    }

    private static List<String> calls(ClassNode owner, String methodPrefix) {
        var result = new ArrayList<String>();
        for (var method : owner.methods) {
            if (!method.name.startsWith(methodPrefix)) continue;
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call)
                    result.add(call.owner + "." + call.name);
            }
        }
        return result;
    }
}
