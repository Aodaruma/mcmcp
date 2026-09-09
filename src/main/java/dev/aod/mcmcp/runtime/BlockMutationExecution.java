package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.McmcpMod;
import dev.aod.mcmcp.agent.action.AgentActionStore;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner;
import dev.aod.mcmcp.agent.action.KnownBlockMutationAttempt;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.dsl.ActionDslCompiler;
import dev.aod.mcmcp.client.AgentInputState;
import dev.aod.mcmcp.client.McmcpClientConfig;
import dev.aod.mcmcp.routine.MinecraftSemanticActionPort;
import dev.aod.mcmcp.routine.SemanticActionRequest;
import dev.aod.mcmcp.runtime.ActionBudgets.BatchTargetDisposition;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** 一つのmutation occurrenceのbatch進行・再証明期限・attemptと受動下降証拠。 */
final class BlockMutationExecution {
    private final UUID actionId;
    private final AgentActionStore agentActions;
    private final MinecraftSemanticActionPort semanticActionPort;
    private final AgentObservations agentObservations;
    private final ActionAdmission actionAdmission;
    private final ClientReconciliationSignals reconciliationSignals;
    private KnownBlockMutationAttempt blockMutationAttempt;
    private int mutationAimFailures;
    private AgentPrimitivePlanner.MutationBatchPlan mutationBatchPlan;
    private int mutationBatchIndex;
    private ActionDsl.Node mutationBatchTarget;
    private AgentPrimitivePlanner.MutationAim mutationBatchTargetAim;
    private boolean mutationBatchTargetBound;
    private long mutationBatchTargetDeadlineTick;
    private double tillSettlingAllowance;
    private ActionDsl.Position tillSettlingTarget;
    private long tillSettlingDeadlineTick;

    BlockMutationExecution(UUID actionId, AgentActionStore agentActions,
            MinecraftSemanticActionPort semanticActionPort, AgentObservations agentObservations,
            ActionAdmission actionAdmission, ClientReconciliationSignals reconciliationSignals) {
        this.actionId = actionId;
        this.agentActions = agentActions;
        this.semanticActionPort = semanticActionPort;
        this.agentObservations = agentObservations;
        this.actionAdmission = actionAdmission;
        this.reconciliationSignals = reconciliationSignals;
    }

    void resetOccurrence() {
        mutationAimFailures = 0;
        mutationBatchPlan = null;
        mutationBatchIndex = 0;
        mutationBatchTarget = null;
        mutationBatchTargetAim = null;
        mutationBatchTargetBound = false;
        mutationBatchTargetDeadlineTick = 0L;
    }

    boolean targetBound() { return mutationBatchTargetBound; }

    void bindPlan(AgentPrimitivePlanner.MutationBatchPlan plan) { mutationBatchPlan = plan; }

    boolean retryAimAllowed() {
        blockMutationAttempt.close();
        blockMutationAttempt = null;
        mutationAimFailures++;
        return ActionBudgets.mutationAimRetryAllowed(mutationAimFailures);
    }

    boolean close() {
        boolean closed = true;
        if (blockMutationAttempt != null) {
            try {
                blockMutationAttempt.close();
                blockMutationAttempt = null;
            } catch (RuntimeException | LinkageError failure) {
                closed = false;
                McmcpMod.LOGGER.error("MCMCP known-block mutation release failed", failure);
            }
        }
        return closed;
    }

    PrimitiveOutcome bindTarget(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            AgentActionStore.Active action,
            AgentActionStore.Progress occurrenceBaseline, ActionDslCompiler.Cost occurrenceLimit,
            long startedAtNanos, long pausedNanos) {
        if (mutationBatchTargetBound) {
            return PrimitiveOutcome.succeeded();
        }
        AgentPrimitivePlanner.MutationBatchPlan plan = mutationBatchPlan;
        if (plan == null || mutationBatchIndex >= plan.steps().size()) {
            return PrimitiveOutcome.failed(
                    AgentActionStore.FailureCode.INTERNAL_ERROR, false, "mutation_batch_plan_missing");
        }
        AgentPrimitivePlanner.MutationBatchStep step =
                plan.steps().get(mutationBatchIndex);
        var player = Objects.requireNonNull(minecraft.player, "player");
        var map = agentObservations.requireAgentMap(session);
        var reconciliation = reconciliationSignals.bindAndSnapshot(
                Objects.requireNonNull(minecraft.level, "level"), session.worldSessionId());
        AgentPrimitivePlanner.Pose currentPose = ActionPlanning.playerPose(player, session.dimension());
        var analysis = actionAdmission.analyzePrimitive(
                action.program().request().program(),
                step.primitive(),
                map,
                currentPose,
                agentObservations.agentPlanningFrame(),
                McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0F,
                ActionEvidence.visualBarrierWorldRevision(map, reconciliation),
                ActionEvidence.surfaceRevisionBarrier(map, reconciliation),
                () -> true);
        ActionDslCompiler.Cost cost = analysis.worstCase(step.primitive()).orElseThrow();
        AgentPrimitivePlanner.MutationAim freshAim = analysis.mutationAims()
                .get(step.primitive().id());
        if (freshAim == null) {
            throw new IllegalStateException("Fresh batch target aim is unavailable");
        }
        ActionDslCompiler.Cost requiredRemainder = ActionBudgets.mutationBatchRequiredRemainder(
                plan,
                mutationBatchIndex,
                currentPose,
                freshAim,
                cost,
                McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0F);
        AgentActionStore.Progress progress = agentActions.get(action.actionId()).progress();
        if (!ActionBudgets.fitsMutationBatchRemainder(
                progress,
                occurrenceBaseline,
                occurrenceLimit,
                action.program().effectiveBudget(),
                requiredRemainder,
                McmcpRuntime.activeElapsedNanos(startedAtNanos, pausedNanos, System.nanoTime()))) {
            return PrimitiveOutcome.failed(
                    AgentActionStore.FailureCode.BUDGET_EXCEEDED,
                    false,
                    "batch_target_budget");
        }
        mutationBatchTarget = step.primitive();
        mutationBatchTargetAim = freshAim;
        mutationBatchTargetBound = true;
        mutationBatchTargetDeadlineTick = 0L;
        return PrimitiveOutcome.succeeded();
    }

    PrimitiveOutcome waitForReproof(long actionTick, AgentPrimitivePlanner.PlanningException unavailable) {
        if (mutationBatchTargetDeadlineTick == 0L) {
            mutationBatchTargetDeadlineTick = Math.addExact(
                    actionTick, AgentPrimitivePlanner.MUTATION_BATCH_REPROOF_TICKS);
            agentActions.setPhase(
                    actionId, AgentActionStore.Phase.REPLANNING,
                    "batch_" + unavailable.code().name().toLowerCase(Locale.ROOT));
        }
        if (ActionBudgets.replanDeadlineReached(
                actionTick, mutationBatchTargetDeadlineTick)) {
            return PrimitiveOutcome.failed(
                    AgentActionStore.FailureCode.PATH_BLOCKED,
                    true,
                    "batch_" + unavailable.code().name().toLowerCase(Locale.ROOT));
        }
        return PrimitiveOutcome.running();
    }


    PrimitiveOutcome tick(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            AgentActionStore.Active action,
            ActionDsl.Node primitive, Map<String, AgentPrimitivePlanner.MutationAim> mutationAims) {
        ActionDsl.Node mutation = ActionBudgets.isMutationBatch(primitive) ? mutationBatchTarget : primitive;
        if (blockMutationAttempt == null) {
            SemanticActionRequest request = ConstructionRequests.blockMutationRequest(
                    mutation,
                    ActionBudgets.isMutationBatch(primitive)
                            ? mutationBatchTargetAim
                            : mutationAims.get(primitive.id()));
            long deadline = Math.addExact(
                    session.clientTick(), AgentPrimitivePlanner.BLOCK_MUTATION_TICK_UPPER_BOUND);
            blockMutationAttempt = new KnownBlockMutationAttempt(
                    semanticActionPort, request, session.clientTick(), deadline);
        }
        KnownBlockMutationAttempt.TickResult result =
                blockMutationAttempt.tick(session.clientTick());
        if (result.dispatchedThisTick()) {
            armBatchTillSettlingAllowance(minecraft, primitive, mutation);
        }
        switch (result.status()) {
            case RUNNING -> { }
            case FAILED -> {
                if (ActionBudgets.isMutationBatch(primitive)
                        && ActionBudgets.mutationBatchDisposition(
                                mutationBatchIndex,
                                mutationBatchPlan.steps().size(),
                                false) != BatchTargetDisposition.STOP) {
                    throw new IllegalStateException("Failed batch target must stop dispatch");
                }
                if (ActionBudgets.retryableMutationAimFailure(result.evidence())) {
                    if (!ActionBudgets.mutationAimRetriesAllowed(primitive)) {
                        return PrimitiveOutcome.failed(
                                AgentActionStore.FailureCode.PATH_BLOCKED,
                                true,
                                "batch_aim_raycast_unavailable");
                    } else {
                        return PrimitiveOutcome.replan(result.evidence());
                    }
                } else {
                    return PrimitiveOutcome.failed(
                            AgentActionStore.FailureCode.SERVER_DENIED_OR_DESYNC,
                            true,
                            result.evidence());
                }
            }
            case SUCCEEDED -> {
                blockMutationAttempt = null;
                if (result.performed()) {
                    if (mutation instanceof ActionDsl.TillKnownBlock
                            || mutation instanceof ActionDsl.OpenKnownFenceGate
                            || mutation instanceof ActionDsl.OpenKnownPassage) {
                        agentActions.recordInteraction(action.actionId());
                    } else if (mutation instanceof ActionDsl.PlantKnownWheat) {
                        agentActions.recordBlockPlace(action.actionId());
                    } else {
                        agentActions.recordBlockBreak(action.actionId());
                    }
                }
                if (ActionBudgets.isMutationBatch(primitive)) {
                    agentActions.recordNodeEvidence(
                            action.actionId(), ActionBudgets.batchTargetTrace(mutation));
                    BatchTargetDisposition disposition = ActionBudgets.mutationBatchDisposition(
                            mutationBatchIndex,
                            mutationBatchPlan.steps().size(),
                            true);
                    mutationBatchIndex++;
                    mutationBatchTarget = null;
                    mutationBatchTargetAim = null;
                    mutationBatchTargetBound = false;
                    mutationBatchTargetDeadlineTick = 0L;
                    mutationAimFailures = 0;
                    if (disposition == BatchTargetDisposition.COMPLETE) return PrimitiveOutcome.succeeded();
                } else {
                    return PrimitiveOutcome.succeeded();
                }
            }
        }
        return PrimitiveOutcome.running();
    }

    private void armBatchTillSettlingAllowance(Minecraft minecraft, ActionDsl.Node primitive, ActionDsl.Node mutation) {
        tillSettlingAllowance = 0.0D;
        tillSettlingTarget = null;
        tillSettlingDeadlineTick = 0L;
        if (!ActionBudgets.isMutationBatch(primitive)
                || !(mutation instanceof ActionDsl.TillKnownBlock till)
                || minecraft.player == null) {
            return;
        }
        var player = minecraft.player;
        if (Mth.floor(player.getY()) == till.target().y() + 1
                && Mth.floor(player.getX()) == till.target().x()
                && Mth.floor(player.getZ()) == till.target().z()) {
            tillSettlingAllowance = 1.0D / 16.0D;
            tillSettlingTarget = till.target();
            tillSettlingDeadlineTick = Math.addExact(
                    agentActions.get(actionId).progress().ticks(), 2L);
        }
    }

    double consumeSettlingCredit(Vec3 previousPosition, Vec3 position, boolean movementActive) {
        double distance = position.distanceTo(previousPosition);
        var settlingTarget = tillSettlingTarget;
        long currentTick = agentActions.get(actionId).progress().ticks();
        var level = Minecraft.getInstance().level;
        boolean settlingWindow = settlingTarget != null
                && currentTick <= tillSettlingDeadlineTick
                && !movementActive
                && level != null
                && "minecraft:farmland".equals(BuiltInRegistries.BLOCK.getKey(
                                level.getBlockState(new BlockPos(
                                        settlingTarget.x(),
                                        settlingTarget.y(),
                                        settlingTarget.z())).getBlock())
                        .toString())
                && Mth.floor(previousPosition.x) == settlingTarget.x()
                && Mth.floor(previousPosition.z) == settlingTarget.z()
                && Mth.floor(position.x) == settlingTarget.x()
                && Mth.floor(position.z) == settlingTarget.z();
        var movement = AgentInputState.global().movementSnapshot();
        boolean inputNeutral = !movement.forward()
                && !movement.backward()
                && !movement.left()
                && !movement.right()
                && !movement.jump();
        double settlingCredit = ActionBudgets.batchTillSettlingCredit(
                previousPosition,
                position,
                tillSettlingAllowance,
                settlingWindow,
                inputNeutral);
        if (settlingCredit > 0.0D
                || distance > 1.0e-9D
                || currentTick > tillSettlingDeadlineTick) {
            tillSettlingAllowance = 0.0D;
            tillSettlingTarget = null;
            tillSettlingDeadlineTick = 0L;
        }
        return settlingCredit;
    }
}
