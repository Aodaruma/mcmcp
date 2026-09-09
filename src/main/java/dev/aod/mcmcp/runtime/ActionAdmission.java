package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentActionStore.RendererRecoveryStage;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.dsl.ActionDslCompiler;
import dev.aod.mcmcp.agent.dsl.ActionDslSource;
import dev.aod.mcmcp.agent.navigation.DeterministicAStar;
import dev.aod.mcmcp.agent.navigation.KnownTraversabilitySnapshot;
import dev.aod.mcmcp.agent.navigation.LocalObservationProjector;
import dev.aod.mcmcp.agent.observation.ClientFogDistanceSignals;
import dev.aod.mcmcp.agent.observation.ObservationFrame;
import dev.aod.mcmcp.client.McmcpClientConfig;
import dev.aod.mcmcp.construction.SafeConstructionBlocks;
import dev.aod.mcmcp.mcp.McpRuntimePort.RuntimeReply;
import dev.aod.mcmcp.mcp.RuntimeCallContext;
import dev.aod.mcmcp.observation.ClientRecipeCatalog;
import dev.aod.mcmcp.runtime.ActionPredicates.AdmissionPolicySnapshot;
import dev.aod.mcmcp.runtime.ActionPredicates.PredicateRequirements;
import dev.aod.mcmcp.runtime.RuntimeFailures.RuntimeInvocationException;
import dev.aod.mcmcp.safety.LocalArmingState;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.ToLongFunction;
import net.minecraft.client.Minecraft;

/** Action受付と各dispatch前に、同じ配送lease・姿勢・policyの証拠を検査する。 */
final class ActionAdmission {
    private final LocalArmingState arming;
    private final AgentObservations agentObservations;
    private final ClientReconciliationSignals reconciliationSignals;
    private final ClientRecipeCatalog recipeCatalog;
    private final DeterministicAStar agentPathfinder;
    private final BooleanSupplier busy;
    private final java.util.function.Predicate<Minecraft> multiplayerPolicyAllows;
    private final java.util.function.Consumer<SurfacePreflightRecovery> publishRendererRecovery;

    ActionAdmission(LocalArmingState arming, AgentObservations agentObservations,
            ClientReconciliationSignals reconciliationSignals, ClientRecipeCatalog recipeCatalog,
            DeterministicAStar agentPathfinder, BooleanSupplier busy,
            java.util.function.Predicate<Minecraft> multiplayerPolicyAllows,
            java.util.function.Consumer<SurfacePreflightRecovery> publishRendererRecovery) {
        this.arming = arming;
        this.agentObservations = agentObservations;
        this.reconciliationSignals = reconciliationSignals;
        this.recipeCatalog = recipeCatalog;
        this.agentPathfinder = agentPathfinder;
        this.busy = busy;
        this.multiplayerPolicyAllows = multiplayerPolicyAllows;
        this.publishRendererRecovery = publishRendererRecovery;
    }

    AgentAdmissionSnapshot captureAgentAdmission(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            PredicateRequirements predicateRequirements,
            boolean localSafetyRequired,
            boolean recipeReferenceRequired,
            ActionDsl.Program program,
            SurfacePreflightRecovery surfaceRecovery) {
        McmcpRuntime.assertClientThread(minecraft);
        RuntimeFailures.requireReady(session);
        if (busy.getAsBoolean()) {
            throw new RuntimeInvocationException(
                    "task_busy", "Another action is already queued or running.", true, Map.of());
        }
        if (minecraft.isMultiplayerServer() && !multiplayerPolicyAllows.test(minecraft)) {
            throw new RuntimeInvocationException(
                    "multiplayer_not_allowed",
                    "This local policy does not allow multiplayer automation.",
                    false,
                    Map.of());
        }
        var lock = arming.snapshot(session.worldSessionId());
        if (lock.mode() != LocalArmingState.Mode.READY) {
            throw new RuntimeInvocationException(
                    "mcp_operation_disabled",
                    "Enable MCP operation from the in-game Screen before starting an action.",
                    true,
                    Map.of());
        }
        if (localSafetyRequired
                && agentObservations.localSafety() != LocalObservationProjector.CurrentSafety.CONTINUE) {
            throw new RuntimeInvocationException(
                    "unsafe_state",
                    "The Local Observation Volume does not permit action admission.",
                    true,
                    Map.of());
        }
        if (recipeReferenceRequired) {
            recipeCatalog.refreshFromClient(
                    minecraft,
                    Objects.requireNonNull(session.worldSessionId(), "worldSessionId"),
                    session.clientTick());
        }
        var map = agentObservations.requireAgentMap(session);
        var reconciliation = reconciliationSignals.bindAndSnapshot(
                minecraft.level, session.worldSessionId());
        final long visualBarrierWorldRevision;
        try {
            visualBarrierWorldRevision = ActionEvidence.visualBarrierWorldRevision(map, reconciliation);
        } catch (AgentPrimitivePlanner.PlanningException mismatch) {
            throw new RuntimeInvocationException(
                    "unsafe_state",
                    "The visual evidence boundary does not match the traversability map.",
                    true,
                    Map.of());
        }
        var player = Objects.requireNonNull(minecraft.player, "player");
        var predicateSnapshot = AdmissionPolicySnapshot.capture(
                ActionPredicates.policySnapshot(minecraft), predicateRequirements);
        var singlePrimitive = ActionPlanning.firstPrimitive(program, predicateSnapshot).orElse(null);
        surfaceRecovery.capture(singlePrimitive, agentObservations.deliveredEvidence());
        if (surfaceRecovery.lease() != null && !surfaceRecovery.applies(singlePrimitive)) {
            throw admissionPreflightFailure(AdmissionFenceFailure.POLICY_BRANCH_CHANGED);
        }
        requireSurfaceRecoveryReady(minecraft, session, surfaceRecovery);
        String frameRef = ActionPlanning.frameItemTargetRef(singlePrimitive);
        if (frameRef != null) {
            agentObservations.deliveredEvidence().frameDisplayRejection(
                    frameRef, agentObservations.frames().latestFrame(), session.clientTick()).ifPresent(reason -> {
                        throw new RuntimeInvocationException("target_unknown",
                                "Frame witness rejected: " + reason.name().toLowerCase(Locale.ROOT), true, Map.of());
                    });
        }
        return new AgentAdmissionSnapshot(
                session,
                lock,
                map,
                ActionPlanning.playerPose(player, session.dimension()),
                agentObservations.agentPlanningFrame(singlePrimitive, surfaceRecovery.lease()),
                agentObservations.localSafety(),
                localSafetyRequired,
                predicateRequirements,
                predicateSnapshot,
                McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0F,
                minecraft.isMultiplayerServer(),
                multiplayerPolicyAllows.test(minecraft),
                reconciliation,
                visualBarrierWorldRevision,
                reconciliation.positionCorrectionRevision());
    }

    PreparedAgentAction prepareAgentAction(
            ActionDsl.Request request,
            ActionDslSource source,
            AgentAdmissionSnapshot snapshot,
            RuntimeCallContext context,
            SurfacePreflightRecovery surfaceRecovery) {
        ActionPredicates.validatePredicateAvailability(request.program(), snapshot.predicateSnapshot());
        var allowed = EnumSet.noneOf(ActionDsl.Capability.class);
        if (snapshot.control().capabilities().contains("movement")) {
            allowed.add(ActionDsl.Capability.MOVEMENT);
        }
        if (snapshot.control().capabilities().contains("camera")) {
            allowed.add(ActionDsl.Capability.CAMERA);
        }
        if (snapshot.control().capabilities().contains("block_break")) {
            allowed.add(ActionDsl.Capability.BLOCK_BREAK);
        }
        if (snapshot.control().capabilities().contains("block_interact")) {
            allowed.add(ActionDsl.Capability.BLOCK_INTERACT);
        }
        if (snapshot.control().capabilities().contains("block_place")) {
            allowed.add(ActionDsl.Capability.BLOCK_PLACE);
        }
        if (snapshot.control().capabilities().contains("inventory_transfer")) {
            allowed.add(ActionDsl.Capability.INVENTORY_TRANSFER);
        }
        if (snapshot.control().capabilities().contains("item_use")) {
            allowed.add(ActionDsl.Capability.ITEM_USE);
        }
        if (snapshot.control().capabilities().contains("entity_attack")) {
            allowed.add(ActionDsl.Capability.ENTITY_ATTACK);
        }
        ActionDslCompiler.CompiledProgram program = ActionDslCompiler.compile(
                request, this::admissionPrimitiveCost, allowed);
        surfaceRecovery.effectiveBudget(program.effectiveBudget());
        Optional<ActionDsl.Node> initialPrimitive = ActionPlanning.firstPrimitive(
                request.program(), snapshot.predicateSnapshot());
        final AgentPrimitivePlanner.Analysis analysis;
        final Optional<AgentPrimitivePlanner.FrameItemAim> frameItemAim;
        try {
            analysis = initialPrimitive
                    .filter(ActionPlanning::requiresWorldPlanning)
                    .map(primitive -> analyzePrimitive(
                            request.program(),
                            primitive,
                            snapshot.map(),
                            snapshot.pose(),
                            snapshot.frame(),
                            snapshot.cameraDegreesPerTick(),
                            snapshot.visualBarrierWorldRevision(),
                            ActionEvidence.primitiveSurfaceRevisionBarrier(
                                    primitive,
                                    snapshot.map(),
                                    snapshot.reconciliation()),
                            context::canBeginWork))
                    .orElseGet(ActionPlanning::emptyPrimitiveAnalysis);
            initialPrimitive.flatMap(analysis::worstCase).ifPresent(cost ->
                    ActionDslCompiler.requireWithinBudget(cost, program.effectiveBudget()));
            frameItemAim = initialPrimitive.filter(ActionEvidence::isFrameItemPrimitive)
                    .map(primitive -> AgentPrimitivePlanner.requireFrameItemAim(
                            snapshot.map(), snapshot.pose(), snapshot.frame(), primitive,
                            snapshot.visualBarrierWorldRevision()));
            if (frameItemAim.isPresent()
                    && !ActionEvidence.frameItemEvidenceFresh(frameItemAim.orElseThrow(), snapshot.session().clientTick())) {
                throw new RuntimeInvocationException("target_unknown",
                        "Frame display evidence expired.", true, Map.of());
            }
        } catch (AgentPrimitivePlanner.PlanningException failure) {
            if (surfaceRecovery.lease() != null
                    && failure.code() == AgentPrimitivePlanner.Code.TARGET_UNKNOWN) {
                throw admissionPreflightFailure(AdmissionFenceFailure.SURFACE_REOBSERVATION_MISMATCH);
            }
            throw ActionBudgets.planningFailure(failure);
        }
        surfaceRecovery.noteRevalidated(RendererRecoveryStage.CAPTURE);
        return new PreparedAgentAction(
                snapshot, program, source, analysis, initialPrimitive, frameItemAim, surfaceRecovery);
    }

    AgentPrimitivePlanner.Analysis analyzePrimitive(
            ActionDsl.Program program,
            ActionDsl.Node primitive,
            KnownTraversabilitySnapshot map,
            AgentPrimitivePlanner.Pose pose,
            Optional<ObservationFrame> frame,
            float cameraDegreesPerTick,
            long visualBarrierWorldRevision,
            ToLongFunction<ActionDsl.Position> surfaceRevisionBarrier,
            java.util.function.BooleanSupplier canContinue) {
        var oneNode = new ActionDsl.Program(
                program.dslVersion(), Optional.empty(), program.capabilities(), List.of(primitive));
        return AgentPrimitivePlanner.analyze(
                oneNode,
                map,
                agentPathfinder,
                pose,
                frame,
                cameraDegreesPerTick,
                visualBarrierWorldRevision,
                surfaceRevisionBarrier,
                canContinue,
                agentObservations.deliveredEvidence()::resolvePlacementState);
    }

    private Optional<ActionDslCompiler.Cost> admissionPrimitiveCost(ActionDsl.Node node) {
        if (node instanceof ActionDsl.PillarUpKnown pillar) {
            return Optional.of(ActionPlanning.pillarAdmissionCost(
                    pillar, agentObservations.deliveredEvidence()::resolvePlacementState));
        }
        if (node instanceof ActionDsl.ApplyKnownBlockPlan plan) {
            long placements = plan.entries().stream()
                    .mapToLong(this::rememberedPlacementCells)
                    .sum();
            return Optional.of(ActionDslCompiler.intrinsicKnownBlockPlanCost(
                    plan.entries().size(), placements));
        }
        return ActionPlanning.structuralPrimitiveCost(node);
    }

    private long rememberedPlacementCells(ActionDsl.BlockPlanEntry entry) {
        Optional<ActionDsl.BlockStateSpec> state = entry.sourceState();
        if (state.isEmpty()) {
            state = entry.placementStateRef()
                    .flatMap(agentObservations.deliveredEvidence()::resolvePlacementState)
                    .map(remembered -> new ActionDsl.BlockStateSpec(
                            remembered.state().block().value(),
                            remembered.state().properties()));
        }
        // Unknown/evicted references remain fail-closed here and are rejected by planning.
        return state.map(value -> (long) SafeConstructionBlocks
                        .placementCellCount(value.block()))
                .orElse(2L);
    }

    static boolean sameAdmissionSession(
            WorldSessionTracker.Snapshot captured,
            WorldSessionTracker.Snapshot current) {
        return current.worldReady()
                && captured.generation() == current.generation()
                && Objects.equals(captured.worldSessionId(), current.worldSessionId())
                && Objects.equals(captured.dimension(), current.dimension());
    }

    enum AdmissionFenceFailure {
        WORLD_SESSION_CHANGED,
        PLAYER_UNAVAILABLE,
        CONTROL_MODE_CHANGED,
        CONTROL_EPOCH_CHANGED,
        CAPABILITIES_CHANGED,
        POSE_CHANGED,
        LOCAL_SAFETY_CHANGED,
        CAMERA_POLICY_CHANGED,
        MULTIPLAYER_CONTEXT_CHANGED,
        MULTIPLAYER_POLICY_CHANGED,
        OBSERVATION_UNAVAILABLE,
        POSITION_CORRECTION_CHANGED,
        POLICY_UNAVAILABLE,
        POLICY_BRANCH_CHANGED,
        ROUTE_CHANGED,
        KNOWN_TARGET_CHANGED,
        FACING_SURFACE_CHANGED,
        KNOWN_SURFACE_CHANGED,
        RENDERER_EVIDENCE_MISSING,
        RENDERER_EVIDENCE_TIMEOUT,
        DELIVERY_EXPIRED,
        TARGET_NOT_DELIVERED,
        SURFACE_REOBSERVATION_MISMATCH,
        VISIBLE_ITEM_CHANGED,
        VISIBLE_BATCH_ITEM_CHANGED,
        FRAME_ITEM_CHANGED,
        BREAK_PRECONDITION_CHANGED;

        String code() {
            return name().toLowerCase(Locale.ROOT);
        }

        String executionEvidence() {
            return "admission_" + code() + "_before_execution";
        }
    }

    static RuntimeException admissionPreflightFailure(AdmissionFenceFailure reason) {
        return new RuntimeInvocationException(
                "unsafe_state",
                "The world, local control, pose, observation, or policy changed during preflight. "
                        + "Reason: " + Objects.requireNonNull(reason, "reason").code() + ".",
                true,
                Map.of("admission_reason", reason.code()));
    }

    static RuntimeReply mapAdmissionFailure(Throwable failure, SurfacePreflightRecovery recovery) {
        var cause = RuntimeFailures.unwrap(failure);
        return recovery.hasWaited()
                && (cause instanceof TimeoutException || cause instanceof ClientCommandInbox.CommandTimeoutException)
                ? RuntimeFailures.mapFailure(admissionPreflightFailure(AdmissionFenceFailure.RENDERER_EVIDENCE_TIMEOUT))
                : RuntimeFailures.mapFailure(failure);
    }

    Optional<AdmissionFenceFailure> surfaceRecoveryFailure(
            Minecraft minecraft, WorldSessionTracker.Snapshot session,
            SurfacePreflightRecovery surfaceRecovery, RendererRecoveryStage stage) {
        var decision = surfaceRecovery.evaluate(agentObservations.deliveredEvidence(), session.clientTick(), System.nanoTime(),
                minecraft.level != null && minecraft.player != null && ClientFogDistanceSignals.current(
                        minecraft.level, minecraft.player, minecraft.player.tickCount).isPresent());
        if (decision == SurfacePreflightRecovery.Decision.RENDERER_EVIDENCE_MISSING
                && surfaceRecovery.noteMissing(stage)) publishRendererRecovery.accept(surfaceRecovery);
        return switch (decision) {
            case READY -> Optional.empty();
            case RENDERER_EVIDENCE_MISSING -> Optional.of(AdmissionFenceFailure.RENDERER_EVIDENCE_MISSING);
            case RENDERER_EVIDENCE_TIMEOUT -> Optional.of(AdmissionFenceFailure.RENDERER_EVIDENCE_TIMEOUT);
            case DELIVERY_EXPIRED -> Optional.of(AdmissionFenceFailure.DELIVERY_EXPIRED);
            case TARGET_NOT_DELIVERED -> Optional.of(AdmissionFenceFailure.TARGET_NOT_DELIVERED);
        };
    }

    private void requireSurfaceRecoveryReady(Minecraft minecraft, WorldSessionTracker.Snapshot session,
            SurfacePreflightRecovery surfaceRecovery) {
        surfaceRecoveryFailure(minecraft, session, surfaceRecovery, RendererRecoveryStage.CAPTURE).ifPresent(reason -> {
            if (reason == AdmissionFenceFailure.RENDERER_EVIDENCE_MISSING) {
                throw new ClientCommandInbox.DeferControl();
            }
            throw admissionPreflightFailure(reason);
        });
    }

    void rendererRecoveryRevalidated(SurfacePreflightRecovery recovery, RendererRecoveryStage stage) {
        if (recovery.noteRevalidated(stage)) publishRendererRecovery.accept(recovery);
    }

    Optional<AdmissionFenceFailure> admissionFenceFailure(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            PreparedAgentAction prepared,
            LocalArmingState.Mode expectedMode,
            long expectedControlEpoch, RendererRecoveryStage stage) {
        var captured = prepared.snapshot();
        var player = minecraft.player;
        var lock = arming.snapshot(session.worldSessionId());
        if (!sameAdmissionSession(captured.session(), session)) {
            return Optional.of(AdmissionFenceFailure.WORLD_SESSION_CHANGED);
        }
        if (player == null) {
            return Optional.of(AdmissionFenceFailure.PLAYER_UNAVAILABLE);
        }
        if (lock.mode() != expectedMode) {
            return Optional.of(AdmissionFenceFailure.CONTROL_MODE_CHANGED);
        }
        if (lock.controlEpoch() != expectedControlEpoch) {
            return Optional.of(AdmissionFenceFailure.CONTROL_EPOCH_CHANGED);
        }
        if (!lock.capabilities().equals(captured.control().capabilities())) {
            return Optional.of(AdmissionFenceFailure.CAPABILITIES_CHANGED);
        }
        if (!ActionPlanning.playerPose(player, session.dimension()).equals(captured.pose())) {
            return Optional.of(AdmissionFenceFailure.POSE_CHANGED);
        }
        if (captured.localSafetyRequired()
                && (captured.localSafety() != LocalObservationProjector.CurrentSafety.CONTINUE
                        || agentObservations.localSafety() != LocalObservationProjector.CurrentSafety.CONTINUE)) {
            return Optional.of(AdmissionFenceFailure.LOCAL_SAFETY_CHANGED);
        }
        if (McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0F
                != captured.cameraDegreesPerTick()) {
            return Optional.of(AdmissionFenceFailure.CAMERA_POLICY_CHANGED);
        }
        if (minecraft.isMultiplayerServer() != captured.multiplayerServer()) {
            return Optional.of(AdmissionFenceFailure.MULTIPLAYER_CONTEXT_CHANGED);
        }
        if (multiplayerPolicyAllows.test(minecraft) != captured.multiplayerAllowed()) {
            return Optional.of(AdmissionFenceFailure.MULTIPLAYER_POLICY_CHANGED);
        }
        final KnownTraversabilitySnapshot currentMap;
        final AdmissionPolicySnapshot currentPredicates;
        final ClientReconciliationSignals.Snapshot currentReconciliation;
        final long currentVisualBarrierWorldRevision;
        final ToLongFunction<ActionDsl.Position> currentSurfaceRevisionBarrier;
        try {
            currentMap = agentObservations.requireAgentMap(session);
            currentReconciliation = reconciliationSignals.bindAndSnapshot(
                    Objects.requireNonNull(minecraft.level, "level"), session.worldSessionId());
            currentVisualBarrierWorldRevision = ActionEvidence.visualBarrierWorldRevision(
                    currentMap, currentReconciliation);
            currentSurfaceRevisionBarrier = prepared.initialPrimitive()
                    .map(primitive -> ActionEvidence.primitiveSurfaceRevisionBarrier(
                            primitive, currentMap, currentReconciliation))
                    .orElseGet(() -> ActionEvidence.surfaceRevisionBarrier(
                            currentMap, currentReconciliation));
            if (currentReconciliation.positionCorrectionRevision()
                    != captured.positionCorrectionRevision()) {
                return Optional.of(AdmissionFenceFailure.POSITION_CORRECTION_CHANGED);
            }
        } catch (RuntimeException | LinkageError changed) {
            return Optional.of(AdmissionFenceFailure.OBSERVATION_UNAVAILABLE);
        }
        try {
            currentPredicates = AdmissionPolicySnapshot.capture(
                    ActionPredicates.policySnapshot(minecraft), captured.predicateRequirements());
            ActionPredicates.validatePredicateAvailability(
                    prepared.program().request().program(), currentPredicates);
        } catch (RuntimeException | LinkageError changed) {
            return Optional.of(AdmissionFenceFailure.POLICY_UNAVAILABLE);
        }
        if (!ActionPlanning.firstPrimitive(prepared.program().request().program(), currentPredicates)
                .equals(prepared.initialPrimitive())) {
            return Optional.of(AdmissionFenceFailure.POLICY_BRANCH_CHANGED);
        }
        if (!ActionEvidence.routeDependenciesCurrent(currentMap, prepared.analysis().routeDependencies())) {
            return Optional.of(AdmissionFenceFailure.ROUTE_CHANGED);
        }
        var rendererFailure = surfaceRecoveryFailure(minecraft, session, prepared.surfaceRecovery(), stage);
        if (rendererFailure.isPresent()) return rendererFailure;
        Optional<ObservationFrame> currentPlanningFrame = agentObservations.agentPlanningFrame(
                prepared.initialPrimitive().orElse(null), prepared.surfaceRecovery().lease());
        if (prepared.frameItemAim().isPresent()) {
            try {
                var currentAim = AgentPrimitivePlanner.requireFrameItemAim(
                        currentMap, ActionPlanning.playerPose(player, session.dimension()), currentPlanningFrame,
                        prepared.initialPrimitive().orElseThrow(), currentVisualBarrierWorldRevision);
                if (!ActionEvidence.frameItemEvidenceFresh(currentAim, session.clientTick())
                        || !ActionEvidence.sameFrameItemAuthorization(prepared.frameItemAim().orElseThrow(), currentAim)) {
                    return Optional.of(AdmissionFenceFailure.FRAME_ITEM_CHANGED);
                }
            } catch (RuntimeException unavailable) {
                return Optional.of(AdmissionFenceFailure.FRAME_ITEM_CHANGED);
            }
        }
        if (!prepared.analysis().knownTargets().stream().allMatch(target ->
                        prepared.initialPrimitive()
                                        .filter(ActionDsl.FaceKnownPosition.class::isInstance)
                                        .isPresent()
                                ? AgentPrimitivePlanner.knownFacingTarget(
                                        currentMap, currentPlanningFrame, target)
                                : AgentPrimitivePlanner.knownTarget(
                                        currentMap,
                                        currentPlanningFrame,
                                        target,
                                        currentSurfaceRevisionBarrier.applyAsLong(target)))) {
            return Optional.of(prepared.surfaceRecovery().lease() == null
                    ? AdmissionFenceFailure.KNOWN_TARGET_CHANGED
                    : AdmissionFenceFailure.SURFACE_REOBSERVATION_MISMATCH);
        }
        if (!prepared.analysis().knownFacingSurfaces().stream().allMatch(surface ->
                        AgentPrimitivePlanner.knownFacingSurface(
                                currentMap, currentPlanningFrame, surface))) {
            return Optional.of(AdmissionFenceFailure.FACING_SURFACE_CHANGED);
        }
        if (!prepared.analysis().knownSurfaces().stream().allMatch(surface ->
                        AgentPrimitivePlanner.knownSurface(
                                currentMap,
                                currentPlanningFrame,
                                surface,
                                currentSurfaceRevisionBarrier.applyAsLong(surface.position())))) {
            return Optional.of(prepared.surfaceRecovery().lease() == null
                    ? AdmissionFenceFailure.KNOWN_SURFACE_CHANGED
                    : AdmissionFenceFailure.SURFACE_REOBSERVATION_MISMATCH);
        }
        if (!prepared.initialPrimitive()
                        .filter(ActionDsl.CollectVisibleItem.class::isInstance)
                        .map(ActionDsl.CollectVisibleItem.class::cast)
                        .map(target -> AgentPrimitivePlanner.visibleItemCurrent(
                                currentMap,
                                currentPlanningFrame,
                                target,
                                currentVisualBarrierWorldRevision,
                                session.clientTick(),
                                ActionBudgets.visibleItemEvidenceMaxAgeTicks(
                                        McmcpClientConfig.raysPerTick())))
                        .orElse(true)) {
            return Optional.of(AdmissionFenceFailure.VISIBLE_ITEM_CHANGED);
        }
        if (!prepared.initialPrimitive()
                        .filter(ActionDsl.CollectVisibleItemBatch.class::isInstance)
                        .map(ActionDsl.CollectVisibleItemBatch.class::cast)
                        .map(batch -> AgentPrimitivePlanner.visibleBatchItemAabbs(
                                        currentMap,
                                        currentPlanningFrame,
                                        batch,
                                        currentVisualBarrierWorldRevision,
                                        session.clientTick(),
                                        ActionBudgets.visibleItemEvidenceMaxAgeTicks(
                                                McmcpClientConfig.raysPerTick()))
                                .stream().allMatch(Optional::isPresent))
                        .orElse(true)) {
            return Optional.of(AdmissionFenceFailure.VISIBLE_BATCH_ITEM_CHANGED);
        }
        if (!KnownBreakSafety.breakProgramPreconditionsCurrent(
                minecraft, prepared.program(), prepared.initialPrimitive())) {
            return Optional.of(AdmissionFenceFailure.BREAK_PRECONDITION_CHANGED);
        }
        rendererRecoveryRevalidated(prepared.surfaceRecovery(), stage);
        return Optional.empty();
    }

    record AgentAdmissionSnapshot(
            WorldSessionTracker.Snapshot session,
            LocalArmingState.Snapshot control,
            KnownTraversabilitySnapshot map,
            AgentPrimitivePlanner.Pose pose,
            Optional<ObservationFrame> frame,
            LocalObservationProjector.CurrentSafety localSafety,
            boolean localSafetyRequired,
            PredicateRequirements predicateRequirements,
            AdmissionPolicySnapshot predicateSnapshot,
            float cameraDegreesPerTick,
            boolean multiplayerServer,
            boolean multiplayerAllowed,
            ClientReconciliationSignals.Snapshot reconciliation,
            long visualBarrierWorldRevision,
            long positionCorrectionRevision) {
        AgentAdmissionSnapshot {
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(control, "control");
            Objects.requireNonNull(map, "map");
            Objects.requireNonNull(pose, "pose");
            frame = Objects.requireNonNull(frame, "frame");
            Objects.requireNonNull(localSafety, "localSafety");
            Objects.requireNonNull(predicateRequirements, "predicateRequirements");
            Objects.requireNonNull(predicateSnapshot, "predicateSnapshot");
            Objects.requireNonNull(reconciliation, "reconciliation");
            if (ActionEvidence.visualBarrierWorldRevision(map, reconciliation)
                    != visualBarrierWorldRevision) {
                throw new IllegalArgumentException("visual barrier snapshot mismatch");
            }
            if (positionCorrectionRevision < 0L) {
                throw new IllegalArgumentException(
                        "positionCorrectionRevision must be non-negative");
            }
        }
    }

    record PreparedAgentAction(
            AgentAdmissionSnapshot snapshot,
            ActionDslCompiler.CompiledProgram program,
            ActionDslSource source,
            AgentPrimitivePlanner.Analysis analysis,
            Optional<ActionDsl.Node> initialPrimitive,
            Optional<AgentPrimitivePlanner.FrameItemAim> frameItemAim,
            SurfacePreflightRecovery surfaceRecovery) {
        PreparedAgentAction {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(program, "program");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(analysis, "analysis");
            Objects.requireNonNull(initialPrimitive, "initialPrimitive");
            Objects.requireNonNull(frameItemAim, "frameItemAim");
        }
    }

}
