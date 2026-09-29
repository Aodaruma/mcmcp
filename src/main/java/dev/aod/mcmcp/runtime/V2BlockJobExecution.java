package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentJobLimits;

import dev.aod.mcmcp.agent.action.AgentJobStore;
import dev.aod.mcmcp.agent.navigation.NavCell;
import dev.aod.mcmcp.routine.BlockStateFingerprint;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** One delivery-gated block job over a bounded box, with one input owner. */
final class V2BlockJobExecution<R extends V2BlockWorkRequest> implements V2JobExecution {
    private static final int MAX_OBSERVATION_WAIT_TICKS = 80;
    private static final int MAX_RETAINED_CONFIRMED_CELLS = 128;

    private final AgentJobStore jobs;
    private final UUID actionId;
    private final UUID worldSessionId;
    private final R request;
    private final List<NavCell> cells;
    private final Driver<R> driver;
    private final BooleanSupplier releaseAndVerify;
    private final ArrayDeque<Map<String, Object>> confirmedCells = new ArrayDeque<>();
    private final ArrayDeque<Map<String, Object>> observedCells = new ArrayDeque<>();
    private int observedCount;
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

    public AgentJobStore.Snapshot tick(UUID currentWorldSessionId, long clientTick,
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
        if (nowNanos - startedNanos >= AgentJobLimits.wallNanos(job.maxOperations())
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
                        recordObservedCell(cells.get(index), driver.confirmedChange());
                        driver.close();
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
                    recordObservedCell(cells.get(index), driver.confirmedChange());
                    driver.close();
                    targetActive = false;
                    index++;
                    jobs.recordBlockProgress(actionId, index, completed);
                    yield jobs.get(actionId);
                }
                case CONFIRMED -> {
                    NavCell confirmedCell = cells.get(index);
                    index++;
                    completed++;
                    jobs.recordBlockProgress(actionId, index, completed);
                    recordConfirmedCell(confirmedCell, driver.confirmedChange());
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

    int completedBlocks() { return completed; }
    int scannedCells() { return index; }

    private void recordConfirmedCell(NavCell cell, ConfirmedChange change) {
        if (confirmedCells.size() == MAX_RETAINED_CONFIRMED_CELLS) {
            confirmedCells.removeFirst();
        }
        confirmedCells.addLast(cellEntry(cell, change));
        publishCells(cell.dimension());
    }

    private void recordObservedCell(NavCell cell, ConfirmedChange change) {
        if (change == null) return;
        observedCount++;
        if (observedCells.size() == MAX_RETAINED_CONFIRMED_CELLS) observedCells.removeFirst();
        observedCells.addLast(cellEntry(cell, change));
        publishCells(cell.dimension());
    }

    private static Map<String, Object> cellEntry(NavCell cell, ConfirmedChange change) {
        var entry = new java.util.LinkedHashMap<String, Object>();
        entry.put("x", cell.x()); entry.put("y", cell.y()); entry.put("z", cell.z());
        if (change != null) {
            entry.put("before", compactState(change.before())); entry.put("after", compactState(change.after()));
            if (!change.companions().isEmpty()) entry.put("companions", change.companions().stream().map(other -> Map.of(
                    "x", other.cell().x(), "y", other.cell().y(), "z", other.cell().z(),
                    "before", compactState(other.before()), "after", compactState(other.after()))).toList());
        }
        return Map.copyOf(entry);
    }

    private void publishCells(String dimension) {
        jobs.recordResult(actionId, Map.of(
                "dimension", dimension,
                "confirmed_count", completed,
                "observed_count", observedCount,
                "observed_cells", List.copyOf(observedCells),
                "retained_from", completed - confirmedCells.size() + 1,
                "confirmed_cells", List.copyOf(confirmedCells),
                "truncated", completed > confirmedCells.size() || observedCount > observedCells.size()));
    }

    private static String compactState(BlockStateFingerprint state) {
        if (state.properties().isEmpty()) return state.blockId();
        var value = new StringBuilder(state.blockId()).append('[');
        state.properties().forEach((key, property) -> {
            if (value.charAt(value.length() - 1) != '[') value.append(',');
            value.append(key).append('=').append(property);
        });
        return value.append(']').toString();
    }

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

    record CompanionChange(NavCell cell, BlockStateFingerprint before, BlockStateFingerprint after) { }

    record ConfirmedChange(BlockStateFingerprint before, BlockStateFingerprint after, List<CompanionChange> companions) {
        ConfirmedChange(BlockStateFingerprint before, BlockStateFingerprint after) { this(before, after, List.of()); }
        ConfirmedChange {
            Objects.requireNonNull(before, "before");
            Objects.requireNonNull(after, "after");
            companions = List.copyOf(companions);
        }
    }

    interface Driver<R extends V2BlockWorkRequest> {
        String dimension();
        BeginResult begin(NavCell target, R request, BooleanSupplier outputAllowed);
        StepResult tick(long clientTick, BooleanSupplier outputAllowed);
        default ConfirmedChange confirmedChange() { return null; }
        void close();
    }
}
