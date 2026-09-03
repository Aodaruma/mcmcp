package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.brewing.StandardPotionStackSpec;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McmcpRuntimeBrewingTest {
    @Test
    @SuppressWarnings("unchecked")
    void convertsTheClosedDslNodeIntoOneStationaryBrewingRequest() {
        var brew = brew(
                "minecraft:brewing_stand",
                "minecraft:nether_wart",
                "minecraft:blaze_powder");
        var request = McmcpRuntime.brewingRequest(brew, aim(brew), 1.25F);

        assertThat(request.target().dimension()).isEqualTo("minecraft:overworld");
        assertThat(request.operation().kind()).isEqualTo("brew_known_potion_batch");
        assertThat(request.operation().bounds().maxTravelBlocks()).isZero();
        assertThat(request.operation().bounds().maxDurationSeconds()).isEqualTo(70);
        assertThat(request.operation().expectedUnits()).isEqualTo(3);
        assertThat(request.maxCameraDegreesPerTick()).isEqualTo(1.25F);
        assertThat(request.operation().parameters()
                .get("max_camera_degrees_per_tick")).isEqualTo(1.25F);
        assertThat((Map<String, Object>) request.operation().parameters().get("aim_point"))
                .containsEntry("dimension", "minecraft:overworld")
                .containsEntry("x", 2.5D)
                .containsEntry("y", 65.0D)
                .containsEntry("z", 3.5D);
        assertThat(McmcpRuntime.structuralPrimitiveCost(
                brew("minecraft:brewing_stand", "minecraft:nether_wart",
                        "minecraft:blaze_powder")).orElseThrow().interactions())
                .isEqualTo(16);
    }

    @Test
    void rejectsWrongStationFuelAndUnknownTransitionAtTheInternalBoundary() {
        ActionDsl.BrewKnownPotionBatch wrongStation = brew(
                "minecraft:barrel", "minecraft:nether_wart", "minecraft:blaze_powder");
        assertThatThrownBy(() -> McmcpRuntime.brewingRequest(
                wrongStation, aim(wrongStation), 1.25F))
                .isInstanceOf(IllegalArgumentException.class);
        ActionDsl.BrewKnownPotionBatch wrongFuel = brew(
                "minecraft:brewing_stand", "minecraft:nether_wart", "minecraft:coal");
        assertThatThrownBy(() -> McmcpRuntime.brewingRequest(
                wrongFuel, aim(wrongFuel), 1.25F))
                .isInstanceOf(IllegalArgumentException.class);
        ActionDsl.BrewKnownPotionBatch wrongTransition = brew(
                "minecraft:brewing_stand", "minecraft:diamond", "minecraft:blaze_powder");
        assertThatThrownBy(() -> McmcpRuntime.brewingRequest(
                wrongTransition, aim(wrongTransition), 1.25F))
                .isInstanceOf(IllegalArgumentException.class);
        ActionDsl.BrewKnownPotionBatch invalidCamera = brew(
                "minecraft:brewing_stand", "minecraft:nether_wart",
                "minecraft:blaze_powder");
        assertThatThrownBy(() -> McmcpRuntime.brewingRequest(
                invalidCamera, aim(invalidCamera), 0.5F))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnavailableOrMismatchedDeliveryBackedAim() {
        ActionDsl.BrewKnownPotionBatch brew = brew(
                "minecraft:brewing_stand", "minecraft:nether_wart",
                "minecraft:blaze_powder");
        assertThatThrownBy(() -> McmcpRuntime.brewingRequest(brew, null, 1.25F))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> McmcpRuntime.brewingRequest(
                brew,
                new dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.MutationAim(
                        new ActionDsl.Position("minecraft:overworld", 3, 64, 3),
                        ActionDsl.BlockFace.UP,
                        new Vec3(3.5D, 65.0D, 3.5D)),
                1.25F))
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

    private static dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.MutationAim aim(
            ActionDsl.BrewKnownPotionBatch brew) {
        ActionDsl.Position target = brew.target();
        return new dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.MutationAim(
                target,
                ActionDsl.BlockFace.UP,
                new Vec3(target.x() + 0.5D, target.y() + 1.0D, target.z() + 0.5D));
    }
}
