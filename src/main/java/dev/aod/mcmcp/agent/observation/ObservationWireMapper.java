package dev.aod.mcmcp.agent.observation;

import dev.aod.mcmcp.agent.observation.ObservationRecord.Hazard;
import dev.aod.mcmcp.agent.observation.ObservationRecord.SoundClue;
import dev.aod.mcmcp.agent.observation.ObservationRecord.Traversability;
import dev.aod.mcmcp.agent.observation.ObservationRecord.UnknownBoundary;
import dev.aod.mcmcp.agent.observation.ObservationRecord.VisibleEntity;
import dev.aod.mcmcp.agent.observation.ObservationRecord.VisibleSurface;
import dev.aod.mcmcp.agent.observation.ObservationValues.Aabb;
import dev.aod.mcmcp.agent.observation.ObservationValues.BlockPosition;
import dev.aod.mcmcp.agent.observation.ObservationValues.Vector;
import dev.aod.mcmcp.agent.observation.ObservationValues.WorldPosition;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exact snake-case projection used by the checked-in MCP tool catalog. */
public final class ObservationWireMapper {
    private ObservationWireMapper() {
    }

    public static Map<String, Object> summary(ObservationFrameSummary summary) {
        var counts = new LinkedHashMap<String, Object>();
        for (ObservationKind kind : ObservationKind.values()) {
            counts.put(kind.wireName(), summary.recordCounts().get(kind));
        }
        return map(
                "mode", summary.mode(),
                "latest_frame_id", summary.latestFrameId(),
                "configured_visual_radius_blocks", summary.configuredVisualRadiusBlocks(),
                "near_volume_radius_blocks", summary.nearVolumeRadiusBlocks(),
                "full_azimuth", summary.fullAzimuth(),
                "full_elevation", summary.fullElevation(),
                "sampling_coverage", summary.samplingCoverage(),
                "oldest_tick", summary.oldestTick(),
                "newest_tick", summary.newestTick(),
                "camera_motion_generated", summary.cameraMotionGenerated(),
                "record_counts", Collections.unmodifiableMap(counts),
                "visible_entities_truncated", summary.visibleEntitiesTruncated(),
                "recent_sound_clues_truncated", summary.recentSoundCluesTruncated());
    }

    public static Map<String, Object> page(ObservationPage page) {
        List<Map<String, Object>> records = page.records().stream()
                .map(ObservationWireMapper::record)
                .toList();
        return map(
                "schema_version", page.schemaVersion(),
                "frame_id", page.frameId(),
                "frame_completed_tick", page.frameCompletedTick(),
                "visible_entities_truncated", page.visibleEntitiesTruncated(),
                "records", records,
                "next_cursor", page.nextCursor(),
                "sampling_coverage", page.samplingCoverage());
    }

    public static Map<String, Object> record(ObservationRecord record) {
        return switch (record) {
            case VisibleSurface surface -> visibleSurface(surface);
            case VisibleEntity entity -> map(
                    "kind", entity.kind().wireName(),
                    "entity_type", entity.entityType().value(),
                    "position", worldPosition(entity.position()),
                    "velocity", vector(entity.velocity()),
                    "aabb", aabb(entity.aabb()),
                    "hazard_class", entity.hazardClass().wireName(),
                    "eye_origin", worldPosition(entity.eyeOrigin()),
                    "observed_tick", entity.observedTick(),
                    "world_revision", entity.worldRevision(),
                    "provenance", entity.provenance().name());
            case Traversability edge -> map(
                    "kind", edge.kind().wireName(),
                    "from", worldPosition(edge.from()),
                    "to", worldPosition(edge.to()),
                    "status", edge.status().name(),
                    "target_support", edge.targetSupport().wireName(),
                    "transition_clearance", edge.transitionClearance().wireName(),
                    "fluid", edge.fluid().wireName(),
                    "observer_position", worldPosition(edge.observerPosition()),
                    "observed_tick", edge.observedTick(),
                    "world_revision", edge.worldRevision(),
                    "provenance", edge.provenance().name());
            case Hazard hazard -> map(
                    "kind", hazard.kind().wireName(),
                    "hazard_type", hazard.hazardType().wireName(),
                    "position", worldPosition(hazard.position()),
                    "severity", hazard.severity().wireName(),
                    "observer_position", worldPosition(hazard.observerPosition()),
                    "observed_tick", hazard.observedTick(),
                    "world_revision", hazard.worldRevision(),
                    "provenance", hazard.provenance().name());
            case UnknownBoundary boundary -> map(
                    "kind", boundary.kind().wireName(),
                    "position", worldPosition(boundary.position()),
                    "reason", boundary.reason().wireName(),
                    "eye_origin", worldPosition(boundary.eyeOrigin()),
                    "observed_tick", boundary.observedTick(),
                    "world_revision", boundary.worldRevision());
            case SoundClue sound -> map(
                    "kind", sound.kind().wireName(),
                    "sound_event", sound.soundEvent().value(),
                    "category", sound.category().wireName(),
                    "position", worldPosition(sound.position()),
                    "first_observed_tick", sound.firstObservedTick(),
                    "last_observed_tick", sound.lastObservedTick(),
                    "age_ticks", sound.ageTicks(),
                    "occurrences", sound.occurrences(),
                    "entity_hint", sound.entityHint() == null ? null : sound.entityHint().value(),
                    "world_revision", sound.worldRevision(),
                    "provenance", sound.provenance());
        };
    }

    private static Map<String, Object> worldPosition(WorldPosition position) {
        return map(
                "dimension", position.dimension().value(),
                "x", position.x(),
                "y", position.y(),
                "z", position.z());
    }

    private static Map<String, Object> visibleSurface(VisibleSurface surface) {
        var result = new LinkedHashMap<String, Object>();
        result.put("kind", surface.kind().wireName());
        result.put("position", blockPosition(surface.position()));
        result.put("face", surface.face().wireName());
        result.put("block", surface.block().value());
        result.put("shape_class", surface.shapeClass().wireName());
        if (surface.cropMature() != null) result.put("crop_mature", surface.cropMature());
        result.put("eye_origin", worldPosition(surface.eyeOrigin()));
        result.put("observed_tick", surface.observedTick());
        result.put("world_revision", surface.worldRevision());
        result.put("provenance", surface.provenance().name());
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Object> blockPosition(BlockPosition position) {
        return map(
                "dimension", position.dimension().value(),
                "x", position.x(),
                "y", position.y(),
                "z", position.z());
    }

    private static Map<String, Object> vector(Vector vector) {
        return map("x", vector.x(), "y", vector.y(), "z", vector.z());
    }

    private static Map<String, Object> aabb(Aabb box) {
        return map(
                "min_x", box.minX(),
                "min_y", box.minY(),
                "min_z", box.minZ(),
                "max_x", box.maxX(),
                "max_y", box.maxY(),
                "max_z", box.maxZ());
    }

    private static Map<String, Object> map(Object... pairs) {
        if ((pairs.length & 1) != 0) {
            throw new IllegalArgumentException("Wire map requires key/value pairs");
        }
        var result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < pairs.length; index += 2) {
            result.put((String) pairs[index], pairs[index + 1]);
        }
        return Collections.unmodifiableMap(result);
    }
}
