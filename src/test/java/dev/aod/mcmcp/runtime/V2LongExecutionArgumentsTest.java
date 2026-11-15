package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentJobLimits;
import dev.aod.mcmcp.agent.navigation.NavCell;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class V2LongExecutionArgumentsTest {
    @Test
    void everyActionAcceptsExplicitLongTimeAndExistingDefaultsRemainShort() {
        int max=AgentJobLimits.MAX_TICKS;
        var origin=new NavCell("minecraft:overworld",0,64,0);
        assertThat(V2MoveArguments.parse(Map.of("direction","east","distance",1024,"max_ticks",max,"max_distance",4096),origin).maxTicks()).isEqualTo(max);
        assertThat(V2MoveArguments.parse(Map.of("x",1,"y",64,"z",0),origin).maxTicks()).isEqualTo(1200);
        assertThat(V2BreakArguments.parse(Map.of("x",0,"y",64,"z",0,"max_ticks",max),origin.dimension()).maxTicks()).isEqualTo(max);
        assertThat(V2PlaceArguments.parse(Map.of("x",0,"y",64,"z",0,"block","minecraft:stone","max_ticks",max),origin.dimension()).maxTicks()).isEqualTo(max);
        assertThat(V2BlockInteractArguments.parse(Map.of("target","block","x",0,"y",64,"z",0,"max_ticks",max),origin.dimension()).maxTicks()).isEqualTo(max);
        assertThat(V2EntityInteractArguments.parse(Map.of("target","entity","entity_ref","A".repeat(24),"max_ticks",max,"max_distance",4096)).maxTicks()).isEqualTo(max);
        assertThat(V2ItemUseArguments.parse(Map.of("target","item","hold_ticks",1500,"max_ticks",max)).maxTicks()).isEqualTo(max);
        assertThat(V2InventoryRequest.parse(Map.of("operation","drop","item","minecraft:stone","count",1,"max_ticks",max)).maxTicks()).isEqualTo(max);
        assertThat(V2InventoryRequest.parse(Map.of("operation","inspect","target","container","x",0,"y",64,"z",0,"max_ticks",max,"max_distance",4096)).maxTicks()).isEqualTo(max);
        assertThat(V2InventoryRequest.parse(Map.of("operation","inspect","target","storage","storage_slot",0,"max_ticks",max)).maxTicks()).isEqualTo(max);
        var script=V2ScriptArguments.parse(Map.of("source","repeat(1500){inventory(operation=\"inspect\");}","max_duration_ticks",max,"max_calls",1500,"max_work",200000,"max_iterations",20000));
        assertThat(script.maxDurationTicks()).isEqualTo(max);
        assertThat(script.calls()).isEqualTo(1500);
        assertThat(V2ScriptArguments.parse(Map.of("source","inventory(operation=\"inspect\");")).maxDurationTicks()).isEqualTo(24000);
    }

    @Test
    void timedHoldsCoverAllInputsAndGuardedUseHasBoundedRefill() {
        for(String input:List.of("forward","back","left","right","jump","sneak","attack","use","pick")) {
            var request=V2InputSequenceArguments.parse(Map.of("inputs",List.of(input),"duration_seconds",86400));
            assertThat(request.sequence().totalTicks()).isEqualTo(1728000);
            assertThat(request.durationSeconds()).isEqualTo(86400);
            assertThat(request.maxDistance()).isEqualTo(48);
        }
        var guarded=V2InputSequenceArguments.parse(Map.of("inputs",List.of("use"),"duration_seconds",86400,"item","minecraft:black_concrete_powder"));
        assertThat(guarded.refillWaitSeconds()).isEqualTo(30);
        assertThatThrownBy(()->V2InputSequenceArguments.parse(Map.of("inputs",List.of("forward"),"duration_seconds",1,"item","minecraft:stone"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->V2InputSequenceArguments.parse(Map.of("inputs",List.of("use"),"duration_seconds",86401))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->V2InputSequenceArguments.parse(Map.of("inputs",List.of("use"),"duration_seconds",1.5))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->V2InputSequenceArguments.parse(Map.of("inputs",List.of("use"),"duration_seconds",1,"steps",List.of(Map.of("inputs",List.of("use"),"hold_ticks",1))))).isInstanceOf(IllegalArgumentException.class);
    }
}
