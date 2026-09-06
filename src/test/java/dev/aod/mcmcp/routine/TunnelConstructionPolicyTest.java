package dev.aod.mcmcp.routine;

import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TunnelConstructionPolicyTest {
    @Test void tunnelBreakSafetyAdmitsOreWithoutExpandingOrdinaryConstruction() {
        assertThat(MinecraftApplyBlockPlanPort.tunnelBlockAllowed(
                Blocks.DIAMOND_ORE.defaultBlockState(), false)).isTrue();
        assertThat(MinecraftApplyBlockPlanPort.tunnelBlockAllowed(
                Blocks.DEEPSLATE.defaultBlockState(), false)).isTrue();
        assertThat(SafeConstructionBlockPolicy.allowsLiveState(
                Blocks.DIAMOND_ORE.defaultBlockState(), false)).isFalse();
        for (var block : List.of(Blocks.GRAVEL, Blocks.SAND, Blocks.WATER, Blocks.LAVA,
                Blocks.CHEST, Blocks.REDSTONE_ORE, Blocks.INFESTED_STONE)) {
            assertThat(MinecraftApplyBlockPlanPort.tunnelBlockAllowed(block.defaultBlockState(), false)).isFalse();
        }
        assertThat(MinecraftApplyBlockPlanPort.tunnelBlockAllowed(
                Blocks.DIAMOND_ORE.defaultBlockState(), true)).isFalse();
    }

    @Test void onlySingleExactBreakToAirCanUseTheInternalTunnelMode() {
        var target = new BlockTarget("minecraft:overworld", 1, 64, 0);
        var step = new ApplyBlockPlanStep("ore", ApplyBlockPlanOperation.BREAK_TO_AIR,
                target, new BlockStateFingerprint("minecraft:diamond_ore", Map.of()),
                new BlockStateFingerprint("minecraft:air", Map.of()), Optional.empty(), Optional.empty());
        var bounds = new ActionBounds(target.dimension(), target, target, 0, 15, true);
        var tunnel = new KnownConstructionRequest(new ApplyBlockPlanRequest("tunnel", 1, 1,
                List.of(step), bounds, ApplyBlockPlanRequest.BreakSafety.SAFE_TUNNEL_BLOCK));
        assertThat(tunnel.breakOnly()).isTrue();
        assertThatThrownBy(() -> new KnownConstructionRequest(new ApplyBlockPlanRequest("normal", 1, 1,
                List.of(step), bounds, ApplyBlockPlanRequest.BreakSafety.SAFE_CONSTRUCTION_BLOCK)))
                .isInstanceOf(SafeConstructionBlockPolicy.UnsafeConstructionBlockException.class);
        var other = new BlockTarget(target.dimension(), 2, 64, 0);
        var second = new ApplyBlockPlanStep("other", step.operation(), other,
                step.expectedBefore(), step.expectedAfter(), Optional.empty(), Optional.empty());
        assertThatThrownBy(() -> new KnownConstructionRequest(new ApplyBlockPlanRequest("many", 1, 1,
                List.of(step, second), new ActionBounds(target.dimension(), target, other, 0, 30, true),
                ApplyBlockPlanRequest.BreakSafety.SAFE_TUNNEL_BLOCK)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exactly one");
    }
}
