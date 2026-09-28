package dev.aod.mcmcp.agent.navigation;

import dev.aod.mcmcp.agent.action.AgentJobStore;
import dev.aod.mcmcp.agent.action.MinecraftActionPrimitiveExecutor;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** One coordinate move job: plan a known segment, execute it, then observe and plan again. */
public final class CoordinateMoveJobExecution {
    private static final int MAX_EVIDENCE_WAIT_TICKS = 80;
    private static final long MAX_WALL_NANOS = Duration.ofMinutes(2).toNanos();

    private final AgentJobStore jobs;
    private final UUID actionId;
    private final UUID worldSessionId;
    private final NavCell goal;
    private final double tolerance;
    private final double maxDistance;
    private final CoordinateGoalPlanner planner;
    private final MovementDriver driver;
    private final BooleanSupplier releaseAndVerify;
    private boolean routeWasGoal;
    private Map<TraversabilityEdge.Key, TraversabilityEdge> waitingEvidence;
    private int evidenceWaitTicks;
    private long startedNanos = Long.MIN_VALUE;
    private AgentJobStore.State terminalIntent;
    private String terminalFailure;

    public CoordinateMoveJobExecution(AgentJobStore jobs, UUID actionId, UUID worldSessionId,
                                      NavCell goal, double tolerance, double maxDistance,
                                      MovementDriver driver, BooleanSupplier releaseAndVerify) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.actionId = Objects.requireNonNull(actionId, "actionId");
        this.worldSessionId = Objects.requireNonNull(worldSessionId, "worldSessionId");
        this.goal = Objects.requireNonNull(goal, "goal");
        this.driver = Objects.requireNonNull(driver, "driver");
        this.releaseAndVerify = Objects.requireNonNull(releaseAndVerify, "releaseAndVerify");
        if (!Double.isFinite(tolerance) || tolerance < 0.1D
                || tolerance > 0.49D || !Double.isFinite(maxDistance)
                || maxDistance < 1.0D || maxDistance > 256.0D) {
            throw new IllegalArgumentException("invalid move bounds");
        }
        this.tolerance = tolerance;
        this.maxDistance = maxDistance;
        this.planner = new CoordinateGoalPlanner(worldSessionId, goal);
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
            job = jobs.get(actionId);
        }
        if (nowNanos - startedNanos >= MAX_WALL_NANOS) {
            retainTerminal(AgentJobStore.State.FAILED, "duration_limit");
            return publishAfterRelease();
        }
        if (job.completedOperations() == job.maxOperations()) {
            retainTerminal(AgentJobStore.State.FAILED, "tick_limit");
            return publishAfterRelease();
        }
        if (!jobs.canDispatch(actionId, currentWorldSessionId)) {
            retainTerminal(AgentJobStore.State.FAILED, "dispatch_denied");
            return publishAfterRelease();
        }
        try {
            if (!driver.active()) {
                if (currentCell.equals(goal)) {
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
                        retainTerminal(currentCell.equals(goal)
                                ? AgentJobStore.State.SUCCEEDED : AgentJobStore.State.FAILED,
                                currentCell.equals(goal) ? null : "goal_not_reached");
                        yield publishAfterRelease();
                    }
                    waitingEvidence = map.edges();
                    evidenceWaitTicks = 0;
                    yield jobs.get(actionId);
                }
                case REPLAN_REQUIRED -> {
                    driver.close();
                    if (routeWasGoal) {
                        retainTerminal(AgentJobStore.State.FAILED, "route_replan_required");
                        yield publishAfterRelease();
                    }
                    waitingEvidence = map.edges();
                    evidenceWaitTicks = 0;
                    yield jobs.get(actionId);
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

    private AgentJobStore.Snapshot publishAfterRelease() {
        try {
            driver.close();
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

    public interface MovementDriver {
        void begin(RoutePlan route, double tolerance);
        MinecraftActionPrimitiveExecutor.TickResult tick(KnownTraversabilitySnapshot map,
                double remainingDistance, long clientTick, BooleanSupplier outputAllowed);
        boolean active();
        void close();
    }
}
