package dev.aod.mcmcp.agent.input;

import dev.aod.mcmcp.agent.action.AgentJobStore;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Client-tick owner for one v2 input job. No terminal result is published before full release. */
public final class InputSequenceJobExecution {
    private final AgentJobStore jobs;
    private final UUID actionId;
    private final UUID worldSessionId;
    private final InputSequenceLeaseDriver driver;
    private final BooleanSupplier releaseAndVerify;
    private final Consumer<FiniteInputSequence.Frame> afterInputPublished;
    private AgentJobStore.State terminalIntent;
    private String terminalFailure;

    public InputSequenceJobExecution(AgentJobStore jobs, UUID actionId, UUID worldSessionId,
                                     InputSequenceLeaseDriver driver,
                                     BooleanSupplier releaseAndVerify) {
        this(jobs, actionId, worldSessionId, driver, releaseAndVerify, ignored -> { });
    }

    public InputSequenceJobExecution(AgentJobStore jobs, UUID actionId, UUID worldSessionId,
                                     InputSequenceLeaseDriver driver,
                                     BooleanSupplier releaseAndVerify,
                                     Consumer<FiniteInputSequence.Frame> afterInputPublished) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.actionId = Objects.requireNonNull(actionId, "actionId");
        this.worldSessionId = Objects.requireNonNull(worldSessionId, "worldSessionId");
        this.driver = Objects.requireNonNull(driver, "driver");
        this.releaseAndVerify = Objects.requireNonNull(releaseAndVerify, "releaseAndVerify");
        this.afterInputPublished = Objects.requireNonNull(afterInputPublished, "afterInputPublished");
        var job = jobs.get(actionId);
        if (job.kind() != AgentJobStore.Kind.INPUT_SEQUENCE
                || !job.worldSessionId().equals(worldSessionId)
                || job.maxOperations() != driver.totalTicks()) {
            throw new IllegalArgumentException("input job does not match its session");
        }
    }

    /** Called once per client tick after the runtime has checked world, screen and local safety. */
    public AgentJobStore.Snapshot tick(UUID currentWorldSessionId, long clientTick, long nowNanos,
                                       boolean safeToInput, boolean stopConditionMet) {
        var job = jobs.get(actionId);
        if (job.state().terminal()) return job;
        if (terminalIntent != null) return publishAfterRelease();
        if (job.state() == AgentJobStore.State.UNCONFIRMED) {
            jobs.expireUnconfirmed(nowNanos);
            return jobs.get(actionId);
        }
        if (job.cancelRequested()) {
            retainTerminal(AgentJobStore.State.CANCELLED, "client_request");
            return publishAfterRelease();
        }
        if (!worldSessionId.equals(currentWorldSessionId)) {
            retainTerminal(AgentJobStore.State.FAILED, "world_session_changed");
            return publishAfterRelease();
        }
        if (!safeToInput) {
            retainTerminal(AgentJobStore.State.FAILED, "safety_interrupted");
            return publishAfterRelease();
        }
        if (job.state() == AgentJobStore.State.QUEUED) {
            jobs.start(actionId, currentWorldSessionId);
            job = jobs.get(actionId);
        }
        if (job.completedOperations() == job.maxOperations()) {
            retainTerminal(AgentJobStore.State.SUCCEEDED, null);
            return publishAfterRelease();
        }
        if (!jobs.canDispatch(actionId, currentWorldSessionId)) {
            retainTerminal(AgentJobStore.State.FAILED, "dispatch_denied");
            return publishAfterRelease();
        }
        try {
            var frame = driver.tick(clientTick, nowNanos, stopConditionMet, false);
            afterInputPublished.accept(frame);
            if (frame.state() == FiniteInputSequence.State.RUNNING) {
                jobs.recordOperation(actionId);
            } else if (frame.state() == FiniteInputSequence.State.COMPLETED) {
                retainTerminal(AgentJobStore.State.SUCCEEDED, null);
                return publishAfterRelease();
            } else {
                retainTerminal(AgentJobStore.State.CANCELLED, "sequence_cancelled");
                return publishAfterRelease();
            }
        } catch (RuntimeException | LinkageError failure) {
            retainTerminal(AgentJobStore.State.FAILED, "input_sequence_failed");
            return publishAfterRelease();
        }
        return jobs.get(actionId);
    }

    /** Esc, UI OFF, world change and endpoint faults use the same release gate. */
    public AgentJobStore.Snapshot stop(String reason) {
        Objects.requireNonNull(reason, "reason");
        if (!reason.matches("[a-z0-9_]{1,128}")) {
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

    /** Explicit client cancellation wins over a pending success until the release fence passes. */
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
            // Keep the first terminal intent and retry idempotent release on the next client tick.
        }
        return jobs.get(actionId);
    }
}
