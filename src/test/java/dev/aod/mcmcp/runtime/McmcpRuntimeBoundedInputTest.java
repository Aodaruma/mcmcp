package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.dsl.ActionDsl;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class McmcpRuntimeBoundedInputTest {
    @Test
    void runtimeRetainsTheFullTwentyFourHourStructuralCost() {
        var hold = new ActionDsl.HoldBoundedInputs(
                "hold", List.of(ActionDsl.BoundedInput.USE), 1_728_000L,
                Optional.of(new ActionDsl.ExactBlockTargetGuard(
                        new ActionDsl.Position("minecraft:overworld", 1, 64, 2),
                        ActionDsl.BlockFace.UP,
                        new ActionDsl.BlockStateSpec("minecraft:note_block", Map.of(
                                "instrument", "harp", "note", "0", "powered", "false")))),
                Optional.of("minecraft:fishing_rod"));

        assertThat(McmcpRuntime.structuralPrimitiveCost(hold).orElseThrow())
                .isEqualTo(new dev.aod.mcmcp.agent.dsl.ActionDslCompiler.Cost(
                        86_400_000L, 1_728_000L, 0.0D, 0.0D, 1L, 0L, 0L));
    }
}
