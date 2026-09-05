package dev.aod.mcmcp.agent.action;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.dsl.ActionDslCompiler;
import dev.aod.mcmcp.agent.dsl.ActionDslParser;
import dev.aod.mcmcp.agent.dsl.ActionDslSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class ContainerInspectionTest {
    private static final ActionDsl.Position TARGET = new ActionDsl.Position("minecraft:overworld", 1, 64, 2);
    private static final UUID SESSION = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    @Test
    void all256ResultsSurviveTraceEvictionAndFailedLaterNodeWithStablePaging() {
        var store = new AgentActionStore();
        UUID id = start(store);
        for (int index = 0; index < 256; index++) {
            store.beginNode(id, "repeat_inspect");
            store.recordContainerInspection(id, TARGET, contents(54));
            store.completeNode(id);
        }
        store.fail(id, new AgentActionStore.Failure(AgentActionStore.FailureCode.SERVER_DENIED_OR_DESYNC,
                true, List.of("later_node_failed")));
        var snapshot = store.get(id);
        assertThat(snapshot.containerResults()).hasSize(256);
        assertThat(snapshot.trace()).hasSize(256);
        String cursor = null;
        int returned = 0;
        do {
            var args = new java.util.HashMap<String, Object>();
            args.put("include_container_results", true);
            args.put("container_results_limit", 8);
            if (cursor != null) args.put("container_results_cursor", cursor);
            var page = ContainerInspection.page(snapshot, ContainerInspection.Query.parse(args));
            returned += (Integer) page.get("returned_results");
            assertThat(page).containsEntry("truncated", false).containsEntry("action_terminal", true);
            // Fixed schema bounds cap the added payload independently of the trace/source payload.
            assertThat(new Gson().toJson(page).getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                    .isLessThan(100_000);
            cursor = (String) page.get("next_cursor");
        } while (cursor != null);
        assertThat(returned).isEqualTo(256);
        assertThat(snapshot.containerResults().getLast().nodeExecution()).isEqualTo(256);
        start(store);
        assertThat(store.get(id).containerResults()).hasSize(256);
        var latest = store.active().orElseThrow().actionId();
        store.fail(latest, new AgentActionStore.Failure(AgentActionStore.FailureCode.SERVER_DENIED_OR_DESYNC,
                true, List.of("cancelled")));
        start(store);
        assertThatThrownBy(() -> store.get(id)).isInstanceOf(AgentActionStore.NotFoundException.class);
    }

    @Test
    void pageCursorFreezesResultsAndRejectsOtherActionsAndInventedOffsets() {
        var store = new AgentActionStore();
        UUID id = start(store);
        for (int index = 0; index < 3; index++) record(store, id, contents(0));
        var firstSnapshot = store.get(id);
        var first = ContainerInspection.page(firstSnapshot, ContainerInspection.Query.parse(Map.of(
                "include_container_results", true, "container_results_limit", 2)));
        String cursor = (String) first.get("next_cursor");
        record(store, id, contents(1));
        var second = ContainerInspection.page(store.get(id), ContainerInspection.Query.parse(Map.of(
                "include_container_results", true, "container_results_cursor", cursor)));
        assertThat(second).containsEntry("snapshot_result_count", 3).containsEntry("total_results", 4)
                .containsEntry("returned_results", 1).containsEntry("has_more", false);
        assertThat(firstSnapshot.containerResults()).hasSize(3);
        for (String bad : List.of(SESSION + ":3:2", id + ":9:2", id + ":3:3", id + ":3:0")) {
            assertThatThrownBy(() -> ContainerInspection.page(store.get(id),
                    ContainerInspection.Query.parse(Map.of("include_container_results", true,
                            "container_results_cursor", bad)))).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(store.get(id).containerResults().getFirst().payload())
                .containsEntry("items", List.of()).containsEntry("total_item_types", 0)
                .containsEntry("truncated", false);
    }

    @Test
    void invalidOptionsDuplicatesAndOversizedContentsFailClosed() {
        for (Map<String, Object> args : List.<Map<String, Object>>of(
                Map.of("include_container_results", "true"),
                Map.of("container_results_limit", 2),
                Map.of("include_container_results", true, "container_results_limit", 9),
                Map.of("include_container_results", true, "container_results_limit", 1.5),
                Map.of("include_container_results", true, "container_results_cursor", "untrusted"))) {
            assertThatThrownBy(() -> ContainerInspection.Query.parse(args))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> contents(55)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContainerInspection.Contents(SESSION, 1, 1,
                List.of(new KnownContainerAttempt.ItemCount("minecraft:stone", 3456),
                        new KnownContainerAttempt.ItemCount("minecraft:wheat", 1))))
                .isInstanceOf(IllegalArgumentException.class);
        var store = new AgentActionStore();
        UUID id = start(store);
        assertThat(store.get(id).containerResults()).isEmpty(); // No success is not an empty chest.
        store.beginNode(id, "inspect");
        store.recordContainerInspection(id, TARGET, contents(1));
        assertThatThrownBy(() -> store.recordContainerInspection(id, TARGET, contents(1)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static void record(AgentActionStore store, UUID id, ContainerInspection.Contents contents) {
        store.beginNode(id, "inspect");
        store.recordContainerInspection(id, TARGET, contents);
        store.completeNode(id);
    }

    private static ContainerInspection.Contents contents(int count) {
        return new ContainerInspection.Contents(SESSION, 4, 9,
                java.util.stream.IntStream.range(0, count).mapToObj(index ->
                        new KnownContainerAttempt.ItemCount("minecraft:item_%02d".formatted(index), 64)).toList());
    }

    private static UUID start(AgentActionStore store) {
        var json = JsonParser.parseString("""
                {"schema_version":1,"program":{"dsl_version":1,"capabilities":[],
                "body":[{"id":"wait","op":"wait_ticks","ticks":1}]},
                "budget":{"max_duration_ms":100,"max_ticks":2,"max_distance_blocks":0,
                "max_camera_degrees":0,"max_interactions":0,"max_blocks_broken":0,"max_blocks_placed":0}}
                """).getAsJsonObject();
        var body = new com.google.gson.JsonArray();
        for (int index = 0; index < 32; index++) {
            body.add(JsonParser.parseString("""
                    {"id":"repeat_%d","op":"repeat","count":7,
                    "body":[{"id":"wait_%d","op":"wait_ticks","ticks":1}]}
                    """.formatted(index, index)));
        }
        json.getAsJsonObject("program").add("body", body);
        json.getAsJsonObject("budget").addProperty("max_ticks", 1000);
        json.getAsJsonObject("budget").addProperty("max_duration_ms", 50000);
        var compiled = ActionDslCompiler.compile(ActionDslParser.parse(json),
                ignored -> java.util.Optional.empty(), Set.of());
        var accepted = store.start(compiled, ActionDslSource.capture(json), Instant.EPOCH);
        store.markRunning(accepted.actionId());
        return accepted.actionId();
    }
}
