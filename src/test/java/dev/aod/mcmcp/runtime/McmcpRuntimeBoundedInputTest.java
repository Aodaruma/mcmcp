package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.dsl.ActionDsl;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class McmcpRuntimeBoundedInputTest {
    @Test
    void repeatStartsChargeTheActionLedgerWithoutClaimingConfirmedBreaks() {
        var json = com.google.gson.JsonParser.parseString("""
                {"schema_version":1,"program":{"dsl_version":1,"capabilities":["block_break"],
                  "body":[{"id":"hold","op":"hold_bounded_inputs","inputs":["attack"],
                    "duration_ticks":20,"repeat_target":true,"max_repetitions":2,
                    "target_guard":{"target":{"dimension":"minecraft:overworld","x":1,"y":64,"z":2},
                      "face":"up","expected_state":null,"expected_block":"minecraft:snow"},
                    "selected_item":"minecraft:wooden_shovel"}]},
                 "budget":{"max_duration_ms":1000,"max_ticks":20,"max_distance_blocks":0,
                   "max_camera_degrees":0,"max_interactions":2,"max_blocks_broken":2,"max_blocks_placed":0}}
                """).getAsJsonObject();
        var request = dev.aod.mcmcp.agent.dsl.ActionDslParser.parse(json);
        var program = dev.aod.mcmcp.agent.dsl.ActionDslCompiler.compile(request, ignored -> Optional.empty(),
                java.util.Set.of(ActionDsl.Capability.BLOCK_BREAK));
        var store = new dev.aod.mcmcp.agent.action.AgentActionStore();
        var accepted = store.start(program, dev.aod.mcmcp.agent.dsl.ActionDslSource.capture(json), java.time.Instant.EPOCH);
        store.markRunning(accepted.actionId());
        var action = store.active().orElseThrow();
        var execution = new BoundedInputExecution(new Object(), java.util.UUID.randomUUID(),
                new ScreenOwnershipSignals(new ContainerSyncSignals()), store);
        var hold = (ActionDsl.HoldBoundedInputs) request.program().body().getFirst();
        assertThat(execution.reserveInputStart(action, hold)).isTrue();
        store.recordTick(action.actionId()); // Waiting keeps the same action ledger.
        assertThat(execution.reserveInputStart(action, hold)).isTrue();
        assertThat(execution.reserveInputStart(action, hold)).isFalse();
        var snapshot = store.get(action.actionId());
        assertThat(snapshot.progress().interactions()).isEqualTo(2);
        assertThat(snapshot.progress().ticks()).isEqualTo(1);
        assertThat(snapshot.progress().blocksBroken()).isZero();
        assertThat(snapshot.effects()).isEmpty();
    }

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

        assertThat(ActionPlanning.structuralPrimitiveCost(hold).orElseThrow())
                .isEqualTo(new dev.aod.mcmcp.agent.dsl.ActionDslCompiler.Cost(
                        86_400_000L, 1_728_000L, 0.0D, 0.0D, 1L, 0L, 0L));
    }
}
