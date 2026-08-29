package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.brewing.StandardPotionStackSpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McmcpRuntimeBrewingTest {
    @Test
    void convertsTheClosedDslNodeIntoOneStationaryBrewingRequest() {
        var request = McmcpRuntime.brewingRequest(brew(
                "minecraft:brewing_stand",
                "minecraft:nether_wart",
                "minecraft:blaze_powder"), 1.25F);

        assertThat(request.target().dimension()).isEqualTo("minecraft:overworld");
        assertThat(request.operation().kind()).isEqualTo("brew_known_potion_batch");
        assertThat(request.operation().bounds().maxTravelBlocks()).isZero();
        assertThat(request.operation().bounds().maxDurationSeconds()).isEqualTo(70);
        assertThat(request.operation().expectedUnits()).isEqualTo(3);
        assertThat(request.maxCameraDegreesPerTick()).isEqualTo(1.25F);
        assertThat(request.operation().parameters()
                .get("max_camera_degrees_per_tick")).isEqualTo(1.25F);
        assertThat(McmcpRuntime.structuralPrimitiveCost(
                brew("minecraft:brewing_stand", "minecraft:nether_wart",
                        "minecraft:blaze_powder")).orElseThrow().interactions())
                .isEqualTo(16);
    }

    @Test
    void rejectsWrongStationFuelAndUnknownTransitionAtTheInternalBoundary() {
        assertThatThrownBy(() -> McmcpRuntime.brewingRequest(brew(
                "minecraft:barrel", "minecraft:nether_wart", "minecraft:blaze_powder"),
                1.25F))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> McmcpRuntime.brewingRequest(brew(
                "minecraft:brewing_stand", "minecraft:nether_wart", "minecraft:coal"),
                1.25F))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> McmcpRuntime.brewingRequest(brew(
                "minecraft:brewing_stand", "minecraft:diamond", "minecraft:blaze_powder"),
                1.25F))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> McmcpRuntime.brewingRequest(brew(
                "minecraft:brewing_stand", "minecraft:nether_wart",
                "minecraft:blaze_powder"), 0.5F))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ActionDsl.BrewKnownPotionBatch brew(
            String expectedBlock, String ingredient, String fuel) {
        return new ActionDsl.BrewKnownPotionBatch(
                "brew",
                new ActionDsl.Position("minecraft:overworld", 2, 64, 3),
                expectedBlock,
                new StandardPotionStackSpec("minecraft:potion", "minecraft:water", 3),
                ingredient,
                fuel,
                new StandardPotionStackSpec("minecraft:potion", "minecraft:awkward", 3));
    }
}
