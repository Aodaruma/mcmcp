package dev.aod.mcmcp.agent.navigation;

import dev.aod.mcmcp.agent.action.AgentJobLimits;

import dev.aod.mcmcp.agent.action.AgentJobStore;
import dev.aod.mcmcp.agent.action.MinecraftActionPrimitiveExecutor;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** One coordinate move job: plan a known segment, execute it, then observe and plan again. */
public final class CoordinateMoveJobExecution {
    private static final int MAX_EVIDENCE_WAIT_TICKS = 80;

    private final AgentJobStore jobs;
    private final UUID actionId;
    private final UUID worldSessionId;
    private final NavCell goal;
    private final double arrivalRadius;
    private final double tolerance;
    private final double maxDistance;
    private CoordinateGoalPlanner planner;
    private final MovementDriver driver;
    private final BooleanSupplier releaseAndVerify;
    private boolean routeWasGoal;
    private boolean awaitingFinalPosition;
    private Map<TraversabilityEdge.Key, TraversabilityEdge> waitingEvidence;
    private int evidenceWaitTicks;
    private long startedNanos = Long.MIN_VALUE;
    private long startedClientTick;
    private AgentJobStore.State terminalIntent;
    private String terminalFailure;
    private BooleanSupplier stopCondition = () -> false;
    private ObstacleDriver obstacles;
    private boolean clearing;
    private int pathReplans;
    private boolean autoReplan = true;
    private boolean localObservationRequired;
    private int localObservationWaitTicks;

    public void stopWhen(BooleanSupplier condition) { stopCondition = Objects.requireNonNull(condition); }
    public void clearPathWith(ObstacleDriver driver) { obstacles = Objects.requireNonNull(driver); }
    public void autoReplan(boolean enabled) { autoReplan = enabled; }
    public void requireLocalObservation(boolean required) { localObservationRequired = required; }

    public CoordinateMoveJobExecution(AgentJobStore jobs, UUID actionId, UUID worldSessionId,
                                      NavCell goal, double tolerance, double maxDistance,
                                      MovementDriver driver, BooleanSupplier releaseAndVerify) {
        this(jobs, actionId, worldSessionId, goal, 0.0D, tolerance, maxDistance,
                driver, releaseAndVerify);
    }

    public CoordinateMoveJobExecution(AgentJobStore jobs, UUID actionId, UUID worldSessionId,
                                      NavCell goal, double arrivalRadius,
                                      double tolerance, double maxDistance,
                                      MovementDriver driver, BooleanSupplier releaseAndVerify) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.actionId = Objects.requireNonNull(actionId, "actionId");
        this.worldSessionId = Objects.requireNonNull(worldSessionId, "worldSessionId");
        this.goal = Objects.requireNonNull(goal, "goal");
        this.driver = Objects.requireNonNull(driver, "driver");
        this.releaseAndVerify = Objects.requireNonNull(releaseAndVerify, "releaseAndVerify");
        if (!Double.isFinite(arrivalRadius) || arrivalRadius < 0.0D
                || arrivalRadius > 16.0D
                || !Double.isFinite(tolerance) || tolerance < 0.1D
                || tolerance > 0.49D || !Double.isFinite(maxDistance)
                || maxDistance < 1.0D || maxDistance > AgentJobLimits.MAX_DISTANCE) {
            throw new IllegalArgumentException("invalid move bounds");
        }
        this.tolerance = tolerance;
        this.arrivalRadius = arrivalRadius;
        this.maxDistance = maxDistance;
        this.planner = new CoordinateGoalPlanner(worldSessionId, goal, arrivalRadius);
        var job = jobs.get(actionId);
        if (job.kind() != AgentJobStore.Kind.MOVE
                || !job.worldSessionId().equals(worldSessionId)) {
            throw new IllegalArgumentException("move job does not match its session");
        }
    }

    public AgentJobStore.Snapshot tick(KnownTraversabilitySnapshot map, NavCell currentCell,
                                       UUID currentWorldSessionId, long currentRevision,
                                       long clientTick, long nowNanos, boolean safeToMove,
                                       double travelled, BooleanSupplier outputAllowed) {
        var job = jobs.get(actionId);
        if (job.state().terminal()) return job;
        if (terminalIntent != null) return publishAfterRelease();
        if (job.cancelRequested()) {
            retainTerminal(AgentJobStore.State.CANCELLED, "client_request");
            return publishAfterRelease();
        }
        if (job.state() == AgentJobStore.State.UNCONFIRMED) {
            jobs.expireUnconfirmed(nowNanos);
            return jobs.get(actionId);
        }
        if (!worldSessionId.equals(currentWorldSessionId)
                || !worldSessionId.equals(map.worldSessionId())
                || !goal.dimension().equals(map.dimension())
                || !goal.dimension().equals(currentCell.dimension())) {
            retainTerminal(AgentJobStore.State.FAILED, "world_session_changed");
            return publishAfterRelease();
        }
        if (!safeToMove || !Double.isFinite(travelled) || travelled > maxDistance) {
            retainTerminal(AgentJobStore.State.FAILED, "safety_interrupted");
            return publishAfterRelease();
        }
        if (job.state() == AgentJobStore.State.QUEUED) {
            jobs.start(actionId, currentWorldSessionId);
            startedNanos = nowNanos;
            startedClientTick = clientTick;
            job = jobs.get(actionId);
        }
        if (nowNanos - startedNanos >= AgentJobLimits.wallNanos(job.maxOperations())) {
            retainTerminal(AgentJobStore.State.FAILED, "duration_limit");
            return publishAfterRelease();
        }
        if (stopCondition.getAsBoolean()) {
            jobs.recordResult(actionId, Map.of("stop_condition_met", true));
            retainTerminal(AgentJobStore.State.SUCCEEDED, null);
            return publishAfterRelease();
        }
        // The route driver can finish after currentCell was sampled for this tick. Confirm
        // arrival from the next tick's position without dispatching another movement input.
        if (!localObservationRequired && awaitingFinalPosition) {
            boolean arrived = reached(currentCell);
            retainTerminal(arrived ? AgentJobStore.State.SUCCEEDED
                    : AgentJobStore.State.FAILED,
                    arrived ? null : "goal_not_reached");
            return publishAfterRelease();
        }
        if (!localObservationRequired && !driver.active() && reached(currentCell)) {
            retainTerminal(AgentJobStore.State.SUCCEEDED, null);
            return publishAfterRelease();
        }
        if (job.completedOperations() == job.maxOperations()
                || clientTick - startedClientTick >= job.maxOperations()) {
            retainTerminal(AgentJobStore.State.FAILED, "tick_limit");
            return publishAfterRelease();
        }
        if (localObservationRequired) {
            if (clearing) {
                retainTerminal(AgentJobStore.State.FAILED, "path_work_safety_interrupted");
            } else if (localObservationWaitTicks++ == 0) {
                replan(map, "local_safety_reobservation");
            } else if (localObservationWaitTicks > MAX_EVIDENCE_WAIT_TICKS) {
                retainTerminal(AgentJobStore.State.FAILED, "local_safety_unconfirmed");
            }
            return terminalIntent == null ? jobs.get(actionId) : publishAfterRelease();
        }
        localObservationWaitTicks = 0;
        if (!jobs.canDispatch(actionId, currentWorldSessionId)) {
            retainTerminal(AgentJobStore.State.FAILED, "dispatch_denied");
            return publishAfterRelease();
        }
        try {
            if (clearing) {
                return clearObstacle(currentCell, clientTick, outputAllowed, map);
            }
            if (!driver.active()) {
                if (!driver.prepare(clientTick, Math.max(0.0D, maxDistance - travelled), () -> jobs.canDispatch(actionId, currentWorldSessionId)
                        && outputAllowed.getAsBoolean())) {
                    jobs.recordOperation(actionId);
                    return jobs.get(actionId);
                }
                if (reached(currentCell)) {
                    retainTerminal(AgentJobStore.State.SUCCEEDED, null);
                    return publishAfterRelease();
                }
                if (waitingEvidence != null && waitingEvidence.equals(map.edges())) {
                    if (++evidenceWaitTicks <= MAX_EVIDENCE_WAIT_TICKS) return jobs.get(actionId);
                    retainTerminal(AgentJobStore.State.FAILED, "target_not_observed");
                    return publishAfterRelease();
                }
                waitingEvidence = null;
                var planned = planner.plan(map, currentCell, currentWorldSessionId,
                        currentRevision, CoordinateGoalPlanner.Budget.DEFAULT,
                        () -> jobs.get(actionId).cancelRequested() || !outputAllowed.getAsBoolean());
                switch (planned.status()) {
                    case REACHED_KNOWN_GOAL -> {
                        retainTerminal(AgentJobStore.State.SUCCEEDED, null);
                        return publishAfterRelease();
                    }
                    case KNOWN_GOAL_ROUTE, PARTIAL_WAYPOINT -> {
                        routeWasGoal = planned.status() == CoordinateGoalPlanner.Status.KNOWN_GOAL_ROUTE;
                        driver.begin(planned.route().orElseThrow(), tolerance);
                    }
                    case CANCELLED -> {
                        retainTerminal(AgentJobStore.State.CANCELLED, "client_request");
                        return publishAfterRelease();
                    }
                    case BLOCKED -> {
                        if (obstacles != null) return clearObstacle(currentCell, clientTick, outputAllowed, map);
                        waitingEvidence = map.edges();
                        evidenceWaitTicks = 0;
                        return jobs.get(actionId);
                    }
                    case LIMIT, STALE_MAP, WORLD_MISMATCH -> {
                        retainTerminal(AgentJobStore.State.FAILED,
                                planned.status().name().toLowerCase(java.util.Locale.ROOT));
                        return publishAfterRelease();
                    }
                }
            }
            var step = driver.tick(map, Math.max(0.0D, maxDistance - travelled), clientTick,
                    () -> jobs.canDispatch(actionId, currentWorldSessionId)
                            && outputAllowed.getAsBoolean());
            jobs.recordOperation(actionId);
            return switch (step.status()) {
                case RUNNING -> jobs.get(actionId);
                case SUCCEEDED -> {
                    driver.close();
                    if (routeWasGoal) {
                        // currentCell predates this driver.tick; the next client tick rechecks
                        // the live player cell without reissuing the same route.
                        awaitingFinalPosition = true;
                        yield jobs.get(actionId);
                    }
                    waitingEvidence = map.edges();
                    evidenceWaitTicks = 0;
                    yield jobs.get(actionId);
                }
                case REPLAN_REQUIRED -> {
                    replan(map, step.reason().name().toLowerCase(java.util.Locale.ROOT));
                    yield terminalIntent == null ? jobs.get(actionId) : publishAfterRelease();
                }
                case FAILED -> {
                    retainTerminal(AgentJobStore.State.FAILED,
                            "movement_" + step.reason().name().toLowerCase(java.util.Locale.ROOT));
                    yield publishAfterRelease();
                }
            };
        } catch (RuntimeException | LinkageError failure) {
            retainTerminal(AgentJobStore.State.FAILED, "movement_runtime_failed");
            return publishAfterRelease();
        }
    }

    private void replan(KnownTraversabilitySnapshot map, String reason) {
        driver.close();
        awaitingFinalPosition = false;
        var result = new java.util.LinkedHashMap<>(jobs.get(actionId).result());
        result.put("last_replan_reason", reason);
        result.put("path_replans", pathReplans);
        jobs.recordResult(actionId, result);
        if (!autoReplan || pathReplans >= 8) {
            retainTerminal(AgentJobStore.State.FAILED,
                    autoReplan ? "route_replan_limit" : "route_replan_required");
            return;
        }
        result.put("path_replans", ++pathReplans);
        jobs.recordResult(actionId, result);
        planner = new CoordinateGoalPlanner(worldSessionId, goal, arrivalRadius);
        waitingEvidence = map.edges();
        evidenceWaitTicks = 0;
    }

    public AgentJobStore.Snapshot cancel() {
        var job = jobs.get(actionId);
        if (job.state().terminal()) return job;
        jobs.requestCancel(actionId);
        if (terminalIntent == null || terminalIntent == AgentJobStore.State.SUCCEEDED) {
            terminalIntent = AgentJobStore.State.CANCELLED;
            terminalFailure = "client_request";
        }
        return publishAfterRelease();
    }

    public AgentJobStore.Snapshot stop(String reason) {
        if (!Objects.requireNonNull(reason, "reason").matches("[a-z0-9_]{1,128}")) {
            throw new IllegalArgumentException("invalid stop reason");
        }
        var job = jobs.get(actionId);
        if (job.state().terminal()) return job;
        if (job.state() == AgentJobStore.State.UNCONFIRMED) {
            jobs.abandon(actionId);
            return jobs.get(actionId);
        }
        retainTerminal(AgentJobStore.State.FAILED, reason);
        return publishAfterRelease();
    }

    private void retainTerminal(AgentJobStore.State state, String failure) {
        if (terminalIntent != null) return;
        terminalIntent = state;
        terminalFailure = failure;
    }

    private boolean reached(NavCell cell) {
        return cell.distanceTo(goal) <= arrivalRadius;
    }

    private AgentJobStore.Snapshot publishAfterRelease() {
        try {
            driver.close();
            if (obstacles != null) {
                try { obstacles.close(); }
                finally { captureObstacleResult(); }
            }
            if (releaseAndVerify.getAsBoolean()) {
                if (terminalIntent == AgentJobStore.State.SUCCEEDED
                        && jobs.get(actionId).cancelRequested()) {
                    terminalIntent = AgentJobStore.State.CANCELLED;
                    terminalFailure = "client_request";
                }
                jobs.finish(actionId, terminalIntent, terminalFailure, true);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Retain the first result and retry idempotent release on the next client tick.
        }
        return jobs.get(actionId);
    }

    private AgentJobStore.Snapshot clearObstacle(NavCell current, long tick,
            BooleanSupplier allowed, KnownTraversabilitySnapshot map) {
        var step = obstacles.tick(current, tick, () -> jobs.canDispatch(actionId, worldSessionId) && allowed.getAsBoolean());
        captureObstacleResult();
        jobs.recordOperation(actionId);
        clearing = step == ObstacleStep.WORKING;
        if (step == ObstacleStep.FAILED) {
            retainTerminal(AgentJobStore.State.FAILED, "path_mutation_not_confirmed");
            return publishAfterRelease();
        }
        if (!clearing) {
            waitingEvidence = map.edges();
            evidenceWaitTicks = 0;
        }
        return jobs.get(actionId);
    }

    private void captureObstacleResult() {
        if (obstacles == null || jobs.get(actionId).state() != AgentJobStore.State.RUNNING) return;
        var result = new java.util.LinkedHashMap<>(jobs.get(actionId).result());
        result.remove("unconfirmed_path_target");
        result.putAll(obstacles.result());
        jobs.recordResult(actionId, result);
    }

    public enum ObstacleStep { UNAVAILABLE, WORKING, CHANGED, FAILED }
    public interface ObstacleDriver {
        ObstacleStep tick(NavCell current, long tick, BooleanSupplier allowed);
        Map<String, Object> result();
        void close();
    }

    public interface MovementDriver {
        default boolean prepare(long clientTick, double remainingDistance, BooleanSupplier outputAllowed) { return true; }
        void begin(RoutePlan route, double tolerance);
        MinecraftActionPrimitiveExecutor.TickResult tick(KnownTraversabilitySnapshot map,
                double remainingDistance, long clientTick, BooleanSupplier outputAllowed);
        boolean active();
        void close();
    }
}
