package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner;
import dev.aod.mcmcp.agent.action.MinecraftActionPrimitiveExecutor;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.routine.InteractBlockRequest;
import dev.aod.mcmcp.routine.PlaceBlockRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McmcpRuntimeMutationAimTest {
    @Test
    void plantRequestKeepsThePlannerSelectedSupportUpWitness() {
        var support = new ActionDsl.Position("minecraft:overworld", 3, 64, 0);
        var target = new ActionDsl.Position("minecraft:overworld", 3, 65, 0);
        var point = MinecraftActionPrimitiveExecutor.blockFaceAimPoint(
                support, ActionDsl.BlockFace.UP);
        var node = new ActionDsl.PlantKnownWheat(
                "plant", target, support, "minecraft:wheat_seeds");

        var request = (PlaceBlockRequest) McmcpRuntime.blockMutationRequest(
                node,
                new AgentPrimitivePlanner.MutationAim(
                        support, ActionDsl.BlockFace.UP, point));

        var witness = request.plannedAim().orElseThrow();
        assertThat(request.target().y()).isEqualTo(witness.block().y() + 1);
        assertThat(witness.face().name()).isEqualTo("UP");
        assertThat(new double[] {witness.x(), witness.y(), witness.z()})
                .containsExactly(point.x, point.y, point.z);
    }

    @Test
    void fenceGateRequestIsAnOakOpenTransitionWithThePlannedWitness() {
        var target = new ActionDsl.Position("minecraft:overworld", -11, 56, -15);
        var point = MinecraftActionPrimitiveExecutor.blockFaceAimPoint(
                target, ActionDsl.BlockFace.SOUTH);

        var request = (InteractBlockRequest) McmcpRuntime.blockMutationRequest(
                new ActionDsl.OpenKnownFenceGate("open_gate", target),
                new AgentPrimitivePlanner.MutationAim(
                        target, ActionDsl.BlockFace.SOUTH, point));

        assertThat(request.expectedBefore().blockId()).isEqualTo("minecraft:oak_fence_gate");
        assertThat(request.expectedBefore().properties()).isEqualTo(
                java.util.Map.of("open", "false"));
        assertThat(request.expectedAfter().blockId()).isEqualTo("minecraft:oak_fence_gate");
        assertThat(request.expectedAfter().properties()).isEqualTo(
                java.util.Map.of("open", "true"));
        assertThat(request.bounds().allowBreak()).isFalse();
        var witness = request.plannedAim().orElseThrow();
        assertThat(witness.block().x()).isEqualTo(-11);
        assertThat(witness.face().name()).isEqualTo("SOUTH");
        assertThat(new double[] {witness.x(), witness.y(), witness.z()})
                .containsExactly(point.x, point.y, point.z);
    }
}
