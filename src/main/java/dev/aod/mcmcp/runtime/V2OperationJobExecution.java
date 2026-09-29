package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentJobLimits;

import dev.aod.mcmcp.agent.action.AgentJobStore;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** One delivery-gated inventory or interaction job, retaining partial effects through cancellation. */
final class V2OperationJobExecution implements V2JobExecution {

    private final AgentJobStore jobs;
    private final UUID actionId;
    private final UUID worldSessionId;
    private final Driver driver;
    private final String operation;
    private final BooleanSupplier releaseAndVerify;
    private boolean begun;
    private long startedNanos = Long.MIN_VALUE;
    private AgentJobStore.State terminalIntent;
    private String terminalFailure;

    V2OperationJobExecution(AgentJobStore jobs, UUID actionId, UUID worldSessionId,
            Driver driver, BooleanSupplier releaseAndVerify) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.actionId = Objects.requireNonNull(actionId, "actionId");
        this.worldSessionId = Objects.requireNonNull(worldSessionId, "worldSessionId");
        this.driver = Objects.requireNonNull(driver, "driver");
        this.releaseAndVerify = Objects.requireNonNull(releaseAndVerify, "releaseAndVerify");
        var job = jobs.get(actionId);
        operation = job.kind() == AgentJobStore.Kind.INVENTORY ? "inventory"
                : job.kind() == AgentJobStore.Kind.LOOK ? "look" : "interaction";
        if ((job.kind() != AgentJobStore.Kind.INVENTORY
                && job.kind() != AgentJobStore.Kind.INTERACT && job.kind() != AgentJobStore.Kind.LOOK)
                || !job.worldSessionId().equals(worldSessionId)) {
            throw new IllegalArgumentException("operation job does not match its session");
        }
    }

    public AgentJobStore.Snapshot tick(UUID currentSession, long clientTick, long nowNanos,
            boolean safe, BooleanSupplier outputAllowed) {
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
        if (!worldSessionId.equals(currentSession) || !safe) {
            retainTerminal(AgentJobStore.State.FAILED, "safety_interrupted");
            return publishAfterRelease();
        }
        if (job.state() == AgentJobStore.State.QUEUED) {
            jobs.start(actionId, currentSession);
            startedNanos = nowNanos;
            job = jobs.get(actionId);
        }
        if (nowNanos - startedNanos >= AgentJobLimits.wallNanos(job.maxOperations())
                || job.completedOperations() >= job.maxOperations()) {
            retainTerminal(AgentJobStore.State.FAILED, "duration_limit");
            return publishAfterRelease();
        }
        if (!jobs.canDispatch(actionId, currentSession)
                || !outputAllowed.getAsBoolean()) {
            retainTerminal(AgentJobStore.State.FAILED, "dispatch_denied");
            return publishAfterRelease();
        }
        try {
            if (!begun) {
                begun = true; // An uncertain begin is never retried.
                driver.begin(clientTick, outputAllowed);
            }
            var step = driver.tick(clientTick, outputAllowed);
            captureResult();
            jobs.recordOperation(actionId);
            if (step == Step.CONFIRMED) {
                retainTerminal(AgentJobStore.State.SUCCEEDED, null);
            } else if (step == Step.FAILED) {
                retainTerminal(AgentJobStore.State.FAILED, operation + "_not_confirmed");
            }
        } catch (RuntimeException | LinkageError failure) {
            try { captureResult(); } catch (RuntimeException | LinkageError ignored) { }
            retainTerminal(AgentJobStore.State.FAILED, operation + "_runtime_failed");
        }
        return terminalIntent == null ? jobs.get(actionId) : publishAfterRelease();
    }

    public AgentJobStore.Snapshot cancel() {
        var job = jobs.get(actionId);
        if (job.state().terminal()) return job;
        jobs.requestCancel(actionId);
        if (job.state() == AgentJobStore.State.RUNNING) captureResultSafely();
        retainTerminal(AgentJobStore.State.CANCELLED, "client_request");
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
        if (job.state() == AgentJobStore.State.RUNNING) captureResultSafely();
        retainTerminal(AgentJobStore.State.FAILED, reason);
        return publishAfterRelease();
    }

    private void captureResult() {
        Map<String, Object> result = driver.result();
        if (!result.isEmpty()) jobs.recordResult(actionId, result);
    }

    private void captureResultSafely() {
        try { captureResult(); } catch (RuntimeException | LinkageError ignored) { }
    }

    private void retainTerminal(AgentJobStore.State outcome, String failure) {
        if (terminalIntent != null && outcome == AgentJobStore.State.SUCCEEDED) return;
        terminalIntent = outcome;
        terminalFailure = failure;
    }

    private AgentJobStore.Snapshot publishAfterRelease() {
        try {
            try { driver.close(); }
            finally { if (begun) captureResultSafely(); }
            if (releaseAndVerify.getAsBoolean()) {
                if (terminalIntent == AgentJobStore.State.SUCCEEDED
                        && jobs.get(actionId).cancelRequested()) {
                    terminalIntent = AgentJobStore.State.CANCELLED;
                    terminalFailure = "client_request";
                }
                jobs.finish(actionId, terminalIntent, terminalFailure, true);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Retry idempotent release before publishing a terminal result.
        }
        return jobs.get(actionId);
    }

    enum Step { RUNNING, CONFIRMED, FAILED }

    public boolean allowsScreenChange() { return begun && driver.allowsScreenChange(); }

    interface Driver {
        void begin(long clientTick, BooleanSupplier outputAllowed);
        Step tick(long clientTick, BooleanSupplier outputAllowed);
        Map<String, Object> result();
        default boolean allowsScreenChange() { return false; }
        void close();
    }
}
