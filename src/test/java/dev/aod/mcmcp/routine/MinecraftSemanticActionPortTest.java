package dev.aod.mcmcp.routine;

import dev.aod.mcmcp.observation.BlockPosition;
import dev.aod.mcmcp.observation.BlockStateView;
import dev.aod.mcmcp.observation.MinecraftObservationService.BlockOutcome;
import dev.aod.mcmcp.observation.MinecraftObservationService.BlockSample;
import dev.aod.mcmcp.observation.ObservedBlock;
import dev.aod.mcmcp.observation.ObservedContext;
import dev.aod.mcmcp.observation.ObservationProvenance;
import dev.aod.mcmcp.runtime.ClientReconciliationSignals.WorldMutation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    void blockInteractionAllowlistIncludesOnlyWoodenDoors() {
        assertThat(MinecraftSemanticActionPort.allowedInteractBlock(
                Blocks.LEVER.defaultBlockState())).isTrue();
        assertThat(MinecraftSemanticActionPort.allowedInteractBlock(
                Blocks.OAK_FENCE_GATE.defaultBlockState())).isTrue();
        assertThat(MinecraftSemanticActionPort.allowedInteractBlock(
                Blocks.OAK_TRAPDOOR.defaultBlockState())).isTrue();

        assertThat(MinecraftSemanticActionPort.allowedInteractBlock(
                Blocks.OAK_DOOR.defaultBlockState())).isTrue();
        assertThat(MinecraftSemanticActionPort.allowedInteractBlock(
                Blocks.IRON_DOOR.defaultBlockState())).isFalse();
        assertThat(Blocks.COPPER_DOOR.asList()).allSatisfy(block ->
                assertThat(MinecraftSemanticActionPort.allowedInteractBlock(
                        block.defaultBlockState())).isFalse());
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
    void preparationPrefersSelectedThenHotbarBeforeMainInventory() {
        assertThat(MinecraftSemanticActionPort.firstPreparingSlot(2, slot -> slot == 2))
                .isEqualTo(2);
        assertThat(MinecraftSemanticActionPort.firstPreparingSlot(2, slot -> slot == 7))
                .isEqualTo(7);
        assertThat(MinecraftSemanticActionPort.firstPreparingSlot(2, slot -> slot == 12))
                .isEqualTo(12);
        assertThat(MinecraftSemanticActionPort.firstPreparingSlot(2, slot -> false))
                .isEqualTo(-1);
    }

    @Test
    void plannedAimRequiresTheExactRaycastBlockAndFace() {
        var block = new BlockTarget("minecraft:overworld", 1, 64, 2);
        var bounds = new ActionBounds(block.dimension(), block, block, 0, 30, true);
        var request = new BreakBlockRequest(
                block,
                new BlockStateFingerprint("minecraft:wheat", Map.of("age", "7")),
                new BlockStateFingerprint("minecraft:air", Map.of()),
                bounds,
                Optional.of(new BlockAimWitness(
                        block, BlockAimWitness.Face.UP, 1.5D, 64.999D, 2.5D)));

        assertThat(MinecraftSemanticActionPort.matchesPlannedAim(
                request,
                new BlockHitResult(
                        new Vec3(1.5D, 65.0D, 2.5D), Direction.UP,
                        new BlockPos(1, 64, 2), false))).isTrue();
        assertThat(MinecraftSemanticActionPort.matchesPlannedAim(
                request,
                new BlockHitResult(
                        new Vec3(1.5D, 64.5D, 2.0D), Direction.NORTH,
                        new BlockPos(1, 64, 2), false))).isFalse();
        assertThat(MinecraftSemanticActionPort.matchesPlannedAim(
                request,
                new BlockHitResult(
                        new Vec3(2.5D, 65.0D, 2.5D), Direction.UP,
                        new BlockPos(2, 64, 2), false))).isFalse();
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
                beforeState, request, Direction.NORTH);

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
                                Map.of("face", "wall")),
                        Direction.NORTH));
        assertThatIllegalArgumentException().isThrownBy(() ->
                MinecraftSemanticActionPort.expectedInteractionServerState(
                        beforeState,
                        interactBlockRequest(Map.of("powered", "false"),
                                Map.of("powered", "not_boolean")),
                        Direction.NORTH));
        assertThatIllegalArgumentException().isThrownBy(() ->
                MinecraftSemanticActionPort.expectedInteractionServerState(
                        beforeState,
                        interactBlockRequest(Map.of("powered", "false"),
                                Map.of("missing", "true")),
                        Direction.NORTH));
        assertThatIllegalArgumentException().isThrownBy(() ->
                MinecraftSemanticActionPort.expectedInteractionServerState(
                        beforeState,
                        interactBlockRequest(Map.of("powered", "false"),
                                Map.of("face", "wall", "facing", "north", "powered", "false")),
                        Direction.NORTH));
        assertThatIllegalArgumentException().isThrownBy(() ->
                MinecraftSemanticActionPort.expectedInteractionServerState(
                        beforeState,
                        interactBlockRequest(Map.of("powered", "false"),
                                Map.of("face", "wall", "facing", "east", "powered", "true")),
                        Direction.NORTH));
    }

    @Test
    void fenceGateOpenUsesTheVanillaPlayerFacingTransitionAndFreezesTheFullState() {
        var beforeState = Blocks.OAK_FENCE_GATE.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                .setValue(BlockStateProperties.OPEN, false)
                .setValue(BlockStateProperties.POWERED, false)
                .setValue(BlockStateProperties.IN_WALL, false);
        var target = new BlockTarget("minecraft:overworld", 1, 64, 2);
        var request = new InteractBlockRequest(
                target,
                new BlockStateFingerprint(
                        "minecraft:oak_fence_gate", Map.of("open", "false")),
                new BlockStateFingerprint(
                        "minecraft:oak_fence_gate", Map.of("open", "true")),
                new ActionBounds(target.dimension(), target, target, 0, 5, false));

        var expected = MinecraftSemanticActionPort.expectedInteractionServerState(
                beforeState, request, Direction.SOUTH);

        assertThat(expected).isEqualTo(new BlockStateFingerprint(
                "minecraft:oak_fence_gate",
                Map.of(
                        "facing", "south",
                        "in_wall", "false",
                        "open", "true",
                        "powered", "false")));
        assertThatIllegalArgumentException().isThrownBy(() ->
                MinecraftSemanticActionPort.expectedInteractionServerState(
                        beforeState.setValue(BlockStateProperties.OPEN, true),
                        request,
                        Direction.SOUTH));
    }

    @Test
    void woodenDoorOpenFreezesAConsistentTwoHalfTransition() {
        var primaryPosition = new BlockPos(1, 64, 2);
        var lower = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST)
                .setValue(BlockStateProperties.DOOR_HINGE, DoorHingeSide.RIGHT)
                .setValue(BlockStateProperties.OPEN, false)
                .setValue(BlockStateProperties.POWERED, false);
        var upper = lower.setValue(
                BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        var target = new BlockTarget("minecraft:overworld", 1, 64, 2);
        var request = new InteractBlockRequest(
                target,
                new BlockStateFingerprint("minecraft:oak_door", Map.of("open", "false")),
                new BlockStateFingerprint("minecraft:oak_door", Map.of("open", "true")),
                new ActionBounds(target.dimension(), target, target, 0, 5, false));

        var transition = MinecraftSemanticActionPort.expectedDoorInteractionTransition(
                primaryPosition, lower, upper, request);
        var upperTransition = MinecraftSemanticActionPort.expectedDoorInteractionTransition(
                primaryPosition.above(), upper, lower, request);

        assertThat(transition.companionPosition()).isEqualTo(primaryPosition.above());
        assertThat(transition.primaryBefore().properties()).containsEntry("open", "false");
        assertThat(transition.companionBefore().properties()).containsEntry("open", "false");
        assertThat(transition.expectedPrimaryAfter().properties())
                .containsEntry("half", "lower")
                .containsEntry("facing", "west")
                .containsEntry("hinge", "right")
                .containsEntry("open", "true")
                .containsEntry("powered", "false");
        assertThat(upperTransition.companionPosition()).isEqualTo(primaryPosition);
        assertThat(upperTransition.expectedPrimaryAfter().properties())
                .containsEntry("half", "upper")
                .containsEntry("open", "true");
        assertThat(transition.expectedCompanionAfter().properties())
                .containsEntry("half", "upper")
                .containsEntry("facing", "west")
                .containsEntry("hinge", "right")
                .containsEntry("open", "true")
                .containsEntry("powered", "false");
    }

    @Test
    void woodenDoorOpenRejectsInconsistentOrAlreadyOpenHalves() {
        var primaryPosition = new BlockPos(1, 64, 2);
        var lower = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST)
                .setValue(BlockStateProperties.DOOR_HINGE, DoorHingeSide.RIGHT)
                .setValue(BlockStateProperties.OPEN, false)
                .setValue(BlockStateProperties.POWERED, false);
        var upper = lower.setValue(
                BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        var target = new BlockTarget("minecraft:overworld", 1, 64, 2);
        var request = new InteractBlockRequest(
                target,
                new BlockStateFingerprint("minecraft:oak_door", Map.of("open", "false")),
                new BlockStateFingerprint("minecraft:oak_door", Map.of("open", "true")),
                new ActionBounds(target.dimension(), target, target, 0, 5, false));

        assertThatIllegalArgumentException().isThrownBy(() ->
                MinecraftSemanticActionPort.expectedDoorInteractionTransition(
                        primaryPosition, lower,
                        upper.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST),
                        request));
        assertThatIllegalArgumentException().isThrownBy(() ->
                MinecraftSemanticActionPort.expectedDoorInteractionTransition(
                        primaryPosition, lower,
                        upper.setValue(BlockStateProperties.OPEN, true),
                        request));
        assertThatIllegalArgumentException().isThrownBy(() ->
                MinecraftSemanticActionPort.expectedDoorInteractionTransition(
                        primaryPosition, lower.setValue(BlockStateProperties.OPEN, true),
                        upper.setValue(BlockStateProperties.OPEN, true),
                        request));
    }

    @Test
    void doorCompanionRequiresAnExactPostDispatchServerBlockMutation() {
        var companion = new BlockPos(1, 65, 2);

        assertThat(MinecraftSemanticActionPort.hasAuthoritativeBlockMutation(
                10L,
                List.of(new WorldMutation(11L, WorldMutation.Kind.BLOCK, 1, 65, 2)),
                companion)).isTrue();
        assertThat(MinecraftSemanticActionPort.hasAuthoritativeBlockMutation(
                11L,
                List.of(new WorldMutation(11L, WorldMutation.Kind.BLOCK, 1, 65, 2)),
                companion)).isFalse();
        assertThat(MinecraftSemanticActionPort.hasAuthoritativeBlockMutation(
                10L,
                List.of(new WorldMutation(11L, WorldMutation.Kind.BLOCK, 1, 64, 2)),
                companion)).isFalse();
        assertThat(MinecraftSemanticActionPort.hasAuthoritativeBlockMutation(
                10L,
                List.of(new WorldMutation(11L, WorldMutation.Kind.CHUNK, 0, 0, 0)),
                companion)).isFalse();
    }

    @Test
    void woodenDoorSuccessRequiresPrimaryConfirmationAndConfirmedCompanion() {
        assertThat(MinecraftSemanticActionPort.doorInteractionAcknowledged(
                true, true, MinecraftSemanticActionPort.DoorCompanionStatus.CONFIRMED)).isTrue();
        assertThat(MinecraftSemanticActionPort.doorInteractionAcknowledged(
                false, true, MinecraftSemanticActionPort.DoorCompanionStatus.CONFIRMED)).isFalse();
        assertThat(MinecraftSemanticActionPort.doorInteractionAcknowledged(
                true, false, MinecraftSemanticActionPort.DoorCompanionStatus.CONFIRMED)).isFalse();
        assertThat(MinecraftSemanticActionPort.doorInteractionAcknowledged(
                true, true, MinecraftSemanticActionPort.DoorCompanionStatus.PENDING)).isFalse();
        assertThat(MinecraftSemanticActionPort.doorInteractionAcknowledged(
                true, true, MinecraftSemanticActionPort.DoorCompanionStatus.MISMATCH)).isFalse();
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
