package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentActionStore;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner;
import dev.aod.mcmcp.agent.action.MinecraftActionPrimitiveExecutor;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.dsl.ActionDslCompiler;
import dev.aod.mcmcp.agent.navigation.DeterministicAStar;
import dev.aod.mcmcp.agent.navigation.KnownTraversabilitySnapshot;
import dev.aod.mcmcp.agent.navigation.NavCell;
import dev.aod.mcmcp.agent.navigation.RoutePlan;
import dev.aod.mcmcp.agent.observation.ObservationFrame;
import dev.aod.mcmcp.agent.safety.LocalObservationVolume;
import dev.aod.mcmcp.client.McmcpClientConfig;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;

/** 移動・照準の準備と入力executorを所有する。DSL遷移と再計画期限はrootが決める。 */
final class MovementExecution {
    private final MinecraftActionPrimitiveExecutor primitiveExecutor;
    private final DeterministicAStar agentPathfinder;
    private final AgentObservations agentObservations;
    private final ClientReconciliationSignals reconciliationSignals;
    private int agentSelectedSlot = -1;
    private NavCell pickupCell;

    MovementExecution(float maxCameraDegreesPerTick, DeterministicAStar agentPathfinder,
            AgentObservations agentObservations, ClientReconciliationSignals reconciliationSignals) {
        primitiveExecutor = new MinecraftActionPrimitiveExecutor(maxCameraDegreesPerTick);
        this.agentPathfinder = agentPathfinder;
        this.agentObservations = agentObservations;
        this.reconciliationSignals = reconciliationSignals;
    }

    int selectedSlot() { return agentSelectedSlot; }
    NavCell pickupCell() { return pickupCell; }
    boolean active() { return primitiveExecutor.active(); }
    void close() { primitiveExecutor.close(); }

    MinecraftActionPrimitiveExecutor.TickResult tick(Minecraft minecraft, KnownTraversabilitySnapshot map,
            LocalObservationVolume localVolume, double remainingDistance, double remainingCameraDegrees,
            long actionTick, BooleanSupplier deadlineCurrent) {
        return primitiveExecutor.tick(minecraft, map, localVolume, remainingDistance,
                remainingCameraDegrees, actionTick, deadlineCurrent);
    }

    /** この準備に必要な予算証拠だけを固定する。時計は計画後にも実時間を読む。 */
    record BudgetEvidence(AgentActionStore.Progress baseline, ActionDslCompiler.Cost limit,
            long startedAtNanos, long pausedNanos) {
        long elapsedNanos() {
            return McmcpRuntime.activeElapsedNanos(startedAtNanos, pausedNanos, System.nanoTime());
        }
        boolean fits(AgentActionStore.Progress progress, ActionDslCompiler.Cost cost) {
            return ActionBudgets.fitsOccurrenceBudget(progress,
                    Objects.requireNonNull(baseline, "occurrenceBaseline"),
                    Objects.requireNonNull(limit, "occurrenceLimit"), cost);
        }
    }

    PrimitiveOutcome begin(
            Minecraft minecraft,
            AgentActionStore.Active action,
            KnownTraversabilitySnapshot map,
            AgentActionStore.Progress progressBeforeTick,
            long currentTick, ActionDsl.Node primitive, BudgetEvidence budget, boolean replanning,
            Map<String, AgentPrimitivePlanner.MutationAim> mutationAims,
            ActionDsl.CollectVisibleItem collectTarget, int pickupInventoryBefore) {
        agentSelectedSlot = -1;
        pickupCell = null;
        var player = Objects.requireNonNull(minecraft.player, "player");
        var reconciliation = reconciliationSignals.bindAndSnapshot(
                Objects.requireNonNull(minecraft.level, "level"),
                map.worldSessionId());
        long visualBarrierWorldRevision = ActionEvidence.visualBarrierWorldRevision(map, reconciliation);
        var surfaceRevisionBarrier = ActionEvidence.surfaceRevisionBarrier(map, reconciliation);
        if (primitive instanceof ActionDsl.NavigateToKnown navigate) {
            return beginNavigate(navigate, player, action, map, progressBeforeTick, budget, replanning);
        }
        if (primitive instanceof ActionDsl.ApproachKnownSurface approach) {
            return beginApproachSurface(approach, player, action, map, progressBeforeTick, budget, replanning, surfaceRevisionBarrier);
        }
        if (primitive
                instanceof ActionDsl.ApproachKnownPlacement approach) {
            return beginApproachPlacement(approach, player, action, map, progressBeforeTick, budget, replanning, surfaceRevisionBarrier);
        }
        if (primitive instanceof ActionDsl.FaceKnownPosition face) {
            return beginFacePosition(face, player, action, map, progressBeforeTick, budget);
        }
        if (primitive instanceof ActionDsl.FaceKnownBlockFace face) {
            return beginFaceBlock(face, player, action, map, progressBeforeTick, budget);
        }
        if (primitive instanceof ActionDsl.BreakKnownFace block) {
            return beginBreakFace(block, player, action, map, progressBeforeTick, budget, replanning, surfaceRevisionBarrier);
        }
        if (primitive instanceof ActionDsl.BreakKnownBlock block) {
            return beginBreakBlock(block, player, action, map, progressBeforeTick, budget, replanning, surfaceRevisionBarrier);
        }
        if (primitive instanceof ActionDsl.CastKnownFishingRod cast) {
            return beginFishingAim(cast, player, map, budget, mutationAims);
        }
        if (primitive instanceof ActionDsl.CollectVisibleItem || primitive instanceof ActionDsl.CollectVisibleItemBatch) {
            return beginPickup(player, action, map, progressBeforeTick, currentTick, primitive, budget, replanning, collectTarget, pickupInventoryBefore, visualBarrierWorldRevision);
        }
        return PrimitiveOutcome.failed(AgentActionStore.FailureCode.INTERNAL_ERROR, false, "primitive_unavailable");
    }

    private PrimitiveOutcome beginNavigate(
            ActionDsl.NavigateToKnown navigate,
            net.minecraft.client.player.LocalPlayer player,
            AgentActionStore.Active action,
            KnownTraversabilitySnapshot map,
            AgentActionStore.Progress progressBeforeTick,
            BudgetEvidence budget,
            boolean replanning) {
        ActionDslCompiler.Cost cost;
        RoutePlan route = AgentPrimitivePlanner.requireRoute(
                map,
                agentPathfinder,
                ActionPlanning.playerCell(player, map.dimension()),
                navigate.target());
        var pose = ActionPlanning.playerPose(player, map.dimension());
        cost = replanning
                ? AgentPrimitivePlanner.navigationReplanCost(route, pose)
                : AgentPrimitivePlanner.navigationCost(route, pose);
        if (replanning) {
            String evidence = ActionBudgets.replannedRouteBudgetFailure(
                    progressBeforeTick,
                    budget.baseline(),
                    budget.limit(),
                    action.program().effectiveBudget(),
                    cost,
                    budget.elapsedNanos());
            if (evidence != null) {
                return PrimitiveOutcome.failed(
                        AgentActionStore.FailureCode.BUDGET_EXCEEDED,
                        false,
                        evidence);
            }
        } else if (!ActionBudgets.fitsRemainingBudget(
                        progressBeforeTick,
                        action.program().effectiveBudget(),
                        cost,
                        budget.elapsedNanos())) {
            return PrimitiveOutcome.failed(
                    AgentActionStore.FailureCode.BUDGET_EXCEEDED,
                    false,
                    "navigate_to_known");
        } else if (!budget.fits(progressBeforeTick, cost)) {
            return PrimitiveOutcome.failed(
                    AgentActionStore.FailureCode.BUDGET_EXCEEDED,
                    false,
                    "primitive_navigate_to_known");
        }
        primitiveExecutor.beginNavigate(route, navigate.tolerance());
        return PrimitiveOutcome.succeeded();
    }

    private PrimitiveOutcome beginApproachSurface(
            ActionDsl.ApproachKnownSurface approach,
            net.minecraft.client.player.LocalPlayer player,
            AgentActionStore.Active action,
            KnownTraversabilitySnapshot map,
            AgentActionStore.Progress progressBeforeTick,
            BudgetEvidence budget,
            boolean replanning,
            java.util.function.ToLongFunction<ActionDsl.Position> surfaceRevisionBarrier) {
        ActionDslCompiler.Cost cost;
        Optional<ObservationFrame> approachFrame = agentObservations.agentPlanningFrame();
        long approachSurfaceBarrier =
                surfaceRevisionBarrier.applyAsLong(approach.target());
        var pose = ActionPlanning.playerPose(player, map.dimension());
        AgentPrimitivePlanner.ApproachPlan plan =
                ActionPlanning.requireRuntimeApproachPlan(
                        map,
                        agentPathfinder,
                        pose,
                        approach,
                        approachFrame,
                        approachSurfaceBarrier);
        cost = replanning
                ? AgentPrimitivePlanner.navigationReplanCost(plan.route(), pose)
                : AgentPrimitivePlanner.navigationCost(plan.route(), pose);
        if (replanning) {
            String evidence = ActionBudgets.replannedRouteBudgetFailure(
                    progressBeforeTick,
                    budget.baseline(),
                    budget.limit(),
                    action.program().effectiveBudget(),
                    cost,
                    budget.elapsedNanos());
            if (evidence != null) {
                return PrimitiveOutcome.failed(
                        AgentActionStore.FailureCode.BUDGET_EXCEEDED,
                        false,
                        evidence);
            }
        } else if (!ActionBudgets.fitsRemainingBudget(
                        progressBeforeTick,
                        action.program().effectiveBudget(),
                        cost,
                        budget.elapsedNanos())
                || !budget.fits(progressBeforeTick, cost)) {
            return PrimitiveOutcome.failed(
                    AgentActionStore.FailureCode.BUDGET_EXCEEDED,
                    false,
                    "approach_known_surface");
        }
        primitiveExecutor.beginNavigate(plan.route(), 0.25D);
        return PrimitiveOutcome.succeeded();
    }

    private PrimitiveOutcome beginApproachPlacement(
            ActionDsl.ApproachKnownPlacement approach,
            net.minecraft.client.player.LocalPlayer player,
            AgentActionStore.Active action,
            KnownTraversabilitySnapshot map,
            AgentActionStore.Progress progressBeforeTick,
            BudgetEvidence budget,
            boolean replanning,
            java.util.function.ToLongFunction<ActionDsl.Position> surfaceRevisionBarrier) {
        ActionDslCompiler.Cost cost;
        var pose = ActionPlanning.playerPose(player, map.dimension());
        AgentPrimitivePlanner.ApproachPlan plan =
                ActionPlanning.requireRuntimeKnownPlacementApproachPlan(
                        map,
                        agentPathfinder,
                        pose,
                        approach,
                        agentObservations.agentPlanningFrame(),
                        surfaceRevisionBarrier,
                        agentObservations.deliveredEvidence()::resolvePlacementState);
        cost = replanning
                ? AgentPrimitivePlanner.navigationReplanCost(plan.route(), pose)
                : AgentPrimitivePlanner.navigationCost(plan.route(), pose);
        if (replanning) {
            String evidence = ActionBudgets.replannedRouteBudgetFailure(
                    progressBeforeTick,
                    budget.baseline(),
                    budget.limit(),
                    action.program().effectiveBudget(),
                    cost,
                    budget.elapsedNanos());
            if (evidence != null) {
                return PrimitiveOutcome.failed(
                        AgentActionStore.FailureCode.BUDGET_EXCEEDED,
                        false,
                        evidence);
            }
        } else if (!ActionBudgets.fitsRemainingBudget(
                        progressBeforeTick,
                        action.program().effectiveBudget(),
                        cost,
                        budget.elapsedNanos())
                || !budget.fits(progressBeforeTick, cost)) {
            return PrimitiveOutcome.failed(
                    AgentActionStore.FailureCode.BUDGET_EXCEEDED,
                    false,
                    "approach_known_placement");
        }
        primitiveExecutor.beginNavigate(plan.route(), 0.25D);
        return PrimitiveOutcome.succeeded();
    }

    private PrimitiveOutcome beginFacePosition(
            ActionDsl.FaceKnownPosition face,
            net.minecraft.client.player.LocalPlayer player,
            AgentActionStore.Active action,
            KnownTraversabilitySnapshot map,
            AgentActionStore.Progress progressBeforeTick,
            BudgetEvidence budget) {
        ActionDslCompiler.Cost cost;
        var target = AgentPrimitivePlanner.requireKnownFaceTarget(
                map,
                agentObservations.agentPlanningFrame(),
                face.target());
        cost = AgentPrimitivePlanner.faceCost(
                ActionPlanning.playerPose(player, map.dimension()),
                face.target(),
                McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0F);
        if (!ActionBudgets.fitsRemainingBudget(
                progressBeforeTick,
                action.program().effectiveBudget(),
                cost,
                budget.elapsedNanos())) {
            return PrimitiveOutcome.failed(
                    AgentActionStore.FailureCode.BUDGET_EXCEEDED, false, "face_target");
        }
        if (!budget.fits(progressBeforeTick, cost)) {
            return PrimitiveOutcome.failed(
                    AgentActionStore.FailureCode.BUDGET_EXCEEDED,
                    false,
                    "primitive_face_target");
        }
        primitiveExecutor.beginFace(target, cost.ticks());
        return PrimitiveOutcome.succeeded();
    }

    private PrimitiveOutcome beginFaceBlock(
            ActionDsl.FaceKnownBlockFace face,
            net.minecraft.client.player.LocalPlayer player,
            AgentActionStore.Active action,
            KnownTraversabilitySnapshot map,
            AgentActionStore.Progress progressBeforeTick,
            BudgetEvidence budget) {
        ActionDslCompiler.Cost cost;
        var target = AgentPrimitivePlanner.requireKnownBlockFaceTarget(
                map,
                agentObservations.agentPlanningFrame(),
                face);
        cost = AgentPrimitivePlanner.faceCost(
                ActionPlanning.playerPose(player, map.dimension()),
                face,
                McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0F);
        if (!ActionBudgets.fitsRemainingBudget(
                progressBeforeTick,
                action.program().effectiveBudget(),
                cost,
                budget.elapsedNanos())) {
            return PrimitiveOutcome.failed(
                    AgentActionStore.FailureCode.BUDGET_EXCEEDED,
                    false,
                    "face_block_target");
        }
        if (!budget.fits(progressBeforeTick, cost)) {
            return PrimitiveOutcome.failed(
                    AgentActionStore.FailureCode.BUDGET_EXCEEDED,
                    false,
                    "primitive_face_block_target");
        }
        primitiveExecutor.beginFace(target, cost.ticks());
        return PrimitiveOutcome.succeeded();
    }

    private PrimitiveOutcome beginBreakFace(
            ActionDsl.BreakKnownFace block,
            net.minecraft.client.player.LocalPlayer player,
            AgentActionStore.Active action,
            KnownTraversabilitySnapshot map,
            AgentActionStore.Progress progressBeforeTick,
            BudgetEvidence budget,
            boolean replanning,
            java.util.function.ToLongFunction<ActionDsl.Position> surfaceRevisionBarrier) {
        ActionDslCompiler.Cost cost;
        AgentPrimitivePlanner.MutationAim breakAim =
                AgentPrimitivePlanner.requireKnownBreakAim(
                map,
                agentObservations.agentPlanningFrame(),
                block,
                surfaceRevisionBarrier.applyAsLong(block.target()));
        cost = AgentPrimitivePlanner.breakCost(
                ActionPlanning.playerPose(player, map.dimension()),
                block,
                breakAim.point(),
                McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0F);
        long aimTicks = ActionBudgets.breakAimTicks(cost);
        cost = ActionBudgets.breakExecutionCost(cost, replanning);
        if (!ActionBudgets.fitsRemainingBudget(
                progressBeforeTick,
                action.program().effectiveBudget(),
                cost,
                budget.elapsedNanos())
                || !budget.fits(progressBeforeTick, cost)) {
            return PrimitiveOutcome.failed(
                    AgentActionStore.FailureCode.BUDGET_EXCEEDED,
                    false,
                    "break_known_face");
        }
        int remainingBreaks = Math.toIntExact(Math.max(
                1L,
                action.program().worstCaseCost().blocksBroken()
                        - progressBeforeTick.blocksBroken()));
        int toolSlot = KnownBreakSafety.findDurableHotbarTool(
                player, block.toolItem(), remainingBreaks);
        if (toolSlot < 0 || !KnownBreakSafety.inventoryCanReceiveKnownBreakDrops(
                player, action.program())) {
            return PrimitiveOutcome.failed(
                    AgentActionStore.FailureCode.WORLD_CHANGED,
                    true,
                    toolSlot < 0 ? "required_axe_unavailable" : "inventory_full");
        }
        player.getInventory().setSelectedSlot(toolSlot);
        agentSelectedSlot = toolSlot;
        primitiveExecutor.beginFace(
                new MinecraftActionPrimitiveExecutor.KnownFaceTarget(
                        map.worldSessionId(), map.worldRevision(),
                        block.target(), breakAim.point().x,
                        breakAim.point().y, breakAim.point().z, true),
                aimTicks);
        return PrimitiveOutcome.succeeded();
    }

    private PrimitiveOutcome beginBreakBlock(
            ActionDsl.BreakKnownBlock block,
            net.minecraft.client.player.LocalPlayer player,
            AgentActionStore.Active action,
            KnownTraversabilitySnapshot map,
            AgentActionStore.Progress progressBeforeTick,
            BudgetEvidence budget,
            boolean replanning,
            java.util.function.ToLongFunction<ActionDsl.Position> surfaceRevisionBarrier) {
        ActionDslCompiler.Cost cost;
        AgentPrimitivePlanner.MutationAim breakAim =
                AgentPrimitivePlanner.requireKnownBreakAim(
                        map,
                        agentObservations.agentPlanningFrame(),
                        block,
                        surfaceRevisionBarrier.applyAsLong(block.target()));
        cost = AgentPrimitivePlanner.breakCost(
                ActionPlanning.playerPose(player, map.dimension()),
                block,
                breakAim.point(),
                McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0F);
        long aimTicks = ActionBudgets.breakAimTicks(cost);
        cost = ActionBudgets.breakExecutionCost(cost, replanning);
        if (!ActionBudgets.fitsRemainingBudget(
                progressBeforeTick,
                action.program().effectiveBudget(),
                cost,
                budget.elapsedNanos())
                || !budget.fits(progressBeforeTick, cost)) {
            return PrimitiveOutcome.failed(
                    AgentActionStore.FailureCode.BUDGET_EXCEEDED,
                    false,
                    "break_known_block");
        }
        int remainingBreaks = Math.toIntExact(Math.max(
                1L,
                action.program().worstCaseCost().blocksBroken()
                        - progressBeforeTick.blocksBroken()));
        int toolSlot = KnownBreakSafety.findDurableHotbarTool(
                player, block.toolItem(), remainingBreaks);
        if (toolSlot < 0 || !KnownBreakSafety.inventoryCanReceiveKnownBreakDrops(
                player, action.program())) {
            return PrimitiveOutcome.failed(
                    AgentActionStore.FailureCode.WORLD_CHANGED,
                    true,
                    toolSlot < 0 ? "required_tool_unavailable" : "inventory_full");
        }
        player.getInventory().setSelectedSlot(toolSlot);
        agentSelectedSlot = toolSlot;
        primitiveExecutor.beginFace(
                new MinecraftActionPrimitiveExecutor.KnownFaceTarget(
                        map.worldSessionId(), map.worldRevision(),
                        block.target(), breakAim.point().x,
                        breakAim.point().y, breakAim.point().z, true),
                aimTicks);
        return PrimitiveOutcome.succeeded();
    }

    private PrimitiveOutcome beginFishingAim(
            ActionDsl.CastKnownFishingRod cast,
            net.minecraft.client.player.LocalPlayer player,
            KnownTraversabilitySnapshot map,
            BudgetEvidence budget,
            Map<String, AgentPrimitivePlanner.MutationAim> mutationAims) {
        ActionDslCompiler.Cost cost;
        if (!PlayerInventoryEvidence.exactFishingRodHeld(player, cast.hand(), cast.rodItem())
                || player.fishing != null) {
            return PrimitiveOutcome.failed(
                    AgentActionStore.FailureCode.WORLD_CHANGED,
                    true,
                    player.fishing == null
                            ? "required_fishing_rod_unavailable"
                            : "owned_bobber_already_present");
        }
        AgentPrimitivePlanner.MutationAim aim = Objects.requireNonNull(
                mutationAims.get(cast.id()), "fishing cast aim");
        cost = Objects.requireNonNull(
                budget.limit(), "fishing cast cost");
        primitiveExecutor.beginFace(
                new MinecraftActionPrimitiveExecutor.KnownFaceTarget(
                        map.worldSessionId(), map.worldRevision(), cast.target(),
                        aim.point().x, aim.point().y, aim.point().z, true),
                Math.max(1L, Math.min(600L, cost.ticks())));
        return PrimitiveOutcome.succeeded();
    }

    private PrimitiveOutcome beginPickup(
            net.minecraft.client.player.LocalPlayer player,
            AgentActionStore.Active action,
            KnownTraversabilitySnapshot map,
            AgentActionStore.Progress progressBeforeTick,
            long currentTick,
            ActionDsl.Node primitive,
            BudgetEvidence budget,
            boolean replanning,
            ActionDsl.CollectVisibleItem collectTarget,
            int pickupInventoryBefore,
            long visualBarrierWorldRevision) {
        ActionDslCompiler.Cost cost;
        ActionDsl.CollectVisibleItem collect = Objects.requireNonNull(
                collectTarget, "active collect target");
        AgentPrimitivePlanner.PickupPlan pickup = AgentPrimitivePlanner.requirePickupPlan(
                map,
                agentPathfinder,
                ActionPlanning.playerCell(player, map.dimension()),
                agentObservations.agentPlanningFrame(),
                collect,
                visualBarrierWorldRevision,
                currentTick,
                ActionBudgets.visibleItemEvidenceMaxAgeTicks(McmcpClientConfig.raysPerTick()));
        var pose = ActionPlanning.playerPose(player, map.dimension());
        cost = replanning
                ? AgentPrimitivePlanner.pickupReplanCost(pickup.route(), pose)
                : AgentPrimitivePlanner.pickupCost(pickup.route(), pose);
        if (replanning) {
            String evidence = ActionBudgets.replannedRouteBudgetFailure(
                    progressBeforeTick,
                    budget.baseline(),
                    budget.limit(),
                    action.program().effectiveBudget(),
                    cost,
                    budget.elapsedNanos());
            if (evidence != null) {
                return PrimitiveOutcome.failed(
                        AgentActionStore.FailureCode.BUDGET_EXCEEDED,
                        false,
                        evidence);
            }
        } else if (!ActionBudgets.fitsRemainingBudget(
                        progressBeforeTick,
                        action.program().effectiveBudget(),
                        cost,
                        budget.elapsedNanos())
                || !budget.fits(progressBeforeTick, cost)) {
            return PrimitiveOutcome.failed(
                    AgentActionStore.FailureCode.BUDGET_EXCEEDED,
                    false,
                    "collect_visible_item");
        }
        if (primitive instanceof ActionDsl.CollectVisibleItem
                && pickupInventoryBefore < 0) {
            throw new IllegalStateException(
                    "collect occurrence inventory baseline was not captured");
        }
        pickupCell = pickup.pickupCell();
        primitiveExecutor.beginNavigate(pickup.route(), 0.25D);
        return PrimitiveOutcome.succeeded();
    }
}
