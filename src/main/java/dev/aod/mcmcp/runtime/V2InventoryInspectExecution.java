package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentJobStore;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** A delivery-gated, one-tick inventory readback with the common action ID. */
final class V2InventoryInspectExecution {
    private final AgentJobStore jobs;
    private final UUID actionId;
    private final UUID worldSessionId;
    private final Supplier<Map<String, Object>> capture;
    private final BooleanSupplier releaseAndVerify;
    private AgentJobStore.State terminalIntent;
    private String terminalFailure;

    V2InventoryInspectExecution(AgentJobStore jobs, UUID actionId, UUID worldSessionId,
            Supplier<Map<String, Object>> capture, BooleanSupplier releaseAndVerify) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.actionId = Objects.requireNonNull(actionId, "actionId");
        this.worldSessionId = Objects.requireNonNull(worldSessionId, "worldSessionId");
        this.capture = Objects.requireNonNull(capture, "capture");
        this.releaseAndVerify = Objects.requireNonNull(releaseAndVerify, "releaseAndVerify");
        var job = jobs.get(actionId);
        if (job.kind() != AgentJobStore.Kind.INVENTORY
                || !job.worldSessionId().equals(worldSessionId)) {
            throw new IllegalArgumentException("inventory job does not match its session");
        }
    }

    AgentJobStore.Snapshot tick(UUID currentSession, long nowNanos,
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
        }
        if (!jobs.canDispatch(actionId, currentSession)
                || !outputAllowed.getAsBoolean()) {
            retainTerminal(AgentJobStore.State.FAILED, "dispatch_denied");
            return publishAfterRelease();
        }
        try {
            jobs.recordResult(actionId, capture.get());
            jobs.recordOperation(actionId);
            retainTerminal(AgentJobStore.State.SUCCEEDED, null);
        } catch (RuntimeException | LinkageError failure) {
            retainTerminal(AgentJobStore.State.FAILED, "inventory_read_failed");
        }
        return publishAfterRelease();
    }

    AgentJobStore.Snapshot cancel() {
        var job = jobs.get(actionId);
        if (job.state().terminal()) return job;
        jobs.requestCancel(actionId);
        retainTerminal(AgentJobStore.State.CANCELLED, "client_request");
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

    private void retainTerminal(AgentJobStore.State outcome, String failure) {
        if (terminalIntent != null) return;
        terminalIntent = outcome;
        terminalFailure = failure;
    }

    private AgentJobStore.Snapshot publishAfterRelease() {
        try {
            if (releaseAndVerify.getAsBoolean()) {
                if (terminalIntent == AgentJobStore.State.SUCCEEDED
                        && jobs.get(actionId).cancelRequested()) {
                    terminalIntent = AgentJobStore.State.CANCELLED;
                    terminalFailure = "client_request";
                }
                jobs.finish(actionId, terminalIntent, terminalFailure, true);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Keep the outcome pending until release succeeds on a later tick.
        }
        return jobs.get(actionId);
    }
}
