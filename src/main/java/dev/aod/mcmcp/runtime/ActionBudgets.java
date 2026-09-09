package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentActionStore;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner;
import dev.aod.mcmcp.agent.action.MinecraftActionPrimitiveExecutor;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.dsl.ActionDslCompiler;
import dev.aod.mcmcp.agent.dsl.ActionDslValidator;
import dev.aod.mcmcp.agent.observation.OmnidirectionalObserver;
import dev.aod.mcmcp.runtime.RuntimeFailures.RuntimeInvocationException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Actionとprimitiveの残予算・再計画・mutation再試行の有界計算。 */
final class ActionBudgets {
    private ActionBudgets() {}

    static long activeElapsedNanos(long startedAtNanos, long pausedNanos, long nowNanos) {
        if (pausedNanos < 0L) {
            throw new IllegalArgumentException("pausedNanos must be non-negative");
        }
        long elapsed = nonNegativeNanoElapsed(startedAtNanos, nowNanos);
        return pausedNanos >= elapsed ? 0L : elapsed - pausedNanos;
    }

    static final int MAX_MUTATION_AIM_FAILURES = 3;
    static final String REPLANNED_ROUTE_SHAPE_EVIDENCE =
            "replanned_route_shape_exceeds_occurrence";
    static final String REPLANNED_ROUTE_GLOBAL_EVIDENCE =
            "replanned_route_global_budget";
    static final String REPLANNED_ROUTE_REMAINING_EVIDENCE =
            "replanned_route_remaining_occurrence";

    static int pickupOccurrenceBaseline(int existing, int current) {
        if (existing < -1 || current < 0) {
            throw new IllegalArgumentException("pickup inventory counts are invalid");
        }
        return existing < 0 ? current : existing;
    }

    static int visibleItemEvidenceMaxAgeTicks(int raysPerTick) {
        if (raysPerTick < OmnidirectionalObserver.MIN_RAYS_PER_TICK
                || raysPerTick > OmnidirectionalObserver.MAX_RAYS_PER_TICK) {
            throw new IllegalArgumentException("raysPerTick is outside the observer policy");
        }
        return Math.ceilDiv(OmnidirectionalObserver.DIRECTION_COUNT, raysPerTick);
    }

    static boolean isMutationBatch(ActionDsl.Node node) {
        return node instanceof ActionDsl.TillKnownBatch
                || node instanceof ActionDsl.PlantKnownWheatBatch
                || node instanceof ActionDsl.HarvestKnownWheatBatch;
    }

    static boolean mutationAimRetriesAllowed(ActionDsl.Node node) {
        return !isMutationBatch(Objects.requireNonNull(node, "node"));
    }

    static BatchTargetDisposition mutationBatchDisposition(
            int completedBeforeTarget, int targetCount, boolean serverConfirmed) {
        if (targetCount < 1
                || targetCount > ActionDslValidator.MAX_MUTATION_BATCH_TARGETS
                || completedBeforeTarget < 0
                || completedBeforeTarget >= targetCount) {
            throw new IllegalArgumentException("invalid mutation batch progress");
        }
        if (!serverConfirmed) return BatchTargetDisposition.STOP;
        return completedBeforeTarget + 1 == targetCount
                ? BatchTargetDisposition.COMPLETE
                : BatchTargetDisposition.CONTINUE;
    }

    enum BatchTargetDisposition { STOP, CONTINUE, COMPLETE }

    static ActionDslCompiler.Cost mutationBatchRequiredRemainder(
            AgentPrimitivePlanner.MutationBatchPlan plan,
            int currentIndex,
            AgentPrimitivePlanner.Pose currentPose,
            AgentPrimitivePlanner.MutationAim freshCurrentAim,
            ActionDslCompiler.Cost freshCurrent,
            float cameraLimit) {
        return AgentPrimitivePlanner.recostMutationBatchRemainder(
                plan,
                currentIndex,
                currentPose,
                freshCurrentAim,
                freshCurrent,
                cameraLimit);
    }

    static String batchTargetTrace(ActionDsl.Node mutation) {
        ActionDsl.Position target = switch (mutation) {
            case ActionDsl.TillKnownBlock till -> till.target();
            case ActionDsl.PlantKnownWheat plant -> plant.target();
            case ActionDsl.HarvestKnownWheat harvest -> harvest.target();
            default -> throw new IllegalArgumentException("node is not a batch target");
        };
        return "batch_target=" + target.x() + "," + target.y() + "," + target.z();
    }

    static boolean retryableMutationAimFailure(String evidence) {
        return "aim_raycast_unavailable".equals(evidence);
    }

    static boolean mutationAimRetryAllowed(int failureCount) {
        if (failureCount < 1) {
            throw new IllegalArgumentException("failureCount must be positive");
        }
        return failureCount < MAX_MUTATION_AIM_FAILURES;
    }

    static RuntimeInvocationException planningFailure(
            AgentPrimitivePlanner.PlanningException failure) {
        String code = switch (failure.code()) {
            case TIMEOUT -> "timeout";
            case PROGRAM_BUDGET_UNPROVABLE -> "program_budget_unprovable";
            case TARGET_UNKNOWN, NO_KNOWN_PATH ->
                    failure.code().name().toLowerCase(Locale.ROOT);
        };
        return new RuntimeInvocationException(
                code,
                failure.getMessage(),
                true,
                Map.of());
    }

    static boolean fitsRemainingBudget(
            AgentActionStore.Progress used,
            ActionDsl.Budget budget,
            ActionDslCompiler.Cost next,
            long activeElapsedNanos) {
        Objects.requireNonNull(used, "used");
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(next, "next");
        if (activeElapsedNanos < 0L || used.motionOverflowed()) return false;
        return fits(
                        activeElapsedNanos,
                        Duration.ofMillis(next.durationMillis()).toNanos(),
                        Duration.ofMillis(budget.maxDurationMillis()).toNanos())
                && fits(used.ticks(), next.ticks(), budget.maxTicks())
                && fits(used.distanceTravelled(), next.distanceBlocks(), budget.maxDistanceBlocks())
                && fits(used.cameraDegrees(), next.cameraDegrees(), budget.maxCameraDegrees())
                && fits(used.interactions(), next.interactions(), budget.maxInteractions())
                && fits(used.blocksBroken(), next.blocksBroken(), budget.maxBlocksBroken())
                && fits(used.blocksPlaced(), next.blocksPlaced(), budget.maxBlocksPlaced());
    }

    /**
     * Returns a fixed, non-reflective diagnostic when a freshly planned movement route cannot
     * use the reserve admitted for its original logical occurrence.
     */
    static String replannedRouteBudgetFailure(
            AgentActionStore.Progress used,
            AgentActionStore.Progress occurrenceBaseline,
            ActionDslCompiler.Cost occurrenceLimit,
            ActionDsl.Budget globalBudget,
            ActionDslCompiler.Cost retry,
            long activeElapsedNanos) {
        Objects.requireNonNull(used, "used");
        Objects.requireNonNull(occurrenceBaseline, "occurrenceBaseline");
        Objects.requireNonNull(occurrenceLimit, "occurrenceLimit");
        Objects.requireNonNull(globalBudget, "globalBudget");
        Objects.requireNonNull(retry, "retry");
        if (!costFitsLimit(retry, occurrenceLimit)) {
            return REPLANNED_ROUTE_SHAPE_EVIDENCE;
        }
        if (!fitsRemainingBudget(used, globalBudget, retry, activeElapsedNanos)) {
            return REPLANNED_ROUTE_GLOBAL_EVIDENCE;
        }
        if (!fitsOccurrenceBudget(used, occurrenceBaseline, occurrenceLimit, retry)) {
            return REPLANNED_ROUTE_REMAINING_EVIDENCE;
        }
        return null;
    }

    static boolean costFitsLimit(
            ActionDslCompiler.Cost cost, ActionDslCompiler.Cost limit) {
        Objects.requireNonNull(cost, "cost");
        Objects.requireNonNull(limit, "limit");
        return fits(0L, cost.durationMillis(), limit.durationMillis())
                && fits(0L, cost.ticks(), limit.ticks())
                && fits(0.0D, cost.distanceBlocks(), limit.distanceBlocks())
                && fits(0.0D, cost.cameraDegrees(), limit.cameraDegrees())
                && fits(0L, cost.interactions(), limit.interactions())
                && fits(0L, cost.blocksBroken(), limit.blocksBroken())
                && fits(0L, cost.blocksPlaced(), limit.blocksPlaced());
    }

    static ActionDslCompiler.Cost firstPrimitiveRemainingCost(
            AgentActionStore.Progress used,
            ActionDslCompiler.Cost planned,
            long activeElapsedNanos) {
        Objects.requireNonNull(used, "used");
        Objects.requireNonNull(planned, "planned");
        if (used.ticks() != 0L || activeElapsedNanos <= 0L) return planned;
        long elapsedMillis = Math.ceilDiv(activeElapsedNanos, 1_000_000L);
        return new ActionDslCompiler.Cost(
                Math.max(0L, planned.durationMillis() - elapsedMillis),
                planned.ticks(),
                planned.distanceBlocks(),
                planned.cameraDegrees(),
                planned.interactions(),
                planned.blocksBroken(),
                planned.blocksPlaced());
    }

    /**
     * Charges renderer waiting against the explicit container headroom without pretending that
     * the bounded menu attempt itself became shorter. Other recovered surface primitives retain
     * their complete JIT cost because they do not declare this separate operation reserve.
     */
    static ActionDslCompiler.Cost firstRecoveredSurfacePrimitiveRemainingCost(
            AgentActionStore.Progress used,
            boolean firstPrimitiveOccurrence,
            ActionDsl.Node primitive,
            ActionDslCompiler.Cost planned,
            long activeElapsedNanos) {
        Objects.requireNonNull(used, "used");
        Objects.requireNonNull(primitive, "primitive");
        Objects.requireNonNull(planned, "planned");
        if (!firstPrimitiveOccurrence) {
            return planned;
        }
        long operationTicks = switch (primitive) {
            case ActionDsl.InspectKnownContainer ignored ->
                    AgentPrimitivePlanner.CONTAINER_OPERATION_TICK_UPPER_BOUND;
            case ActionDsl.TakeKnownContainerStack take ->
                    ActionDslCompiler.knownContainerTransferOperationTicks(take.maxStacks());
            case ActionDsl.StoreKnownContainerStack store ->
                    ActionDslCompiler.knownContainerTransferOperationTicks(store.maxStacks());
            default -> -1L;
        };
        if (operationTicks < 0L) {
            return firstPrimitiveRemainingCost(used, planned, activeElapsedNanos);
        }
        if (activeElapsedNanos < 0L) return planned;
        long elapsedMillis = Math.ceilDiv(activeElapsedNanos, 1_000_000L);
        long operationDurationMillis = Math.multiplyExact(operationTicks, 50L);
        return new ActionDslCompiler.Cost(
                Math.max(operationDurationMillis,
                        Math.max(0L, planned.durationMillis() - elapsedMillis)),
                Math.max(operationTicks, Math.max(0L, planned.ticks() - used.ticks())),
                planned.distanceBlocks(),
                planned.cameraDegrees(),
                planned.interactions(),
                planned.blocksBroken(),
                planned.blocksPlaced());
    }

    static boolean motionBudgetExhausted(
            AgentActionStore.Progress used,
            ActionDsl.Budget budget,
            ActionDsl.Node primitive) {
        Objects.requireNonNull(used, "used");
        Objects.requireNonNull(budget, "budget");
        return used.motionOverflowed()
                || primitive instanceof ActionDsl.NavigateToKnown
                        && used.distanceTravelled() >= budget.maxDistanceBlocks()
                || primitive instanceof ActionDsl.ApproachKnownSurface
                        && used.distanceTravelled() >= budget.maxDistanceBlocks()
                || primitive instanceof ActionDsl.ApproachKnownPlacement
                        && used.distanceTravelled() >= budget.maxDistanceBlocks()
                || primitive instanceof ActionDsl.CollectVisibleItem
                        && used.distanceTravelled() >= budget.maxDistanceBlocks()
                || primitive instanceof ActionDsl.CollectVisibleItemBatch
                        && used.distanceTravelled() >= budget.maxDistanceBlocks()
                || primitive instanceof ActionDsl.FaceKnownPosition
                        && used.cameraDegrees() >= budget.maxCameraDegrees()
                || primitive instanceof ActionDsl.FaceKnownBlockFace
                        && used.cameraDegrees() >= budget.maxCameraDegrees()
                || KnownBreakSafety.isKnownBreak(primitive)
                        && used.cameraDegrees() >= budget.maxCameraDegrees();
    }

    static boolean fitsOccurrenceBudget(
            AgentActionStore.Progress used,
            AgentActionStore.Progress baseline,
            ActionDslCompiler.Cost limit,
            ActionDslCompiler.Cost next) {
        Objects.requireNonNull(used, "used");
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(limit, "limit");
        Objects.requireNonNull(next, "next");
        return !used.motionOverflowed()
                && fits(consumedDurationMillis(used, baseline), next.durationMillis(),
                        limit.durationMillis())
                && fits(consumedTicks(used, baseline), next.ticks(), limit.ticks())
                && fits(consumedDistance(used, baseline), next.distanceBlocks(),
                        limit.distanceBlocks())
                && fits(consumedCamera(used, baseline), next.cameraDegrees(),
                        limit.cameraDegrees())
                && fits(consumedInteractions(used, baseline), next.interactions(),
                        limit.interactions())
                && fits(consumedBreaks(used, baseline), next.blocksBroken(),
                        limit.blocksBroken())
                && fits(consumedPlacements(used, baseline), next.blocksPlaced(),
                        limit.blocksPlaced());
    }

    static boolean fitsMutationBatchRemainder(
            AgentActionStore.Progress used,
            AgentActionStore.Progress occurrenceBaseline,
            ActionDslCompiler.Cost occurrenceLimit,
            ActionDsl.Budget globalBudget,
            ActionDslCompiler.Cost requiredRemainder,
            long activeElapsedNanos) {
        return fitsRemainingBudget(
                        used, globalBudget, requiredRemainder, activeElapsedNanos)
                && fitsOccurrenceBudget(
                        used, occurrenceBaseline, occurrenceLimit, requiredRemainder);
    }

    static ActionDslCompiler.Cost breakExecutionCost(
            ActionDslCompiler.Cost planned, boolean reobservationComplete) {
        Objects.requireNonNull(planned, "planned");
        if (!reobservationComplete) return planned;
        long ticks = Math.subtractExact(
                planned.ticks(), AgentPrimitivePlanner.BREAK_REOBSERVATION_TICKS);
        long duration = Math.subtractExact(
                planned.durationMillis(),
                Math.multiplyExact(AgentPrimitivePlanner.BREAK_REOBSERVATION_TICKS, 50L));
        return new ActionDslCompiler.Cost(
                duration,
                ticks,
                planned.distanceBlocks(),
                planned.cameraDegrees(),
                planned.interactions(),
                planned.blocksBroken(),
                planned.blocksPlaced());
    }

    static long breakAimTicks(ActionDslCompiler.Cost planned) {
        Objects.requireNonNull(planned, "planned");
        return Math.max(
                1L,
                planned.ticks()
                        - AgentPrimitivePlanner.BREAK_REOBSERVATION_TICKS
                        - AgentPrimitivePlanner.BREAK_TICK_UPPER_BOUND);
    }

    static ActionDslCompiler.Cost occurrenceCostIncludingConsumed(
            AgentActionStore.Progress used,
            AgentActionStore.Progress baseline,
            ActionDslCompiler.Cost next) {
        return new ActionDslCompiler.Cost(
                Math.addExact(consumedDurationMillis(used, baseline), next.durationMillis()),
                Math.addExact(consumedTicks(used, baseline), next.ticks()),
                consumedDistance(used, baseline) + next.distanceBlocks(),
                consumedCamera(used, baseline) + next.cameraDegrees(),
                Math.addExact(consumedInteractions(used, baseline), next.interactions()),
                Math.addExact(consumedBreaks(used, baseline), next.blocksBroken()),
                Math.addExact(consumedPlacements(used, baseline), next.blocksPlaced()));
    }

    static long consumedDurationMillis(
            AgentActionStore.Progress used, AgentActionStore.Progress baseline) {
        return Math.multiplyExact(consumedTicks(used, baseline), 50L);
    }

    static long consumedTicks(
            AgentActionStore.Progress used, AgentActionStore.Progress baseline) {
        return nonNegativeDifference(used.ticks(), baseline.ticks());
    }

    static double consumedDistance(
            AgentActionStore.Progress used, AgentActionStore.Progress baseline) {
        return nonNegativeDifference(used.distanceTravelled(), baseline.distanceTravelled());
    }

    static double consumedCamera(
            AgentActionStore.Progress used, AgentActionStore.Progress baseline) {
        return nonNegativeDifference(used.cameraDegrees(), baseline.cameraDegrees());
    }

    static long consumedInteractions(
            AgentActionStore.Progress used, AgentActionStore.Progress baseline) {
        return nonNegativeDifference(used.interactions(), baseline.interactions());
    }

    static long consumedBreaks(
            AgentActionStore.Progress used, AgentActionStore.Progress baseline) {
        return nonNegativeDifference(used.blocksBroken(), baseline.blocksBroken());
    }

    static long consumedPlacements(
            AgentActionStore.Progress used, AgentActionStore.Progress baseline) {
        return nonNegativeDifference(used.blocksPlaced(), baseline.blocksPlaced());
    }

    static long nonNegativeDifference(long current, long baseline) {
        if (current < baseline) throw new IllegalStateException("Action progress moved backwards");
        return current - baseline;
    }

    static double nonNegativeDifference(double current, double baseline) {
        double difference = current - baseline;
        if (!Double.isFinite(difference) || difference < -1.0e-9D) {
            throw new IllegalStateException("Action progress moved backwards");
        }
        return Math.max(0.0D, difference);
    }

    static boolean motionBudgetExceededAfterPrimitive(
            AgentActionStore.Progress used,
            ActionDsl.Budget budget,
            ActionDsl.Node primitive,
            MinecraftActionPrimitiveExecutor.Status status) {
        Objects.requireNonNull(status, "status");
        if (used.motionOverflowed()
                || used.distanceTravelled() > budget.maxDistanceBlocks()
                || used.cameraDegrees() > budget.maxCameraDegrees()) {
            return true;
        }
        return status == MinecraftActionPrimitiveExecutor.Status.REPLAN_REQUIRED
                && motionBudgetExhausted(used, budget, primitive);
    }

    static boolean replanDeadlineReached(long actionTick, long deadlineTick) {
        return deadlineTick > 0L && actionTick >= deadlineTick;
    }

    static boolean shouldVerifyReplanHeartbeat(
            boolean replanning, MinecraftActionPrimitiveExecutor.TickResult result) {
        Objects.requireNonNull(result, "result");
        return replanning && result.status() == MinecraftActionPrimitiveExecutor.Status.RUNNING;
    }

    static boolean repeatedPositionCorrection(
            long previousRevision, long currentRevision, int previousCorrections) {
        return currentRevision - previousRevision > 1L || previousCorrections > 0;
    }

    static double batchTillSettlingCredit(
            net.minecraft.world.phys.Vec3 previous,
            net.minecraft.world.phys.Vec3 current,
            double allowance,
            boolean qualifiedWindow,
            boolean inputNeutral) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(current, "current");
        if (!qualifiedWindow || !inputNeutral
                || !Double.isFinite(allowance) || allowance <= 0.0D) return 0.0D;
        double dx = current.x - previous.x;
        double dz = current.z - previous.z;
        double descent = previous.y - current.y;
        if (Math.hypot(dx, dz) > 1.0e-6D
                || descent <= 0.0D
                || descent > allowance + 1.0e-6D) {
            return 0.0D;
        }
        return Math.min(descent, allowance);
    }

    static double cameraDelta(
            float yaw, float pitch, float previousYaw, float previousPitch) {
        return Math.abs(Mth.wrapDegrees((double) yaw - previousYaw))
                + Math.abs((double) pitch - previousPitch);
    }

    static boolean fits(long used, long next, long maximum) {
        return used >= 0L && next >= 0L && used <= maximum && next <= maximum - used;
    }

    static boolean fits(double used, double next, double maximum) {
        return Double.isFinite(used) && Double.isFinite(next) && Double.isFinite(maximum)
                && used >= 0.0D && next >= 0.0D && used <= maximum
                && next <= maximum - used + 1.0e-9D;
    }

    static long nonNegativeNanoElapsed(long startedAtNanos, long nowNanos) {
        long elapsed = nowNanos - startedAtNanos;
        return elapsed < 0L ? 0L : elapsed;
    }

    static long saturatingAdd(long left, long right) {
        if (left < 0L || right < 0L) {
            throw new IllegalArgumentException("saturating tick add requires non-negative operands");
        }
        return saturatingNonNegativeAdd(left, right);
    }

    static long saturatingNonNegativeAdd(long left, long right) {
        if (left < 0L || right < 0L) {
            throw new IllegalArgumentException("saturating add requires non-negative operands");
        }
        if (left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
