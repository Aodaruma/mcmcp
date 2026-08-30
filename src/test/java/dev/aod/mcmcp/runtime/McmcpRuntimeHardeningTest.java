package dev.aod.mcmcp.runtime;

import com.google.gson.Gson;
import dev.aod.mcmcp.agent.action.AgentActionStore;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner;
import dev.aod.mcmcp.agent.action.MinecraftActionPrimitiveExecutor;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.dsl.ActionDslCompiler;
import dev.aod.mcmcp.agent.navigation.KnownTraversabilityMap;
import dev.aod.mcmcp.agent.safety.ObservationRecord;
import dev.aod.mcmcp.routine.ActionBounds;
import dev.aod.mcmcp.routine.ApplyBlockPlanOperation;
import dev.aod.mcmcp.routine.ApplyBlockPlanRequest;
import dev.aod.mcmcp.routine.ApplyBlockPlanStep;
import dev.aod.mcmcp.routine.BlockTarget;
import dev.aod.mcmcp.routine.BlockStateFingerprint;
import dev.aod.mcmcp.routine.BreakBlockRequest;
import dev.aod.mcmcp.routine.FinitePlanRequest;
import dev.aod.mcmcp.routine.InteractBlockRequest;
import dev.aod.mcmcp.routine.InteractEntityRequest;
import dev.aod.mcmcp.routine.NavigateToRequest;
import dev.aod.mcmcp.routine.PlaceBlockRequest;
import dev.aod.mcmcp.routine.RoutineEventRing;
import dev.aod.mcmcp.routine.RoutineFailure;
import dev.aod.mcmcp.routine.RoutineManager;
import dev.aod.mcmcp.routine.RoutineProgress;
import dev.aod.mcmcp.routine.SemanticActionPort;
import dev.aod.mcmcp.routine.RoutineSnapshot;
import dev.aod.mcmcp.routine.RoutineState;
import dev.aod.mcmcp.routine.StationaryBreakGoal;
import dev.aod.mcmcp.routine.StationaryBreakPort;
import dev.aod.mcmcp.routine.StationaryBreakRequest;
import dev.aod.mcmcp.routine.UseItemOnBlockRequest;
import dev.aod.mcmcp.safety.LocalArmingState;
import dev.aod.mcmcp.voice.VoiceChatSafetyController;
import org.junit.jupiter.api.Test;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class McmcpRuntimeHardeningTest {
    @Test
    void actionInputReleaseRetriesBoundedlyAndRequiresConfirmedSuccess() {
        var attempts = new AtomicInteger();
        assertThat(McmcpRuntime.boundedActionInputRelease(
                () -> attempts.incrementAndGet() == 3)).isTrue();
        assertThat(attempts).hasValue(3);

        attempts.set(0);
        assertThat(McmcpRuntime.boundedActionInputRelease(() -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("synthetic release failure");
        })).isFalse();
        assertThat(attempts).hasValue(McmcpRuntime.MAX_ACTION_INPUT_RELEASE_ATTEMPTS);
    }

    @Test
    void retainedTerminalIntentMustMatchAnAlreadyPublishedActionExactly() {
        UUID actionId = UUID.randomUUID();
        var progress = new AgentActionStore.Progress(
                AgentActionStore.Phase.FINISHED,
                null,
                0,
                0,
                0.0D,
                0.0D,
                0,
                0,
                0,
                0,
                false);
        var expectedFailure = new AgentActionStore.Failure(
                AgentActionStore.FailureCode.PATH_BLOCKED,
                true,
                List.of("original_reason"));
        var replacementFailure = new AgentActionStore.Failure(
                AgentActionStore.FailureCode.EMERGENCY_STOP,
                true,
                List.of("later_reason"));

        assertThat(McmcpRuntime.terminalMatchesSnapshot(
                new McmcpRuntime.PendingAgentTerminal(
                        actionId, McmcpRuntime.AgentTerminalKind.SUCCESS, null),
                new AgentActionStore.Snapshot(
                        actionId, AgentActionStore.State.SUCCEEDED, progress, null, List.of())))
                .isTrue();
        assertThat(McmcpRuntime.terminalMatchesSnapshot(
                new McmcpRuntime.PendingAgentTerminal(
                        actionId, McmcpRuntime.AgentTerminalKind.CANCEL, null),
                new AgentActionStore.Snapshot(
                        actionId, AgentActionStore.State.CANCELLED, progress,
                        new AgentActionStore.Failure(
                                AgentActionStore.FailureCode.CANCELLED_BY_CLIENT,
                                true,
                                List.of("client_request")),
                        List.of())))
                .isTrue();
        var failureIntent = new McmcpRuntime.PendingAgentTerminal(
                actionId, McmcpRuntime.AgentTerminalKind.FAILURE, expectedFailure);
        var laterIntent = new McmcpRuntime.PendingAgentTerminal(
                actionId, McmcpRuntime.AgentTerminalKind.FAILURE, replacementFailure);
        assertThat(McmcpRuntime.firstTerminalIntent(null, failureIntent))
                .isSameAs(failureIntent);
        assertThat(McmcpRuntime.firstTerminalIntent(failureIntent, laterIntent))
                .isSameAs(failureIntent);
        assertThat(McmcpRuntime.terminalMatchesSnapshot(
                failureIntent,
                new AgentActionStore.Snapshot(
                        actionId, AgentActionStore.State.FAILED, progress,
                        expectedFailure, List.of())))
                .isTrue();
        assertThat(McmcpRuntime.terminalMatchesSnapshot(
                failureIntent,
                new AgentActionStore.Snapshot(
                        actionId, AgentActionStore.State.FAILED, progress,
                        replacementFailure, List.of())))
                .isFalse();
        assertThat(McmcpRuntime.terminalMatchesSnapshot(
                failureIntent,
                new AgentActionStore.Snapshot(
                        actionId, AgentActionStore.State.CANCELLED, progress,
                        expectedFailure, List.of())))
                .isFalse();
    }

    @Test
    void visualBarrierMustMatchTheExactReconciliationSessionAndMapRevision() {
        UUID session = UUID.randomUUID();
        var signals = new ClientReconciliationSignals.SessionChannel();
        signals.bindAndSnapshot(session);
        signals.worldMutation(
                ClientReconciliationSignals.WorldMutation.Kind.BLOCK,
                1, 65, 1,
                ClientReconciliationSignals.NavigationImpact.NONE);
        var map = new KnownTraversabilityMap();
        map.startSession(session, "minecraft:overworld", 1L);

        assertThat(McmcpRuntime.visualBarrierWorldRevision(
                map.snapshot().orElseThrow(), signals.snapshot())).isZero();
        var neutralSurfaceBarriers = McmcpRuntime.surfaceRevisionBarrier(
                map.snapshot().orElseThrow(), signals.snapshot());
        assertThat(neutralSurfaceBarriers.applyAsLong(
                new ActionDsl.Position("minecraft:overworld", 1, 65, 1))).isEqualTo(1L);
        assertThat(neutralSurfaceBarriers.applyAsLong(
                new ActionDsl.Position("minecraft:overworld", 9, 65, 9))).isZero();
        var waitSurfaceBarriers = McmcpRuntime.waitTargetSurfaceRevisionBarrier(
                map.snapshot().orElseThrow(), signals.snapshot());
        assertThat(waitSurfaceBarriers.applyAsLong(
                new ActionDsl.Position("minecraft:overworld", 1, 65, 1))).isEqualTo(1L);
        assertThat(waitSurfaceBarriers.applyAsLong(
                new ActionDsl.Position("minecraft:overworld", 9, 65, 9))).isZero();

        signals.worldMutation(
                ClientReconciliationSignals.WorldMutation.Kind.BLOCK,
                2, 64, 1,
                ClientReconciliationSignals.NavigationImpact.LOCAL);
        map.advanceWorldRevision(2L, List.of(), List.of());
        assertThat(McmcpRuntime.visualBarrierWorldRevision(
                map.snapshot().orElseThrow(), signals.snapshot())).isEqualTo(2L);
        assertThat(McmcpRuntime.surfaceRevisionBarrier(
                map.snapshot().orElseThrow(), signals.snapshot()).applyAsLong(
                        new ActionDsl.Position("minecraft:overworld", 9, 65, 9)))
                .isEqualTo(2L);
        assertThat(McmcpRuntime.waitTargetSurfaceRevisionBarrier(
                map.snapshot().orElseThrow(), signals.snapshot()).applyAsLong(
                        new ActionDsl.Position("minecraft:overworld", 1, 65, 1)))
                .isEqualTo(2L);

        var staleMap = new KnownTraversabilityMap();
        staleMap.startSession(session, "minecraft:overworld", 1L);
        assertThatThrownBy(() -> McmcpRuntime.visualBarrierWorldRevision(
                staleMap.snapshot().orElseThrow(), signals.snapshot()))
                .isInstanceOf(AgentPrimitivePlanner.PlanningException.class);
        assertThatThrownBy(() -> McmcpRuntime.waitTargetSurfaceRevisionBarrier(
                staleMap.snapshot().orElseThrow(), signals.snapshot()))
                .isInstanceOf(AgentPrimitivePlanner.PlanningException.class);

        var otherSessionMap = new KnownTraversabilityMap();
        otherSessionMap.startSession(UUID.randomUUID(), "minecraft:overworld", 2L);
        assertThatThrownBy(() -> McmcpRuntime.visualBarrierWorldRevision(
                otherSessionMap.snapshot().orElseThrow(), signals.snapshot()))
                .isInstanceOf(AgentPrimitivePlanner.PlanningException.class);
        assertThatThrownBy(() -> McmcpRuntime.waitTargetSurfaceRevisionBarrier(
                otherSessionMap.snapshot().orElseThrow(), signals.snapshot()))
                .isInstanceOf(AgentPrimitivePlanner.PlanningException.class);
    }

    @Test
    void visibleItemPickupRequiresAnAbsoluteInventoryIncrease() {
        assertThat(McmcpRuntime.pickupInventoryIncreased(10, 11)).isTrue();
        assertThat(McmcpRuntime.pickupInventoryIncreased(10, 10)).isFalse();
        assertThat(McmcpRuntime.pickupInventoryIncreased(10, 9)).isFalse();
        assertThatThrownBy(() -> McmcpRuntime.pickupInventoryIncreased(-1, 0))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(McmcpRuntime.pickupOccurrenceBaseline(-1, 10)).isEqualTo(10);
        assertThat(McmcpRuntime.pickupOccurrenceBaseline(10, 11)).isEqualTo(10);
        assertThatThrownBy(() -> McmcpRuntime.pickupOccurrenceBaseline(-2, 10))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(McmcpRuntime.visibleItemEvidenceMaxAgeTicks(512)).isEqualTo(4);
        assertThat(McmcpRuntime.visibleItemEvidenceMaxAgeTicks(64)).isEqualTo(32);
        assertThatThrownBy(() -> McmcpRuntime.visibleItemEvidenceMaxAgeTicks(63))
                .isInstanceOf(IllegalArgumentException.class);

        var player = new AABB(0.2D, 64.0D, 0.2D, 0.8D, 65.8D, 0.8D);
        assertThat(McmcpRuntime.playerPickupAreaIntersects(
                player,
                new dev.aod.mcmcp.agent.observation.ObservationValues.Aabb(
                        1.7D, 64.0D, 0.4D, 1.9D, 64.25D, 0.6D)))
                .isTrue();
        assertThat(McmcpRuntime.playerPickupAreaIntersects(
                player,
                new dev.aod.mcmcp.agent.observation.ObservationValues.Aabb(
                        0.4D, 66.4D, 0.4D, 0.6D, 66.65D, 0.6D)))
                .isFalse();
    }

    @Test
    void staticCompilationCountsFutureMutationsWithoutPlanningFutureWorldStates() {
        var support = new ActionDsl.Position("minecraft:overworld", 1, 64, 1);
        var crop = new ActionDsl.Position("minecraft:overworld", 1, 65, 1);
        var request = new ActionDsl.Request(
                1,
                new ActionDsl.Program(
                        1,
                        Optional.of("jit_wheat"),
                        Set.of(
                                ActionDsl.Capability.CAMERA,
                                ActionDsl.Capability.BLOCK_INTERACT,
                                ActionDsl.Capability.BLOCK_PLACE,
                                ActionDsl.Capability.BLOCK_BREAK),
                        List.of(
                                new ActionDsl.TillKnownBlock(
                                        "till", support, "minecraft:dirt", "minecraft:iron_hoe"),
                                new ActionDsl.PlantKnownWheat(
                                        "plant", crop, support, "minecraft:wheat_seeds"),
                                new ActionDsl.WaitUntil(
                                        "grow", new ActionDsl.CropMatureCondition(crop), 10),
                                new ActionDsl.HarvestKnownWheat("harvest", crop))),
                new ActionDsl.Budget(10_000, 100, 0, 0, 1, 1, 1));

        var compiled = ActionDslCompiler.compile(
                request,
                McmcpRuntime::structuralPrimitiveCost,
                request.program().capabilities());

        assertThat(compiled.worstCaseCost()).isEqualTo(
                new ActionDslCompiler.Cost(500, 10, 0, 0, 1, 1, 1));
        assertThat(compiled.primitiveCostBounds().get("plant").ticks()).isZero();
        assertThat(McmcpRuntime.primitiveReobservationTicks(request.program().body().get(1)))
                .isEqualTo(AgentPrimitivePlanner.BREAK_REOBSERVATION_TICKS);
        assertThat(McmcpRuntime.primitiveReobservationTicks(
                new ActionDsl.NavigateToKnown("move", support, 0.75)))
                .isEqualTo(AgentPrimitivePlanner.BREAK_REOBSERVATION_TICKS);
        assertThat(McmcpRuntime.primitiveReobservationTicks(
                new ActionDsl.FaceKnownPosition("face", support)))
                .isEqualTo(AgentPrimitivePlanner.BREAK_REOBSERVATION_TICKS);
        assertThat(McmcpRuntime.structuralPrimitiveCost(
                new ActionDsl.OpenKnownFenceGate("open_gate", support))).contains(
                        new ActionDslCompiler.Cost(0, 0, 0, 0, 1, 0, 0));
        assertThat(McmcpRuntime.structuralPrimitiveCost(
                new ActionDsl.InspectKnownContainer(
                        "inspect", support, "minecraft:chest"))).contains(
                        new ActionDslCompiler.Cost(0, 0, 0, 0, 1, 0, 0));
        assertThat(McmcpRuntime.structuralPrimitiveCost(
                new ActionDsl.TakeKnownContainerStack(
                        "take", support, "minecraft:chest", "minecraft:wheat_seeds",
                        "default_components_only", 64))).contains(
                        new ActionDslCompiler.Cost(0, 0, 0, 0, 3, 0, 0));
        assertThat(McmcpRuntime.primitiveReobservationTicks(
                new ActionDsl.OpenKnownFenceGate("open_gate", support)))
                .isEqualTo(AgentPrimitivePlanner.BREAK_REOBSERVATION_TICKS);
        assertThat(McmcpRuntime.primitiveReobservationTicks(new ActionDsl.WaitTicks("hold", 1)))
                .isZero();
    }

    @Test
    void waitNodeStartMustFitItsWholeRemainingBound() {
        var usedAtBoundary = new AgentActionStore.Progress(
                AgentActionStore.Phase.EXECUTING,
                "grow",
                1,
                2,
                0,
                0,
                0,
                0,
                0,
                10,
                false);
        var budget = new ActionDsl.Budget(2_000, 30, 0, 0, 0, 0, 0);
        var waitCost = new ActionDslCompiler.Cost(1_000, 20, 0, 0, 0, 0, 0);

        assertThat(McmcpRuntime.fitsRemainingBudget(
                usedAtBoundary,
                budget,
                waitCost,
                Duration.ofMillis(1_000).toNanos())).isTrue();
        assertThat(McmcpRuntime.fitsRemainingBudget(
                new AgentActionStore.Progress(
                        AgentActionStore.Phase.EXECUTING,
                        "grow",
                        1,
                        2,
                        0,
                        0,
                        0,
                        0,
                        0,
                        11,
                        false),
                budget,
                waitCost,
                Duration.ofMillis(1_000).toNanos())).isFalse();
        assertThat(McmcpRuntime.fitsRemainingBudget(
                usedAtBoundary,
                budget,
                waitCost,
                Duration.ofMillis(1_001).toNanos())).isFalse();
    }

    @Test
    void onlyAimRaycastFailureRebindsWithoutErasingConsumedOccurrenceBudget() {
        assertThat(McmcpRuntime.retryableMutationAimFailure("aim_raycast_unavailable")).isTrue();
        assertThat(McmcpRuntime.retryableMutationAimFailure("mutation_precondition_changed"))
                .isFalse();
        assertThat(McmcpRuntime.retryableMutationAimFailure("AIM_RAYCAST_UNAVAILABLE")).isFalse();

        var baseline = new AgentActionStore.Progress(
                AgentActionStore.Phase.EXECUTING,
                "plant",
                0,
                1,
                0,
                10,
                0,
                0,
                0,
                20,
                false);
        var used = new AgentActionStore.Progress(
                AgentActionStore.Phase.REPLANNING,
                "plant",
                0,
                1,
                0,
                25,
                0,
                0,
                0,
                25,
                false);
        var rebound = new ActionDslCompiler.Cost(5_050, 101, 0, 5, 0, 0, 1);

        assertThat(McmcpRuntime.occurrenceCostIncludingConsumed(used, baseline, rebound))
                .isEqualTo(new ActionDslCompiler.Cost(5_300, 106, 0, 20, 0, 0, 1));

        assertThat(McmcpRuntime.mutationAimRetryAllowed(1)).isTrue();
        assertThat(McmcpRuntime.mutationAimRetryAllowed(2)).isTrue();
        assertThat(McmcpRuntime.mutationAimRetryAllowed(3)).isFalse();
        assertThatThrownBy(() -> McmcpRuntime.mutationAimRetryAllowed(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cropWaitReadsOnlyTheAuthorizedLiveWheatState() {
        var target = new ActionDsl.Position("minecraft:overworld", 2, 65, 3);
        var wait = new ActionDsl.WaitUntil(
                "wait", new ActionDsl.CropMatureCondition(target), 20);
        var sessions = new WorldSessionTracker();
        sessions.latchReady(target.dimension());
        sessions.tick();
        var playerPosition = new Vec3(1.5D, 64.0D, 1.5D);
        var observerEye = new Vec3(1.5D, 65.62D, 1.5D);
        var witnessEye = observerEye.add(
                McmcpRuntime.CROP_WAIT_OBSERVER_EPSILON_BLOCKS / 2.0D, 0.0D, 0.0D);
        var analysis = new AgentPrimitivePlanner.Analysis(
                Map.of("wait", ActionDslCompiler.intrinsicWaitCost(20)),
                Map.of(),
                Set.of(),
                Set.of(new AgentPrimitivePlanner.KnownSurface(
                        target, ActionDsl.BlockFace.UP, "minecraft:wheat", null, witnessEye)),
                Map.of(),
                Map.of());
        var authorization = McmcpRuntime.requireCropWaitAuthorization(
                sessions.snapshot(), wait, analysis,
                4L, playerPosition, observerEye);

        var staleOriginAnalysis = new AgentPrimitivePlanner.Analysis(
                Map.of("wait", ActionDslCompiler.intrinsicWaitCost(20)),
                Map.of(),
                Set.of(),
                Set.of(new AgentPrimitivePlanner.KnownSurface(
                        target,
                        ActionDsl.BlockFace.UP,
                        "minecraft:wheat",
                        null,
                        observerEye.add(1.0D, 0.0D, 0.0D))),
                Map.of(),
                Map.of());
        assertThatThrownBy(() -> McmcpRuntime.requireCropWaitAuthorization(
                sessions.snapshot(), wait, staleOriginAnalysis,
                4L, playerPosition, observerEye))
                .isInstanceOf(IllegalStateException.class);

        assertThat(authorization.target()).isEqualTo(target);
        assertThat(authorization.observerEye()).isEqualTo(witnessEye);
        assertThat(McmcpRuntime.cropWaitVisibilityState(
                authorization,
                sessions.snapshot(),
                target,
                4L,
                playerPosition,
                observerEye)).isEqualTo(McmcpRuntime.CropWaitVisibilityState.CURRENT);
        assertThat(McmcpRuntime.cropWaitLiveState(
                true, Blocks.WHEAT.defaultBlockState())).isEqualTo(
                        McmcpRuntime.CropWaitLiveState.PENDING);
        assertThat(McmcpRuntime.cropWaitLiveState(
                true,
                Blocks.WHEAT.defaultBlockState().setValue(BlockStateProperties.AGE_7, 7)))
                .isEqualTo(McmcpRuntime.CropWaitLiveState.MATURE);
        assertThat(McmcpRuntime.cropWaitLiveState(
                true, Blocks.STONE.defaultBlockState())).isEqualTo(
                        McmcpRuntime.CropWaitLiveState.TARGET_CHANGED);
        assertThat(McmcpRuntime.cropWaitLiveState(false, null)).isEqualTo(
                McmcpRuntime.CropWaitLiveState.UNLOADED);

        // A navigation-neutral exact-target wheat AGE update leaves the visual
        // barrier unchanged, so both pending and mature live reads remain authorized.
        assertThat(McmcpRuntime.cropWaitVisibilityState(
                authorization,
                sessions.snapshot(),
                target,
                4L,
                playerPosition,
                observerEye)).isEqualTo(McmcpRuntime.CropWaitVisibilityState.CURRENT);
        assertThat(McmcpRuntime.cropWaitVisibilityState(
                authorization,
                sessions.snapshot(),
                target,
                5L,
                playerPosition,
                observerEye)).isEqualTo(
                        McmcpRuntime.CropWaitVisibilityState.VISIBILITY_INVALIDATED);
        assertThat(McmcpRuntime.cropWaitVisibilityState(
                authorization,
                sessions.snapshot(),
                target,
                4L,
                playerPosition.add(
                        McmcpRuntime.CROP_WAIT_OBSERVER_EPSILON_BLOCKS * 2.0D, 0.0D, 0.0D),
                observerEye)).isEqualTo(
                        McmcpRuntime.CropWaitVisibilityState.VISIBILITY_INVALIDATED);
        assertThat(McmcpRuntime.cropWaitVisibilityState(
                authorization,
                sessions.snapshot(),
                target,
                4L,
                playerPosition,
                observerEye.add(
                        0.0D, McmcpRuntime.CROP_WAIT_OBSERVER_EPSILON_BLOCKS * 2.0D, 0.0D)))
                .isEqualTo(McmcpRuntime.CropWaitVisibilityState.VISIBILITY_INVALIDATED);

        var differentSession = new WorldSessionTracker();
        differentSession.latchReady(target.dimension());
        assertThat(authorization.matches(differentSession.snapshot(), target)).isFalse();
        assertThat(authorization.matches(
                sessions.snapshot(),
                new ActionDsl.Position(target.dimension(), 3, 65, 3))).isFalse();
        assertThat(McmcpRuntime.cropWaitVisibilityState(
                authorization,
                differentSession.snapshot(),
                target,
                4L,
                playerPosition,
                observerEye)).isEqualTo(McmcpRuntime.CropWaitVisibilityState.WORLD_CHANGED);
        assertThat(AgentActionStore.FailureCode.CONDITION_TIMEOUT.wireName())
                .isEqualTo("CONDITION_TIMEOUT");
    }

    @Test
    void recoveryEvidenceUsesSessionClockAcrossImmediateCollectNodeCompletion() {
        var sessions = new WorldSessionTracker();
        sessions.latchReady("minecraft:overworld");
        long unchangedActionProgressTick = 3L;

        sessions.tick();
        long pickupA = McmcpRuntime.recoveryEvidenceClientTick(sessions.snapshot());
        sessions.tick();
        long pickupB = McmcpRuntime.recoveryEvidenceClientTick(sessions.snapshot());

        assertThat(unchangedActionProgressTick).isEqualTo(3L);
        assertThat(pickupB).isEqualTo(pickupA + 1L);
    }

    @Test
    void completedBreakReobservationDoesNotReserveTheSameWaitTwice() {
        var planned = new ActionDslCompiler.Cost(5_500, 110, 0, 15, 0, 1, 0);

        assertThat(McmcpRuntime.breakExecutionCost(planned, false)).isEqualTo(planned);
        assertThat(McmcpRuntime.breakAimTicks(planned)).isEqualTo(10);
        assertThat(McmcpRuntime.breakExecutionCost(planned, true)).isEqualTo(
                new ActionDslCompiler.Cost(
                        3_500,
                        110 - AgentPrimitivePlanner.BREAK_REOBSERVATION_TICKS,
                        0,
                        15,
                        0,
                        1,
                        0));
        assertThat(McmcpRuntime.agentReplanWindowTicks(new ActionDsl.BreakKnownFace(
                "chop",
                new ActionDsl.Position("minecraft:overworld", 1, 64, 1),
                ActionDsl.BlockFace.WEST,
                "minecraft:oak_log",
                "minecraft:iron_axe")))
                .isEqualTo(AgentPrimitivePlanner.BREAK_REOBSERVATION_TICKS);
    }

    @Test
    void recoveryGeometryUsesAabbCenterRatherThanFeetHeight() {
        var center = new Vec3(0.5D, 64.9D, 0.5D);
        var horizontal = new Vec3(1.5D, 64.9D, 0.5D);
        var upward = new Vec3(1.5D, 65.2D, 0.5D);

        assertThat(McmcpRuntime.requiresRecoveryJump(center.y, horizontal)).isFalse();
        assertThat(McmcpRuntime.recoveryDistance(center, horizontal)).isEqualTo(1.0D);
        assertThat(McmcpRuntime.requiresRecoveryJump(center.y, upward)).isTrue();
        assertThat(McmcpRuntime.recoveryDistance(center, upward)).isCloseTo(
                1.3D, org.assertj.core.data.Offset.offset(1.0E-9D));
        assertThat(McmcpRuntime.recoveryCandidateId("exit", horizontal))
                .isNotEqualTo(McmcpRuntime.recoveryCandidateId("exit", upward));
    }

    @Test
    void replannedPrimitiveMustFitEveryRemainingBudgetComponent() {
        var used = new AgentActionStore.Progress(
                AgentActionStore.Phase.REPLANNING,
                "move",
                1,
                4,
                3.0D,
                10.0D,
                0,
                0,
                0,
                40,
                false);
        var budget = new ActionDsl.Budget(10_000, 100, 8, 90, 0, 0, 0);
        var fits = new ActionDslCompiler.Cost(2_000, 40, 5, 80, 0, 0, 0);
        var tooFar = new ActionDslCompiler.Cost(2_000, 40, 5.01D, 80, 0, 0, 0);

        assertThat(McmcpRuntime.fitsRemainingBudget(
                used, budget, fits, Duration.ofMillis(8_000).toNanos())).isTrue();
        assertThat(McmcpRuntime.fitsRemainingBudget(
                used, budget, tooFar, Duration.ofMillis(8_000).toNanos())).isFalse();
        assertThat(McmcpRuntime.fitsRemainingBudget(
                used, budget, fits, Duration.ofMillis(8_001).toNanos())).isFalse();
        assertThat(McmcpRuntime.fitsRemainingBudget(
                new AgentActionStore.Progress(
                        AgentActionStore.Phase.EXECUTING, "move", 0, 0,
                        0, 0, 0, 0, 0, 0, false),
                new ActionDsl.Budget(100, 100, 8, 90, 0, 0, 0),
                new ActionDslCompiler.Cost(50, 1, 0, 0, 0, 0, 0),
                Duration.ofMillis(50).toNanos() + 1)).isFalse();
    }

    @Test
    void anActivePrimitiveCannotEmitAgainAtItsExactMotionLimit() {
        var used = new AgentActionStore.Progress(
                AgentActionStore.Phase.EXECUTING,
                "move",
                0,
                1,
                8.0D,
                90.0D,
                0,
                0,
                0,
                10,
                false);
        var budget = new ActionDsl.Budget(10_000, 100, 8, 90, 0, 0, 0);
        var target = new ActionDsl.Position("minecraft:overworld", 1, 64, 1);

        assertThat(McmcpRuntime.motionBudgetExhausted(
                used, budget, new ActionDsl.NavigateToKnown("move", target, 0.75D))).isTrue();
        assertThat(McmcpRuntime.motionBudgetExhausted(
                used, budget, new ActionDsl.FaceKnownPosition("face", target))).isTrue();
        assertThat(McmcpRuntime.motionBudgetExhausted(
                used, budget, new ActionDsl.WaitTicks("hold", 1))).isFalse();
        var move = new ActionDsl.NavigateToKnown("move", target, 0.75D);
        assertThat(McmcpRuntime.motionBudgetExceededAfterPrimitive(
                used,
                budget,
                move,
                dev.aod.mcmcp.agent.action.MinecraftActionPrimitiveExecutor.Status.SUCCEEDED))
                .isFalse();
        assertThat(McmcpRuntime.motionBudgetExceededAfterPrimitive(
                used,
                budget,
                move,
                dev.aod.mcmcp.agent.action.MinecraftActionPrimitiveExecutor.Status.RUNNING))
                .isFalse();
        assertThat(McmcpRuntime.motionBudgetExceededAfterPrimitive(
                used,
                budget,
                move,
                dev.aod.mcmcp.agent.action.MinecraftActionPrimitiveExecutor.Status.REPLAN_REQUIRED))
                .isTrue();
    }

    @Test
    void replanGraceEndsAtTheFixedDeadlineRatherThanSlidingForever() {
        assertThat(McmcpRuntime.replanDeadlineReached(119, 120)).isFalse();
        assertThat(McmcpRuntime.replanDeadlineReached(120, 120)).isTrue();
        assertThat(McmcpRuntime.replanDeadlineReached(121, 120)).isTrue();
    }

    @Test
    void acceptedProbeStartsTheSameReplanHeartbeatVerificationAsOtherRunningWork() {
        var probe = new MinecraftActionPrimitiveExecutor.TickResult(
                MinecraftActionPrimitiveExecutor.Status.RUNNING,
                MinecraftActionPrimitiveExecutor.Reason.PROBE_MICRO_STEP);
        var ordinary = new MinecraftActionPrimitiveExecutor.TickResult(
                MinecraftActionPrimitiveExecutor.Status.RUNNING,
                MinecraftActionPrimitiveExecutor.Reason.NONE);
        var terminal = new MinecraftActionPrimitiveExecutor.TickResult(
                MinecraftActionPrimitiveExecutor.Status.REPLAN_REQUIRED,
                MinecraftActionPrimitiveExecutor.Reason.ROUTE_EDGE_CHANGED);

        assertThat(McmcpRuntime.shouldVerifyReplanHeartbeat(true, probe)).isTrue();
        assertThat(McmcpRuntime.shouldVerifyReplanHeartbeat(true, ordinary)).isTrue();
        assertThat(McmcpRuntime.shouldVerifyReplanHeartbeat(false, probe)).isFalse();
        assertThat(McmcpRuntime.shouldVerifyReplanHeartbeat(true, terminal)).isFalse();
    }

    @Test
    void navigationReplanCanUseItsAdmittedOccurrenceTicks() {
        var target = new ActionDsl.Position("minecraft:overworld", 1, 64, 1);
        var navigate = new ActionDsl.NavigateToKnown("move", target, 0.75D);
        var face = new ActionDsl.FaceKnownPosition("face", target);
        var collectBatch = new ActionDsl.CollectVisibleItemBatch(
                "drops",
                List.of(
                        new ActionDsl.CollectTarget(
                                "minecraft:wheat",
                                new ActionDsl.WorldPosition(
                                        "minecraft:overworld", 1.5D, 64.1D, 1.5D)),
                        new ActionDsl.CollectTarget(
                                "minecraft:wheat",
                                new ActionDsl.WorldPosition(
                                        "minecraft:overworld", 2.5D, 64.1D, 1.5D))));

        assertThat(McmcpRuntime.agentReplanDeadlineTick(navigate, 2, 0, 38))
                .isEqualTo(39);
        assertThat(McmcpRuntime.agentReplanDeadlineTick(face, 2, 0, 38))
                .isEqualTo(22);
        assertThat(McmcpRuntime.agentReplanDeadlineTick(collectBatch, 2, 0, 38))
                .isEqualTo(22);
    }

    @Test
    void onlyOneSingleRevisionPositionCorrectionGetsAReplanChance() {
        assertThat(McmcpRuntime.repeatedPositionCorrection(7, 8, 0)).isFalse();
        assertThat(McmcpRuntime.repeatedPositionCorrection(7, 9, 0)).isTrue();
        assertThat(McmcpRuntime.repeatedPositionCorrection(8, 9, 1)).isTrue();
    }

    @Test
    void localVolumeLavaAndDangerousDropFeedTheRecoveryGovernorBeforeVanillaFlagsCatchUp() {
        var hazards = McmcpRuntime.recoveryHazards(
                ObservationRecord.Fluid.LAVA,
                ObservationRecord.Hazard.FALL,
                false,
                true,
                0.0D,
                0.0D);

        assertThat(hazards.inLava()).isTrue();
        assertThat(hazards.onGround()).isFalse();
        assertThat(hazards.verticalVelocity()).isLessThan(-0.08D);
        assertThat(hazards.descentSinceGround()).isGreaterThan(3.0D);
    }

    @Test
    void recoveryDescentAccumulatesOnlyRealDropsAndRetainsTheWorstEvidence() {
        var tracker = new McmcpRuntime.RecoveryDescentTracker();
        var player = new Object();
        var level = new Object();
        var session = UUID.randomUUID();

        assertThat(tracker.update(player, level, session, 70, false, false, 0)).isZero();
        assertThat(tracker.update(player, level, session, 68, false, false, 0)).isEqualTo(2);
        assertThat(tracker.update(player, level, session, 69, false, false, 0)).isEqualTo(2);
        assertThat(tracker.update(player, level, session, 50, false, false, 1)).isEqualTo(2);
        assertThat(tracker.update(player, level, session, 49, false, false, 1)).isEqualTo(3);
        assertThat(tracker.current(player, level, session)).isEqualTo(3);
        assertThat(tracker.update(player, level, session, 48, true, false, 1)).isZero();
    }

    @Test
    void pendingMotionNeverCrossesAWorldOrDimensionBoundary() {
        UUID sessionId = UUID.randomUUID();
        var current = new WorldSessionTracker.Snapshot(
                WorldSessionTracker.Readiness.WORLD_READY,
                4,
                10,
                sessionId,
                "minecraft:overworld");

        assertThat(McmcpRuntime.sameAgentMotionBoundary(
                sessionId, current, "minecraft:overworld")).isTrue();
        assertThat(McmcpRuntime.sameAgentMotionBoundary(
                UUID.randomUUID(), current, "minecraft:overworld")).isFalse();
        assertThat(McmcpRuntime.sameAgentMotionBoundary(
                sessionId, current, "minecraft:the_nether")).isFalse();
    }

    @Test
    void cameraMeterDoesNotRoundFloatEndpointsBeforeAccumulation() {
        float yaw = -14.365085F;
        float pitch = -8.478999F;
        float previousYaw = 1.5507406F;
        float previousPitch = -40.073437F;

        assertThat(McmcpRuntime.cameraDelta(yaw, pitch, previousYaw, previousPitch))
                .isEqualTo(
                        Math.abs((double) yaw - previousYaw)
                                + Math.abs((double) pitch - previousPitch));
    }

    @Test
    void tillSettlingCreditExcludesOnlyPassiveOneSixteenthDescent() {
        var previous = new Vec3(0.5D, 65.0D, 0.5D);
        var oneSixteenthDown = new Vec3(0.5D, 64.9375D, 0.5D);

        assertThat(McmcpRuntime.batchTillSettlingCredit(
                previous, oneSixteenthDown, 1.0D / 16.0D, true, true))
                .isEqualTo(1.0D / 16.0D);
        assertThat(McmcpRuntime.batchTillSettlingCredit(
                previous, new Vec3(0.50001D, 64.9375D, 0.5D),
                1.0D / 16.0D, true, true)).isZero();
        assertThat(McmcpRuntime.batchTillSettlingCredit(
                previous, new Vec3(0.5D, 64.9374D, 0.5D),
                1.0D / 16.0D, true, true)).isZero();
        assertThat(McmcpRuntime.batchTillSettlingCredit(
                previous, new Vec3(0.5D, 65.0625D, 0.5D),
                1.0D / 16.0D, true, true)).isZero();
        assertThat(McmcpRuntime.batchTillSettlingCredit(
                previous, oneSixteenthDown, 1.0D / 16.0D, false, true)).isZero();
        assertThat(McmcpRuntime.batchTillSettlingCredit(
                previous, oneSixteenthDown, Double.NaN, true, true)).isZero();
        assertThat(McmcpRuntime.batchTillSettlingCredit(
                previous, oneSixteenthDown, 1.0D / 16.0D, true, false)).isZero();
    }

    @Test
    void mutationBatchStopsAfterAnUnconfirmedTargetAndNeverDispatchesTheRemainder() {
        assertThat(McmcpRuntime.mutationBatchDisposition(0, 3, false))
                .isEqualTo(McmcpRuntime.BatchTargetDisposition.STOP);
        assertThat(McmcpRuntime.mutationBatchDisposition(0, 3, true))
                .isEqualTo(McmcpRuntime.BatchTargetDisposition.CONTINUE);
        assertThat(McmcpRuntime.mutationBatchDisposition(2, 3, true))
                .isEqualTo(McmcpRuntime.BatchTargetDisposition.COMPLETE);
        assertThat(AgentPrimitivePlanner.MUTATION_BATCH_REPROOF_TICKS).isEqualTo(40L);
        var position = new ActionDsl.Position("minecraft:overworld", 0, 64, 0);
        assertThat(McmcpRuntime.mutationAimRetriesAllowed(
                new ActionDsl.TillKnownBatch(
                        "batch", List.of(position),
                        "minecraft:dirt", "minecraft:iron_hoe"))).isFalse();
        assertThat(McmcpRuntime.mutationAimRetriesAllowed(
                new ActionDsl.TillKnownBlock(
                        "single", position,
                        "minecraft:dirt", "minecraft:iron_hoe"))).isTrue();
    }

    @Test
    void mutationBatchReservesFreshCurrentAndTheEntireUnstartedSuffix() {
        var position = new ActionDsl.Position("minecraft:overworld", 0, 64, 0);
        var oldAim = new AgentPrimitivePlanner.MutationAim(
                position, ActionDsl.BlockFace.UP, new Vec3(0.5D, 65.0D, 0.5D));
        var secondAim = new AgentPrimitivePlanner.MutationAim(
                new ActionDsl.Position("minecraft:overworld", 1, 64, 0),
                ActionDsl.BlockFace.UP,
                new Vec3(3.5D, 65.0D, 0.5D));
        var thirdAim = new AgentPrimitivePlanner.MutationAim(
                new ActionDsl.Position("minecraft:overworld", 2, 64, 0),
                ActionDsl.BlockFace.UP,
                new Vec3(4.5D, 65.0D, 0.5D));
        var firstPlanned = new ActionDslCompiler.Cost(2_500, 50, 0, 1, 1, 0, 0);
        var secondPlanned = new ActionDslCompiler.Cost(2_000, 40, 0, 1, 1, 0, 0);
        var thirdPlanned = new ActionDslCompiler.Cost(1_500, 30, 0, 1, 1, 0, 0);
        var plan = new AgentPrimitivePlanner.MutationBatchPlan(List.of(
                new AgentPrimitivePlanner.MutationBatchStep(
                        new ActionDsl.TillKnownBlock(
                                "batch_0", position,
                                "minecraft:dirt", "minecraft:iron_hoe"),
                        oldAim,
                        firstPlanned),
                new AgentPrimitivePlanner.MutationBatchStep(
                        new ActionDsl.TillKnownBlock(
                                "batch_1", new ActionDsl.Position(
                                        "minecraft:overworld", 1, 64, 0),
                                "minecraft:dirt", "minecraft:iron_hoe"),
                        secondAim,
                        secondPlanned),
                new AgentPrimitivePlanner.MutationBatchStep(
                        new ActionDsl.TillKnownBlock(
                                "batch_2", new ActionDsl.Position(
                                        "minecraft:overworld", 2, 64, 0),
                                "minecraft:dirt", "minecraft:iron_hoe"),
                        thirdAim,
                        thirdPlanned)));
        var freshCurrent = new ActionDslCompiler.Cost(500, 10, 0, 5, 1, 0, 0);
        var currentPose = new AgentPrimitivePlanner.Pose(
                new dev.aod.mcmcp.agent.navigation.NavCell(
                        "minecraft:overworld", 0, 64, 0),
                0.5D, 64.0D, 0.5D, 1.62D, 0.0F, 0.0F);
        var freshAim = new AgentPrimitivePlanner.MutationAim(
                position, ActionDsl.BlockFace.UP, new Vec3(-3.5D, 65.0D, 0.5D));
        var oldPoseRequired = plan.requiredRemainder(0, freshCurrent);
        var required = McmcpRuntime.mutationBatchRequiredRemainder(
                plan, 0, currentPose, freshAim, freshCurrent, 4.5F);
        assertThat(required.interactions()).isEqualTo(3);
        assertThat(required.ticks()).isGreaterThanOrEqualTo(
                freshCurrent.ticks()
                        + 2L * (AgentPrimitivePlanner.BLOCK_MUTATION_TICK_UPPER_BOUND
                                + AgentPrimitivePlanner.MUTATION_BATCH_REPROOF_TICKS));
        assertThat(required.cameraDegrees()).isGreaterThan(oldPoseRequired.cameraDegrees());

        var baseline = new AgentActionStore.Progress(
                AgentActionStore.Phase.EXECUTING, "batch", 0, 1,
                0, 0, 0, 0, 0, 0, false);
        var used = new AgentActionStore.Progress(
                AgentActionStore.Phase.EXECUTING, "batch", 0, 1,
                0, 5, 0, 0, 0, 20, false);
        var occurrenceLimit = new ActionDslCompiler.Cost(
                required.durationMillis() + 1_000,
                required.ticks() + 20,
                0,
                required.cameraDegrees() + 5,
                3, 0, 0);
        var global = new ActionDsl.Budget(
                occurrenceLimit.durationMillis(),
                occurrenceLimit.ticks(),
                0,
                occurrenceLimit.cameraDegrees(),
                3, 0, 0);

        assertThat(McmcpRuntime.fitsMutationBatchRemainder(
                used, baseline, occurrenceLimit, global, required,
                Duration.ofMillis(1_000).toNanos())).isTrue();
        var oneTickTooLarge = new ActionDslCompiler.Cost(
                required.durationMillis() + 50,
                required.ticks() + 1,
                0,
                required.cameraDegrees(),
                3, 0, 0);
        assertThat(McmcpRuntime.fitsMutationBatchRemainder(
                used, baseline, occurrenceLimit, global, oneTickTooLarge,
                Duration.ofMillis(1_000).toNanos())).isFalse();
    }

    @Test
    void productionStateAdapterUsesTheNormativeAgentStateShape() {
        var lock = new LocalArmingState.Snapshot(
                LocalArmingState.Mode.READY,
                UUID.randomUUID(),
                Set.of("movement"),
                null,
                1L);

        var state = McmcpRuntime.statePayload(
                lock, false, null, List.of());

        assertThat(state.keySet()).containsExactly(
                "schema_version", "control", "world", "inventory",
                "standard_potions", "policy", "observation", "action");
        assertThat(state).containsEntry("schema_version", 1)
                .containsEntry("world", null)
                .containsEntry("inventory", List.of())
                .containsEntry("standard_potions", List.of())
                .containsEntry("observation", null)
                .containsEntry("action", null);
        var control = (Map<?, ?>) state.get("control");
        assertThat(control.get("mode")).isEqualTo("ready");
        assertThat(control.get("ready_expires_at")).isNull();
        assertThat(control.get("game_paused")).isEqualTo(false);
        var policy = (Map<?, ?>) state.get("policy");
        assertThat(policy.get("profile")).isEqualTo("survival_omnidirectional");
        assertThat(policy.get("max_duration_ms")).isEqualTo(600_000);
        assertThat(policy.get("max_ticks")).isEqualTo(12_000);
        assertThat(policy.get("max_distance_blocks")).isEqualTo(32);
        assertThat(policy.get("max_blocks_broken")).isEqualTo(8);
        assertThat(policy.get("max_interactions")).isEqualTo(16);
    }

    @Test
    void localPriorityStopRequestsBeforeItDrainsSynchronously() {
        var calls = new ArrayList<String>();

        McmcpRuntime.runPriorityStop(
                () -> calls.add("request"),
                () -> calls.add("drain"));

        assertThat(calls).containsExactly("request", "drain");
    }

    @Test
    void lifecycleFenceClearsEveryAutomationPortIncludingBlockPlans() {
        var calls = new ArrayList<String>();

        McmcpRuntime.clearAutomationPortSessions(
                () -> calls.add("stationary_break"),
                () -> calls.add("semantic_action"),
                () -> calls.add("apply_block_plan"));

        assertThat(calls).containsExactly(
                "stationary_break", "semantic_action", "apply_block_plan");

        calls.clear();
        McmcpRuntime.clearAutomationPortSessions(
                () -> {
                    calls.add("stationary_failed");
                    throw new IllegalStateException("fixture");
                },
                () -> calls.add("semantic_after_failure"),
                () -> calls.add("plan_after_failure"));
        assertThat(calls).containsExactly(
                "stationary_failed", "semantic_after_failure", "plan_after_failure");
    }

    @Test
    void priorityEventStopsBeforeTickWhileAnAbsentEventContinues() {
        var calls = new ArrayList<String>();

        boolean stopped = McmcpRuntime.runPriorityEventStopIfRequired(
                true,
                () -> calls.add("event_stop_requested"),
                () -> calls.add("active_and_pending_released"));
        if (!stopped) {
            calls.add("routine_tick");
        }

        assertThat(stopped).isTrue();
        assertThat(calls).containsExactly(
                "event_stop_requested", "active_and_pending_released");

        calls.clear();
        stopped = McmcpRuntime.runPriorityEventStopIfRequired(
                false,
                () -> calls.add("request"),
                () -> calls.add("drain"));
        if (!stopped) {
            calls.add("routine_tick");
        }

        assertThat(stopped).isFalse();
        assertThat(calls).containsExactly("routine_tick");
    }

    @Test
    void explicitArmingLockStopsBeforeTheNextRoutineTick() {
        var arming = new LocalArmingState();
        var sessionId = UUID.randomUUID();
        arming.arm(sessionId, Set.of("navigate_to"));
        arming.lock("local_ui_disabled");
        var locked = arming.snapshot(sessionId);
        var calls = new ArrayList<String>();

        var mayTick = McmcpRuntime.enforceActiveRoutineArming(
                locked,
                () -> calls.add("request:local_arming_locked"),
                () -> calls.add("drain"));
        if (mayTick) {
            calls.add("routine_tick");
        }

        assertThat(mayTick).isFalse();
        assertThat(locked.lastLockReason()).isEqualTo("local_ui_disabled");
        assertThat(calls).containsExactly("request:local_arming_locked", "drain");
    }

    @Test
    void routineWallClockDeadlineIsIndependentFromTheUnlimitedLocalArm() {
        var routineId = UUID.randomUUID();
        long negativeStart = -Duration.ofSeconds(100).toNanos();
        var negativeClock = McmcpRuntime.RoutineWallClockDeadline.start(
                routineId, 30, negativeStart);
        long nearWrap = Long.MAX_VALUE - Duration.ofSeconds(1).toNanos();
        var wrappedClock = McmcpRuntime.RoutineWallClockDeadline.start(
                routineId, 1, nearWrap);

        assertThat(negativeClock.durationNanos()).isEqualTo(Duration.ofSeconds(35).toNanos());
        assertThat(negativeClock.allows(
                routineId, negativeStart + Duration.ofSeconds(35).toNanos() - 1)).isTrue();
        assertThat(negativeClock.allows(
                routineId, negativeStart + Duration.ofSeconds(35).toNanos())).isFalse();
        assertThat(wrappedClock.allows(
                routineId, nearWrap + Duration.ofSeconds(5).toNanos())).isTrue();
        assertThat(wrappedClock.allows(
                routineId, nearWrap + Duration.ofSeconds(6).toNanos())).isFalse();
        var shiftedClock = negativeClock.shiftStart(Duration.ofSeconds(7).toNanos());
        assertThat(shiftedClock.allows(
                routineId, negativeStart + Duration.ofSeconds(42).toNanos() - 1)).isTrue();
        assertThat(shiftedClock.allows(
                routineId, negativeStart + Duration.ofSeconds(42).toNanos())).isFalse();
        assertThat(negativeClock.allows(UUID.randomUUID(), negativeStart)).isFalse();
        assertThat(McmcpRuntime.activeElapsedNanos(-100L, 20L, -50L)).isEqualTo(30L);
    }

    @Test
    void retainsVoiceOwnershipAfterAFailedEndSoFinalizationCanRetryRecovery() {
        var routineId = UUID.randomUUID();
        var failed = new McmcpRuntime.VoiceEndOutcome(
                false, "restore_readback_mismatch", true, true, false);
        var restored = new McmcpRuntime.VoiceEndOutcome(
                true, null, true, true, true);

        assertThat(McmcpRuntime.voiceRoutineAfterEnd(routineId, routineId, failed))
                .isEqualTo(routineId);
        assertThat(McmcpRuntime.voiceRoutineAfterEnd(routineId, routineId, restored))
                .isNull();
    }

    @Test
    void exposesBeginRollbackAndEndDiagnosticsAsSchemaSafeScalars() {
        var begin = new VoiceChatSafetyController.BeginResult(
                false,
                true,
                true,
                null,
                "mute_readback_mismatch",
                true,
                false,
                "restore_readback_mismatch");
        var details = new java.util.LinkedHashMap<>(
                McmcpRuntime.voiceBeginFailureDetails(begin));
        var end = new McmcpRuntime.VoiceEndOutcome(
                false, "voicechat_disconnected", true, true, false);

        var mapped = McmcpRuntime.mapFailure(
                McmcpRuntime.withVoiceEndFailureDiagnostics(
                        new ClientCommandInbox.CommandTimeoutException("start_routine"),
                        end));

        details.forEach((key, value) -> assertThat(value)
                .as("scalar diagnostic %s", key)
                .isInstanceOfAny(String.class, Number.class, Boolean.class));
        assertThat(details)
                .containsEntry("reason", "mute_readback_mismatch")
                .containsEntry("voice.rollback_attempted", true)
                .containsEntry("voice.rollback_restored", false)
                .containsEntry("voice.rollback_failure", "restore_readback_mismatch");
        assertThat(mapped.failure().code()).isEqualTo("server_busy");
        assertThat(mapped.failure().message()).isEqualTo("The client-thread deadline expired");
        assertThat(mapped.failure().retryable()).isTrue();
        assertThat(mapped.failure().details())
                .containsEntry("voice.end_succeeded", false)
                .containsEntry("voice.end_session_existed", true)
                .containsEntry("voice.end_restore_attempted", true)
                .containsEntry("voice.end_restored", false)
                .containsEntry("voice.end_failure", "voicechat_disconnected");
    }

    @Test
    void turnsARetriedRecordExceptionIntoAVisibleFinalizationFailure() {
        var failure = McmcpRuntime.finalizationFailure(
                terminalSnapshot(),
                true,
                true,
                null,
                "finalization_record_exception_IllegalStateException",
                false,
                "voicechat_disconnected");

        assertThat(failure.code()).isEqualTo("FINALIZATION_BOUNDARY_FAILED");
        assertThat(failure.observed())
                .containsEntry(
                        "boundary_failure",
                        "finalization_record_exception_IllegalStateException")
                .containsEntry("previous_voicechat_failure", "voicechat_disconnected")
                .containsEntry("inputs_released", true)
                .containsEntry("voicechat_restored", true);
        assertThat(failure.evidence()).containsEntry("finalization_retry", true);
    }

    @Test
    void blocksNewAdmissionUntilPendingFinalizationRecordingRecovers() {
        var retries = new FinalizationRetryQueue();
        var routineId = UUID.randomUUID();
        var first = retries.attempt(
                routineId,
                10,
                ignored -> {
                    retries.rememberCleanupOutcome(routineId, true, true, null);
                    throw new IllegalStateException("record failed");
                },
                () -> "unused");
        assertThat(first.success()).isFalse();

        var mapped = McmcpRuntime.mapFailure(
                catchThrowable(() -> McmcpRuntime.requireNoPendingFinalizations(retries)));

        assertThat(mapped.failure().code()).isEqualTo("unsafe_state");
        assertThat(mapped.failure().retryable()).isTrue();
        assertThat(mapped.failure().details())
                .containsEntry("reason", "finalization_pending")
                .containsEntry("pending_finalizations", 1);

        var recovered = retries.attempt(
                routineId,
                first.incident().nextRetryClientTick(),
                ignored -> "recorded",
                () -> "unused");
        assertThat(recovered.success()).isTrue();
        assertThatCode(() -> McmcpRuntime.requireNoPendingFinalizations(retries))
                .doesNotThrowAnyException();
    }

    @Test
    void checksPendingFinalizationBeforeEitherReplayCanPurgeATerminalRoutine() {
        var retries = new FinalizationRetryQueue();
        var routines = new RoutineManager(unusedStationaryBreakPort());
        var key = UUID.randomUUID().toString();
        var request = new StationaryBreakRequest(
                new BlockTarget("minecraft:overworld", 1, 64, 2),
                new BlockStateFingerprint("minecraft:cobblestone", Map.of()),
                new StationaryBreakGoal("minecraft:cobblestone", 1),
                100,
                5,
                20);
        var receipt = routines.startStationaryBreak(key, "same-request", request, 10);
        routines.cancelRoutine(receipt.routineId(), "test", 0, 1);
        retries.attempt(
                receipt.routineId(),
                10,
                ignored -> {
                    retries.rememberCleanupOutcome(receipt.routineId(), false, false, "pending");
                    throw new IllegalStateException("record failed");
                },
                Object::new);

        var blocked = catchThrowable(() ->
                McmcpRuntime.replayStationaryBreakAfterFinalizationGate(
                        retries,
                        routines,
                        key,
                        "same-request",
                        10 + RoutineManager.DEFAULT_TERMINAL_TTL_TICKS));

        assertThat(McmcpRuntime.mapFailure(blocked).failure().code())
                .isEqualTo("unsafe_state");
        assertThat(routines.getRoutine(receipt.routineId(), 0, 1).state())
                .isEqualTo(RoutineState.CANCELLED);

        var semanticRetries = new FinalizationRetryQueue();
        var semanticRoutines = new RoutineManager(
                unusedStationaryBreakPort(), unusedSemanticActionPort());
        var semanticKey = UUID.randomUUID().toString();
        var target = new BlockTarget("minecraft:overworld", 1, 64, 2);
        var bounds = new ActionBounds(
                target.dimension(), target, target, 1, 30, false);
        var semanticRequest = new NavigateToRequest(target, 0.5D, bounds);
        var semanticReceipt = semanticRoutines.startSemanticAction(
                semanticKey, "same-request", semanticRequest, 10);
        semanticRoutines.cancelRoutine(semanticReceipt.routineId(), "test", 0, 1);
        semanticRetries.attempt(
                semanticReceipt.routineId(),
                10,
                ignored -> {
                    semanticRetries.rememberCleanupOutcome(
                            semanticReceipt.routineId(), false, false, "pending");
                    throw new IllegalStateException("record failed");
                },
                Object::new);

        var semanticBlocked = catchThrowable(() ->
                McmcpRuntime.replaySemanticActionAfterFinalizationGate(
                        semanticRetries,
                        semanticRoutines,
                        semanticKey,
                        "same-request",
                        semanticRequest,
                        10 + RoutineManager.DEFAULT_TERMINAL_TTL_TICKS));

        assertThat(McmcpRuntime.mapFailure(semanticBlocked).failure().code())
                .isEqualTo("unsafe_state");
        assertThat(semanticRoutines.getRoutine(semanticReceipt.routineId(), 0, 1).state())
                .isEqualTo(RoutineState.CANCELLED);
    }

    @Test
    void advertisesCompactRoutineSummariesAndOneOnDemandSchema() throws Exception {
        var catalog = McmcpRuntime.routineCatalog();

        assertThat(catalog).containsEntry("catalog_version", "phase-6-compact-v2");
        @SuppressWarnings("unchecked")
        var entries = (List<Map<String, Object>>) catalog.get("routines");
        assertThat(entries).hasSize(15);
        assertThat(entries).extracting(entry -> entry.get("kind"))
                .containsExactly(
                        "stationary_break",
                        "navigate_to",
                        "break_block",
                        "place_block",
                        "interact_block",
                        "interact_entity",
                        "use_item_on_block",
                        "apply_block_plan",
                        "craft_items",
                        "transfer_items",
                        "tend_crop_area",
                        "harvest_tree_area",
                        "sleep_at_bed",
                        "survey_area",
                        "execute_plan");
        assertThat(entries).allSatisfy(entry -> {
            assertThat(entry).containsKeys("capabilities").doesNotContainKeys("input_schema", "postconditions");
            assertThat((List<?>) entry.get("capabilities")).isNotEmpty();
        });
        assertThat(new Gson().toJson(catalog)
                .getBytes(StandardCharsets.UTF_8).length).isLessThan(8_000);

        @SuppressWarnings("unchecked")
        var detailed = (List<Map<String, Object>>) McmcpRuntime
                .routineCatalog("tend_crop_area")
                .get("routines");
        assertThat(detailed).singleElement().satisfies(entry -> {
            assertThat(entry).containsEntry("kind", "tend_crop_area");
            assertThat(entry.get("input_schema")).isInstanceOf(Map.class);
            assertThat(entry.get("postconditions")).isInstanceOf(List.class);
        });
        assertThat(new Gson().toJson(McmcpRuntime.routineCatalog("tend_crop_area"))
                .getBytes(StandardCharsets.UTF_8).length).isLessThan(15_000);
    }

    @Test
    void parsesBoundedSurveyAndUsesOrderIndependentPhaseFiveIdentity() {
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("waypoints", List.of(Map.of(
                "id", "start", "target", targetMap(), "look_at", targetMap())));
        parameters.put("samples", List.of(Map.of(
                "id", "sample-0", "position", targetMap())));
        parameters.put("goal", Map.of("minimum_observed_samples", 1));
        parameters.put("assessment", "coverage_only");

        var parsed = McmcpRuntime.phaseFiveRequestArgument(
                startArguments("survey_area", parameters, 128, 600, false),
                "minecraft:overworld");
        var reordered = new LinkedHashMap<String, Object>();
        reordered.put("assessment", "coverage_only");
        reordered.put("goal", Map.of("minimum_observed_samples", 1));
        reordered.put("samples", parameters.get("samples"));
        reordered.put("waypoints", parameters.get("waypoints"));
        var same = McmcpRuntime.phaseFiveRequestArgument(
                startArguments("survey_area", reordered, 128, 600, false),
                "minecraft:overworld");

        assertThat(parsed.request().kind()).isEqualTo("survey_area");
        assertThat(parsed.request().expectedUnits()).isEqualTo(1);
        assertThat(parsed.request().progressUnit()).isEqualTo("cells");
        assertThat(parsed.targets()).containsExactly(new BlockTarget(
                "minecraft:overworld", 1, 64, 2));
        assertThat(parsed.requestIdentity()).isEqualTo(same.requestIdentity())
                .matches("sha256:[0-9a-f]{64}");

        var impossibleGoal = new LinkedHashMap<>(parameters);
        impossibleGoal.put("goal", Map.of("minimum_observed_samples", 2));
        assertThatThrownBy(() -> McmcpRuntime.phaseFiveRequestArgument(
                startArguments("survey_area", impossibleGoal, 128, 600, false),
                "minecraft:overworld"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimum_observed_samples");
    }

    @Test
    void strictlyParsesAllSixClosedSemanticActionBranches() {
        assertThat(McmcpRuntime.semanticActionArgument(
                startArguments("navigate_to", Map.of(
                        "target", targetMap(),
                        "horizontal_tolerance_blocks", 0.5D), 1, 120, false),
                "minecraft:overworld"))
                .isInstanceOf(NavigateToRequest.class);
        assertThat(McmcpRuntime.semanticActionArgument(
                startArguments("break_block", Map.of(
                        "target", targetMap(),
                        "expected_before", blockState("minecraft:stone"),
                        "expected_after", blockState("minecraft:air")), 0, 30, true),
                "minecraft:overworld"))
                .isInstanceOf(BreakBlockRequest.class);
        assertThat(McmcpRuntime.semanticActionArgument(
                startArguments("place_block", Map.of(
                        "target", targetMap(),
                        "expected_before", blockState("minecraft:air"),
                        "item", "minecraft:stone",
                        "expected_after", blockState("minecraft:stone")), 0, 30, false),
                "minecraft:overworld"))
                .isInstanceOf(PlaceBlockRequest.class);
        assertThat(McmcpRuntime.semanticActionArgument(
                startArguments("use_item_on_block", Map.of(
                        "target", targetMap(),
                        "expected_before", blockState("minecraft:dirt"),
                        "item", "minecraft:wooden_hoe",
                        "expected_after", blockState("minecraft:farmland")), 0, 30, false),
                "minecraft:overworld"))
                .isInstanceOf(UseItemOnBlockRequest.class);
        assertThat(McmcpRuntime.semanticActionArgument(
                startArguments("interact_block", Map.of(
                        "target", targetMap(),
                        "expected_before", Map.of(
                                "block", "minecraft:lever",
                                "properties", Map.of("powered", "false")),
                        "expected_after", Map.of(
                                "block", "minecraft:lever",
                                "properties", Map.of("powered", "true"))), 0, 30, false),
                "minecraft:overworld"))
                .isInstanceOf(InteractBlockRequest.class);
        assertThat(McmcpRuntime.semanticActionArgument(
                startArguments("interact_entity", Map.of(
                        "entity_ref", "abcdefghijklmnopqrstuvwx",
                        "expected_type", "minecraft:cow",
                        "hand", "main_hand",
                        "held_item", "minecraft:bucket",
                        "goal", Map.of(
                                "item", "minecraft:milk_bucket",
                                "minimum_inventory_count", 1)), 0, 30, false),
                "minecraft:overworld"))
                .isInstanceOf(InteractEntityRequest.class);
    }

    @Test
    void parsesTokenLightFinitePlanAndHashesFractionalChildArguments() {
        var actionArguments = new LinkedHashMap<String, Object>();
        actionArguments.put("parameters", Map.of(
                "target", targetMap(),
                "horizontal_tolerance_blocks", 0.5D));
        actionArguments.put("bounds", boundsMap(1, 30, false));
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("plan_id", "farm-pass");
        parameters.put("max_ticks", 72_000);
        parameters.put("steps", List.of(Map.of(
                "id", "move-to-plot",
                "op", "action",
                "kind", "navigate_to",
                "arguments", actionArguments)));
        var arguments = new LinkedHashMap<String, Object>();
        arguments.put("kind", "execute_plan");
        arguments.put("parameters", parameters);
        arguments.put("bounds", Map.of());
        arguments.put("idempotency_key", "7f7809c5-eae4-48a6-9fea-d50b600d5641");

        var parsed = McmcpRuntime.finitePlanRequestArgument(arguments);

        assertThat(parsed.request().maxTicks()).isEqualTo(72_000);
        assertThat(((FinitePlanRequest.Action) parsed.request().steps().getFirst()).kind())
                .isEqualTo(FinitePlanRequest.RoutineKind.NAVIGATE_TO);
        assertThat(parsed.requestIdentity()).matches("sha256:[0-9a-f]{64}");

        var invalidArguments = new LinkedHashMap<>(actionArguments);
        invalidArguments.put("completion_intent", "finish_goal");
        parameters.put("steps", List.of(Map.of(
                "id", "bad-child-envelope",
                "op", "action",
                "kind", "navigate_to",
                "arguments", invalidArguments)));
        assertThatThrownBy(() -> McmcpRuntime.finitePlanRequestArgument(arguments))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("action arguments must contain exactly");
    }

    @Test
    void rejectsHybridUnknownAndFractionalSemanticArgumentsAtTheRuntimeBoundary() {
        var hybrid = new LinkedHashMap<String, Object>(Map.of(
                "target", targetMap(),
                "horizontal_tolerance_blocks", 0.5D));
        hybrid.put("expected_after", blockState("minecraft:air"));
        var fractionalBounds = boundsMap(1, 120, false);
        fractionalBounds.put("max_duration_seconds", 1.5D);

        assertThat(McmcpRuntime.mapFailure(catchThrowable(() ->
                McmcpRuntime.semanticActionArgument(
                        startArguments("navigate_to", hybrid, 1, 120, false),
                        "minecraft:overworld"))).failure().code())
                .isEqualTo("invalid_argument");
        assertThat(McmcpRuntime.mapFailure(catchThrowable(() ->
                McmcpRuntime.semanticActionArgument(
                        rawStartArguments("navigate_to", Map.of(
                                "target", targetMap(),
                                "horizontal_tolerance_blocks", 0.5D), fractionalBounds),
                        "minecraft:overworld"))).failure().code())
                .isEqualTo("invalid_argument");
        assertThat(McmcpRuntime.mapFailure(catchThrowable(() ->
                McmcpRuntime.semanticActionArgument(
                        startArguments("unknown_action", Map.of(), 0, 30, false),
                        "minecraft:overworld"))).failure().code())
                .isEqualTo("invalid_argument");
    }

    @Test
    void rejectsUnsupportedEntityInteractionDuringParsingBeforeVoiceAdmission() {
        var unsupported = startArguments("interact_entity", Map.of(
                "entity_ref", "abcdefghijklmnopqrstuvwx",
                "expected_type", "minecraft:pig",
                "hand", "main_hand",
                "held_item", "minecraft:bucket",
                "goal", Map.of(
                        "item", "minecraft:milk_bucket",
                        "minimum_inventory_count", 1)), 0, 30, false);

        var mapped = McmcpRuntime.mapFailure(catchThrowable(() ->
                McmcpRuntime.semanticActionArgument(unsupported, "minecraft:overworld")));

        assertThat(mapped.failure().code()).isEqualTo("invalid_argument");
        assertThat(mapped.failure().message()).contains("only supports adult cow milking");
    }

    @Test
    void rejectsNonAirBreakPostconditionDuringParsingBeforeVoiceAdmission() {
        var unsupported = startArguments("break_block", Map.of(
                "target", targetMap(),
                "expected_before", blockState("minecraft:stone"),
                "expected_after", blockState("minecraft:cobblestone")), 0, 30, true);

        var mapped = McmcpRuntime.mapFailure(catchThrowable(() ->
                McmcpRuntime.semanticActionArgument(unsupported, "minecraft:overworld")));

        assertThat(mapped.failure().code()).isEqualTo("invalid_argument");
        assertThat(mapped.failure().message()).contains("only supports minecraft:air");
    }

    @Test
    void rejectsUnsafePhaseTwoAndThreeBreakSourcesBeforeVoiceAdmission() {
        for (var unsafe : List.of(
                "minecraft:tnt",
                "minecraft:infested_stone",
                "minecraft:chest",
                "minecraft:ice",
                "example:stone")) {
            assertThatThrownBy(() -> McmcpRuntime.validateStationaryBreakAllowedBlocks(
                    Set.of("minecraft:cobblestone", unsafe)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("closed safe allowlist");

            var request = startArguments("break_block", Map.of(
                    "target", targetMap(),
                    "expected_before", blockState(unsafe),
                    "expected_after", blockState("minecraft:air")), 0, 30, true);
            var mapped = McmcpRuntime.mapFailure(catchThrowable(() ->
                    McmcpRuntime.semanticActionArgument(
                            request, "minecraft:overworld")));
            assertThat(mapped.failure().code()).isEqualTo("invalid_argument");
            assertThat(mapped.failure().message()).contains("closed safe allowlist");
        }

        assertThatCode(() -> McmcpRuntime.validateStationaryBreakAllowedBlocks(
                Set.of("minecraft:cobblestone", "minecraft:stone")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsInteractBlockWithoutASameBlockPropertyTransitionDuringParsing() {
        var noPropertyTransition = startArguments("interact_block", Map.of(
                "target", targetMap(),
                "expected_before", blockState("minecraft:lever"),
                "expected_after", blockState("minecraft:lever")), 0, 30, false);
        var differentBlock = startArguments("interact_block", Map.of(
                "target", targetMap(),
                "expected_before", Map.of(
                        "block", "minecraft:lever",
                        "properties", Map.of("powered", "false")),
                "expected_after", Map.of(
                        "block", "minecraft:oak_trapdoor",
                        "properties", Map.of("open", "true"))), 0, 30, false);

        for (var invalid : List.of(noPropertyTransition, differentBlock)) {
            var mapped = McmcpRuntime.mapFailure(catchThrowable(() ->
                    McmcpRuntime.semanticActionArgument(invalid, "minecraft:overworld")));
            assertThat(mapped.failure().code()).isEqualTo("invalid_argument");
        }
    }

    @Test
    void canonicalSemanticIdentityIsOrderIndependentAndCoversBoundsAndProperties() {
        var firstProperties = new LinkedHashMap<String, String>();
        firstProperties.put("waterlogged", "false");
        firstProperties.put("facing", "north");
        var secondProperties = new LinkedHashMap<String, String>();
        secondProperties.put("facing", "north");
        secondProperties.put("waterlogged", "false");
        var target = new BlockTarget("minecraft:overworld", 1, 64, 2);
        var first = new PlaceBlockRequest(
                target,
                new BlockStateFingerprint("minecraft:air", Map.of()),
                "minecraft:oak_stairs",
                new BlockStateFingerprint("minecraft:oak_stairs", firstProperties),
                new ActionBounds(target.dimension(), target, target, 0, 30, false));
        var second = new PlaceBlockRequest(
                target,
                new BlockStateFingerprint("minecraft:air", Map.of()),
                "minecraft:oak_stairs",
                new BlockStateFingerprint("minecraft:oak_stairs", secondProperties),
                new ActionBounds(target.dimension(), target, target, 0, 30, false));
        var changedDuration = new PlaceBlockRequest(
                target,
                second.expectedBefore(),
                second.item(),
                second.expectedAfter(),
                new ActionBounds(target.dimension(), target, target, 0, 29, false));

        assertThat(McmcpRuntime.semanticActionIdentity(first))
                .matches("sha256:[0-9a-f]{64}")
                .isEqualTo(McmcpRuntime.semanticActionIdentity(second))
                .isNotEqualTo(McmcpRuntime.semanticActionIdentity(changedDuration))
                .isEqualTo(McmcpRuntime.semanticActionIdentity(
                        first, GoalContinuationSession.FINISH_GOAL))
                .isNotEqualTo(McmcpRuntime.semanticActionIdentity(
                        first, GoalContinuationSession.CONTINUE_GOAL));
    }

    @Test
    void defaultsCompletionToFinish() {
        var omitted = Map.<String, Object>of();
        var explicitContinue = Map.<String, Object>of(
                "completion_intent", GoalContinuationSession.CONTINUE_GOAL);

        assertThat(McmcpRuntime.completionIntentArgument(omitted))
                .isEqualTo(GoalContinuationSession.FINISH_GOAL);
        assertThat(McmcpRuntime.completionIntentArgument(explicitContinue))
                .isEqualTo(GoalContinuationSession.CONTINUE_GOAL);
        assertThatThrownBy(() -> McmcpRuntime.completionIntentArgument(
                Map.of("completion_intent", "continue_forever")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void actionTerminalWaitDefaultsToImmediateAndRejectsWidening() {
        String actionId = "550e8400-e29b-41d4-a716-446655440000";
        assertThat(McmcpRuntime.agentActionWaitTimeoutMillis(
                Map.of("action_id", actionId))).isZero();
        assertThat(McmcpRuntime.agentActionWaitTimeoutMillis(Map.of(
                "action_id", actionId,
                "wait_timeout_ms", AgentActionStore.MAX_TERMINAL_WAIT_MILLIS)))
                .isEqualTo(AgentActionStore.MAX_TERMINAL_WAIT_MILLIS);
        assertThatThrownBy(() -> McmcpRuntime.agentActionWaitTimeoutMillis(Map.of(
                "action_id", actionId,
                "wait_timeout_ms", AgentActionStore.MAX_TERMINAL_WAIT_MILLIS + 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> McmcpRuntime.agentActionWaitTimeoutMillis(Map.of(
                "action_id", actionId,
                "unexpected", true)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void safeStayRequiresAStableHealthyScreenFreeCheckpoint() {
        assertThat(McmcpRuntime.safeStayFailure(
                true, true, true, false, 20.0F, 0.0D,
                false, true, true, true))
                .isNull();
        assertThat(McmcpRuntime.safeStayFailure(
                true, true, true, false, 20.0F, 0.0D,
                false, true, true, false))
                .isEqualTo("safe_stay_visible_hostile");
        assertThat(McmcpRuntime.safeStayFailure(
                true, true, true, false, 5.0F, 0.0D,
                false, true, true, true))
                .isEqualTo("safe_stay_low_health");
    }

    @Test
    void strictlyParsesAllFourClosedApplyBlockPlanOperations() {
        var verify = McmcpRuntime.applyBlockPlanArgument(
                applyPlanArguments("verify_only", fullState("minecraft:air", Map.of()),
                        fullState("minecraft:air", Map.of()), null, false),
                "minecraft:overworld");
        var breakToAir = McmcpRuntime.applyBlockPlanArgument(
                applyPlanArguments("break_to_air", fullState("minecraft:stone", Map.of()),
                        fullState("minecraft:air", Map.of()), null, true),
                "minecraft:overworld");
        var place = McmcpRuntime.applyBlockPlanArgument(
                applyPlanArguments("place", fullState("minecraft:air", Map.of()),
                        fullState("minecraft:stone", Map.of()), "minecraft:stone", false),
                "minecraft:overworld");
        var replace = McmcpRuntime.applyBlockPlanArgument(
                applyPlanArguments("replace", fullState("minecraft:dirt", Map.of()),
                        fullState("minecraft:stone", Map.of()), "minecraft:stone", true),
                "minecraft:overworld");

        assertThat(List.of(verify, breakToAir, place, replace))
                .extracting(parsed -> parsed.request().steps().getFirst().operation())
                .containsExactly(
                        ApplyBlockPlanOperation.VERIFY_ONLY,
                        ApplyBlockPlanOperation.BREAK_TO_AIR,
                        ApplyBlockPlanOperation.PLACE,
                        ApplyBlockPlanOperation.REPLACE);
        assertThat(place.request().requiredResources()).containsEntry("minecraft:stone", 1);
        assertThat(place.resourceEstimate())
                .containsEntry("break_operations", 0)
                .containsEntry("place_operations", 1);
    }

    @Test
    void applyBlockPlanParserRequiresCompleteRuntimeBlockStatesBeforeAdmission() {
        var incompleteStairs = fullState(
                "minecraft:oak_stairs", Map.of("facing", "north"));
        var arguments = applyPlanArguments(
                "place",
                fullState("minecraft:air", Map.of()),
                incompleteStairs,
                "minecraft:oak_stairs",
                false);

        var mapped = McmcpRuntime.mapFailure(catchThrowable(() ->
                McmcpRuntime.applyBlockPlanArgument(arguments, "minecraft:overworld")));

        assertThat(mapped.failure().code()).isEqualTo("invalid_argument");
        assertThat(mapped.failure().message()).contains("complete runtime BlockState");
        assertThat(mapped.failure().details())
                .containsEntry("plan_validation_code", "incomplete_block_state")
                .containsEntry("path", "entries[0].expected_after.properties");
    }

    @Test
    void applyBlockPlanIdentityCoversRawTransformPhaseAndBounds() {
        var first = applyPlanArguments(
                "verify_only", fullState("minecraft:air", Map.of()),
                fullState("minecraft:air", Map.of()), null, false);
        var changedTransform = deepCopy(first);
        @SuppressWarnings("unchecked")
        var parameters = (Map<String, Object>) changedTransform.get("parameters");
        parameters.put("transform", Map.of("rotation", 90, "mirror", "none"));
        var changedDuration = deepCopy(first);
        @SuppressWarnings("unchecked")
        var bounds = (Map<String, Object>) changedDuration.get("bounds");
        bounds.put("max_duration_seconds", 29);

        var parsed = McmcpRuntime.applyBlockPlanArgument(first, "minecraft:overworld");
        var transformed = McmcpRuntime.applyBlockPlanArgument(
                changedTransform, "minecraft:overworld");
        var shorter = McmcpRuntime.applyBlockPlanArgument(
                changedDuration, "minecraft:overworld");

        assertThat(parsed.requestIdentity()).matches("sha256:[0-9a-f]{64}")
                .isNotEqualTo(transformed.requestIdentity())
                .isNotEqualTo(shorter.requestIdentity());
    }

    @Test
    void applyBlockPlanCanonicalizesFullPropertyOrderAndTransformsDirectionalState() {
        var firstProperties = new LinkedHashMap<String, String>();
        firstProperties.put("waterlogged", "false");
        firstProperties.put("shape", "straight");
        firstProperties.put("half", "bottom");
        firstProperties.put("facing", "north");
        var secondProperties = new LinkedHashMap<String, String>();
        secondProperties.put("facing", "north");
        secondProperties.put("half", "bottom");
        secondProperties.put("shape", "straight");
        secondProperties.put("waterlogged", "false");
        var first = applyPlanArguments(
                "verify_only",
                fullState("minecraft:oak_stairs", firstProperties),
                fullState("minecraft:oak_stairs", firstProperties),
                null,
                false);
        var second = applyPlanArguments(
                "verify_only",
                fullState("minecraft:oak_stairs", secondProperties),
                fullState("minecraft:oak_stairs", secondProperties),
                null,
                false);
        var rotated = deepCopy(first);
        @SuppressWarnings("unchecked")
        var rotatedParameters = (Map<String, Object>) rotated.get("parameters");
        rotatedParameters.put("transform", Map.of("rotation", 90, "mirror", "none"));

        var firstParsed = McmcpRuntime.applyBlockPlanArgument(
                first, "minecraft:overworld");
        var secondParsed = McmcpRuntime.applyBlockPlanArgument(
                second, "minecraft:overworld");
        var rotatedParsed = McmcpRuntime.applyBlockPlanArgument(
                rotated, "minecraft:overworld");

        assertThat(firstParsed.requestIdentity()).isEqualTo(secondParsed.requestIdentity());
        assertThat(rotatedParsed.request().steps().getFirst().expectedAfter().properties())
                .containsEntry("facing", "east")
                .containsEntry("half", "bottom")
                .containsEntry("shape", "straight")
                .containsEntry("waterlogged", "false");
    }

    @Test
    void rejectsDoorBedAndDoubleHeightItemsBeforePlanAdmission() {
        var target = new BlockTarget("minecraft:overworld", 1, 64, 2);
        var executionBounds = new ActionBounds(
                target.dimension(), target, target, 0, 30, false);

        for (var itemAndBlock : Map.of(
                "minecraft:oak_door", "minecraft:oak_door",
                "minecraft:red_bed", "minecraft:red_bed").entrySet()) {
            var request = new ApplyBlockPlanRequest(
                    "fixture", 1, 1,
                    List.of(new ApplyBlockPlanStep(
                            "cell-0",
                            ApplyBlockPlanOperation.PLACE,
                            target,
                            new BlockStateFingerprint("minecraft:air", Map.of()),
                            new BlockStateFingerprint(itemAndBlock.getValue(), Map.of()),
                            Optional.of(itemAndBlock.getKey()))),
                    executionBounds);

            assertThatThrownBy(() -> McmcpRuntime.validateApplyBlockPlanItems(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("multi-cell mutation");
        }

        var stone = new ApplyBlockPlanRequest(
                "fixture", 1, 1,
                List.of(new ApplyBlockPlanStep(
                        "cell-0",
                        ApplyBlockPlanOperation.PLACE,
                        target,
                        new BlockStateFingerprint("minecraft:air", Map.of()),
                        new BlockStateFingerprint("minecraft:stone", Map.of()),
                        Optional.of("minecraft:stone"))),
                executionBounds);
        assertThatCode(() -> McmcpRuntime.validateApplyBlockPlanItems(stone))
                .doesNotThrowAnyException();

        var breakDoor = new ApplyBlockPlanRequest(
                "fixture", 1, 1,
                List.of(new ApplyBlockPlanStep(
                        "door-break",
                        ApplyBlockPlanOperation.BREAK_TO_AIR,
                        target,
                        new BlockStateFingerprint("minecraft:oak_door", Map.of()),
                        new BlockStateFingerprint("minecraft:air", Map.of()),
                        Optional.empty())),
                new ActionBounds(target.dimension(), target, target, 0, 30, true));
        assertThatThrownBy(() -> McmcpRuntime.validateApplyBlockPlanItems(breakDoor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("multi-cell mutation");

    }

    @Test
    void rejectsSolidBucketPlacementBeforePlanAdmission() {
        var target = new BlockTarget("minecraft:overworld", 1, 64, 2);
        var request = new ApplyBlockPlanRequest(
                "fixture", 1, 1,
                List.of(new ApplyBlockPlanStep(
                        "powder-snow",
                        ApplyBlockPlanOperation.PLACE,
                        target,
                        new BlockStateFingerprint("minecraft:air", Map.of()),
                        new BlockStateFingerprint("minecraft:powder_snow", Map.of()),
                        Optional.of("minecraft:powder_snow_bucket"))),
                new ActionBounds(target.dimension(), target, target, 0, 30, false));

        assertThatThrownBy(() -> McmcpRuntime.validateApplyBlockPlanItems(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different container item");
    }

    @Test
    void rejectsUnsafeSourceMutationBeforePlanAdmission() {
        var target = new BlockTarget("minecraft:overworld", 1, 64, 2);
        for (var unsafe : List.of(
                "minecraft:tnt",
                "minecraft:infested_stone",
                "minecraft:hopper",
                "minecraft:ice",
                "example:stone")) {
            for (var operation : List.of(
                    ApplyBlockPlanOperation.BREAK_TO_AIR,
                    ApplyBlockPlanOperation.REPLACE)) {
                var request = new ApplyBlockPlanRequest(
                        "fixture", 1, 1,
                        List.of(new ApplyBlockPlanStep(
                                "unsafe-source",
                                operation,
                                target,
                                new BlockStateFingerprint(unsafe, Map.of()),
                                operation == ApplyBlockPlanOperation.BREAK_TO_AIR
                                        ? new BlockStateFingerprint("minecraft:air", Map.of())
                                        : new BlockStateFingerprint("minecraft:stone", Map.of()),
                                operation == ApplyBlockPlanOperation.REPLACE
                                        ? Optional.of("minecraft:stone")
                                        : Optional.empty())),
                        new ActionBounds(target.dimension(), target, target, 0, 30, true));

                assertThatThrownBy(() -> McmcpRuntime.validateApplyBlockPlanItems(request))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("closed safe allowlist");
            }
        }

        var placeEmptyHopper = new ApplyBlockPlanRequest(
                "fixture", 1, 1,
                List.of(new ApplyBlockPlanStep(
                        "new-hopper",
                        ApplyBlockPlanOperation.PLACE,
                        target,
                        new BlockStateFingerprint("minecraft:air", Map.of()),
                        new BlockStateFingerprint(
                                "minecraft:hopper",
                                Map.of("enabled", "true", "facing", "down")),
                        Optional.of("minecraft:hopper"))),
                new ActionBounds(target.dimension(), target, target, 0, 30, false));
        assertThatCode(() -> McmcpRuntime.validateApplyBlockPlanItems(placeEmptyHopper))
                .doesNotThrowAnyException();
    }

    @Test
    void stopsRetryingGlobalCleanupAfterTheAutomaticBudget() {
        var retries = new FinalizationRetryQueue();
        var routineId = UUID.randomUUID();
        var cleanupAttempts = new AtomicInteger();
        long clientTick = 0;
        FinalizationRetryQueue.Attempt<Object, Object> attempt = null;

        for (int index = 0; index < FinalizationRetryQueue.MAX_AUTOMATIC_ATTEMPTS; index++) {
            long attemptTick = clientTick;
            attempt = retries.attempt(
                    routineId,
                    attemptTick,
                    incident -> {
                        if (McmcpRuntime.shouldRetryFinalizationCleanup(incident)) {
                            cleanupAttempts.incrementAndGet();
                        }
                        throw new IllegalStateException("record failed");
                    },
                    Object::new);
            clientTick = attempt.incident().nextRetryClientTick();
        }

        assertThat(attempt).isNotNull();
        assertThat(McmcpRuntime.shouldRetryFinalizationCleanup(attempt.incident())).isFalse();

        retries.attempt(
                routineId,
                clientTick,
                incident -> {
                    if (McmcpRuntime.shouldRetryFinalizationCleanup(incident)) {
                        cleanupAttempts.incrementAndGet();
                    }
                    return "recorded";
                },
                Object::new);

        assertThat(cleanupAttempts)
                .hasValue(FinalizationRetryQueue.MAX_AUTOMATIC_ATTEMPTS);
    }

    @Test
    void continuedGoalKeepsArmingOnlyForSafeReplanFailures() {
        var replan = new RoutineFailure(
                RoutineFailure.Category.PRECONDITION,
                "ITEM_NOT_FOUND",
                false,
                RoutineFailure.Recovery.REPLAN,
                RoutineFailure.Scope.ROUTINE,
                0,
                Map.of(),
                Map.of(),
                Map.of(),
                List.of("inventory"),
                false);
        var safety = new RoutineFailure(
                RoutineFailure.Category.SAFETY,
                "THREAT_VISIBLE",
                false,
                RoutineFailure.Recovery.REPLAN,
                RoutineFailure.Scope.ROUTINE,
                0,
                Map.of(),
                Map.of(),
                Map.of(),
                List.of("player"),
                false);

        assertThat(McmcpRuntime.recoverableContinuationFailure(
                GoalContinuationSession.CONTINUE_GOAL, replan, null)).isTrue();
        assertThat(McmcpRuntime.recoverableContinuationFailure(
                GoalContinuationSession.FINISH_GOAL, replan, null)).isFalse();
        assertThat(McmcpRuntime.recoverableContinuationFailure(
                GoalContinuationSession.CONTINUE_GOAL, safety, null)).isFalse();
        assertThat(McmcpRuntime.recoverableContinuationFailure(
                GoalContinuationSession.CONTINUE_GOAL, replan, replan)).isFalse();
    }

    private static RoutineSnapshot terminalSnapshot() {
        return new RoutineSnapshot(
                UUID.randomUUID(),
                "stationary_break",
                RoutineState.CANCELLED,
                "cancelled",
                false,
                new RoutineProgress(0, 1, "items"),
                new BlockTarget("minecraft:overworld", 1, 64, 2),
                null,
                10,
                1,
                Map.of("attempts", 0),
                null,
                false,
                null,
                new RoutineEventRing.EventPage(List.of(), false, false, 1, 0));
    }

    private static Map<String, Object> startArguments(
            String kind,
            Map<String, Object> parameters,
            int maxTravelBlocks,
            int maxDurationSeconds,
            boolean allowBreak) {
        return rawStartArguments(
                kind,
                parameters,
                boundsMap(maxTravelBlocks, maxDurationSeconds, allowBreak));
    }

    private static Map<String, Object> rawStartArguments(
            String kind,
            Map<String, Object> parameters,
            Map<String, Object> bounds) {
        return Map.of(
                "kind", kind,
                "parameters", parameters,
                "bounds", bounds,
                "completion_intent", "finish_goal",
                "idempotency_key", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    }

    private static LinkedHashMap<String, Object> boundsMap(
            int maxTravelBlocks,
            int maxDurationSeconds,
            boolean allowBreak) {
        var result = new LinkedHashMap<String, Object>();
        result.put("dimension", "minecraft:overworld");
        result.put("region", Map.of("min", positionMap(), "max", positionMap()));
        result.put("max_travel_blocks", maxTravelBlocks);
        result.put("max_duration_seconds", maxDurationSeconds);
        result.put("allow_break", allowBreak);
        return result;
    }

    private static Map<String, Object> targetMap() {
        return Map.of(
                "dimension", "minecraft:overworld",
                "x", 1,
                "y", 64,
                "z", 2);
    }

    private static Map<String, Object> positionMap() {
        return Map.of("x", 1, "y", 64, "z", 2);
    }

    private static Map<String, Object> blockState(String block) {
        return Map.of("block", block);
    }

    private static Map<String, Object> fullState(
            String block,
            Map<String, String> properties) {
        return Map.of("block", block, "properties", properties);
    }

    private static Map<String, Object> applyPlanArguments(
            String operation,
            Map<String, Object> expectedBefore,
            Map<String, Object> expectedAfter,
            String item,
            boolean allowBreak) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("id", "cell-0");
        entry.put("offset", Map.of("x", 0, "y", 0, "z", 0));
        entry.put("operation", operation);
        entry.put("expected_before", expectedBefore);
        entry.put("expected_after", expectedAfter);
        if (item != null) {
            entry.put("item", item);
        }
        return rawStartArguments(
                ApplyBlockPlanRequest.KIND,
                new LinkedHashMap<>(Map.of(
                        "anchor", targetMap(),
                        "transform", Map.of("rotation", 0, "mirror", "none"),
                        "phase", Map.of("id", "foundation", "index", 1, "total", 2),
                        "entries", List.of(entry))),
                boundsMap(0, 30, allowBreak));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> source) {
        var result = new LinkedHashMap<String, Object>();
        for (var entry : source.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> map) {
                value = deepCopy((Map<String, Object>) map);
            }
            else if (value instanceof List<?> list) {
                var copy = new ArrayList<>();
                for (var item : list) {
                    copy.add(item instanceof Map<?, ?> map
                            ? deepCopy((Map<String, Object>) map)
                            : item);
                }
                value = copy;
            }
            result.put(entry.getKey(), value);
        }
        return result;
    }

    private static StationaryBreakPort unusedStationaryBreakPort() {
        return (StationaryBreakPort) Proxy.newProxyInstance(
                StationaryBreakPort.class.getClassLoader(),
                new Class<?>[]{StationaryBreakPort.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("retire")) {
                        return null;
                    }
                    throw new AssertionError("Unexpected port call: " + method.getName());
                });
    }

    private static SemanticActionPort unusedSemanticActionPort() {
        return (SemanticActionPort) Proxy.newProxyInstance(
                SemanticActionPort.class.getClassLoader(),
                new Class<?>[]{SemanticActionPort.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("retire")
                            || method.getName().equals("release")) {
                        return null;
                    }
                    throw new AssertionError("Unexpected semantic port call: " + method.getName());
                });
    }
}
