package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentActionStore;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.observation.ObservationRecord;
import dev.aod.mcmcp.runtime.ActionEvidence.CropWaitAuthorization;
import dev.aod.mcmcp.runtime.ActionEvidence.CropWaitLiveState;
import dev.aod.mcmcp.runtime.ActionEvidence.CropWaitVisibilityState;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.projectile.FishingHook;

/** wait occurrenceの残tickと、限定座標のcrop・実再生sound証拠を所有する。 */
final class WaitExecution {
    private final AgentObservations agentObservations;
    private final ClientReconciliationSignals reconciliationSignals;
    private int waitTicksRemaining;
    private CropWaitAuthorization cropWaitAuthorization;

    WaitExecution(AgentObservations agentObservations, ClientReconciliationSignals reconciliationSignals) {
        this.agentObservations = agentObservations;
        this.reconciliationSignals = reconciliationSignals;
    }

    void begin(ActionDsl.Node primitive) {
        cropWaitAuthorization = null;
        if (primitive instanceof ActionDsl.WaitTicks wait) waitTicksRemaining = wait.ticks();
        else if (primitive instanceof ActionDsl.WaitUntil wait) waitTicksRemaining = wait.maxTicks();
    }

    void authorize(CropWaitAuthorization authorization) { cropWaitAuthorization = authorization; }

    PrimitiveOutcome tick(Minecraft minecraft, WorldSessionTracker.Snapshot session, ActionDsl.Node primitive) {
        boolean complete = false;
        if (primitive instanceof ActionDsl.WaitUntil wait
                && wait.condition() instanceof ActionDsl.CropMatureCondition) {
            CropWaitLiveState live = authorizedCropWaitLiveState(
                    minecraft,
                    session,
                    wait,
                    cropWaitAuthorization);
            if (live == CropWaitLiveState.WORLD_CHANGED) {
                return PrimitiveOutcome.failed(
                        AgentActionStore.FailureCode.WORLD_CHANGED,
                        true,
                        "crop_wait_world_changed");
            }
            if (live == CropWaitLiveState.VISIBILITY_INVALIDATED) {
                return PrimitiveOutcome.failed(
                        AgentActionStore.FailureCode.PATH_BLOCKED,
                        true,
                        "crop_wait_visibility_invalidated");
            }
            if (live == CropWaitLiveState.UNLOADED) {
                return PrimitiveOutcome.failed(
                        AgentActionStore.FailureCode.PATH_BLOCKED,
                        true,
                        "crop_wait_target_unloaded");
            }
            if (live == CropWaitLiveState.TARGET_CHANGED) {
                return PrimitiveOutcome.failed(
                        AgentActionStore.FailureCode.PATH_BLOCKED,
                        true,
                        "crop_wait_target_changed");
            }
            complete = live == CropWaitLiveState.MATURE;
        } else if (primitive instanceof ActionDsl.WaitUntil wait
                && wait.condition() instanceof ActionDsl.SoundClueCondition sound) {
            complete = soundClueMatched(minecraft, sound, session);
        }
        if (complete || primitive instanceof ActionDsl.WaitTicks
                && --waitTicksRemaining == 0) {
            return PrimitiveOutcome.succeeded();
        } else if (primitive instanceof ActionDsl.WaitUntil
                && --waitTicksRemaining == 0) {
            return PrimitiveOutcome.failed(
                    AgentActionStore.FailureCode.CONDITION_TIMEOUT,
                    true,
                    "wait_condition_timeout");
        }
        return PrimitiveOutcome.running();
    }

    private CropWaitLiveState authorizedCropWaitLiveState(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            ActionDsl.WaitUntil wait,
            CropWaitAuthorization authorization) {
        var level = minecraft.level;
        var player = minecraft.player;
        if (level == null || player == null) {
            return CropWaitLiveState.WORLD_CHANGED;
        }
        var reconciliation = reconciliationSignals.bindAndSnapshot(
                level, session.worldSessionId());
        CropWaitVisibilityState visibility = ActionEvidence.cropWaitVisibilityState(
                authorization,
                session,
                ((ActionDsl.CropMatureCondition) wait.condition()).target(),
                reconciliation.visualBarrierWorldRevision(),
                player.position(),
                player.getEyePosition());
        if (visibility == CropWaitVisibilityState.WORLD_CHANGED) {
            return CropWaitLiveState.WORLD_CHANGED;
        }
        if (visibility == CropWaitVisibilityState.VISIBILITY_INVALIDATED) {
            return CropWaitLiveState.VISIBILITY_INVALIDATED;
        }
        ActionDsl.Position target = authorization.target();
        var position = new BlockPos(target.x(), target.y(), target.z());
        if (!level.isLoaded(position) || !level.getWorldBorder().isWithinBounds(position)) {
            return CropWaitLiveState.UNLOADED;
        }
        // The authorization is coordinate-exact; do not inspect any neighboring or hidden state.
        return ActionEvidence.cropWaitLiveState(true, level.getBlockState(position));
    }

    private boolean soundClueMatched(
            Minecraft minecraft, ActionDsl.SoundClueCondition condition, WorldSessionTracker.Snapshot session) {
        long currentTick = session.clientTick();
        var player = minecraft.player;
        FishingHook hook = player == null ? null : player.fishing;
        if (player == null || !PlayerInventoryEvidence.ownedFishingHook(player, hook, null)
                || !ActionEvidence.pointInside(condition.bounds(), session.dimension(),
                        hook.getX(), hook.getY(), hook.getZ())) {
            return false;
        }
        List<ObservationRecord.SoundClue> nearby = agentObservations.soundClues().snapshot(currentTick).clues()
                .stream()
                .filter(clue -> {
                    double dx = clue.position().x() - hook.getX();
                    double dy = clue.position().y() - hook.getY();
                    double dz = clue.position().z() - hook.getZ();
                    return dx * dx + dy * dy + dz * dz <= 4.0;
                })
                .toList();
        return ActionEvidence.soundClueMatches(condition, currentTick, nearby);
    }
}
