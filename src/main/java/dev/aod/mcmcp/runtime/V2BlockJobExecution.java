package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentJobStore;
import dev.aod.mcmcp.agent.navigation.NavCell;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** One delivery-gated block job over a bounded box, with one input owner. */
final class V2BlockJobExecution<R extends V2BlockWorkRequest> {
    private static final int MAX_OBSERVATION_WAIT_TICKS = 80;
    private static final long MAX_WALL_NANOS = Duration.ofMinutes(2).toNanos();

    private final AgentJobStore jobs;
    private final UUID actionId;
    private final UUID worldSessionId;
    private final R request;
    private final List<NavCell> cells;
    private final Driver<R> driver;
    private final BooleanSupplier releaseAndVerify;
    private int index;
    private int completed;
    private int observationWaitTicks;
    private boolean targetActive;
    private long startedNanos = Long.MIN_VALUE;
    private AgentJobStore.State terminalIntent;
    private String terminalFailure;

    V2BlockJobExecution(AgentJobStore jobs, UUID actionId, UUID worldSessionId,
            AgentJobStore.Kind kind, R request, Driver<R> driver,
            BooleanSupplier releaseAndVerify) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.actionId = Objects.requireNonNull(actionId, "actionId");
        this.worldSessionId = Objects.requireNonNull(worldSessionId, "worldSessionId");
        Objects.requireNonNull(kind, "kind");
        if (kind != AgentJobStore.Kind.BREAK_BLOCK && kind != AgentJobStore.Kind.PLACE_BLOCK
                && kind != AgentJobStore.Kind.INTERACT) {
            throw new IllegalArgumentException("unsupported block work kind");
        }
        this.request = Objects.requireNonNull(request, "request");
        this.cells = request.cells();
        this.driver = Objects.requireNonNull(driver, "driver");
        this.releaseAndVerify = Objects.requireNonNull(releaseAndVerify, "releaseAndVerify");
        var job = jobs.get(actionId);
        if (job.kind() != kind
                || !job.worldSessionId().equals(worldSessionId)
                || !request.region().dimension().equals(driver.dimension())) {
            throw new IllegalArgumentException("block job does not match its session");
        }
    }

    AgentJobStore.Snapshot tick(UUID currentWorldSessionId, long clientTick,
            long nowNanos, boolean safe, BooleanSupplier outputAllowed) {
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
        if (!worldSessionId.equals(currentWorldSessionId)) {
            retainTerminal(AgentJobStore.State.FAILED, "world_session_changed");
            return publishAfterRelease();
        }
        if (!safe) {
            retainTerminal(AgentJobStore.State.FAILED, "safety_interrupted");
            return publishAfterRelease();
        }
        if (job.state() == AgentJobStore.State.QUEUED) {
            jobs.start(actionId, currentWorldSessionId);
            startedNanos = nowNanos;
            job = jobs.get(actionId);
        }
        if (nowNanos - startedNanos >= MAX_WALL_NANOS
                || job.completedOperations() >= request.maxTicks()) {
            retainTerminal(AgentJobStore.State.FAILED, "duration_limit");
            return publishAfterRelease();
        }
        if (!jobs.canDispatch(actionId, currentWorldSessionId)
                || !outputAllowed.getAsBoolean()) {
            retainTerminal(AgentJobStore.State.FAILED, "dispatch_denied");
            return publishAfterRelease();
        }
        try {
            if (index >= cells.size() || completed >= request.maxBlocks()) {
                retainTerminal(AgentJobStore.State.SUCCEEDED, null);
                return publishAfterRelease();
            }
            if (!targetActive) {
                BeginResult begin = driver.begin(cells.get(index), request,
                        () -> jobs.canDispatch(actionId, currentWorldSessionId)
                                && outputAllowed.getAsBoolean());
                switch (begin) {
                    case STARTED -> {
                        targetActive = true;
                        observationWaitTicks = 0;
                    }
                    case SKIPPED -> {
                        index++;
                        jobs.recordBlockProgress(actionId, index, completed);
                        observationWaitTicks = 0;
                        return jobs.get(actionId);
                    }
                    case WAITING -> {
                        if (++observationWaitTicks > MAX_OBSERVATION_WAIT_TICKS) {
                            retainTerminal(AgentJobStore.State.FAILED, "target_not_observed");
                            return publishAfterRelease();
                        }
                        return jobs.get(actionId);
                    }
                    case FAILED -> {
                        retainTerminal(AgentJobStore.State.FAILED, "block_target_unavailable");
                        return publishAfterRelease();
                    }
                }
            }
            var step = driver.tick(clientTick,
                    () -> jobs.canDispatch(actionId, currentWorldSessionId)
                            && outputAllowed.getAsBoolean());
            jobs.recordOperation(actionId);
            return switch (step) {
                case RUNNING -> jobs.get(actionId);
                case SKIPPED -> {
                    driver.close();
                    targetActive = false;
                    index++;
                    jobs.recordBlockProgress(actionId, index, completed);
                    yield jobs.get(actionId);
                }
                case CONFIRMED -> {
                    index++;
                    completed++;
                    jobs.recordBlockProgress(actionId, index, completed);
                    driver.close();
                    targetActive = false;
                    if (index >= cells.size() || completed >= request.maxBlocks()) {
                        retainTerminal(AgentJobStore.State.SUCCEEDED, null);
                        yield publishAfterRelease();
                    }
                    yield jobs.get(actionId);
                }
                case FAILED -> {
                    retainTerminal(AgentJobStore.State.FAILED, "block_not_confirmed");
                    yield publishAfterRelease();
                }
            };
        } catch (RuntimeException | LinkageError failure) {
            retainTerminal(AgentJobStore.State.FAILED, "block_runtime_failed");
            return publishAfterRelease();
        }
    }

    AgentJobStore.Snapshot cancel() {
        var job = jobs.get(actionId);
        if (job.state().terminal()) return job;
        jobs.requestCancel(actionId);
        if (terminalIntent == null || terminalIntent == AgentJobStore.State.SUCCEEDED) {
            terminalIntent = AgentJobStore.State.CANCELLED;
            terminalFailure = "client_request";
        }
        return publishAfterRelease();
    }

    AgentJobStore.Snapshot stop(String reason) {
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

    int completedBlocks() { return completed; }
    int scannedCells() { return index; }

    private void retainTerminal(AgentJobStore.State outcome, String failure) {
        if (terminalIntent != null) return;
        terminalIntent = outcome;
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
            // Retain the result and retry idempotent release before publishing terminal state.
        }
        return jobs.get(actionId);
    }

    enum BeginResult { STARTED, SKIPPED, WAITING, FAILED }
    enum StepResult { RUNNING, SKIPPED, CONFIRMED, FAILED }

    interface Driver<R extends V2BlockWorkRequest> {
        String dimension();
        BeginResult begin(NavCell target, R request, BooleanSupplier outputAllowed);
        StepResult tick(long clientTick, BooleanSupplier outputAllowed);
        void close();
    }
}
