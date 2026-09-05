package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.dsl.ActionDslValidator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable server-derived inspection results; paging never reads Minecraft state. */
public final class ContainerInspection {
    public static final int MAX_RESULTS = ActionDslValidator.MAX_EXECUTED_NODES;
    public static final int MAX_ITEMS = 54;
    public static final int MAX_PAGE_SIZE = 8;

    private ContainerInspection() {}

    public record Contents(UUID worldSessionId, long observedClientTick, long packetRevision,
                           List<KnownContainerAttempt.ItemCount> items) {
        public Contents {
            Objects.requireNonNull(worldSessionId, "worldSessionId");
            items = List.copyOf(items);
            if (observedClientTick < 0 || packetRevision < 0 || items.size() > MAX_ITEMS
                    || items.stream().map(KnownContainerAttempt.ItemCount::item).distinct().count()
                            != items.size()) {
                throw new IllegalArgumentException("Invalid complete container contents");
            }
            String prior = null;
            int total = 0;
            for (var item : items) {
                if (item.item().length() > 128
                        || !item.item().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                        || prior != null && prior.compareTo(item.item()) >= 0) {
                    throw new IllegalArgumentException("Invalid complete container item identity");
                }
                prior = item.item();
                total = Math.addExact(total, item.count());
            }
            if (total > 54 * 64) throw new IllegalArgumentException("Container capacity exceeded");
        }
    }

    public record Result(int seq, String nodeId, int nodeExecution, ActionDsl.Position target,
                         Contents contents) {
        public Result {
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(contents, "contents");
            if (seq < 1 || seq > MAX_RESULTS || nodeExecution < 1 || nodeExecution > MAX_RESULTS) {
                throw new IllegalArgumentException("Invalid container result sequence");
            }
        }

        public Map<String, Object> payload() {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("result_seq", seq);
            payload.put("node_id", nodeId);
            payload.put("node_execution", nodeExecution);
            payload.put("target", Map.of("dimension", target.dimension(),
                    "x", target.x(), "y", target.y(), "z", target.z()));
            payload.put("world_session_id", contents.worldSessionId().toString());
            payload.put("observed_client_tick", contents.observedClientTick());
            payload.put("packet_revision", contents.packetRevision());
            payload.put("items", contents.items().stream().map(item ->
                    Map.of("item_id", item.item(), "count", item.count())).toList());
            payload.put("total_item_types", contents.items().size());
            payload.put("returned_item_types", contents.items().size());
            payload.put("truncated", false);
            return Map.copyOf(payload);
        }
    }

    public record Query(boolean include, int limit, String cursor) {
        public static Query parse(Map<String, Object> arguments) {
            Object includeValue = arguments.getOrDefault("include_container_results", false);
            if (!(includeValue instanceof Boolean include)) throw invalidQuery();
            int limit = 4;
            if (arguments.containsKey("container_results_limit")) {
                Object value = arguments.get("container_results_limit");
                if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                        || number.doubleValue() != number.intValue()) throw invalidQuery();
                limit = number.intValue();
            }
            String cursor = null;
            if (arguments.containsKey("container_results_cursor")) {
                Object value = arguments.get("container_results_cursor");
                if (!(value instanceof String text) || text.length() > 44
                        || !text.matches("[0-9a-f-]{36}:[0-9]{1,3}:[0-9]{1,3}")) throw invalidQuery();
                cursor = text;
            }
            if (limit < 1 || limit > MAX_PAGE_SIZE || !include
                    && (cursor != null || arguments.containsKey("container_results_limit"))) {
                throw invalidQuery();
            }
            return new Query(include, limit, cursor);
        }
    }

    /** Cursor freezes the result count even when the Action completes more nodes between reads. */
    public static Map<String, Object> page(AgentActionStore.Snapshot snapshot, Query query) {
        int count = snapshot.containerResults().size();
        int offset = 0;
        if (query.cursor() != null) {
            String[] parts = query.cursor().split(":");
            if (!parts[0].equals(snapshot.actionId().toString())) throw invalidQuery();
            count = Integer.parseInt(parts[1]);
            offset = Integer.parseInt(parts[2]);
            if (count < 1 || count > snapshot.containerResults().size() || offset < 1
                    || offset >= count) throw invalidQuery();
        }
        int end = Math.min(count, offset + query.limit());
        var payload = new LinkedHashMap<String, Object>();
        payload.put("results", snapshot.containerResults().subList(offset, end).stream()
                .map(Result::payload).toList());
        payload.put("total_results", snapshot.containerResults().size());
        payload.put("retained_results", snapshot.containerResults().size());
        payload.put("snapshot_result_count", count);
        payload.put("returned_results", end - offset);
        payload.put("action_terminal", snapshot.state().terminal());
        payload.put("truncated", false);
        payload.put("has_more", end < count);
        payload.put("next_cursor", end < count
                ? snapshot.actionId() + ":" + count + ":" + end : null);
        return payload;
    }

    private static IllegalArgumentException invalidQuery() {
        return new IllegalArgumentException("Invalid container result query or cursor");
    }
}
