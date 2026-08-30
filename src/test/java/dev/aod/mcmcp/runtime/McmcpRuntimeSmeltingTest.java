package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.dsl.ActionDsl;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
                "minecraft:coal", "default_components_only", 1);

        var request = McmcpRuntime.smeltRequest(smelt);
        var station = (Map<String, Object>) request.parameters().get("station");
        var fuel = (Map<String, Object>) request.parameters().get("fuel");

        assertThat(request.kind()).isEqualTo("smelt_items");
        assertThat(request.bounds().maxTravelBlocks()).isZero();
        assertThat(request.bounds().maxDurationSeconds()).isEqualTo(120);
        assertThat(request.expectedUnits()).isOne();
        assertThat(station).containsEntry("kind", "furnace");
        assertThat(fuel).containsEntry("item", "minecraft:coal")
                .containsEntry("stack_policy", "default_components_only");
        assertThat(McmcpRuntime.structuralPrimitiveCost(smelt).orElseThrow())
                .isEqualTo(new dev.aod.mcmcp.agent.dsl.ActionDslCompiler.Cost(
                        120_000, 2_400, 0, 0, 6, 0, 0));
    }
}
