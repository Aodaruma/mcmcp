package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.McmcpMod;
import dev.aod.mcmcp.agent.action.AgentActionStore;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner;
import dev.aod.mcmcp.agent.action.FrameItemAttempt;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.dsl.ActionDslCompiler;
import dev.aod.mcmcp.routine.FrameItemPort;
import dev.aod.mcmcp.routine.MinecraftFrameItemPort;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.client.Minecraft;

/** 額縁一操作の認可aim・ACK待機・使用量と未回収effectを所有する。 */
final class FrameItemExecution {
    private final UUID actionId;
    private final AgentActionStore agentActions;
    private final MinecraftFrameItemPort frameItemPort;
    private final float maxCameraDegreesPerTick;
    private AgentPrimitivePlanner.FrameItemAim frameItemAim;
    private FrameItemAttempt frameItemAttempt;
    private ActionDsl.Node primitive;

    FrameItemExecution(UUID actionId, AgentActionStore agentActions, MinecraftFrameItemPort frameItemPort,
            float maxCameraDegreesPerTick, AgentPrimitivePlanner.FrameItemAim aim) {
        this.actionId = actionId;
        this.agentActions = agentActions;
        this.frameItemPort = frameItemPort;
        this.maxCameraDegreesPerTick = maxCameraDegreesPerTick;
        this.frameItemAim = aim;
    }

    boolean reauthorize(AgentPrimitivePlanner.FrameItemAim currentAim, long clientTick) {
        if (!ActionEvidence.frameItemEvidenceFresh(currentAim, clientTick)
                || !ActionEvidence.sameFrameItemAuthorization(frameItemAim, currentAim)) return false;
        frameItemAim = currentAim;
        return true;
    }

    boolean releaseProgressing() {
        return frameItemAttempt != null
                && frameItemAttempt.releaseStatus() == FrameItemAttempt.ReleaseStatus.PROGRESSING;
    }

    boolean close() {
        boolean closed = true;
        if (frameItemAttempt != null) {
            FrameItemAttempt frameItem = frameItemAttempt;
            try {
                frameItem.close();
                frameItemAttempt = null;
            } catch (RuntimeException | LinkageError failure) {
                closed = false;
                if (frameItem.releaseStatus() != FrameItemAttempt.ReleaseStatus.PROGRESSING) {
                    McmcpMod.LOGGER.error("MCMCP frame-item release failed", failure);
                }
            } finally {
                try {
                    recordFrameItemUsage(actionId, frameItem);
                } catch (RuntimeException | LinkageError failure) {
                    closed = false;
                    McmcpMod.LOGGER.error("MCMCP frame-item effect capture failed", failure);
                }
            }
        }
        return closed;
    }

    PrimitiveOutcome tick(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            ActionDsl.Node primitive) {
        this.primitive = primitive;
        if (frameItemAttempt == null) {
            var aim = Objects.requireNonNull(frameItemAim, "admitted frame item aim");
            boolean remove = primitive instanceof ActionDsl.RemoveVisibleFrameItem;
            var request = new FrameItemPort.Request(
                    remove ? FrameItemPort.Mode.REMOVE : FrameItemPort.Mode.INSERT,
                    aim.entityRef(),
                    (remove ? aim.expectedItem() : aim.insertedItem()).orElseThrow(),
                    aim.rotation(), session.worldSessionId(), session.dimension(),
                    new FrameItemPort.AimPoint(aim.aimPoint().x, aim.aimPoint().y, aim.aimPoint().z),
                    maxCameraDegreesPerTick);
            frameItemAttempt = new FrameItemAttempt(
                    frameItemPort, request, session.clientTick(),
                    Math.addExact(session.clientTick(), ActionDslCompiler.FRAME_ITEM_TICKS));
        }
        FrameItemAttempt attempt = frameItemAttempt;
        FrameItemAttempt.TickResult result;
        try {
            result = attempt.tick(session.clientTick());
        } finally {
            recordFrameItemUsage(actionId, attempt);
        }
        return switch (result.status()) {
            case RUNNING -> PrimitiveOutcome.running();
            case FAILED -> PrimitiveOutcome.failed(
                    AgentActionStore.FailureCode.SERVER_DENIED_OR_DESYNC,
                    false, result.evidence());
            case SUCCEEDED -> {
                frameItemAttempt = null;
                agentActions.recordNodeEvidence(actionId, "frame_display_server_confirmed");
                yield PrimitiveOutcome.succeeded();
            }
        };
    }

    private void recordFrameItemUsage(UUID actionId, FrameItemAttempt attempt) {
        int interactions = attempt.drainInteractionDelta();
        for (int count = 0; count < interactions; count++) {
            agentActions.recordInteraction(actionId);
        }
        var aim = Objects.requireNonNull(frameItemAim, "admitted frame item aim");
        String kind = primitive instanceof ActionDsl.RemoveVisibleFrameItem
                ? "frame_item_remove" : "frame_item_insert";
        for (var effect : attempt.drainEffectDeltas()) {
            agentActions.recordEffect(actionId, kind, aim.entityType(),
                    effect.observedBefore(), effect.observedAfter(), effect.verification(),
                    effect.clientTick(), effect.worldRevision());
        }
    }
}
