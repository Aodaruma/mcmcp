package dev.aod.mcmcp.routine;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlockItemPlacementInvokerContractTest {
    @Test
    void requiredInvokerTargetsTheExactProtectedMinecraftMethod() throws Exception {
        var node = new ClassNode();
        try (var stream = getClass().getResourceAsStream(
                "/dev/aod/mcmcp/mixin/client/BlockItemPlacementInvoker.class")) {
            assertThat(stream).isNotNull();
            new ClassReader(stream).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
        }
        AnnotationNode mixin = annotation("Lorg/spongepowered/asm/mixin/Mixin;",
                node.visibleAnnotations, node.invisibleAnnotations);
        assertThat(mixin.values.toString()).contains(Type.getType(BlockItem.class).toString());
        var bridge = node.methods.stream()
                .filter(method -> method.name.equals("mcmcp$invokeGetPlacementState"))
                .findFirst().orElseThrow();
        assertThat(bridge.desc).isEqualTo(Type.getMethodDescriptor(
                Type.getType(net.minecraft.world.level.block.state.BlockState.class),
                Type.getType(BlockPlaceContext.class)));
        assertThat(annotation("Lorg/spongepowered/asm/mixin/gen/Invoker;",
                bridge.visibleAnnotations, bridge.invisibleAnnotations).values)
                .contains("getPlacementState");

        var target = BlockItem.class.getDeclaredMethod(
                "getPlacementState", BlockPlaceContext.class);
        assertThat(Modifier.isProtected(target.getModifiers())).isTrue();
    }

    @Test
    void requiredClientMixinConfigIncludesPlacementInvoker() throws Exception {
        try (var stream = getClass().getResourceAsStream("/mcmcp.mixins.json")) {
            assertThat(stream).isNotNull();
            String config = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(config).contains("\"required\": true");
            assertThat(config).contains("\"client.BlockItemPlacementInvoker\"");
        }
    }

    @Test
    void planSessionResetDoesNotCloseTheSharedPredictionLevelChannel() throws Exception {
        var node = new ClassNode();
        try (var stream = getClass().getResourceAsStream(
                "/dev/aod/mcmcp/routine/MinecraftApplyBlockPlanPort.class")) {
            assertThat(stream).isNotNull();
            new ClassReader(stream).accept(node, 0);
        }
        var clearSession = node.methods.stream()
                .filter(method -> method.name.equals("clearSession") && method.desc.equals("()V"))
                .findFirst().orElseThrow();
        var invocations = new java.util.ArrayList<String>();
        for (var instruction : clearSession.instructions) {
            if (instruction instanceof MethodInsnNode method) {
                invocations.add(method.owner + "#" + method.name + method.desc);
            }
        }
        assertThat(invocations).doesNotContain(
                "dev/aod/mcmcp/runtime/ClientPredictionSignals#closeLevel"
                        + "(Lnet/minecraft/client/multiplayer/ClientLevel;)V");
    }

    @Test
    void restorationAttemptsBothControlsAndRetriesOnlyTheFailedResource() {
        var restoration = new MinecraftApplyBlockPlanPort.ControlRestoration();
        var rotations = new AtomicInteger();

        assertThatThrownBy(() -> restoration.restore(
                () -> { throw new IllegalStateException("slot"); },
                rotations::incrementAndGet))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("slot");
        assertThat(rotations).hasValue(1);
        assertThat(restoration.started()).isTrue();
        assertThat(restoration.complete()).isFalse();

        restoration.restore(() -> { }, () -> { throw new AssertionError("already restored"); });
        assertThat(restoration.complete()).isTrue();
        assertThat(rotations).hasValue(1);
    }

    @Test
    void restorationCombinesIndependentFailures() {
        var restoration = new MinecraftApplyBlockPlanPort.ControlRestoration();
        assertThatThrownBy(() -> restoration.restore(
                () -> { throw new IllegalStateException("slot"); },
                () -> { throw new IllegalArgumentException("rotation"); }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("slot")
                .satisfies(failure -> {
                    assertThat(failure.getSuppressed()).hasSize(1);
                    assertThat(failure.getSuppressed()[0])
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessage("rotation");
                });
    }

    @Test
    void placementPolicyRejectsContainerReplacementItemsAndSaturatesOnlyAtIntegerMax() {
        assertThat(MinecraftApplyBlockPlanPort.supportsPlacementItem(
                (BlockItem) Items.POWDER_SNOW_BUCKET)).isFalse();
        assertThat(List.of(Items.OAK_STAIRS, Items.STONE_SLAB, Items.HOPPER, Items.COBBLESTONE))
                .allSatisfy(item -> assertThat(MinecraftApplyBlockPlanPort.supportsPlacementItem(
                        (BlockItem) item)).isTrue());
        assertThat(MinecraftApplyBlockPlanPort.supportsPlacementItem(
                (BlockItem) Items.OAK_DOOR)).isTrue();
        assertThat(MinecraftApplyBlockPlanPort.supportsPlacementItem(
                (BlockItem) Items.OAK_SIGN)).isFalse();
        assertThat(MinecraftApplyBlockPlanPort.supportsPlacementItem(
                (BlockItem) Items.SCAFFOLDING)).isFalse();

        assertThat(MinecraftApplyBlockPlanPort.saturatedInventoryCount(64, 64))
                .isEqualTo(128);
        assertThat(MinecraftApplyBlockPlanPort.saturatedInventoryCount(
                Integer.MAX_VALUE - 4, 8)).isEqualTo(Integer.MAX_VALUE);

        assertThat(MinecraftApplyBlockPlanPort.exactInventoryConsumption(64, 63)).isTrue();
        assertThat(MinecraftApplyBlockPlanPort.exactInventoryConsumption(1, 0)).isTrue();
        assertThat(MinecraftApplyBlockPlanPort.exactInventoryConsumption(64, 64)).isFalse();
        assertThat(MinecraftApplyBlockPlanPort.exactInventoryConsumption(64, 62)).isFalse();
        assertThat(MinecraftApplyBlockPlanPort.inventoryConsumption(64, 64))
                .isEqualTo(MinecraftApplyBlockPlanPort.InventoryConsumption.PENDING);
        assertThat(MinecraftApplyBlockPlanPort.inventoryConsumption(64, 63))
                .isEqualTo(MinecraftApplyBlockPlanPort.InventoryConsumption.CONFIRMED);
        assertThat(MinecraftApplyBlockPlanPort.inventoryConsumption(64, 62))
                .isEqualTo(MinecraftApplyBlockPlanPort.InventoryConsumption.MISMATCH);
    }

    @Test
    void placementCandidatesAlsoSampleFloorQuadrantsForDoorHinges() {
        var fullBlock = new AABB(1.0D, 2.0D, 3.0D, 2.0D, 3.0D, 4.0D);

        assertThat(MinecraftApplyBlockPlanPort.placementAimPoints(fullBlock, Direction.NORTH))
                .extracting(point -> point.y)
                .containsExactly(2.5D, 2.25D, 2.75D);
        assertThat(MinecraftApplyBlockPlanPort.placementAimPoints(fullBlock, Direction.UP))
                .hasSize(5)
                .allSatisfy(point -> assertThat(point.y).isEqualTo(3.0D));
        assertThat(MinecraftApplyBlockPlanPort.placementAimPoints(fullBlock, Direction.DOWN))
                .hasSize(5)
                .allSatisfy(point -> assertThat(point.y).isEqualTo(2.0D));
    }

    @Test
    void doorPlacementAdmitsOnlyTheLowerCellAndDerivesTheUpperState() {
        var lower = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        var upper = MinecraftApplyBlockPlanPort.expectedDoorUpper(lower);

        assertThat(MinecraftApplyBlockPlanPort.supportedDoorPlacement(lower)).isTrue();
        assertThat(upper.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF))
                .isEqualTo(DoubleBlockHalf.UPPER);
        assertThat(MinecraftApplyBlockPlanPort.supportedDoorPlacement(upper)).isFalse();
        assertThatThrownBy(() -> MinecraftApplyBlockPlanPort.expectedDoorUpper(
                Blocks.OAK_PLANKS.defaultBlockState()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void doorPlacementUsesDistinctServerConfirmationsForBothCells() throws Exception {
        String begin = "dev/aod/mcmcp/runtime/ClientPredictionSignals#begin";
        String confirm = "dev/aod/mcmcp/runtime/ClientPredictionSignals$PredictionAttempt#confirmation";
        assertThat(invocations(
                "/dev/aod/mcmcp/routine/MinecraftApplyBlockPlanPort.class", "dispatchPlace"))
                .filteredOn(begin::equals)
                .hasSize(2);
        assertThat(invocations(
                "/dev/aod/mcmcp/routine/MinecraftApplyBlockPlanPort.class", "actionEvidence"))
                .filteredOn(confirm::equals)
                .hasSize(2);
    }

    @Test
    void phaseFourBreakUsesTheSharedPacketBoundarySourcePolicy() {
        assertThat(SafeBreakSourcePolicy.allowsLiveState(
                Blocks.STONE.defaultBlockState(), false)).isTrue();
        assertThat(SafeBreakSourcePolicy.allowsLiveState(
                Blocks.HOPPER.defaultBlockState(), false)).isFalse();
        assertThat(SafeBreakSourcePolicy.allowsLiveState(
                Blocks.STONE.defaultBlockState(), true)).isFalse();
    }

    @Test
    void phaseThreePlacementGatesAdmissionObservationAndNormalUseDispatch() throws Exception {
        assertThat(invocations(
                "/dev/aod/mcmcp/runtime/McmcpRuntime.class",
                "startSemanticAction"))
                .contains("dev/aod/mcmcp/routine/MinecraftSemanticActionPort#"
                        + "requireSafePlacementSupportForAdmission");
        String contextualPolicy =
                "dev/aod/mcmcp/routine/MinecraftSemanticActionPort#";
        assertThat(invocations(
                "/dev/aod/mcmcp/routine/MinecraftSemanticActionPort.class",
                "requireSafePlacementSupportForAdmission"))
                .contains(contextualPolicy + "requirePlacementSupport")
                .doesNotContain("dev/aod/mcmcp/routine/MinecraftSemanticActionPort#"
                        + "requirePreparedPlacement");
        assertThat(invocations(
                "/dev/aod/mcmcp/routine/MinecraftSemanticActionPort.class",
                "blockFacts"))
                .contains(contextualPolicy + "allowsPlacementSupport");
        assertThat(invocations(
                "/dev/aod/mcmcp/routine/MinecraftSemanticActionPort.class",
                "dispatchPlace"))
                .containsSubsequence(
                        contextualPolicy + "requirePlacementSupport",
                        "dev/aod/mcmcp/runtime/ClientPredictionSignals#begin",
                        "net/minecraft/client/multiplayer/MultiPlayerGameMode#useItemOn");
    }

    @Test
    void phaseFourPlacementGatesCandidatesTransferAndNormalUseDispatch() throws Exception {
        String policy = "dev/aod/mcmcp/routine/SafePlacementSupportPolicy#";
        assertThat(invocations(
                "/dev/aod/mcmcp/routine/MinecraftApplyBlockPlanPort.class",
                "placeCandidates"))
                .contains(policy + "allowsLiveState");
        assertThat(invocations(
                "/dev/aod/mcmcp/routine/MinecraftApplyBlockPlanPort.class",
                "dispatchPrepared"))
                .contains(policy + "requireLiveState");
        assertThat(invocations(
                "/dev/aod/mcmcp/routine/MinecraftApplyBlockPlanPort.class",
                "dispatchPlace"))
                .containsSubsequence(
                        policy + "requireLiveState",
                        "dev/aod/mcmcp/runtime/ClientPredictionSignals#begin",
                        policy + "dispatchUseIfAllowed");
    }

    @Test
    void pillarDispatchUsesItsBroaderExactSupportCheck() throws Exception {
        assertThat(invocations(
                "/dev/aod/mcmcp/routine/MinecraftPillarUpPort.class",
                "placeOnce"))
                .containsSubsequence(
                        "dev/aod/mcmcp/routine/MinecraftPillarUpPort#requireExactSupport",
                        "dev/aod/mcmcp/runtime/ClientPredictionSignals#begin",
                        "net/minecraft/client/multiplayer/MultiPlayerGameMode#useItemOn");
    }

    @Test
    void phaseFourBreakHeartbeatRejectsUnsafeAndChangedSourcesBeforeInputReassertion() {
        var expectedStone = new BlockStateFingerprint("minecraft:stone", Map.of());

        assertThat(MinecraftApplyBlockPlanPort.breakHeartbeatSourceFailure(
                Blocks.STONE.defaultBlockState(), false, expectedStone)).isNull();

        var unsafe = MinecraftApplyBlockPlanPort.breakHeartbeatSourceFailure(
                Blocks.TNT.defaultBlockState(), false, expectedStone);
        assertThat(unsafe).isNotNull();
        assertThat(unsafe.code()).isEqualTo("UNSAFE_BREAK_SOURCE");
        assertThat(unsafe.category()).isEqualTo(RoutineFailure.Category.PRECONDITION);

        var unexpectedEntity = MinecraftApplyBlockPlanPort.breakHeartbeatSourceFailure(
                Blocks.STONE.defaultBlockState(), true, expectedStone);
        assertThat(unexpectedEntity).isNotNull();
        assertThat(unexpectedEntity.code()).isEqualTo("UNSAFE_BREAK_SOURCE");

        var changed = MinecraftApplyBlockPlanPort.breakHeartbeatSourceFailure(
                Blocks.DIRT.defaultBlockState(), false, expectedStone);
        assertThat(changed).isNotNull();
        assertThat(changed.code()).isEqualTo("BLOCK_TARGET_CHANGED");
        assertThat(changed.category()).isEqualTo(RoutineFailure.Category.DIVERGENCE);
    }

    @Test
    void constructionClearHeartbeatUsesItsClosedFullBlockPolicyOnlyWhenMarked() {
        var smoothStone = new BlockStateFingerprint("minecraft:smooth_stone", Map.of());

        assertThat(MinecraftApplyBlockPlanPort.breakHeartbeatSourceFailure(
                Blocks.SMOOTH_STONE.defaultBlockState(), false, smoothStone))
                .isNotNull();
        assertThat(MinecraftApplyBlockPlanPort.breakHeartbeatSourceFailure(
                Blocks.SMOOTH_STONE.defaultBlockState(), false, smoothStone,
                ApplyBlockPlanRequest.BreakSafety.SAFE_CONSTRUCTION_BLOCK))
                .isNull();
        assertThat(MinecraftApplyBlockPlanPort.breakHeartbeatSourceFailure(
                Blocks.SMOOTH_STONE.defaultBlockState(), true, smoothStone,
                ApplyBlockPlanRequest.BreakSafety.SAFE_CONSTRUCTION_BLOCK))
                .isNotNull();
    }

    @Test
    void semanticBreakStopsOnAnOwnedLocalPostconditionWhileAwaitingServerEvidence() throws Exception {
        assertThat(invocations(
                "/dev/aod/mcmcp/routine/MinecraftSemanticActionPort.class",
                "maintainBreak"))
                .containsSubsequence(
                        "dev/aod/mcmcp/runtime/ClientPredictionSignals$PredictionAttempt#confirmation",
                        "dev/aod/mcmcp/routine/MinecraftSemanticActionPort#currentBlockState",
                        "dev/aod/mcmcp/runtime/ClientPredictionSignals$Confirmation#issuedSequence",
                        "java/util/Optional#filter",
                        "java/util/Optional#isPresent",
                        "dev/aod/mcmcp/runtime/ClientPredictionSignals$Confirmation#postconditionObserved",
                        "dev/aod/mcmcp/routine/MinecraftSemanticActionPort#stopInput");
    }

    @Test
    void stationaryStandPolicyUsesOnlyGroundingVehicleAndPositionFacts() {
        assertThat(MinecraftApplyBlockPlanPort.stationaryStandReady(
                true, false, 0.0D)).isTrue();
        assertThat(MinecraftApplyBlockPlanPort.stationaryStandReady(
                true, false, 0.01D * 0.01D)).isTrue();

        assertThat(MinecraftApplyBlockPlanPort.stationaryStandReady(
                false, false, 0.0D)).isFalse();
        assertThat(MinecraftApplyBlockPlanPort.stationaryStandReady(
                true, true, 0.0D)).isFalse();
        assertThat(MinecraftApplyBlockPlanPort.stationaryStandReady(
                true, false, 0.010_001D * 0.010_001D)).isFalse();
        assertThat(MinecraftApplyBlockPlanPort.stationaryStandReady(
                true, false, Double.NaN)).isFalse();
    }

    @Test
    void ownedObservationPolicyKeepsOnlyTheCurrentChildAndItsPlacementSupports() {
        var air = new BlockStateFingerprint("minecraft:air", Map.of());
        var stone = new BlockStateFingerprint("minecraft:stone", Map.of());
        var firstTarget = new BlockTarget("minecraft:overworld", 0, 64, 0);
        var secondTarget = new BlockTarget("minecraft:overworld", 20, 64, 20);
        var place = new ApplyBlockPlanStep(
                "place", ApplyBlockPlanOperation.PLACE, firstTarget,
                air, stone, Optional.of("minecraft:stone"));
        var breakStep = new ApplyBlockPlanStep(
                "break", ApplyBlockPlanOperation.BREAK_TO_AIR, secondTarget,
                stone, air, Optional.empty());
        var bounds = new ActionBounds(
                firstTarget.dimension(),
                new BlockTarget(firstTarget.dimension(), 0, 64, 0),
                new BlockTarget(firstTarget.dimension(), 20, 64, 20),
                0, 30, true);
        var request = new ApplyBlockPlanRequest(
                "fixture", 1, 1, List.of(place, breakStep), bounds);

        assertThat(MinecraftApplyBlockPlanPort.planObservationPositions(request, null))
                .contains(firstTargetPosition(), secondTargetPosition())
                .hasSize(8);
        assertThat(MinecraftApplyBlockPlanPort.planObservationPositions(
                request, ApplyBlockPlanChildAction.first(0, place)))
                .contains(firstTargetPosition())
                .hasSize(7);
        assertThat(MinecraftApplyBlockPlanPort.planObservationPositions(
                request, ApplyBlockPlanChildAction.first(1, breakStep)))
                .containsExactly(secondTargetPosition());
    }

    @Test
    void constructionObservationUsesOnlyItsExplicitSupportWitness() {
        var air = new BlockStateFingerprint("minecraft:air", Map.of());
        var stone = new BlockStateFingerprint("minecraft:stone", Map.of());
        var target = new BlockTarget("minecraft:overworld", 0, 65, 0);
        var support = new BlockTarget("minecraft:overworld", 0, 64, 0);
        var place = new ApplyBlockPlanStep(
                "place", ApplyBlockPlanOperation.PLACE, target, air, stone,
                Optional.of("minecraft:stone"),
                Optional.of(PlacementSupportWitness.visible(support, "up", stone)));
        var bounds = new ActionBounds(
                target.dimension(), target, target, 0, 15, false);
        var request = new ApplyBlockPlanRequest(
                "construction", 1, 1, List.of(place), bounds);

        assertThat(MinecraftApplyBlockPlanPort.planObservationPositions(request, null))
                .containsExactlyInAnyOrder(
                        new net.minecraft.core.BlockPos(0, 65, 0),
                        new net.minecraft.core.BlockPos(0, 64, 0));
        assertThat(MinecraftApplyBlockPlanPort.planObservationPositions(
                request, ApplyBlockPlanChildAction.first(0, place)))
                .containsExactlyInAnyOrder(
                        new net.minecraft.core.BlockPos(0, 65, 0),
                        new net.minecraft.core.BlockPos(0, 64, 0));
    }

    @Test
    void placeRejectsAnExistingNeighborSensitiveCellOutsideThePlan() {
        BlockPos first = new BlockPos(0, 65, 0);
        BlockPos east = first.east();
        BlockState straight = Blocks.OAK_STAIRS.defaultBlockState();
        var oneStair = placementRequest(Map.of(first, straight));
        var twoStairs = placementRequest(Map.of(first, straight, east, straight));
        BlockState liveStair = Blocks.COBBLESTONE_STAIRS.defaultBlockState();

        assertThat(MinecraftApplyBlockPlanPort.horizontalNeighborMutationsContained(
                oneStair,
                position -> position.equals(east)
                        ? liveStair : Blocks.AIR.defaultBlockState())).isFalse();
        assertThat(MinecraftApplyBlockPlanPort.horizontalNeighborMutationsContained(
                twoStairs,
                position -> position.equals(east)
                        ? liveStair : Blocks.AIR.defaultBlockState())).isTrue();
        assertThat(MinecraftApplyBlockPlanPort.horizontalNeighborMutationsContained(
                twoStairs,
                position -> position.equals(first.west()) ? null
                        : position.equals(east)
                                ? liveStair : Blocks.AIR.defaultBlockState())).isFalse();

        var onePane = placementRequest(Map.of(first, Blocks.GLASS_PANE.defaultBlockState()));
        assertThat(MinecraftApplyBlockPlanPort.horizontalNeighborMutationsContained(
                onePane,
                position -> position.equals(east)
                        ? Blocks.GLASS_PANE.defaultBlockState()
                        : Blocks.AIR.defaultBlockState())).isFalse();

        var fullCubePlace = placementRequest(Map.of(first, Blocks.STONE.defaultBlockState()));
        var placeWithPaneFinal = placementRequest(Map.of(
                first, Blocks.STONE.defaultBlockState(),
                east, Blocks.GLASS_PANE.defaultBlockState()));
        assertThat(MinecraftApplyBlockPlanPort.horizontalNeighborMutationsContained(
                fullCubePlace,
                position -> position.equals(east)
                        ? Blocks.GLASS_PANE.defaultBlockState()
                        : Blocks.AIR.defaultBlockState())).isFalse();
        assertThat(MinecraftApplyBlockPlanPort.horizontalNeighborMutationsContained(
                placeWithPaneFinal,
                position -> position.equals(east)
                        ? Blocks.GLASS_PANE.defaultBlockState()
                        : Blocks.AIR.defaultBlockState())).isTrue();
    }

    @Test
    void breakRejectsAnExistingNeighborSensitiveCellOutsideThePlan() {
        BlockPos first = new BlockPos(0, 65, 0);
        BlockPos east = first.east();
        var breakCube = breakRequest(Map.of(first, Blocks.STONE.defaultBlockState()));
        var breakCubeAndPane = breakRequest(Map.of(
                first, Blocks.STONE.defaultBlockState(),
                east, Blocks.GLASS_PANE.defaultBlockState()));
        assertThat(MinecraftApplyBlockPlanPort.horizontalNeighborMutationsContained(
                breakCube,
                position -> position.equals(east)
                        ? Blocks.GLASS_PANE.defaultBlockState()
                        : Blocks.AIR.defaultBlockState())).isFalse();
        assertThat(MinecraftApplyBlockPlanPort.horizontalNeighborMutationsContained(
                breakCubeAndPane,
                position -> position.equals(first)
                        ? Blocks.STONE.defaultBlockState()
                        : position.equals(east)
                                ? Blocks.GLASS_PANE.defaultBlockState()
                                : Blocks.AIR.defaultBlockState())).isTrue();
    }

    @Test
    void replaceRejectsAnExistingNeighborSensitiveCellOutsideThePlan() {
        BlockPos first = new BlockPos(0, 65, 0);
        BlockPos east = first.east();
        var replaceCube = replaceRequest(first);
        assertThat(MinecraftApplyBlockPlanPort.horizontalNeighborMutationsContained(
                replaceCube,
                position -> position.equals(east)
                        ? Blocks.OAK_STAIRS.defaultBlockState()
                        : Blocks.AIR.defaultBlockState())).isFalse();
    }

    @Test
    void stairShapeDifferencesAreNeverDeferred() {
        BlockPos cornerPosition = new BlockPos(0, 65, 0);
        BlockPos frontPosition = cornerPosition.north();
        BlockState predictedStraight = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        BlockState expectedCorner = predictedStraight
                .setValue(BlockStateProperties.STAIRS_SHAPE, StairsShape.OUTER_LEFT);
        BlockState plannedFront = Blocks.COBBLESTONE_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST);

        var singleton = placementRequest(Map.of(cornerPosition, expectedCorner));
        assertThat(MinecraftApplyBlockPlanPort.derivedPlacementDifferenceHasPlannedCause(
                singleton, target(cornerPosition), fingerprint(expectedCorner),
                predictedStraight, ignored -> Blocks.AIR.defaultBlockState())).isFalse();

        var component = placementRequest(Map.of(
                cornerPosition, expectedCorner,
                frontPosition, plannedFront));
        assertThat(MinecraftApplyBlockPlanPort.derivedPlacementDifferenceHasPlannedCause(
                component, target(cornerPosition), fingerprint(expectedCorner),
                predictedStraight, ignored -> Blocks.AIR.defaultBlockState())).isFalse();
    }

    @Test
    void singletonConnectedPaneNeedsTheMatchingUnfinishedPlanNeighbour() {
        BlockPos panePosition = new BlockPos(0, 65, 0);
        BlockPos east = panePosition.east();
        BlockState predictedUnconnected = Blocks.GLASS_PANE.defaultBlockState();
        BlockState expectedConnected = predictedUnconnected
                .setValue(BlockStateProperties.EAST, true);
        BlockState plannedEast = predictedUnconnected
                .setValue(BlockStateProperties.WEST, true);

        var singleton = placementRequest(Map.of(panePosition, expectedConnected));
        assertThat(MinecraftApplyBlockPlanPort.derivedPlacementDifferenceHasPlannedCause(
                singleton, target(panePosition), fingerprint(expectedConnected),
                predictedUnconnected, ignored -> Blocks.AIR.defaultBlockState())).isFalse();

        var component = placementRequest(Map.of(
                panePosition, expectedConnected,
                east, plannedEast));
        assertThat(MinecraftApplyBlockPlanPort.derivedPlacementDifferenceHasPlannedCause(
                component, target(panePosition), fingerprint(expectedConnected),
                predictedUnconnected, ignored -> Blocks.AIR.defaultBlockState())).isTrue();
    }

    private static ApplyBlockPlanRequest placementRequest(Map<BlockPos, BlockState> states) {
        var steps = new java.util.ArrayList<ApplyBlockPlanStep>();
        int index = 0;
        for (var entry : states.entrySet()) {
            BlockPos position = entry.getKey();
            steps.add(new ApplyBlockPlanStep(
                    "cell" + index++, ApplyBlockPlanOperation.PLACE, target(position),
                    new BlockStateFingerprint("minecraft:air", Map.of()),
                    fingerprint(entry.getValue()),
                    Optional.of(BuiltInRegistries.ITEM.getKey(
                            entry.getValue().getBlock().asItem()).toString())));
        }
        return mutationRequest(steps, false);
    }

    private static ApplyBlockPlanRequest breakRequest(Map<BlockPos, BlockState> states) {
        var steps = new java.util.ArrayList<ApplyBlockPlanStep>();
        int index = 0;
        for (var entry : states.entrySet()) {
            BlockPos position = entry.getKey();
            steps.add(new ApplyBlockPlanStep(
                    "cell" + index++, ApplyBlockPlanOperation.BREAK_TO_AIR,
                    target(position), fingerprint(entry.getValue()),
                    new BlockStateFingerprint("minecraft:air", Map.of()), Optional.empty()));
        }
        return mutationRequest(steps, true);
    }

    private static ApplyBlockPlanRequest replaceRequest(BlockPos position) {
        var step = new ApplyBlockPlanStep(
                "replace", ApplyBlockPlanOperation.REPLACE, target(position),
                fingerprint(Blocks.STONE.defaultBlockState()),
                fingerprint(Blocks.COBBLESTONE.defaultBlockState()),
                Optional.of("minecraft:cobblestone"));
        return mutationRequest(List.of(step), true);
    }

    private static ApplyBlockPlanRequest mutationRequest(
            List<ApplyBlockPlanStep> steps, boolean allowBreak) {
        int minimumX = steps.stream().map(ApplyBlockPlanStep::target)
                .mapToInt(BlockTarget::x).min().orElseThrow();
        int maximumX = steps.stream().map(ApplyBlockPlanStep::target)
                .mapToInt(BlockTarget::x).max().orElseThrow();
        int minimumY = steps.stream().map(ApplyBlockPlanStep::target)
                .mapToInt(BlockTarget::y).min().orElseThrow();
        int maximumY = steps.stream().map(ApplyBlockPlanStep::target)
                .mapToInt(BlockTarget::y).max().orElseThrow();
        int minimumZ = steps.stream().map(ApplyBlockPlanStep::target)
                .mapToInt(BlockTarget::z).min().orElseThrow();
        int maximumZ = steps.stream().map(ApplyBlockPlanStep::target)
                .mapToInt(BlockTarget::z).max().orElseThrow();
        var bounds = new ActionBounds(
                "minecraft:overworld",
                new BlockTarget("minecraft:overworld", minimumX, minimumY, minimumZ),
                new BlockTarget("minecraft:overworld", maximumX, maximumY, maximumZ),
                0, 30, allowBreak);
        return new ApplyBlockPlanRequest("component", 1, 1, steps, bounds);
    }

    private static BlockTarget target(BlockPos position) {
        return new BlockTarget("minecraft:overworld",
                position.getX(), position.getY(), position.getZ());
    }

    private static BlockStateFingerprint fingerprint(BlockState state) {
        var properties = new TreeMap<String, String>();
        state.getValues().forEach(value ->
                properties.put(value.property().getName(), value.valueName()));
        return new BlockStateFingerprint(
                BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), properties);
    }

    private static net.minecraft.core.BlockPos firstTargetPosition() {
        return new net.minecraft.core.BlockPos(0, 64, 0);
    }

    private static net.minecraft.core.BlockPos secondTargetPosition() {
        return new net.minecraft.core.BlockPos(20, 64, 20);
    }

    private List<String> invocations(String resource, String methodName) throws Exception {
        var node = new ClassNode();
        try (var stream = getClass().getResourceAsStream(resource)) {
            assertThat(stream).isNotNull();
            new ClassReader(stream).accept(node, 0);
        }
        var method = node.methods.stream()
                .filter(candidate -> candidate.name.equals(methodName))
                .findFirst().orElseThrow();
        var calls = new java.util.ArrayList<String>();
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                calls.add(call.owner + "#" + call.name);
            }
        }
        return List.copyOf(calls);
    }

    @SafeVarargs
    private static AnnotationNode annotation(
            String descriptor, java.util.List<AnnotationNode>... annotationLists) {
        for (var annotations : annotationLists) {
            if (annotations == null) continue;
            var match = annotations.stream().filter(value -> value.desc.equals(descriptor))
                    .findFirst();
            if (match.isPresent()) return match.orElseThrow();
        }
        throw new AssertionError("missing annotation " + descriptor);
    }
}
