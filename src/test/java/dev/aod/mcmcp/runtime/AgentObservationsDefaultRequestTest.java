package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.observation.ObservationFrame;
import dev.aod.mcmcp.agent.observation.ObservationRecord.Face;
import dev.aod.mcmcp.agent.observation.ObservationRecord.ShapeClass;
import dev.aod.mcmcp.agent.observation.ObservationRecord.UnknownBoundary;
import dev.aod.mcmcp.agent.observation.ObservationRecord.UnknownBoundaryReason;
import dev.aod.mcmcp.agent.observation.ObservationRecord.VisibleSurface;
import dev.aod.mcmcp.agent.observation.ObservationValues.BlockPosition;
import dev.aod.mcmcp.agent.observation.ObservationValues.ResourceId;
import dev.aod.mcmcp.agent.observation.ObservationValues.WorldPosition;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentObservationsDefaultRequestTest {
    @Test
    void emptyRequestPinsLatestFrameWithoutDeliveringUnknownBoundaries() {
        var observations = new AgentObservations(null, null, null, null);
        var dimension = new ResourceId("minecraft:overworld");
        var position = new WorldPosition(dimension, 1, 64, 2);
        observations.frames().publish(new ObservationFrame(
                "obs-0000000000000001", dimension, 10, 16, false, false,
                List.of(
                        new VisibleSurface(new BlockPosition(dimension, 1, 63, 2), Face.UP,
                                new ResourceId("minecraft:stone"), null, null, ShapeClass.OPAQUE,
                                null, position, position, 9, 0),
                        new UnknownBoundary(
                                position, UnknownBoundaryReason.UNLOADED, position, 9, 0))));

        var compact = observations.getAgentObservation(Map.of()).wirePage();
        assertThat(compact.get("frame_id")).isEqualTo("obs-0000000000000001");
        assertThat((List<?>) compact.get("records")).singleElement().satisfies(record -> {
            @SuppressWarnings("unchecked")
            var block = (Map<String, Object>) record;
            assertThat(block)
                    .containsEntry("kind", "block")
                    .containsEntry("block", "minecraft:stone")
                    .containsEntry("state", null)
                    .containsEntry("faces", "U")
                    .doesNotContainKeys("face", "eye_origin", "observed_tick", "world_revision");
        });

        var explicit = new LinkedHashMap<String, Object>();
        explicit.put("schema_version", 1);
        explicit.put("frame_id", compact.get("frame_id"));
        explicit.put("kinds", List.of("unknown_boundary"));
        explicit.put("cursor", null);
        explicit.put("limit", 1);
        var detailed = observations.getAgentObservation(explicit).wirePage();
        assertThat((List<?>) detailed.get("records")).hasSize(1);
    }
}
