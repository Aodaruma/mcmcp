package dev.aod.mcmcp.routine;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
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
                (BlockItem) Items.OAK_SIGN)).isFalse();
        assertThat(MinecraftApplyBlockPlanPort.supportsPlacementItem(
                (BlockItem) Items.SCAFFOLDING)).isFalse();

        assertThat(MinecraftApplyBlockPlanPort.saturatedInventoryCount(64, 64))
                .isEqualTo(128);
        assertThat(MinecraftApplyBlockPlanPort.saturatedInventoryCount(
                Integer.MAX_VALUE - 4, 8)).isEqualTo(Integer.MAX_VALUE);
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
