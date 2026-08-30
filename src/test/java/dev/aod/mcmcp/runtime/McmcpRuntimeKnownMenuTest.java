package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.dsl.ActionDslCompiler;
import dev.aod.mcmcp.routine.BlockTarget;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McmcpRuntimeKnownMenuTest {
    @Test
    void opaqueMenuOperationUsesOneStationaryBoundedAdapterRequest() {
        var operation = new ActionDsl.OperateKnownMenu(
                "transfer", "abcdefghijklmnopqrstuvwx");
        var request = McmcpRuntime.knownMenuRequest(
                operation, new BlockTarget("minecraft:overworld", 1, 64, 2));

        assertThat(request.kind()).isEqualTo("operate_known_menu");
        assertThat(request.parameters()).containsOnly(
                org.assertj.core.data.MapEntry.entry(
                        "operation_ref", "abcdefghijklmnopqrstuvwx"));
        assertThat(request.bounds().maxTravelBlocks()).isZero();
        assertThat(request.bounds().maxDurationSeconds()).isEqualTo(30);
        assertThat(request.expectedUnits()).isEqualTo(1);
        assertThat(McmcpRuntime.structuralPrimitiveCost(operation).orElseThrow())
                .isEqualTo(new ActionDslCompiler.Cost(
                        ActionDslCompiler.KNOWN_MENU_OPERATION_DURATION_MILLIS,
                        ActionDslCompiler.KNOWN_MENU_OPERATION_TICKS,
                        0, 0,
                        ActionDslCompiler.KNOWN_MENU_OPERATION_INTERACTIONS,
                        0, 0));
    }
}
