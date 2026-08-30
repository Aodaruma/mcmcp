package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.redstone.RedstoneSpec;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class McmcpRuntimeRedstoneTest {
    private static final String DIMENSION = "minecraft:overworld";

    @Test
    void convertsEveryIdentityRotationIntoOneStationaryRequest() {
        var expectedOffsets = Map.of(
                0, List.of(1, 0),
                90, List.of(0, 1),
                180, List.of(-1, 0),
                270, List.of(0, -1));

        expectedOffsets.forEach((rotation, offset) -> {
            var node = identity(rotation);
            var anchor = node.anchor();
            var lampSupport = position(anchor.x(), anchor.y() - 1, anchor.z());
            var leverSupport = position(
                    anchor.x() + offset.get(0),
                    anchor.y() - 1,
                    anchor.z() + offset.get(1));

            var request = McmcpRuntime.redstoneIdentityRequest(
                    node,
                    UUID.randomUUID(),
                    aim(lampSupport),
                    aim(leverSupport));

            assertThat(request.lampTarget().x()).isEqualTo(anchor.x());
            assertThat(request.lampTarget().z()).isEqualTo(anchor.z());
            assertThat(request.leverTarget().x()).isEqualTo(anchor.x() + offset.get(0));
            assertThat(request.leverTarget().z()).isEqualTo(anchor.z() + offset.get(1));
            assertThat(request.lampPlacement().item()).isEqualTo("minecraft:redstone_lamp");
            assertThat(request.leverPlacement().item()).isEqualTo("minecraft:lever");
            assertThat(request.bounds().maxTravelBlocks()).isZero();
            assertThat(request.bounds().allowBreak()).isFalse();
            assertThat(request.bounds().contains(request.lampPlacementAim().block())).isTrue();
            assertThat(request.bounds().contains(request.leverPlacementAim().block())).isTrue();
            assertThat(request.spec().bounds().settleTicks()).isEqualTo(5);
        });
    }

    private static ActionDsl.ApplyKnownRedstoneSpec identity(int rotation) {
        return new ActionDsl.ApplyKnownRedstoneSpec(
                "identity",
                position(2, 65, 3),
                rotation,
                List.of(
                        new RedstoneSpec.Component(
                                "input", RedstoneSpec.Role.INPUT, "minecraft:lever"),
                        new RedstoneSpec.Component(
                                "output", RedstoneSpec.Role.OUTPUT,
                                "minecraft:redstone_lamp")),
                List.of(
                        new RedstoneSpec.TruthRow(
                                Map.of("input", false), Map.of("output", false)),
                        new RedstoneSpec.TruthRow(
                                Map.of("input", true), Map.of("output", true))),
                new RedstoneSpec.Footprint(2, 1, 1),
                new ActionDsl.RedstoneTiming(5));
    }

    private static AgentPrimitivePlanner.MutationAim aim(ActionDsl.Position support) {
        return new AgentPrimitivePlanner.MutationAim(
                support,
                ActionDsl.BlockFace.UP,
                new Vec3(support.x() + 0.5D, support.y() + 1.0D, support.z() + 0.5D));
    }

    private static ActionDsl.Position position(int x, int y, int z) {
        return new ActionDsl.Position(DIMENSION, x, y, z);
    }
}
