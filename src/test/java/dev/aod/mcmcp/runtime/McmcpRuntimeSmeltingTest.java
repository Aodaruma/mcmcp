package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.dsl.ActionDsl;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McmcpRuntimeSmeltingTest {
    @Test
    @SuppressWarnings("unchecked")
    void convertsTheClosedDslNodeIntoOneStationaryFurnaceRequest() {
        var smelt = new ActionDsl.SmeltKnownRecipe(
                "smelt", "abcdefghijklmnopqrstuvwx", "sha256:" + "a".repeat(64),
                "minecraft:iron_ingot", "default_components_only", 4,
                "furnace", new ActionDsl.Position("minecraft:overworld", 2, 64, 3),
                new ActionDsl.BlockStateSpec(
                        "minecraft:furnace", Map.of("facing", "north", "lit", "false")),
                "minecraft:coal", "default_components_only", 64);

        var aim = new dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.MutationAim(
                smelt.target(), ActionDsl.BlockFace.UP, new Vec3(2.5D, 65.0D, 3.5D));
        var request = McmcpRuntime.smeltRequest(smelt, aim);
        var station = (Map<String, Object>) request.parameters().get("station");
        var fuel = (Map<String, Object>) request.parameters().get("fuel");
        var aimPoint = (Map<String, Object>) request.parameters().get("aim_point");

        assertThat(request.kind()).isEqualTo("smelt_items");
        assertThat(request.bounds().maxTravelBlocks()).isZero();
        assertThat(request.bounds().maxDurationSeconds()).isEqualTo(750);
        assertThat(request.expectedUnits()).isEqualTo(64);
        assertThat(request.progressUnit()).isEqualTo("smelts");
        assertThat(station).containsEntry("kind", "furnace");
        assertThat(fuel).containsEntry("item", "minecraft:coal")
                .containsEntry("stack_policy", "default_components_only");
        assertThat(aimPoint).containsEntry("dimension", "minecraft:overworld")
                .containsEntry("x", 2.5D)
                .containsEntry("y", 65.0D)
                .containsEntry("z", 3.5D);
        assertThat(McmcpRuntime.structuralPrimitiveCost(smelt).orElseThrow())
                .isEqualTo(new dev.aod.mcmcp.agent.dsl.ActionDslCompiler.Cost(
                        750_000, 15_000, 0, 0, 7, 0, 0));
    }

    @Test
    void rejectsUnavailableOrMismatchedDeliveryBackedAim() {
        var smelt = new ActionDsl.SmeltKnownRecipe(
                "smelt", "abcdefghijklmnopqrstuvwx", "sha256:" + "a".repeat(64),
                "minecraft:iron_ingot", "default_components_only", 1,
                "furnace", new ActionDsl.Position("minecraft:overworld", 2, 64, 3),
                new ActionDsl.BlockStateSpec(
                        "minecraft:furnace", Map.of("facing", "north", "lit", "false")),
                "minecraft:coal", "default_components_only", 1);

        assertThatThrownBy(() -> McmcpRuntime.smeltRequest(smelt, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> McmcpRuntime.smeltRequest(
                smelt,
                new dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.MutationAim(
                        new ActionDsl.Position("minecraft:overworld", 3, 64, 3),
                        ActionDsl.BlockFace.UP,
                        new Vec3(3.5D, 65.0D, 3.5D))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
