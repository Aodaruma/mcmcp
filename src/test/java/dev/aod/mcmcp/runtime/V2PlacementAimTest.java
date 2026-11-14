package dev.aod.mcmcp.runtime;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class V2PlacementAimTest {
    private static final BlockPos SUPPORT = new BlockPos(-780, 79, 264);
    private static final Vec3 EYE = new Vec3(-780.0969758316661, 81.27, 264.2936651499406);

    @Test void centeredTopSurvivesSmallCameraErrorThatMissesAnEdgeRay() {
        var edge = new Vec3(-779.00001, 80, 264.3);
        var center = MinecraftV2PlaceDriver.centeredSupportAim(SUPPORT, Direction.UP, Shapes.block(), EYE);
        assertThat(center).isNotNull();
        assertThat(ray(edge, 0)).isNotNull();
        assertThat(ray(edge, -0.5)).isNull();
        for (double error : new double[] { -0.75, 0, 0.75 }) {
            var hit = ray(center, error);
            assertThat(hit).isNotNull();
            assertThat(hit.getDirection()).isEqualTo(Direction.UP);
        }
    }

    @Test void doesNotUseACubeCenterForPartialShapesHiddenFacesOrDistantEyes() {
        assertThat(MinecraftV2PlaceDriver.centeredSupportAim(SUPPORT, Direction.UP,
                Shapes.box(0, 0, 0, 1, 0.5, 1), EYE)).isNull();
        assertThat(MinecraftV2PlaceDriver.centeredSupportAim(SUPPORT, Direction.DOWN,
                Shapes.block(), EYE)).isNull();
        assertThat(MinecraftV2PlaceDriver.centeredSupportAim(SUPPORT, Direction.UP,
                Shapes.block(), EYE.add(0, 5, 0))).isNull();
        assertThat(MinecraftV2PlaceDriver.centeredSupportAim(SUPPORT, Direction.UP,
                Shapes.block(), Vec3.atCenterOf(SUPPORT))).isNull();
    }

    @Test void everyFaceKeepsItsOwnSupportAndDirection() {
        for (Direction face : Direction.values()) {
            var eye = Vec3.atCenterOf(SUPPORT).add(face.getStepX() * 2, face.getStepY() * 2, face.getStepZ() * 2);
            var point = MinecraftV2PlaceDriver.centeredSupportAim(SUPPORT, face, Shapes.block(), eye);
            assertThat(point).isNotNull();
            var hit = Shapes.block().clip(eye, point, SUPPORT);
            assertThat(hit.getBlockPos()).isEqualTo(SUPPORT);
            assertThat(hit.getDirection()).isEqualTo(face);
        }
    }

    private static net.minecraft.world.phys.BlockHitResult ray(Vec3 aim, double pitchError) {
        var delta = aim.subtract(EYE);
        float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90);
        float pitch = (float) (-Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z))) + pitchError);
        return Shapes.block().clip(EYE, EYE.add(Vec3.directionFromRotation(pitch, yaw).scale(4.5)), SUPPORT);
    }
}
