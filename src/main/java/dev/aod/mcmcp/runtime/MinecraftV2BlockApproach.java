package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.V2BlockAimResolver;
import dev.aod.mcmcp.agent.action.V2BlockApproach;
import dev.aod.mcmcp.agent.navigation.CoordinateGoalPlanner;
import dev.aod.mcmcp.agent.navigation.KnownTraversabilitySnapshot;
import dev.aod.mcmcp.agent.navigation.NavCell;
import dev.aod.mcmcp.agent.safety.LocalObservationVolume;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;

import java.util.List;
import java.util.function.BooleanSupplier;

/** Selects nearby interaction endpoints before spending movement on a block operation. */
final class MinecraftV2BlockApproach {
    private MinecraftV2BlockApproach() { }

    static CoordinateGoalPlanner.Result plan(CoordinateGoalPlanner planner, Minecraft minecraft,
            AgentObservations observations, ClientReconciliationSignals reconciliation,
            WorldSessionTracker.Snapshot session, KnownTraversabilitySnapshot map, NavCell target,
            long tick, BooleanSupplier outputAllowed) {
        var player = minecraft.player;
        var level = minecraft.level;
        var current = ActionPlanning.playerCell(player, session.dimension());
        var ledger = reconciliation.bindAndSnapshot(level, session.worldSessionId());
        var points = observations.latestInternalFrame().map(frame -> V2BlockAimResolver.approachPoints(
                frame, target, tick, ledger.worldRevision(),
                ledger.surfaceBarrierWorldRevision(target.x(), target.y(), target.z()))).orElse(List.of());
        var targetPos = new BlockPos(target.x(), target.y(), target.z());
        double eyeHeight = Math.max(1.62D, player.getEyePosition().y - player.getY());
        return planner.plan(map, current, session.worldSessionId(), map.worldRevision(),
                CoordinateGoalPlanner.Budget.DEFAULT, () -> !outputAllowed.getAsBoolean(),
                cell -> V2BlockApproach.operable(cell, eyeHeight, player.blockInteractionRange(), points,
                        (eye, point) -> {
                            if (!outputAllowed.getAsBoolean() || !level.isLoaded(targetPos)
                                    || !level.isLoaded(BlockPos.containing(eye))) return false;
                            // Extend just into the observed face so an endpoint exactly on it is not a MISS.
                            var end = point.add(point.subtract(eye).normalize().scale(0.001D));
                            var hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE,
                                    ClipContext.Fluid.NONE, player));
                            return hit.getType() == HitResult.Type.BLOCK && !hit.isWorldBorderHit()
                                    && hit.getBlockPos().equals(targetPos);
                        }),
                current.distanceTo(target) > LocalObservationVolume.RADIUS_BLOCKS);
    }
}
