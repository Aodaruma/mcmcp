package dev.aod.mcmcp.agent.observation;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aod.mcmcp.agent.observation.ObservationRecord.EvidenceProvenance;
import dev.aod.mcmcp.agent.observation.ObservationRecord.EntityHazardClass;
import dev.aod.mcmcp.agent.observation.ObservationRecord.Face;
import dev.aod.mcmcp.agent.observation.ObservationRecord.Fluid;
import dev.aod.mcmcp.agent.observation.ObservationRecord.Hazard;
import dev.aod.mcmcp.agent.observation.ObservationRecord.HazardSeverity;
import dev.aod.mcmcp.agent.observation.ObservationRecord.HazardType;
import dev.aod.mcmcp.agent.observation.ObservationRecord.ShapeClass;
import dev.aod.mcmcp.agent.observation.ObservationRecord.SoundCategory;
import dev.aod.mcmcp.agent.observation.ObservationRecord.SoundClue;
import dev.aod.mcmcp.agent.observation.ObservationRecord.TargetSupport;
import dev.aod.mcmcp.agent.observation.ObservationRecord.TransitionClearance;
import dev.aod.mcmcp.agent.observation.ObservationRecord.Traversability;
import dev.aod.mcmcp.agent.observation.ObservationRecord.TraversabilityStatus;
import dev.aod.mcmcp.agent.observation.ObservationRecord.UnknownBoundary;
import dev.aod.mcmcp.agent.observation.ObservationRecord.UnknownBoundaryReason;
import dev.aod.mcmcp.agent.observation.ObservationRecord.VisibleEntity;
import dev.aod.mcmcp.agent.observation.ObservationRecord.VisibleSurface;
import dev.aod.mcmcp.agent.observation.ObservationValues.Aabb;
import dev.aod.mcmcp.agent.observation.ObservationValues.BlockPosition;
import dev.aod.mcmcp.agent.observation.ObservationValues.ResourceId;
import dev.aod.mcmcp.agent.observation.ObservationValues.Vector;
import dev.aod.mcmcp.agent.observation.ObservationValues.WorldPosition;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservationModelContractTest {
    private static final ResourceId DIMENSION = new ResourceId("minecraft:overworld");
    private static final ResourceId OTHER_DIMENSION = new ResourceId("minecraft:the_nether");

    @Test
    void allRecordKindsAndSummaryMatchTheNormativeCatalogSchemas() throws Exception {
        List<ObservationRecord> records = allKinds();
        var frame = new ObservationFrame(
                "obs-0000000000000042", DIMENSION, 100, 16.0, true, true, records);
        var page = new ObservationPage(
                frame.frameId(), frame.frameCompletedTick(),
                frame.visibleEntitiesTruncated(), records, null);

        Map<ObservationKind, Integer> counts = frame.summary().recordCounts();
        assertThat(counts).containsOnly(
                Map.entry(ObservationKind.VISIBLE_SURFACE, 1),
                Map.entry(ObservationKind.VISIBLE_ENTITY, 1),
                Map.entry(ObservationKind.TRAVERSABILITY, 1),
                Map.entry(ObservationKind.HAZARD, 1),
                Map.entry(ObservationKind.UNKNOWN_BOUNDARY, 1),
                Map.entry(ObservationKind.SOUND_CLUE, 1));
        assertThat(frame.summary().oldestTick()).isEqualTo(80);
        assertThat(frame.summary().newestTick()).isEqualTo(99);
        assertThat(frame.summary().visibleEntitiesTruncated()).isTrue();

        JsonObject catalog = JsonParser.parseString(Files.readString(catalogPath())).getAsJsonObject();
        JsonObject getState = tool(catalog, "agent_get_state");
        JsonObject summarySchema = getState.getAsJsonObject("outputSchema")
                .getAsJsonObject("properties")
                .getAsJsonObject("observation")
                .getAsJsonArray("oneOf").get(1).getAsJsonObject();
        JsonObject observationSchema = tool(catalog, "agent_get_observation")
                .getAsJsonObject("outputSchema");

        var gson = new GsonBuilder().serializeNulls().create();
        assertThat(matches(summarySchema, gson.toJsonTree(
                ObservationWireMapper.summary(frame.summary())))).isTrue();
        assertThat(matches(observationSchema, gson.toJsonTree(
                ObservationWireMapper.page(page)))).isTrue();

        @SuppressWarnings("unchecked")
        Map<String, Object> traversability = (Map<String, Object>) ObservationWireMapper.record(records.get(2));
        assertThat(traversability).containsKeys("from", "to", "target_support", "transition_clearance", "fluid");
        assertThat(traversability).doesNotContainKey("cell");
    }

    @Test
    void frameAndSummaryDefensivelyCopyMutableInputs() {
        var mutableRecords = new ArrayList<ObservationRecord>();
        mutableRecords.add(surface(95, 0));
        var frame = new ObservationFrame(
                "obs-0000000000000001", DIMENSION, 100, 8.0, false, mutableRecords);
        mutableRecords.clear();

        assertThat(frame.records()).hasSize(1);
        assertThatThrownBy(() -> frame.records().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> frame.summary().recordCounts().put(ObservationKind.HAZARD, 10))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(ObservationWireMapper.summary(frame.summary()))
                .containsEntry("sampling_coverage", 1)
                .containsEntry("camera_motion_generated", false)
                .containsEntry("full_azimuth", true)
                .containsEntry("full_elevation", true);
    }

    @Test
    void cropSurfaceAddsOnlyTheCompactMaturitySignal() {
        var wheat = new VisibleSurface(
                new BlockPosition(DIMENSION, 1, 65, 1),
                Face.UP,
                new ResourceId("minecraft:wheat"),
                ShapeClass.CUTOUT,
                true,
                world(1.5, 66, 1.5),
                world(0, 65.62, 0),
                10,
                7);

        assertThat(ObservationWireMapper.record(wheat))
                .containsEntry("crop_mature", true)
                .doesNotContainKey("ray_hit");
        assertThat(ObservationWireMapper.record(surface(10, 0)))
                .doesNotContainKey("crop_mature");
    }

    @Test
    void rejectsOutOfCatalogValuesMixedDimensionsAndMutableSoundAge() {
        assertThatThrownBy(() -> new ResourceId("Minecraft:Overworld"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorldPosition(DIMENSION, Double.NaN, 64, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Aabb(1, 0, 0, 0, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VisibleSurface(
                new BlockPosition(DIMENSION, 0, 64, 0),
                Face.UP,
                new ResourceId("minecraft:stone"),
                ShapeClass.OPAQUE,
                new WorldPosition(OTHER_DIMENSION, 0.5, 65.5, 0.5),
                1,
                1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VisibleSurface(
                new BlockPosition(DIMENSION, 0, 64, 0),
                Face.UP,
                new ResourceId("minecraft:stone"),
                ShapeClass.OPAQUE,
                null,
                world(1.01, 65, 0.5),
                world(0.5, 65.62, 0.5),
                1,
                1)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ray hit");

        var wrongAge = new SoundClue(
                new ResourceId("minecraft:entity.zombie.ambient"),
                SoundCategory.HOSTILE,
                world(0, 64, 0),
                80,
                90,
                9,
                1,
                new ResourceId("minecraft:zombie"),
                3);
        assertThatThrownBy(() -> new ObservationFrame(
                "obs-0000000000000002", DIMENSION, 100, 16, false, List.of(wrongAge)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Sound age");
    }

    @Test
    void summaryRequiresEveryKindAndSoundCountNeverExceedsThirtyTwo() {
        var incomplete = new EnumMap<ObservationKind, Integer>(ObservationKind.class);
        incomplete.put(ObservationKind.VISIBLE_SURFACE, 0);
        assertThatThrownBy(() -> new ObservationFrameSummary(
                "obs-0000000000000003", 16, 0, 0, incomplete, false))
                .isInstanceOf(IllegalArgumentException.class);

        var counts = new EnumMap<ObservationKind, Integer>(ObservationKind.class);
        for (ObservationKind kind : ObservationKind.values()) {
            counts.put(kind, 0);
        }
        counts.put(ObservationKind.SOUND_CLUE, 33);
        assertThatThrownBy(() -> new ObservationFrameSummary(
                "obs-0000000000000003", 16, 0, 0, counts, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static List<ObservationRecord> allKinds() {
        return List.of(
                surface(95, 0),
                new VisibleEntity(
                        new ResourceId("minecraft:zombie"),
                        world(2, 64, 2),
                        new Vector(0.1, 0, -0.1),
                        new Aabb(1.7, 64, 1.7, 2.3, 65.95, 2.3),
                        EntityHazardClass.HOSTILE,
                        world(0, 65.62, 0),
                        96,
                        7),
                new Traversability(
                        world(0, 64, 0),
                        world(1, 64, 1),
                        TraversabilityStatus.CONFIRMED,
                        TargetSupport.CONFIRMED,
                        TransitionClearance.CONFIRMED,
                        Fluid.NONE,
                        world(0, 64, 0),
                        97,
                        7,
                        EvidenceProvenance.LOCAL_VOLUME),
                new Hazard(
                        HazardType.FALL,
                        world(3, 63, 3),
                        HazardSeverity.CAUTION,
                        world(0, 64, 0),
                        97,
                        7,
                        EvidenceProvenance.LOCAL_VOLUME),
                new UnknownBoundary(
                        world(16, 65, 0),
                        UnknownBoundaryReason.RADIUS_LIMIT,
                        world(0, 65.62, 0),
                        98,
                        7),
                new SoundClue(
                        new ResourceId("minecraft:entity.zombie.ambient"),
                        SoundCategory.HOSTILE,
                        world(5, 64, 5),
                        80,
                        99,
                        1,
                        2,
                        new ResourceId("minecraft:zombie"),
                        7));
    }

    static VisibleSurface surface(long tick, int x) {
        return new VisibleSurface(
                new BlockPosition(DIMENSION, x, 64, 0),
                Face.UP,
                new ResourceId("minecraft:stone"),
                ShapeClass.OPAQUE,
                world(0, 65.62, 0),
                tick,
                7);
    }

    static WorldPosition world(double x, double y, double z) {
        return new WorldPosition(DIMENSION, x, y, z);
    }

    private static Path catalogPath() {
        String projectDirectory = System.getProperty("mcmcp.projectDir", ".");
        return Path.of(projectDirectory, "docs", "MCMCP_MCP_Tool_Catalog.json");
    }

    private static JsonObject tool(JsonObject catalog, String name) {
        for (JsonElement element : catalog.getAsJsonArray("tools")) {
            if (name.equals(element.getAsJsonObject().get("name").getAsString())) {
                return element.getAsJsonObject();
            }
        }
        throw new AssertionError("Missing catalog tool: " + name);
    }

    private static boolean matches(JsonObject schema, JsonElement value) throws Exception {
        Class<?> validator = Class.forName("dev.aod.mcmcp.mcp.CatalogSchemaValidator");
        Method matches = validator.getDeclaredMethod("matches", JsonObject.class, JsonElement.class);
        matches.setAccessible(true);
        return (boolean) matches.invoke(null, schema, value);
    }
}
