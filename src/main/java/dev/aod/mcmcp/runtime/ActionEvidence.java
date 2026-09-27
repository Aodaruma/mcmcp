package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.navigation.KnownTraversabilitySnapshot;
import dev.aod.mcmcp.agent.observation.ObservationRecord;
import dev.aod.mcmcp.agent.observation.SoundClueStore;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.ToLongFunction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/** 配送された表面・額縁・作物・音の有効性とrevision境界。 */
final class ActionEvidence {
    private ActionEvidence() {}

    static final double CROP_WAIT_OBSERVER_EPSILON_BLOCKS =
            AgentPrimitivePlanner.WAIT_WITNESS_EYE_EPSILON_BLOCKS;

    static boolean routeDependenciesCurrent(
            KnownTraversabilitySnapshot current,
            Map<dev.aod.mcmcp.agent.navigation.TraversabilityEdge.Key,
                    dev.aod.mcmcp.agent.navigation.TraversabilityEdge> required) {
        for (var dependency : required.entrySet()) {
            var currentEdge = current.edge(dependency.getKey()).orElse(null);
            if (currentEdge == null || !sameOrSaferEdge(dependency.getValue(), currentEdge)) {
                return false;
            }
        }
        return true;
    }

    static boolean sameOrSaferEdge(
            dev.aod.mcmcp.agent.navigation.TraversabilityEdge captured,
            dev.aod.mcmcp.agent.navigation.TraversabilityEdge current) {
        return captured.worldSessionId().equals(current.worldSessionId())
                && (captured.status() == current.status()
                        || captured.status()
                                == dev.aod.mcmcp.agent.navigation.TraversabilityEdge.Status.PROBE_ALLOWED
                        && current.status()
                                == dev.aod.mcmcp.agent.navigation.TraversabilityEdge.Status.CONFIRMED);
    }

    static long primitiveReobservationTicks(ActionDsl.Node primitive) {
        return ActionPlanning.requiresWorldPlanning(primitive)
                ? AgentPrimitivePlanner.BREAK_REOBSERVATION_TICKS : 0L;
    }

    static CropWaitAuthorization requireCropWaitAuthorization(
            WorldSessionTracker.Snapshot session,
            ActionDsl.WaitUntil wait,
            AgentPrimitivePlanner.Analysis analysis,
            long visualBarrierWorldRevision,
            Vec3 playerPosition,
            Vec3 observerEye) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(wait, "wait");
        Objects.requireNonNull(analysis, "analysis");
        Objects.requireNonNull(playerPosition, "playerPosition");
        Objects.requireNonNull(observerEye, "observerEye");
        ActionDsl.Position target = ((ActionDsl.CropMatureCondition) wait.condition()).target();
        AgentPrimitivePlanner.KnownSurface visibleWheat = analysis.knownSurfaces().stream()
                .filter(surface -> surface.position().equals(target)
                        && surface.block().equals("minecraft:wheat")
                        && surface.eyeOrigin() != null)
                .findFirst()
                .orElse(null);
        double epsilonSquared = CROP_WAIT_OBSERVER_EPSILON_BLOCKS
                * CROP_WAIT_OBSERVER_EPSILON_BLOCKS;
        if (!session.worldReady()
                || !Objects.equals(session.dimension(), target.dimension())
                || visibleWheat == null
                || observerEye.distanceToSqr(visibleWheat.eyeOrigin())
                        > epsilonSquared) {
            throw new IllegalStateException(
                    "crop wait authorization requires current-origin visible wheat evidence");
        }
        return new CropWaitAuthorization(
                session.worldSessionId(), session.dimension(), target,
                visualBarrierWorldRevision, playerPosition, visibleWheat.eyeOrigin());
    }

    static CropWaitVisibilityState cropWaitVisibilityState(
            CropWaitAuthorization authorization,
            WorldSessionTracker.Snapshot session,
            ActionDsl.Position requestedTarget,
            long currentVisualBarrierWorldRevision,
            Vec3 currentPlayerPosition,
            Vec3 currentObserverEye) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(requestedTarget, "requestedTarget");
        Objects.requireNonNull(currentPlayerPosition, "currentPlayerPosition");
        Objects.requireNonNull(currentObserverEye, "currentObserverEye");
        if (authorization == null || !authorization.matches(session, requestedTarget)) {
            return CropWaitVisibilityState.WORLD_CHANGED;
        }
        double epsilonSquared = CROP_WAIT_OBSERVER_EPSILON_BLOCKS
                * CROP_WAIT_OBSERVER_EPSILON_BLOCKS;
        if (currentVisualBarrierWorldRevision != authorization.visualBarrierWorldRevision()
                || currentPlayerPosition.distanceToSqr(authorization.playerPosition())
                        > epsilonSquared
                || currentObserverEye.distanceToSqr(authorization.observerEye())
                        > epsilonSquared) {
            return CropWaitVisibilityState.VISIBILITY_INVALIDATED;
        }
        return CropWaitVisibilityState.CURRENT;
    }

    static CropWaitLiveState cropWaitLiveState(boolean loaded, BlockState state) {
        if (!loaded) return CropWaitLiveState.UNLOADED;
        Objects.requireNonNull(state, "state");
        if (!state.is(Blocks.WHEAT)) return CropWaitLiveState.TARGET_CHANGED;
        return state.getValue(BlockStateProperties.AGE_7) == 7
                ? CropWaitLiveState.MATURE : CropWaitLiveState.PENDING;
    }

    static boolean soundClueMatches(
            ActionDsl.SoundClueCondition condition,
            long currentTick,
            List<ObservationRecord.SoundClue> clues) {
        if (condition.sinceTick() > currentTick) return false;
        return clues.stream().anyMatch(clue ->
                clue.soundEvent().value().equals(condition.soundEvent())
                        && clue.lastObservedTick() >= condition.sinceTick()
                        && currentTick >= clue.lastObservedTick()
                        && currentTick - clue.lastObservedTick() <= SoundClueStore.TTL_TICKS
                        && pointInside(condition.bounds(), clue.position().dimension().value(),
                                clue.position().x(), clue.position().y(), clue.position().z()));
    }

    static boolean pointInside(
            ActionDsl.WorldBounds bounds, String dimension, double x, double y, double z) {
        Objects.requireNonNull(bounds, "bounds");
        return bounds.dimension().equals(dimension)
                && x >= bounds.min().x() && x <= bounds.max().x()
                && y >= bounds.min().y() && y <= bounds.max().y()
                && z >= bounds.min().z() && z <= bounds.max().z();
    }

    static boolean isAgentWait(ActionDsl.Node primitive) {
        return primitive instanceof ActionDsl.WaitTicks || primitive instanceof ActionDsl.WaitUntil;
    }

    static boolean isFrameItemPrimitive(ActionDsl.Node primitive) {
        return primitive instanceof ActionDsl.RemoveVisibleFrameItem
                || primitive instanceof ActionDsl.InsertVisibleFrameItem;
    }

    static boolean frameItemEvidenceFresh(AgentPrimitivePlanner.FrameItemAim aim, long currentTick) {
        return aim != null && aim.observedTick() <= currentTick && currentTick - aim.observedTick() <= 100;
    }

    /** Fresh delivery can advance its clocks but cannot replace the admitted frame or aim. */
    static boolean sameFrameItemAuthorization(
            AgentPrimitivePlanner.FrameItemAim expected,
            AgentPrimitivePlanner.FrameItemAim current) {
        return expected != null && current != null
                && expected.entityRef().equals(current.entityRef())
                && expected.entityType().equals(current.entityType())
                && expected.expectedItem().equals(current.expectedItem())
                && expected.insertedItem().equals(current.insertedItem())
                && expected.rotation() == current.rotation()
                && expected.aimPoint().equals(current.aimPoint())
                && current.observedTick() >= expected.observedTick()
                && current.worldRevision() >= expected.worldRevision();
    }

    static long visualBarrierWorldRevision(
            KnownTraversabilitySnapshot map,
            ClientReconciliationSignals.Snapshot reconciliation) {
        Objects.requireNonNull(map, "map");
        Objects.requireNonNull(reconciliation, "reconciliation");
        return AgentPrimitivePlanner.requireVisualBarrierWorldRevision(
                map,
                reconciliation.worldSessionId(),
                reconciliation.worldRevision(),
                reconciliation.visualBarrierWorldRevision());
    }

    static ToLongFunction<ActionDsl.Position> surfaceRevisionBarrier(
            KnownTraversabilitySnapshot map,
            ClientReconciliationSignals.Snapshot reconciliation) {
        visualBarrierWorldRevision(map, reconciliation);
        return position -> AgentPrimitivePlanner.requireSurfaceBarrierWorldRevision(
                map,
                reconciliation.surfaceBarrierWorldRevision(
                        position.x(), position.y(), position.z()));
    }

    static ToLongFunction<ActionDsl.Position> waitTargetSurfaceRevisionBarrier(
            KnownTraversabilitySnapshot map,
            ClientReconciliationSignals.Snapshot reconciliation) {
        visualBarrierWorldRevision(map, reconciliation);
        return position -> AgentPrimitivePlanner.requireSurfaceBarrierWorldRevision(
                map,
                reconciliation.waitTargetSurfaceBarrierWorldRevision(
                        position.x(), position.y(), position.z()));
    }

    static ToLongFunction<ActionDsl.Position> primitiveSurfaceRevisionBarrier(
            ActionDsl.Node primitive,
            KnownTraversabilitySnapshot map,
            ClientReconciliationSignals.Snapshot reconciliation) {
        Objects.requireNonNull(primitive, "primitive");
        return primitive instanceof ActionDsl.WaitUntil
                && ((ActionDsl.WaitUntil) primitive).condition()
                        instanceof ActionDsl.CropMatureCondition
                ? waitTargetSurfaceRevisionBarrier(map, reconciliation)
                : surfaceRevisionBarrier(map, reconciliation);
    }

    enum CropWaitLiveState {
        PENDING,
        MATURE,
        TARGET_CHANGED,
        UNLOADED,
        VISIBILITY_INVALIDATED,
        WORLD_CHANGED
    }

    enum CropWaitVisibilityState {
        CURRENT,
        VISIBILITY_INVALIDATED,
        WORLD_CHANGED
    }

    record CropWaitAuthorization(
            UUID worldSessionId,
            String dimension,
            ActionDsl.Position target,
            long visualBarrierWorldRevision,
            Vec3 playerPosition,
            Vec3 observerEye) {
        CropWaitAuthorization {
            Objects.requireNonNull(worldSessionId, "worldSessionId");
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(playerPosition, "playerPosition");
            Objects.requireNonNull(observerEye, "observerEye");
            if (!dimension.equals(target.dimension())) {
                throw new IllegalArgumentException(
                        "crop wait authorization must stay in one dimension");
            }
            if (visualBarrierWorldRevision < 0L
                    || !finite(playerPosition) || !finite(observerEye)) {
                throw new IllegalArgumentException(
                        "crop wait authorization fence must be finite and non-negative");
            }
        }

        private static boolean finite(Vec3 value) {
            return Double.isFinite(value.x)
                    && Double.isFinite(value.y)
                    && Double.isFinite(value.z);
        }

        boolean matches(
                WorldSessionTracker.Snapshot session,
                ActionDsl.Position requestedTarget) {
            return session.worldReady()
                    && worldSessionId.equals(session.worldSessionId())
                    && dimension.equals(session.dimension())
                    && target.equals(requestedTarget);
        }
    }
}
