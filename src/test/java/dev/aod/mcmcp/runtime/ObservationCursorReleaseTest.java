package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.observation.ObservationFrame;
import dev.aod.mcmcp.agent.observation.ObservationRecord;
import dev.aod.mcmcp.agent.observation.ObservationRecord.Face;
import dev.aod.mcmcp.agent.observation.ObservationRecord.ShapeClass;
import dev.aod.mcmcp.agent.observation.ObservationValues.BlockPosition;
import dev.aod.mcmcp.agent.observation.ObservationValues.ResourceId;
import dev.aod.mcmcp.agent.observation.ObservationValues.WorldPosition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ObservationCursorReleaseTest {
    private static final ResourceId DIMENSION = new ResourceId("minecraft:overworld");

    @Test
    void releaseHasNoDeliveryReceiptPreservesPendingAuthorizationAndAllowsNewQuery() {
        var observations = new AgentObservations(null, null, null, null);
        observations.frames().publish(frame());

        AgentObservations.PreparedObservationResponse first =
                observations.getAgentObservation(pageArguments());
        String cursor = (String) first.wireResponse().get("next_cursor");
        assertThat(cursor).isNotNull();
        assertThat(first.receiptId()).isNotNull();

        AgentObservations.PreparedObservationResponse released =
                observations.getAgentObservation(Map.of(
                        "schema_version", 1,
                        "release_cursor", cursor));
        assertThat(released.wireResponse()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "schema_version", 1,
                "release_status", "released"));
        assertThat(released.receiptId()).isNull();
        assertThat(observations.deliveredEvidence().confirmDelivery(first.receiptId())).isTrue();

        AgentObservations.PreparedObservationResponse replacement =
                observations.getAgentObservation(pageArguments());
        assertThat(replacement.wireResponse().get("next_cursor")).isNotNull();
        assertThat(replacement.receiptId()).isNotNull();
    }

    private static Map<String, Object> pageArguments() {
        var arguments = new java.util.LinkedHashMap<String, Object>();
        arguments.put("schema_version", 1);
        arguments.put("frame_id", "obs-0000000000000001");
        arguments.put("kinds", List.of("visible_surface"));
        arguments.put("cursor", null);
        arguments.put("limit", 1);
        return arguments;
    }

    private static ObservationFrame frame() {
        return new ObservationFrame(
                "obs-0000000000000001",
                DIMENSION,
                100,
                16,
                false,
                List.of(surface(0), surface(1), surface(2)));
    }

    private static ObservationRecord surface(int x) {
        return new ObservationRecord.VisibleSurface(
                new BlockPosition(DIMENSION, x, 64, 0),
                Face.UP,
                new ResourceId("minecraft:stone"),
                ShapeClass.OPAQUE,
                null,
                new WorldPosition(DIMENSION, x + 0.5, 65.0, 0.5),
                new WorldPosition(DIMENSION, 0.5, 65.62, 0.5),
                99,
                7);
    }
}
