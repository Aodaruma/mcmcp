package dev.aod.mcmcp.mcp;

import com.google.gson.Gson;
import dev.aod.mcmcp.agent.action.AgentActionStore;
import dev.aod.mcmcp.agent.action.KnownContainerAttempt;
import dev.aod.mcmcp.routine.BlockTarget;
import dev.aod.mcmcp.routine.PhaseFiveAttempt;
import dev.aod.mcmcp.routine.PhaseFiveBounds;
import dev.aod.mcmcp.routine.PhaseFiveEvidence;
import dev.aod.mcmcp.routine.PhaseFiveFrame;
import dev.aod.mcmcp.routine.PhaseFivePort;
import dev.aod.mcmcp.routine.PhaseFiveRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerEffectSchemaTest {
    @Test
    void effectCountsAllowDoubleChestCapacityWithoutExpandingTransferOrInputGoals() {
        var catalog = new McpToolCatalog();
        var observation = catalog.outputSchema("agent_get_action")
                .getAsJsonObject("$defs").getAsJsonObject("effectObservation");
        var gson = new Gson();
        for (String field : new String[] {"source_count", "destination_count"}) {
            for (int count : new int[] {0, 2_367, 3_456}) {
                assertThat(CatalogSchemaValidator.matches(observation,
                        gson.toJsonTree(Map.of(field, count)))).as("%s=%s", field, count).isTrue();
            }
            for (int count : new int[] {-1, 3_457}) {
                assertThat(CatalogSchemaValidator.matches(observation,
                        gson.toJsonTree(Map.of(field, count)))).as("%s=%s", field, count).isFalse();
            }
        }
        assertThat(CatalogSchemaValidator.matches(observation,
                gson.toJsonTree(Map.of("transferred", 64)))).isTrue();
        assertThat(CatalogSchemaValidator.matches(observation,
                gson.toJsonTree(Map.of("transferred", 65)))).isFalse();
        var nodes = catalog.inputSchema("agent_start_action").getAsJsonObject("$defs");
        assertThat(nodes.getAsJsonObject("takeContainerStackNode").getAsJsonObject("properties")
                .getAsJsonObject("minimum_inventory_count").get("maximum").getAsInt())
                .isEqualTo(2_304);
        assertThat(nodes.getAsJsonObject("storeContainerStackNode").getAsJsonObject("properties")
                .getAsJsonObject("minimum_container_count").get("maximum").getAsInt())
                .isEqualTo(2_304);
    }

    @Test
    void actualUnknownReadbackEffectWithReplenishedPlayerFitsThePublishedEffectSchema() {
        var target = new BlockTarget("minecraft:overworld", 1, 64, 2);
        var request = new PhaseFiveRequest("transfer_items", Map.of(),
                new PhaseFiveBounds(target.dimension(), target, target, 0, 120, false), 0, "items");
        var port = new MismatchedReadbackPort();
        var operation = new KnownContainerAttempt(port, request, 1, 101);
        operation.tick(1);
        port.tick = 2;
        var result = operation.tick(2);
        assertThat(result.status()).isEqualTo(KnownContainerAttempt.Status.FAILED);
        operation.close();
        var effects = operation.drainEffectDeltas();
        assertThat(effects).hasSize(1);
        var effect = effects.getFirst();
        assertThat(effect.verification()).isEqualTo(AgentActionStore.Verification.UNKNOWN);
        assertThat(effect.observedBefore()).containsEntry("destination_count", 2_303);
        assertThat(effect.observedAfter()).containsEntry("source_count", 64)
                .containsEntry("destination_count", 2_367).doesNotContainKey("transferred");
        var payload = new Gson().toJsonTree(Map.of(
                "seq", 1, "node_id", "store", "kind", "container_store",
                "subject", "container:minecraft:overworld:1,64,2/minecraft:torch",
                "observed_before", effect.observedBefore(), "observed_after", effect.observedAfter(),
                "verification", effect.verification().wireName(),
                "client_tick", effect.clientTick(), "world_revision", effect.worldRevision()));
        var output = new McpToolCatalog().outputSchema("agent_get_action");
        var schema = output.getAsJsonObject("properties").getAsJsonObject("effects")
                .getAsJsonObject("items").deepCopy();
        schema.add("$defs", output.getAsJsonObject("$defs"));
        assertThat(CatalogSchemaValidator.matches(schema, payload)).as(payload.toString()).isTrue();
    }

    private static final class MismatchedReadbackPort implements PhaseFivePort {
        private long tick = 1;

        @Override public PhaseFiveFrame observe(PhaseFiveRequest request) {
            return new PhaseFiveFrame(tick, tick, null);
        }

        @Override public PhaseFiveAttempt begin(UUID routineId, PhaseFiveRequest request, long deadline) {
            return new PhaseFiveAttempt(routineId, request.kind(), tick, tick, deadline, Map.of());
        }

        @Override public PhaseFiveEvidence evidence(PhaseFiveAttempt attempt) {
            return new PhaseFiveEvidence.Inconclusive(attempt.attemptId(), tick, tick,
                    PhaseFiveEvidence.Certainty.AMBIGUOUS,
                    "transfer_readback_did_not_confirm_exact_full_stack_move",
                    Map.of("open_count", 2, "container_clicks", 1, "recipe_placements", 0,
                            "source_before", 64, "destination_before", 2_303,
                            "source_after", 64, "destination_after", 2_367,
                            "transfer_readback_observed", true));
        }

        @Override public void maintain(PhaseFiveAttempt attempt) { }
        @Override public void release(PhaseFiveAttempt attempt) { }
        @Override public void retire(PhaseFiveRequest request) { }
    }
}
