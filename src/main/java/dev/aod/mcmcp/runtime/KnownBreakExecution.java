package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.McmcpMod;
import dev.aod.mcmcp.agent.action.AgentActionStore;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner;
import dev.aod.mcmcp.agent.action.KnownBlockBreakAttempt;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.navigation.KnownTraversabilitySnapshot;
import dev.aod.mcmcp.routine.BlockStateFingerprint;
import dev.aod.mcmcp.routine.BlockTarget;
import dev.aod.mcmcp.routine.MinecraftStationaryBreakPort;
import dev.aod.mcmcp.routine.SafeBreakSourcePolicy;
import dev.aod.mcmcp.routine.StationaryBreakGoal;
import dev.aod.mcmcp.routine.StationaryBreakRequest;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;

/** 既知block破壊の照準完了・一回のattempt・cleanup時のeffectを所有する。 */
final class KnownBreakExecution {
    private final UUID actionId;
    private final AgentActionStore agentActions;
    private final MinecraftStationaryBreakPort stationaryBreakPort;
    private final ClientReconciliationSignals reconciliationSignals;
    private final AgentObservations agentObservations;
    private KnownBlockBreakAttempt blockBreakAttempt;
    private ActionDsl.Node block;
    private boolean aimComplete;

    KnownBreakExecution(UUID actionId, AgentActionStore agentActions,
            MinecraftStationaryBreakPort stationaryBreakPort, ClientReconciliationSignals reconciliationSignals,
            AgentObservations agentObservations) {
        this.actionId = actionId;
        this.agentActions = agentActions;
        this.stationaryBreakPort = stationaryBreakPort;
        this.reconciliationSignals = reconciliationSignals;
        this.agentObservations = agentObservations;
    }

    boolean aimComplete() { return aimComplete; }
    void aimed() { aimComplete = true; }

    boolean close() {
        boolean closed = true;
        if (blockBreakAttempt != null) {
            KnownBlockBreakAttempt breaking = blockBreakAttempt;
            try {
                breaking.close();
                blockBreakAttempt = null;
            } catch (RuntimeException | LinkageError failure) {
                closed = false;
                McmcpMod.LOGGER.error("MCMCP known-face break release failed", failure);
            } finally {
                try {
                    if (KnownBreakSafety.isKnownBreak(block)) {
                        recordBreakEffects(
                                actionId,
                                KnownBreakSafety.breakTarget(block),
                                breaking.drainEffectDeltas());
                    }
                } catch (RuntimeException | LinkageError failure) {
                    closed = false;
                    McmcpMod.LOGGER.error(
                            "MCMCP known-block break effect capture failed", failure);
                }
            }
        }
        aimComplete = false;
        return closed;
    }

    PrimitiveOutcome tick(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            AgentActionStore.Active action,
            KnownTraversabilitySnapshot map,
            ActionDsl.Node block) {
        this.block = block;
        var player = Objects.requireNonNull(minecraft.player, "player");
        if (blockBreakAttempt == null) {
            var reconciliation = reconciliationSignals.bindAndSnapshot(
                    Objects.requireNonNull(minecraft.level, "level"),
                    session.worldSessionId());
            long surfaceBarrierWorldRevision = ActionEvidence.surfaceRevisionBarrier(map, reconciliation)
                    .applyAsLong(KnownBreakSafety.breakTarget(block));
            if (!KnownBreakSafety.breakTargetStateMatches(minecraft, block)) {
                return PrimitiveOutcome.failed(
                        AgentActionStore.FailureCode.WORLD_CHANGED,
                        true,
                        "break_target_changed");
            }
            if (!AgentPrimitivePlanner.knownSurface(
                            map,
                            agentObservations.agentPlanningFrame(),
                            new AgentPrimitivePlanner.KnownSurface(
                                    KnownBreakSafety.breakTarget(block), KnownBreakSafety.breakFace(block), KnownBreakSafety.breakBlockId(block)),
                            surfaceBarrierWorldRevision)
                    || !KnownBreakSafety.breakSourceControlled(minecraft, block)) {
                return PrimitiveOutcome.replan("break_target_reobservation");
            }
            try {
                var target = new BlockTarget(
                        KnownBreakSafety.breakTarget(block).dimension(),
                        KnownBreakSafety.breakTarget(block).x(),
                        KnownBreakSafety.breakTarget(block).y(),
                        KnownBreakSafety.breakTarget(block).z());
                var expected = stationaryBreakPort.captureExpectedSource(
                        target, Set.of(KnownBreakSafety.breakBlockId(block)));
                if (block instanceof ActionDsl.BreakKnownBlock exact) {
                    var declared = new BlockStateFingerprint(
                            exact.expectedState().block(), exact.expectedState().properties());
                    if (!expected.equals(declared)) {
                        return PrimitiveOutcome.replan("break_precondition_changed");
                    }
                    SafeBreakSourcePolicy.requireKnownBlockCombination(
                            exact.expectedState().block(),
                            exact.toolItem(),
                            exact.expectedDrop());
                }
                int minimumInventoryCount = block instanceof ActionDsl.BreakKnownBlock exact
                        ? exact.minimumInventoryCount()
                        : Math.min(2_304, Math.addExact(
                                PlayerInventoryEvidence.inventoryItemCount(player, KnownBreakSafety.breakExpectedDrop(block)), 1));
                var request = new StationaryBreakRequest(
                        target,
                        expected,
                        new StationaryBreakGoal(
                                KnownBreakSafety.breakExpectedDrop(block), minimumInventoryCount),
                        Math.addExact(
                                session.clientTick(),
                                AgentPrimitivePlanner.BREAK_TICK_UPPER_BOUND),
                        StationaryBreakRequest.MAX_ATTACK_LEASE_TICKS,
                        1);
                blockBreakAttempt = new KnownBlockBreakAttempt(
                        stationaryBreakPort, request, session.clientTick());
                return PrimitiveOutcome.running();
            } catch (SafeBreakSourcePolicy.UnsafeBreakSourceException
                    | IllegalArgumentException changed) {
                return PrimitiveOutcome.replan("break_precondition_changed");
            } catch (RuntimeException | LinkageError failure) {
                McmcpMod.LOGGER.error("MCMCP known-face break could not start", failure);
                return PrimitiveOutcome.failed(
                        AgentActionStore.FailureCode.SERVER_DENIED_OR_DESYNC,
                        true,
                        "break_start_failed");
            }
        }

        final KnownBlockBreakAttempt.TickResult result;
        try {
            result = blockBreakAttempt.tick(
                    session.clientTick(), KnownBreakSafety.breakSourceControlled(minecraft, block));
            recordBreakEffects(
                    action.actionId(), KnownBreakSafety.breakTarget(block),
                    blockBreakAttempt.drainEffectDeltas());
        } catch (RuntimeException | LinkageError failure) {
            McmcpMod.LOGGER.error("MCMCP known-face break confirmation failed", failure);
            return PrimitiveOutcome.failed(
                    AgentActionStore.FailureCode.SERVER_DENIED_OR_DESYNC,
                    true,
                    "break_confirmation_failed");
        }
        return switch (result) {
            case RUNNING -> PrimitiveOutcome.running();
            case SERVER_DENIED_OR_DESYNC -> PrimitiveOutcome.failed(
                    AgentActionStore.FailureCode.SERVER_DENIED_OR_DESYNC,
                    true,
                    "break_not_server_confirmed");
            case SUCCEEDED -> {
                blockBreakAttempt = null;
                agentActions.recordBlockBreak(action.actionId());
                aimComplete = false;
                yield PrimitiveOutcome.succeeded();
            }
        };
    }

    private void recordBreakEffects(
            UUID actionId,
            ActionDsl.Position target,
            List<KnownBlockBreakAttempt.EffectDelta> effects) {
        String subject = "block:" + target.dimension() + ":"
                + target.x() + "," + target.y() + "," + target.z();
        for (var effect : effects) {
            agentActions.recordEffect(
                    actionId,
                    "block_break",
                    subject,
                    effect.observedBefore(),
                    effect.observedAfter(),
                    effect.verification(),
                    effect.clientTick(),
                    effect.worldRevision());
        }
    }
}
