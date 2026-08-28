package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.agent.dsl.ActionDslCompiler.CompiledProgram;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Synchronized one-active-plus-one-terminal action state machine. */
public final class AgentActionStore {
    public static final int TRACE_LIMIT = 256;
    public static final int MAX_RECORDED_TICKS = 12_200;
    public static final double MAX_RECORDED_DISTANCE = 48.0D;
    public static final double MAX_RECORDED_CAMERA_DEGREES = 1_080.0D;
    public static final int MAX_RECORDED_BLOCKS_BROKEN = 12;
    public static final int MAX_RECORDED_INTERACTIONS = 16;
    public static final int MAX_RECORDED_BLOCKS_PLACED = 16;
    public static final int MAX_TERMINAL_WAIT_MILLIS = 25_000;

    private Mutable latest;
    private Snapshot previousTerminal;

    public synchronized Accepted start(CompiledProgram program, Instant acceptedAt) {
        Accepted accepted = reserve(program, acceptedAt, Long.MAX_VALUE);
        latest.state = State.QUEUED;
        latest.phase = Phase.QUEUED;
        latest.trace(0, "QUEUED", "Action accepted");
        return accepted;
    }

    public synchronized Accepted reserve(
            CompiledProgram program, Instant acceptedAt, long confirmationDeadlineNanos) {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
        if (latest != null && !latest.state.terminal()) {
            throw new BusyException();
        }
        if (latest != null) {
            previousTerminal = latest.snapshot();
        }
        latest = new Mutable(UUID.randomUUID(), program, confirmationDeadlineNanos);
        latest.trace(0, "UNCONFIRMED", "Action reserved pending HTTP delivery");
        return new Accepted(latest.id, acceptedAt);
    }

    public synchronized Confirmation confirm(UUID actionId, long nowNanos) {
        Mutable action = current(actionId);
        if (action.state == State.QUEUED) {
            return Confirmation.ALREADY_CONFIRMED;
        }
        if (action.state != State.UNCONFIRMED) {
            return Confirmation.STALE;
        }
        if (deadlineReached(nowNanos, action.confirmationDeadlineNanos)) {
            finish(action, State.FAILED, deliveryFailure("confirmation_timeout"));
            return Confirmation.EXPIRED;
        }
        action.state = State.QUEUED;
        action.phase = Phase.QUEUED;
        action.trace(0, "QUEUED", "HTTP delivery confirmed");
        return Confirmation.CONFIRMED;
    }

    public synchronized boolean abandonUnconfirmed(UUID actionId, String evidence) {
        Mutable action = current(actionId);
        if (action.state != State.UNCONFIRMED) {
            return false;
        }
        finish(action, State.FAILED, deliveryFailure(bounded(evidence, 128)));
        return true;
    }

    public synchronized boolean expireUnconfirmed(long nowNanos) {
        if (latest == null || latest.state != State.UNCONFIRMED
                || !deadlineReached(nowNanos, latest.confirmationDeadlineNanos)) {
            return false;
        }
        finish(latest, State.FAILED, deliveryFailure("confirmation_timeout"));
        return true;
    }

    private static Failure deliveryFailure(String evidence) {
        return new Failure(FailureCode.DELIVERY_UNCONFIRMED, true, List.of(evidence));
    }

    private static boolean deadlineReached(long nowNanos, long deadlineNanos) {
        return nowNanos - deadlineNanos >= 0L;
    }

    public synchronized Optional<Active> active() {
        return latest == null || latest.state.terminal()
                ? Optional.empty()
                : Optional.of(new Active(latest.id, latest.program, latest.state));
    }

    public synchronized Snapshot get(UUID actionId) {
        Objects.requireNonNull(actionId, "actionId");
        if (latest != null && latest.id.equals(actionId)) {
            return latest.snapshot();
        }
        if (previousTerminal != null && previousTerminal.actionId().equals(actionId)) {
            return previousTerminal;
        }
        throw new NotFoundException();
    }

    /**
     * Returns the retained snapshot once it is terminal, or the latest snapshot when the bounded
     * wait elapses. This monitor wait is used only by an MCP worker; it never owns Minecraft state
     * and terminal transitions wake all waiters.
     */
    public synchronized Snapshot awaitTerminal(UUID actionId, int timeoutMillis)
            throws InterruptedException {
        Objects.requireNonNull(actionId, "actionId");
        if (timeoutMillis < 0 || timeoutMillis > MAX_TERMINAL_WAIT_MILLIS) {
            throw new IllegalArgumentException(
                    "timeoutMillis must be in 0.." + MAX_TERMINAL_WAIT_MILLIS);
        }
        if (previousTerminal != null && previousTerminal.actionId().equals(actionId)) {
            return previousTerminal;
        }
        if (latest == null || !latest.id.equals(actionId)) {
            throw new NotFoundException();
        }

        Mutable target = latest;
        if (target.state.terminal() || timeoutMillis == 0) {
            return target.snapshot();
        }
        long deadlineNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (!target.state.terminal()) {
            if (!retains(target)) {
                throw new NotFoundException();
            }
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0L) {
                break;
            }
            long waitMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
            int waitNanos = (int) (remainingNanos
                    - TimeUnit.MILLISECONDS.toNanos(waitMillis));
            wait(waitMillis, waitNanos);
        }
        if (!retains(target)) {
            throw new NotFoundException();
        }
        return target.snapshot();
    }

    private boolean retains(Mutable target) {
        return latest == target
                || (previousTerminal != null
                && previousTerminal.actionId().equals(target.id));
    }

    public synchronized Optional<Summary> latestSummary() {
        return latest == null ? Optional.empty() : Optional.of(new Summary(
                latest.id, latest.state, latest.endReason));
    }

    public synchronized CancelResult cancel(UUID actionId) {
        State stateAtRequest = get(actionId).state();
        boolean requested = latest != null
                && latest.id.equals(actionId)
                && !latest.state.terminal();
        if (requested) {
            finish(latest, State.CANCELLED,
                    new Failure(FailureCode.CANCELLED_BY_CLIENT, true, List.of("client_request")));
        }
        return new CancelResult(actionId, requested, stateAtRequest);
    }

    public synchronized void markRunning(UUID actionId) {
        Mutable action = current(actionId);
        if (action.state != State.QUEUED) {
            throw new IllegalStateException("Only a queued action can start");
        }
        action.state = State.RUNNING;
        action.phase = Phase.EXECUTING;
        action.trace(action.ticks, "STARTED", "Action execution started");
    }

    public synchronized void beginNode(UUID actionId, String nodeId) {
        Mutable action = running(actionId);
        action.currentNodeId = requireNodeId(nodeId);
        action.trace(action.ticks, "NODE_STARTED", nodeId);
    }

    public synchronized void completeNode(UUID actionId) {
        Mutable action = running(actionId);
        if (action.currentNodeId == null) {
            throw new IllegalStateException("No action node is active");
        }
        action.executedNodes++;
        action.trace(action.ticks, "NODE_COMPLETED", action.currentNodeId);
        action.currentNodeId = null;
    }

    /** Adds one bounded, node-scoped server-derived observation to the existing trace surface. */
    public synchronized void recordNodeEvidence(UUID actionId, String detail) {
        Mutable action = running(actionId);
        if (action.currentNodeId == null) {
            throw new IllegalStateException("No action node is active");
        }
        action.trace(action.ticks, "NODE_EVIDENCE", bounded(detail, 256));
    }

    public synchronized void recordTick(UUID actionId) {
        Mutable action = running(actionId);
        if (action.ticks >= MAX_RECORDED_TICKS) {
            throw new IllegalStateException("Action tick limit exceeded");
        }
        action.ticks++;
    }

    public synchronized void recordMotion(UUID actionId, double distance, double cameraDegrees) {
        Mutable action = running(actionId);
        action.motionOverflowed |= exceedsBound(
                action.distanceTravelled, distance, MAX_RECORDED_DISTANCE, "distance")
                || exceedsBound(
                        action.cameraDegrees,
                        cameraDegrees,
                        MAX_RECORDED_CAMERA_DEGREES,
                        "cameraDegrees");
        action.distanceTravelled = boundedAdd(
                action.distanceTravelled, distance, MAX_RECORDED_DISTANCE, "distance");
        action.cameraDegrees = boundedAdd(
                action.cameraDegrees, cameraDegrees, MAX_RECORDED_CAMERA_DEGREES, "cameraDegrees");
    }

    /** Audits bounded world-physics displacement which is intentionally not input-motion use. */
    public synchronized void recordPassiveMotion(UUID actionId, double distance, String cause) {
        Mutable action = running(actionId);
        if (!Double.isFinite(distance) || distance <= 0.0D || distance > 1.0D / 16.0D + 1.0e-6D) {
            throw new IllegalArgumentException("passive motion is outside the bounded settling range");
        }
        String boundedCause = Objects.requireNonNull(cause, "cause");
        if (!boundedCause.matches("[a-z0-9_]{1,32}")) {
            throw new IllegalArgumentException("invalid passive motion cause");
        }
        action.trace(
                action.ticks,
                "PASSIVE_MOTION",
                boundedCause + "=" + String.format(java.util.Locale.ROOT, "%.6f", distance));
    }

    /** Records one server-acknowledged authoritative transition to air. */
    public synchronized void recordBlockBreak(UUID actionId) {
        Mutable action = running(actionId);
        if (action.blocksBroken >= MAX_RECORDED_BLOCKS_BROKEN) {
            throw new IllegalStateException("Action block-break record limit exceeded");
        }
        action.blocksBroken++;
    }

    public synchronized void recordInteraction(UUID actionId) {
        Mutable action = running(actionId);
        if (action.interactions >= MAX_RECORDED_INTERACTIONS) {
            throw new IllegalStateException("Action interaction record limit exceeded");
        }
        action.interactions++;
    }

    public synchronized void recordBlockPlace(UUID actionId) {
        Mutable action = running(actionId);
        if (action.blocksPlaced >= MAX_RECORDED_BLOCKS_PLACED) {
            throw new IllegalStateException("Action block-place record limit exceeded");
        }
        action.blocksPlaced++;
    }

    public synchronized void setPhase(UUID actionId, Phase phase, String detail) {
        Mutable action = running(actionId);
        action.phase = Objects.requireNonNull(phase, "phase");
        action.trace(action.ticks, phase.name(), bounded(detail, 256));
    }

    public synchronized void succeed(UUID actionId) {
        finish(running(actionId), State.SUCCEEDED, null);
    }

    public synchronized void fail(UUID actionId, Failure failure) {
        finish(running(actionId), State.FAILED, Objects.requireNonNull(failure, "failure"));
    }

    public synchronized boolean terminateActive(Failure failure) {
        Objects.requireNonNull(failure, "failure");
        if (latest == null || latest.state.terminal()) {
            return false;
        }
        finish(latest, State.FAILED, failure);
        return true;
    }

    public synchronized void clear() {
        latest = null;
        previousTerminal = null;
        notifyAll();
    }

    private void finish(Mutable action, State terminal, Failure terminalFailure) {
        action.finish(terminal, terminalFailure);
        notifyAll();
    }

    private Mutable current(UUID actionId) {
        Objects.requireNonNull(actionId, "actionId");
        if (latest == null || !latest.id.equals(actionId)) {
            throw new NotFoundException();
        }
        return latest;
    }

    private Mutable running(UUID actionId) {
        Mutable action = current(actionId);
        if (action.state != State.RUNNING) {
            throw new IllegalStateException("Action is not running");
        }
        return action;
    }

    private static double boundedAdd(
            double current, double increment, double maximum, String name) {
        if (!Double.isFinite(increment) || increment < 0) {
            throw new IllegalArgumentException(name + " increment must be finite and non-negative");
        }
        return increment >= maximum - current ? maximum : current + increment;
    }

    private static boolean exceedsBound(
            double current, double increment, double maximum, String name) {
        if (!Double.isFinite(increment) || increment < 0) {
            throw new IllegalArgumentException(name + " increment must be finite and non-negative");
        }
        return increment > maximum - current;
    }

    private static String requireNodeId(String nodeId) {
        Objects.requireNonNull(nodeId, "nodeId");
        if (!nodeId.matches("[a-z][a-z0-9_-]{0,31}")) {
            throw new IllegalArgumentException("Invalid node ID");
        }
        return nodeId;
    }

    private static String bounded(String value, int limit) {
        Objects.requireNonNull(value, "value");
        return value.substring(0, Math.min(value.length(), limit));
    }

    public enum State {
        UNCONFIRMED, QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED;

        public boolean terminal() {
            return this == SUCCEEDED || this == FAILED || this == CANCELLED;
        }

        public String wireName() {
            return this == UNCONFIRMED
                    ? "queued"
                    : name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public enum Confirmation {
        CONFIRMED,
        ALREADY_CONFIRMED,
        EXPIRED,
        STALE;

        public boolean confirmed() {
            return this == CONFIRMED || this == ALREADY_CONFIRMED;
        }
    }

    public enum Phase {
        QUEUED, EXECUTING, REPLANNING, RECOVERING, FINISHED;

        public String wireName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public enum FailureCode {
        CANCELLED_BY_CLIENT,
        USER_DISABLED,
        EMERGENCY_STOP,
        BUDGET_EXCEEDED,
        PATH_BLOCKED,
        WORLD_CHANGED,
        SAFETY_RECOVERED,
        RECOVERY_EXHAUSTED,
        SERVER_DENIED_OR_DESYNC,
        CONDITION_TIMEOUT,
        PREDICATE_UNAVAILABLE,
        CAPABILITY_DENIED,
        DELIVERY_UNCONFIRMED,
        INTERNAL_ERROR;

        public String wireName() {
            return name();
        }
    }

    public record Accepted(UUID actionId, Instant acceptedAt) {
        public Accepted {
            Objects.requireNonNull(actionId, "actionId");
            Objects.requireNonNull(acceptedAt, "acceptedAt");
        }
    }

    public record Active(UUID actionId, CompiledProgram program, State state) {
        public Active {
            Objects.requireNonNull(actionId, "actionId");
            Objects.requireNonNull(program, "program");
            Objects.requireNonNull(state, "state");
        }
    }

    public record Summary(UUID actionId, State state, String endReason) {
    }

    public record CancelResult(UUID actionId, boolean cancelRequested, State stateAtRequest) {
    }

    public record Progress(
            Phase phase,
            String currentNodeId,
            int executedNodes,
            int totalNodeUpperBound,
            double distanceTravelled,
            double cameraDegrees,
            int interactions,
            int blocksBroken,
            int blocksPlaced,
            int ticks,
            boolean motionOverflowed) {
    }

    public record Failure(FailureCode code, boolean recoverable, List<String> evidence) {
        public Failure {
            Objects.requireNonNull(code, "code");
            evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
            if (evidence.size() > 16) {
                throw new IllegalArgumentException("Failure evidence exceeds 16 entries");
            }
            if (evidence.stream().anyMatch(value -> value == null || value.length() > 128)) {
                throw new IllegalArgumentException("Invalid failure evidence");
            }
        }
    }

    public record Trace(int tick, String event, String detail) {
        public Trace {
            if (tick < 0 || tick > MAX_RECORDED_TICKS || event == null
                    || !event.matches("[A-Z0-9_]+") || event.length() > 64
                    || detail == null || detail.length() > 256) {
                throw new IllegalArgumentException("Invalid action trace entry");
            }
        }
    }

    public record Snapshot(
            UUID actionId,
            State state,
            Progress progress,
            Failure failure,
            List<Trace> trace) {
        public Snapshot {
            Objects.requireNonNull(actionId, "actionId");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(progress, "progress");
            trace = List.copyOf(trace);
        }
    }

    public static final class BusyException extends IllegalStateException {
        public BusyException() {
            super("An action is already queued or running");
        }
    }

    public static final class NotFoundException extends IllegalArgumentException {
        public NotFoundException() {
            super("Action is not retained");
        }
    }

    private static final class Mutable {
        private final UUID id;
        private final CompiledProgram program;
        private final long confirmationDeadlineNanos;
        private final ArrayDeque<Trace> trace = new ArrayDeque<>(TRACE_LIMIT);
        private State state = State.UNCONFIRMED;
        private Phase phase = Phase.QUEUED;
        private String currentNodeId;
        private int executedNodes;
        private double distanceTravelled;
        private double cameraDegrees;
        private int interactions;
        private int blocksBroken;
        private int blocksPlaced;
        private int ticks;
        private boolean motionOverflowed;
        private Failure failure;
        private String endReason;

        private Mutable(UUID id, CompiledProgram program, long confirmationDeadlineNanos) {
            this.id = id;
            this.program = program;
            this.confirmationDeadlineNanos = confirmationDeadlineNanos;
        }

        private void finish(State terminal, Failure terminalFailure) {
            if (!terminal.terminal() || state.terminal()) {
                throw new IllegalStateException("Invalid terminal action transition");
            }
            state = terminal;
            phase = Phase.FINISHED;
            currentNodeId = null;
            failure = terminalFailure;
            endReason = terminal == State.SUCCEEDED
                    ? "COMPLETED"
                    : Objects.requireNonNull(terminalFailure, "terminalFailure").code().wireName();
            trace(ticks, terminal.name(), terminal.name().toLowerCase(java.util.Locale.ROOT));
        }

        private void trace(int tick, String event, String detail) {
            if (trace.size() == TRACE_LIMIT) {
                trace.removeFirst();
            }
            trace.addLast(new Trace(tick, event, detail));
        }

        private Snapshot snapshot() {
            var progress = new Progress(
                    phase,
                    currentNodeId,
                    executedNodes,
                    program.executedNodesUpperBound(),
                    distanceTravelled,
                    cameraDegrees,
                    interactions,
                    blocksBroken,
                    blocksPlaced,
                    ticks,
                    motionOverflowed);
            return new Snapshot(id, state, progress, failure, new ArrayList<>(trace));
        }
    }
}
