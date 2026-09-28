package dev.aod.mcmcp.agent.action;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** One active v2 job with delivery-gated admission and release-gated terminal state. */
public final class AgentJobStore {
    public static final int MAX_OPERATIONS = 1_200;
    public static final int MAX_TERMINAL_WAIT_MILLIS = 25_000;

    private Job latest;
    private Snapshot previousTerminal;

    public synchronized UUID reserve(Kind kind, UUID worldSessionId, int maxOperations,
                                     long confirmationDeadlineNanos) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(worldSessionId, "worldSessionId");
        if (maxOperations < 1 || maxOperations > MAX_OPERATIONS) {
            throw new IllegalArgumentException("job operation bound must be in 1..1200");
        }
        if (latest != null && !latest.state.terminal()) {
            throw new IllegalStateException("another job is active");
        }
        if (latest != null) previousTerminal = latest.snapshot();
        latest = new Job(UUID.randomUUID(), kind, worldSessionId, maxOperations,
                confirmationDeadlineNanos);
        return latest.id;
    }

    public synchronized Confirmation confirm(UUID actionId, long nowNanos) {
        Job job = current(actionId);
        if (job.state == State.QUEUED) return Confirmation.ALREADY_CONFIRMED;
        if (job.state != State.UNCONFIRMED) return Confirmation.STALE;
        if (nowNanos - job.confirmationDeadlineNanos >= 0) {
            job.failure = "delivery_confirmation_timeout";
            job.state = State.FAILED;
            notifyAll();
            return Confirmation.EXPIRED;
        }
        job.state = State.QUEUED;
        notifyAll();
        return Confirmation.CONFIRMED;
    }

    public synchronized boolean expireUnconfirmed(long nowNanos) {
        if (latest == null || latest.state != State.UNCONFIRMED
                || nowNanos - latest.confirmationDeadlineNanos < 0) return false;
        latest.failure = "delivery_confirmation_timeout";
        latest.state = State.FAILED;
        notifyAll();
        return true;
    }

    public synchronized boolean abandon(UUID actionId) {
        Job job = current(actionId);
        if (job.state != State.UNCONFIRMED) return false;
        job.failure = "delivery_not_confirmed";
        job.state = State.FAILED;
        notifyAll();
        return true;
    }

    public synchronized void start(UUID actionId, UUID currentWorldSessionId) {
        Job job = current(actionId);
        if (job.state != State.QUEUED || job.cancelRequested) {
            throw new IllegalStateException("only a confirmed queued job can start");
        }
        if (!job.worldSessionId.equals(currentWorldSessionId)) {
            throw new IllegalStateException("job world session changed");
        }
        job.state = State.RUNNING;
        notifyAll();
    }

    /** Check again immediately before a game side effect; recording its result can follow a cancel. */
    public synchronized boolean canDispatch(UUID actionId, UUID currentWorldSessionId) {
        Job job = current(actionId);
        return job.state == State.RUNNING && !job.cancelRequested
                && job.worldSessionId.equals(currentWorldSessionId)
                && job.completedOperations < job.maxOperations;
    }

    public synchronized void recordOperation(UUID actionId) {
        Job job = current(actionId);
        if (job.state != State.RUNNING) {
            throw new IllegalStateException("job is not running");
        }
        if (job.completedOperations == job.maxOperations) {
            throw new IllegalStateException("job operation bound exhausted");
        }
        job.completedOperations++;
        notifyAll();
    }

    /** Retain confirmed block progress even after the execution owner releases its inputs. */
    public synchronized void recordBlockProgress(UUID actionId, int scannedCells, int completedBlocks) {
        Job job = current(actionId);
        if ((job.kind != Kind.BREAK_BLOCK && job.kind != Kind.PLACE_BLOCK)
                || job.state != State.RUNNING
                || scannedCells < job.scannedCells || scannedCells > BlockWorkRegion.MAX_CELLS
                || completedBlocks < job.completedBlocks || completedBlocks > scannedCells) {
            throw new IllegalArgumentException("invalid block progress");
        }
        job.scannedCells = scannedCells;
        job.completedBlocks = completedBlocks;
        notifyAll();
    }

    /** Retains one bounded, immutable job result through cancellation and the next terminal job. */
    public synchronized void recordResult(UUID actionId, Map<String, Object> result) {
        Job job = current(actionId);
        if (job.state != State.RUNNING || result.size() > 32) {
            throw new IllegalArgumentException("invalid job result");
        }
        job.result = Map.copyOf(result);
        notifyAll();
    }

    /** Cancellation is a request; the owner must release inputs before publishing a terminal state. */
    public synchronized boolean requestCancel(UUID actionId) {
        Job job = current(actionId);
        if (job.state.terminal()) return false;
        job.cancelRequested = true;
        notifyAll();
        return true;
    }

    public synchronized void finish(UUID actionId, State outcome, String failure,
                                    boolean inputsReleased) {
        Job job = current(actionId);
        if (job.state != State.RUNNING && job.state != State.QUEUED
                && !(job.state == State.UNCONFIRMED && outcome == State.CANCELLED)) {
            throw new IllegalStateException("job cannot finish from this state");
        }
        if (!inputsReleased) throw new IllegalStateException("job inputs are not released");
        if (outcome != State.SUCCEEDED && outcome != State.FAILED && outcome != State.CANCELLED) {
            throw new IllegalArgumentException("terminal outcome required");
        }
        if (job.state == State.QUEUED && outcome == State.SUCCEEDED) {
            throw new IllegalStateException("queued job cannot report success");
        }
        if (job.cancelRequested && outcome == State.SUCCEEDED) {
            throw new IllegalStateException("cancelled job cannot report success");
        }
        if (outcome == State.SUCCEEDED && failure != null
                || outcome != State.SUCCEEDED && (failure == null || failure.isBlank()
                        || failure.length() > 128)) {
            throw new IllegalArgumentException("invalid job failure");
        }
        job.failure = failure;
        job.state = outcome;
        notifyAll();
    }

    public synchronized Optional<Snapshot> active() {
        return latest == null || latest.state.terminal()
                ? Optional.empty() : Optional.of(latest.snapshot());
    }

    public synchronized Snapshot get(UUID actionId) {
        Objects.requireNonNull(actionId, "actionId");
        if (latest != null && latest.id.equals(actionId)) return latest.snapshot();
        if (previousTerminal != null && previousTerminal.actionId().equals(actionId)) {
            return previousTerminal;
        }
        throw new NotFoundException();
    }

    /** Synchronized status wait for an MCP worker; no Minecraft state is read here. */
    public synchronized Snapshot awaitTerminal(UUID actionId, int timeoutMillis)
            throws InterruptedException {
        if (timeoutMillis < 0 || timeoutMillis > MAX_TERMINAL_WAIT_MILLIS) {
            throw new IllegalArgumentException("invalid job wait timeout");
        }
        Job target = latest != null && latest.id.equals(actionId) ? latest : null;
        if (target == null) return get(actionId);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (!target.state.terminal()) {
            if (latest != target) throw new NotFoundException();
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) break;
            long millis = TimeUnit.NANOSECONDS.toMillis(remaining);
            int nanos = (int) (remaining - TimeUnit.MILLISECONDS.toNanos(millis));
            wait(millis, nanos);
        }
        return get(actionId);
    }

    private Job current(UUID actionId) {
        Objects.requireNonNull(actionId, "actionId");
        if (latest == null || !latest.id.equals(actionId)) throw new NotFoundException();
        return latest;
    }

    public enum Kind {
        MOVE, BREAK_BLOCK, PLACE_BLOCK, INTERACT, INVENTORY, CLICK, INPUT_SEQUENCE, SCRIPT
    }

    public enum State {
        UNCONFIRMED, QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED;

        public boolean terminal() {
            return this == SUCCEEDED || this == FAILED || this == CANCELLED;
        }
    }

    public enum Confirmation { CONFIRMED, ALREADY_CONFIRMED, EXPIRED, STALE }

    public record Snapshot(UUID actionId, Kind kind, UUID worldSessionId, State state,
                           int completedOperations, int maxOperations,
                           int scannedCells, int completedBlocks,
                           boolean cancelRequested, String failure,
                           Map<String, Object> result) { }

    public static final class NotFoundException extends RuntimeException { }

    private static final class Job {
        final UUID id;
        final Kind kind;
        final UUID worldSessionId;
        final int maxOperations;
        final long confirmationDeadlineNanos;
        State state = State.UNCONFIRMED;
        int completedOperations;
        int scannedCells;
        int completedBlocks;
        boolean cancelRequested;
        String failure;
        Map<String, Object> result = Map.of();

        Job(UUID id, Kind kind, UUID worldSessionId, int maxOperations,
            long confirmationDeadlineNanos) {
            this.id = id;
            this.kind = kind;
            this.worldSessionId = worldSessionId;
            this.maxOperations = maxOperations;
            this.confirmationDeadlineNanos = confirmationDeadlineNanos;
        }

        Snapshot snapshot() {
            return new Snapshot(id, kind, worldSessionId, state, completedOperations,
                    maxOperations, scannedCells, completedBlocks, cancelRequested, failure, result);
        }
    }
}
