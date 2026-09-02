package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner;
import dev.aod.mcmcp.agent.action.MinecraftActionPrimitiveExecutor;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.routine.InteractBlockRequest;
import dev.aod.mcmcp.routine.PlaceBlockRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McmcpRuntimeMutationAimTest {
    @Test
    void containerAimSerializesOnlyThePlannerWitnessInsideTheExactTarget() {
        var target = new ActionDsl.Position("minecraft:overworld", -11, 56, 3);
        var point = new net.minecraft.world.phys.Vec3(-10.5D, 57.0D, 3.5D);
        var aim = new AgentPrimitivePlanner.MutationAim(
                target, ActionDsl.BlockFace.UP, point);

        assertThat(McmcpRuntime.inventoryAimPoint(target, aim)).containsExactlyInAnyOrderEntriesOf(
                java.util.Map.of(
                        "dimension", target.dimension(),
                        "x", point.x,
                        "y", point.y,
                        "z", point.z));
        assertThatThrownBy(() -> McmcpRuntime.inventoryAimPoint(
                target,
                new AgentPrimitivePlanner.MutationAim(
                        new ActionDsl.Position("minecraft:overworld", -11, 56, 4),
                        ActionDsl.BlockFace.UP,
                        new net.minecraft.world.phys.Vec3(-10.5D, 57.0D, 4.5D))))
                .isInstanceOf(IllegalArgumentException.class);
    }

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

    @Test
    void passageRequestPreservesTheSelectedWoodenBlockIdentity() {
        var target = new ActionDsl.Position("minecraft:overworld", 4, 64, 5);
        var point = MinecraftActionPrimitiveExecutor.blockFaceAimPoint(
                target, ActionDsl.BlockFace.WEST);

        var request = (InteractBlockRequest) McmcpRuntime.blockMutationRequest(
                new ActionDsl.OpenKnownPassage("open_door", target, "minecraft:oak_door"),
                new AgentPrimitivePlanner.MutationAim(
                        target, ActionDsl.BlockFace.WEST, point));

        assertThat(request.expectedBefore()).isEqualTo(
                new dev.aod.mcmcp.routine.BlockStateFingerprint(
                        "minecraft:oak_door", java.util.Map.of("open", "false")));
        assertThat(request.expectedAfter()).isEqualTo(
                new dev.aod.mcmcp.routine.BlockStateFingerprint(
                        "minecraft:oak_door", java.util.Map.of("open", "true")));
        assertThat(request.plannedAim()).isPresent();
    }
}
