package dev.aodaruma.craftagent.routine;

import dev.aodaruma.craftagent.observation.BlockPosition;
import dev.aodaruma.craftagent.observation.BlockStateView;
import dev.aodaruma.craftagent.observation.MinecraftObservationService.BlockOutcome;
import dev.aodaruma.craftagent.observation.MinecraftObservationService.BlockSample;
import dev.aodaruma.craftagent.observation.ObservedBlock;
import dev.aodaruma.craftagent.observation.ObservedContext;
import dev.aodaruma.craftagent.observation.ObservationProvenance;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class MinecraftSemanticActionPortTest {
    @Test
    void routeSafetyRequiresAllThreeCellsToBeCurrentlyVisible() {
        UUID session = UUID.randomUUID();
        BlockSample feet = currentRouteCell(session, 64);
        BlockSample head = currentRouteCell(session, 65);
        BlockSample floor = currentRouteCell(session, 63);

        assertThat(MinecraftSemanticActionPort.routeCellsCurrentlyVisible(
                List.of(feet, head, floor))).isTrue();

        var hiddenPosition = new BlockPosition("minecraft:overworld", 1, 65, 2);
        var hidden = new BlockSample(
                BlockOutcome.NOT_CURRENTLY_OBSERVABLE,
                hiddenPosition,
                null,
                List.of(),
                false,
                "occluded",
                10);
        assertThat(MinecraftSemanticActionPort.routeCellsCurrentlyVisible(
                List.of(feet, hidden, floor))).isFalse();
    }

    @Test
    void navigationRejectsDamageDimensionAndRedstoneSurfaces() {
        assertThat(MinecraftSemanticActionPort.isNavigationHazard(
                Blocks.MAGMA_BLOCK.defaultBlockState())).isTrue();
        assertThat(MinecraftSemanticActionPort.isNavigationHazard(
                Blocks.CAMPFIRE.defaultBlockState())).isTrue();
        assertThat(MinecraftSemanticActionPort.isNavigationHazard(
                Blocks.WITHER_ROSE.defaultBlockState())).isTrue();
        assertThat(MinecraftSemanticActionPort.isNavigationHazard(
                Blocks.NETHER_PORTAL.defaultBlockState())).isTrue();
        assertThat(MinecraftSemanticActionPort.isNavigationHazard(
                Blocks.STONE_PRESSURE_PLATE.defaultBlockState())).isTrue();
        assertThat(MinecraftSemanticActionPort.isNavigationHazard(
                Blocks.TRIPWIRE.defaultBlockState())).isTrue();

        assertThat(MinecraftSemanticActionPort.isNavigationHazard(
                Blocks.STONE.defaultBlockState())).isFalse();
        assertThat(MinecraftSemanticActionPort.isNavigationHazard(
                Blocks.AIR.defaultBlockState())).isFalse();
    }

    @Test
    void navigationFeetLayerToleratesOnlyCollisionSurfaceFloatingPointNoise() {
        assertThat(MinecraftSemanticActionPort.routeFeetY(199.999_999_9D)).isEqualTo(200);
        assertThat(MinecraftSemanticActionPort.routeFeetY(199.999_8D)).isEqualTo(199);
        assertThat(MinecraftSemanticActionPort.routeFeetY(200.0D)).isEqualTo(200);
        assertThat(MinecraftSemanticActionPort.routeFeetY(
                199.9375D, Blocks.FARMLAND.defaultBlockState())).isEqualTo(200);
        assertThat(MinecraftSemanticActionPort.routeFeetY(
                199.9375D, Blocks.AIR.defaultBlockState())).isEqualTo(199);
        assertThat(MinecraftSemanticActionPort.lowFlatRouteFloor(
                Blocks.FARMLAND.defaultBlockState())).isTrue();
        assertThat(MinecraftSemanticActionPort.lowFlatRouteFloor(
                Blocks.DIRT_PATH.defaultBlockState())).isTrue();
        assertThat(MinecraftSemanticActionPort.lowFlatRouteFloor(
                Blocks.WATER.defaultBlockState())).isFalse();
    }

    @Test
    void groundedVanillaVerticalVelocityDoesNotPreventHorizontalSettle() {
        assertThat(MinecraftSemanticActionPort.horizontalVelocitySquared(
                new Vec3(0.0D, -0.078_400_001_5D, 0.0D))).isZero();
        assertThat(MinecraftSemanticActionPort.horizontalVelocitySquared(
                new Vec3(0.03D, -0.078_400_001_5D, 0.04D))).isEqualTo(0.0025D);
    }

    @Test
    void navigationWaitReasonsSeparateReobservationAndVisibleOccupancy() {
        assertThat(frame(true, SemanticActionFrame.ROUTE_REOBSERVATION_WAIT)
                .routeTransientWait()).isTrue();
        assertThat(frame(true, SemanticActionFrame.ROUTE_REOBSERVATION_WAIT)
                .routeNeedsReobservation()).isTrue();
        assertThat(frame(true, SemanticActionFrame.ROUTE_OCCUPANCY_WAIT)
                .routeTemporarilyOccupied()).isTrue();
        assertThat(frame(false, SemanticActionFrame.PROBE_NOT_CURRENTLY_VISIBLE)
                .routeTransientWait()).isFalse();
        assertThat(frame(false, "feet_hazard").routeNeedsReobservation()).isFalse();
    }

    @Test
    void blockInteractionAllowlistExcludesContainersAndTwoBlockDoors() {
        assertThat(MinecraftSemanticActionPort.allowedInteractBlock(
                Blocks.LEVER.defaultBlockState())).isTrue();
        assertThat(MinecraftSemanticActionPort.allowedInteractBlock(
                Blocks.OAK_FENCE_GATE.defaultBlockState())).isTrue();
        assertThat(MinecraftSemanticActionPort.allowedInteractBlock(
                Blocks.OAK_TRAPDOOR.defaultBlockState())).isTrue();

        assertThat(MinecraftSemanticActionPort.allowedInteractBlock(
                Blocks.OAK_DOOR.defaultBlockState())).isFalse();
        assertThat(MinecraftSemanticActionPort.allowedInteractBlock(
                Blocks.IRON_DOOR.defaultBlockState())).isFalse();
        assertThat(MinecraftSemanticActionPort.allowedInteractBlock(
                Blocks.CHEST.defaultBlockState())).isFalse();
        assertThat(MinecraftSemanticActionPort.allowedInteractBlock(
                Blocks.STONE_BUTTON.defaultBlockState())).isFalse();
    }

    @Test
    void steeringUsesPlayerRelativeNonOpposedVanillaKeys() {
        var targetSouth = new BlockTarget("minecraft:overworld", 0, 64, 4);
        assertThat(MinecraftSemanticActionPort.steering(0.5D, 0.5D, 0.0F, targetSouth))
                .containsExactly(MovementInputLease.MovementKey.FORWARD);

        var targetWest = new BlockTarget("minecraft:overworld", -4, 64, 0);
        assertThat(MinecraftSemanticActionPort.steering(0.5D, 0.5D, 0.0F, targetWest))
                .containsExactly(MovementInputLease.MovementKey.RIGHT);

        var targetNorth = new BlockTarget("minecraft:overworld", 0, 64, -4);
        assertThat(MinecraftSemanticActionPort.steering(0.5D, 0.5D, 180.0F, targetNorth))
                .containsExactly(MovementInputLease.MovementKey.FORWARD);
    }

    @Test
    void blockInteractionRequiresAnEmptyMainHandAndNoSneaking() {
        assertThat(MinecraftSemanticActionPort.safeBlockInteractionHand(
                false, true)).isTrue();
        assertThat(MinecraftSemanticActionPort.safeBlockInteractionHand(
                true, true)).isFalse();
        assertThat(MinecraftSemanticActionPort.safeBlockInteractionHand(
                false, false)).isFalse();
    }

    @Test
    void interactBlockFreezesExactExpectedStateBeforeAServerOnlyToggle() {
        var beforeState = Blocks.LEVER.defaultBlockState()
                .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST)
                .setValue(BlockStateProperties.POWERED, false);
        var request = interactBlockRequest(
                Map.of("powered", "false"),
                Map.of("face", "floor", "facing", "west", "powered", "true"));

        var expected = MinecraftSemanticActionPort.expectedInteractionServerState(
                beforeState, request);

        assertThat(beforeState.getValue(BlockStateProperties.POWERED)).isFalse();
        assertThat(expected).isEqualTo(new BlockStateFingerprint(
                "minecraft:lever",
                Map.of("face", "floor", "facing", "west", "powered", "true")));
    }

    @Test
    void interactBlockRejectsInvalidOrNoOpPropertiesBeforeDispatch() {
        var beforeState = Blocks.LEVER.defaultBlockState()
                .setValue(BlockStateProperties.POWERED, false);

        assertThatIllegalArgumentException().isThrownBy(() ->
                MinecraftSemanticActionPort.expectedInteractionServerState(
                        beforeState,
                        interactBlockRequest(Map.of("powered", "false"),
                                Map.of("powered", "not_boolean"))));
        assertThatIllegalArgumentException().isThrownBy(() ->
                MinecraftSemanticActionPort.expectedInteractionServerState(
                        beforeState,
                        interactBlockRequest(Map.of("powered", "false"),
                                Map.of("missing", "true"))));
        assertThatIllegalArgumentException().isThrownBy(() ->
                MinecraftSemanticActionPort.expectedInteractionServerState(
                        beforeState,
                        interactBlockRequest(Map.of("powered", "false"),
                                Map.of("face", "wall", "facing", "north", "powered", "false"))));
        assertThatIllegalArgumentException().isThrownBy(() ->
                MinecraftSemanticActionPort.expectedInteractionServerState(
                        beforeState,
                        interactBlockRequest(Map.of("powered", "false"),
                                Map.of("face", "wall", "facing", "east", "powered", "true"))));
    }

    @Test
    void breakSeedsItsAirPostconditionBeforeAQuickServerStateAndAckCanArrive() {
        var target = new BlockTarget("minecraft:overworld", 1, 64, 2);
        var bounds = new ActionBounds(
                target.dimension(), target, target, 0, 30, true);
        var request = new BreakBlockRequest(
                target,
                new BlockStateFingerprint("minecraft:stone", Map.of()),
                new BlockStateFingerprint("minecraft:air", Map.of()),
                bounds);

        assertThat(MinecraftSemanticActionPort.initialExpectedServerState(request))
                .isEqualTo(request.expectedAfter());
    }

    private static BlockSample currentRouteCell(UUID session, int y) {
        var position = new BlockPosition("minecraft:overworld", 1, y, 2);
        var observation = new ObservedBlock(
                position,
                new BlockStateView("minecraft:air", Map.of()),
                new ObservedContext(0, 15, null, true, true, List.of()),
                ObservationProvenance.LINE_OF_SIGHT_OBSERVATION,
                10,
                session);
        return new BlockSample(
                BlockOutcome.CURRENT, position, observation, List.of(), true, null, 10);
    }

    private static InteractBlockRequest interactBlockRequest(
            Map<String, String> expectedBefore, Map<String, String> expectedAfter) {
        var target = new BlockTarget("minecraft:overworld", 1, 64, 2);
        return new InteractBlockRequest(
                target,
                new BlockStateFingerprint("minecraft:lever", expectedBefore),
                new BlockStateFingerprint("minecraft:lever", expectedAfter),
                new ActionBounds(target.dimension(), target, target, 0, 30, false));
    }

    private static SemanticActionFrame frame(boolean routeSafe, String routeReason) {
        return new SemanticActionFrame(
                1, 1, true, true, true, true, true, true,
                java.util.Optional.empty(), false, false,
                false, java.util.Optional.empty(), false, false, false, false,
                0, true, 0.5D, 64.0D, 0.5D, 0.0D, true,
                routeSafe, routeReason, 0, true);
    }
}
