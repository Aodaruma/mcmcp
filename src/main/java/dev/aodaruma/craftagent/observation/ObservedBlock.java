package dev.aodaruma.craftagent.observation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** An immutable observation that can be rendered as current or last-known. */
public record ObservedBlock(
        BlockPosition position,
        BlockStateView state,
        ObservedContext observedContext,
        ObservationProvenance provenance,
        long observedAtClientTick,
        UUID worldSessionId) {
    public ObservedBlock {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(observedContext, "observedContext");
        Objects.requireNonNull(provenance, "provenance");
        Objects.requireNonNull(worldSessionId, "worldSessionId");
    }

    public Map<String, Object> toMap(long currentTick, boolean current, List<String> visibleFaces, boolean withinReach) {
        var result = new LinkedHashMap<String, Object>();
        result.put("position", position.toMap());
        result.put("state", state.toMap());
        result.put("observed_context", observedContext.toMap());

        var knowledge = new LinkedHashMap<String, Object>();
        knowledge.put("currentness", current ? "current" : "last_known");
        knowledge.put("provenance", provenance.wireName());
        knowledge.put("observed_at_client_tick", observedAtClientTick);
        knowledge.put("age_ticks", Math.max(0L, currentTick - observedAtClientTick));
        knowledge.put("visible_now", current);
        result.put("knowledge", knowledge);

        if (current) {
            var liveContext = new LinkedHashMap<String, Object>();
            liveContext.put("visible_faces", List.copyOf(visibleFaces));
            liveContext.put("within_reach", withinReach);
            result.put("live_context", liveContext);
        }
        result.put("world_session_id", worldSessionId.toString());
        return result;
    }
}
