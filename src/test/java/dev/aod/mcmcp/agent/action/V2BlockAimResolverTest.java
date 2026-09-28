package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.agent.navigation.NavCell;
import dev.aod.mcmcp.agent.observation.ObservationFrame;
import dev.aod.mcmcp.agent.observation.ObservationRecord;
import dev.aod.mcmcp.agent.observation.ObservationValues.BlockPosition;
import dev.aod.mcmcp.agent.observation.ObservationValues.ResourceId;
import dev.aod.mcmcp.agent.observation.ObservationValues.WorldPosition;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class V2BlockAimResolverTest {
    private static final ResourceId DIMENSION = new ResourceId("minecraft:overworld");
    private static final NavCell TARGET = new NavCell(DIMENSION.value(), 1, 64, 0);
    private static final Vec3 EYE = new Vec3(0.5D, 65.6D, 0.5D);
    private static final UUID SESSION = UUID.randomUUID();

    @Test
    void selectsLocalRayWithoutAnyMcpDelivery() {
        var result = V2BlockAimResolver.resolve(frame(10, 3), TARGET,
                EYE, SESSION, 10, 3, 3).orElseThrow();
        assertThat(result.blockId()).isEqualTo("minecraft:deepslate");
        assertThat(result.aim().target().x()).isEqualTo(1);
        assertThat(result.aim().aimX()).isEqualTo(1.0D);
    }

    @Test
    void rejectsInvalidatedOldOrMovedEyeEvidence() {
        var frame = frame(10, 3);
        assertThat(V2BlockAimResolver.resolve(frame, TARGET,
                EYE, SESSION, 10, 4, 4)).isEmpty();
        assertThat(V2BlockAimResolver.resolve(frame, TARGET,
                EYE, SESSION, 51, 3, 3)).isEmpty();
        assertThat(V2BlockAimResolver.resolve(frame, TARGET,
                new Vec3(0.8D, 65.6D, 0.5D), SESSION, 10, 3, 3)).isEmpty();
        assertThat(V2BlockAimResolver.resolve(frame,
                new NavCell(DIMENSION.value(), 2, 64, 0),
                EYE, SESSION, 10, 3, 3)).isEmpty();
    }

    private static ObservationFrame frame(long tick, long revision) {
        return new ObservationFrame("obs-0000000000000001", DIMENSION, tick, 8.0D,
                false, List.of(new ObservationRecord.VisibleSurface(
                        new BlockPosition(DIMENSION, 1, 64, 0),
                        ObservationRecord.Face.WEST,
                        new ResourceId("minecraft:deepslate"),
                        ObservationRecord.ShapeClass.OPAQUE,
                        null,
                        new WorldPosition(DIMENSION, 1.0D, 65.0D, 0.5D),
                        new WorldPosition(DIMENSION, EYE.x, EYE.y, EYE.z),
                        tick, revision)));
    }
}
