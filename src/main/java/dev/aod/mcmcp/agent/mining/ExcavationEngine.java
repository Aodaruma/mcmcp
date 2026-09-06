package dev.aod.mcmcp.agent.mining;

import dev.aod.mcmcp.agent.action.KnownConstructionAttempt;
import dev.aod.mcmcp.agent.action.AgentActionStore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static dev.aod.mcmcp.agent.mining.ExcavationPort.*;

/**
 * A finite route cursor: fresh head/feet proof, one authoritative break, fresh proof,
 * then one safe movement. It never authorizes or dispatches an unknown operation twice.
 * Break confirmation is deliberately separate from item pickup evidence.
 */
public final class ExcavationEngine implements AutoCloseable {
    private final TunnelGeometry.Plan plan;
    private final ExcavationPort port;
    private final Limits limits;
    private final Set<TunnelGeometry.Cell> visited = new HashSet<>();
    private final Set<TunnelGeometry.Cell> broken = new HashSet<>();
    private final Set<String> unknownEffects = new HashSet<>();
    private final Map<String, TunnelGeometry.Cell> dispatchedBlocks = new HashMap<>();
    private final ArrayList<KnownConstructionAttempt.EffectDelta> pendingEffects = new ArrayList<>();
    private TunnelGeometry.Cell feet;
    private TunnelGeometry.Cell breaking;
    private int routeIndex;
    private int breakDispatches;
    private int confirmedBreaks;
    private int pendingBrokenDelta;
    private long minObservationTick;
    private long minObservationRevision;
    private long observationWaitStart = -1;
    private long lastTick;
    private long lastNanos;
    private long lastAdvancedTick = -1;
    private Phase phase = Phase.HEAD;
    private Status terminalIntent;
    private Status status = Status.RUNNING;
    private StopReason reason = StopReason.NONE;

    public ExcavationEngine(TunnelGeometry.Plan plan, ExcavationPort port, Limits limits) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.port = Objects.requireNonNull(port, "port");
        this.limits = Objects.requireNonNull(limits, "limits");
        if (limits.maxBreaks() > plan.maxBreaks()) throw new IllegalArgumentException("break limit exceeds footprint");
        feet = plan.startFeet();
        lastTick = limits.startTick();
        lastNanos = limits.startNanos();
        minObservationTick = lastTick;
    }

    public TickResult tick(long clientTick, long nowNanos) {
        if (clientTick < lastTick || nowNanos < lastNanos) {
            throw new IllegalArgumentException("excavation clock moved backwards");
        }
        lastTick = clientTick;
        lastNanos = nowNanos;
        if (status != Status.RUNNING) return result();
        try {
            if (terminalIntent == null) {
                if (clientTick >= limits.hardDeadlineTick() || nowNanos >= limits.hardDeadlineNanos()) {
                    stop(Status.FAILED, StopReason.DEADLINE);
                } else {
                    StopReason unsafe = Objects.requireNonNull(port.safety(), "safety");
                    if (unsafe != StopReason.NONE) stop(Status.FAILED, unsafe);
                }
            }
            if (terminalIntent != null) {
                release();
                return result();
            }
            // Reentrant control delivery may occur within one client tick. Safety and
            // cancellation still run, but it cannot advance another gameplay boundary.
            if (clientTick == lastAdvancedTick) return result();
            lastAdvancedTick = clientTick;
            switch (phase) {
                case HEAD -> inspect(nextFeet().head(), Phase.FEET, clientTick);
                case FEET -> inspect(nextFeet(), Phase.MOVE_READY, clientTick);
                case BREAKING_HEAD, BREAKING_FEET -> tickBreak(clientTick);
                case MOVE_READY -> prepareMove(clientTick);
                case MOVING -> tickMove(clientTick);
                case RELEASING, TERMINAL -> throw new IllegalStateException("invalid excavation phase");
            }
        } catch (RuntimeException | LinkageError failure) {
            // Do not expose adapter exception text or retry a possibly dispatched mutation.
            stop(Status.FAILED, StopReason.ADAPTER_FAILURE);
        }
        if (terminalIntent != null) release();
        return result();
    }

    private void inspect(TunnelGeometry.Cell cell, Phase next, long tick) {
        if (!plan.containsBlock(cell)) throw new IllegalStateException("target escaped excavation footprint");
        BlockInspection observation = Objects.requireNonNull(
                port.inspectBlock(cell, minObservationTick, minObservationRevision), "block inspection");
        if (observation.status() == BlockStatus.STOP) {
            stop(Status.FAILED, observation.reason());
            return;
        }
        if (observation.status() == BlockStatus.WAIT
                || observation.observedTick() != tick
                || !afterBoundary(observation.observedTick(), observation.worldRevision(), tick)) {
            waitForObservation(tick);
            return;
        }
        observationWaitStart = -1;
        if (observation.status() == BlockStatus.CLEAR) {
            phase = next;
            return;
        }
        Witness witness = observation.witness();
        if (!cell.equals(witness.cell())) throw new IllegalStateException("witness target changed");
        // A changed return corridor is an interruption, never permission to mine it again.
        if (broken.contains(cell) || visited.contains(nextFeet())) {
            stop(Status.FAILED, StopReason.TARGET_CHANGED);
            return;
        }
        if (breakDispatches >= limits.maxBreaks()) {
            stop(Status.FAILED, StopReason.BUDGET);
            return;
        }
        breaking = cell;
        phase = next == Phase.FEET ? Phase.BREAKING_HEAD : Phase.BREAKING_FEET;
        breakDispatches++;
        dispatchedBlocks.put(subject(cell), cell);
        // Phase and charge are committed before crossing the potentially mutating boundary.
        port.beginBreak(witness);
    }

    private void tickBreak(long tick) {
        OperationResult operation = Objects.requireNonNull(port.pollBreak(), "break result");
        record(operation, tick);
        switch (operation.status()) {
            case RUNNING -> { }
            case FAILED -> stop(Status.FAILED, StopReason.SERVER_DENIED);
            case UNKNOWN -> stop(Status.FAILED, StopReason.UNKNOWN_EFFECT);
            case SUCCEEDED -> {
                if (!broken.contains(breaking)) throw new IllegalStateException("break success lacks confirmed evidence");
                minObservationTick = Math.max(minObservationTick, operation.clientTick());
                minObservationRevision = Math.max(minObservationRevision, operation.worldRevision());
                phase = phase == Phase.BREAKING_HEAD ? Phase.HEAD : Phase.FEET;
                breaking = null;
                observationWaitStart = -1;
            }
        }
    }

    private void prepareMove(long tick) {
        TunnelGeometry.Cell to = nextFeet();
        if (!feet.adjacent(to)) throw new IllegalStateException("movement escaped finite route");
        MoveInspection movement = Objects.requireNonNull(
                port.inspectMove(feet, to, minObservationTick, minObservationRevision), "movement inspection");
        if (movement.status() == Readiness.STOP) {
            stop(Status.FAILED, movement.reason());
            return;
        }
        if (movement.status() == Readiness.WAIT
                || movement.observedTick() != tick
                || !afterBoundary(movement.observedTick(), movement.worldRevision(), tick)) {
            waitForObservation(tick);
            return;
        }
        observationWaitStart = -1;
        phase = Phase.MOVING;
        port.beginMove(to);
    }

    private void tickMove(long tick) {
        OperationResult operation = Objects.requireNonNull(port.pollMove(), "movement result");
        record(operation, tick);
        switch (operation.status()) {
            case RUNNING -> { }
            case FAILED -> stop(Status.FAILED, StopReason.UNSAFE_MOVEMENT);
            case UNKNOWN -> stop(Status.FAILED, StopReason.UNKNOWN_EFFECT);
            case SUCCEEDED -> {
                feet = nextFeet();
                visited.add(feet);
                routeIndex++;
                minObservationTick = Math.max(minObservationTick, operation.clientTick());
                minObservationRevision = Math.max(minObservationRevision, operation.worldRevision());
                observationWaitStart = -1;
                if (routeIndex == plan.route().size()) stop(Status.SUCCEEDED, StopReason.NONE);
                else phase = Phase.HEAD;
            }
        }
    }

    private boolean afterBoundary(long observedTick, long revision, long tick) {
        return observedTick >= minObservationTick && observedTick <= tick && revision >= minObservationRevision;
    }

    private void record(OperationResult operation, long tick) {
        recordEffects(operation.effects());
        if (operation.clientTick() > tick
                || operation.status() == OperationStatus.SUCCEEDED
                        && !afterBoundary(operation.clientTick(), operation.worldRevision(), tick)) {
            throw new IllegalStateException("invalid operation evidence clock");
        }
    }

    private void recordEffects(List<KnownConstructionAttempt.EffectDelta> effects) {
        for (var effect : effects) {
            TunnelGeometry.Cell cell = dispatchedBlocks.get(effect.subject());
            if (!"block_break".equals(effect.kind()) || cell == null) {
                throw new IllegalStateException("effect is outside dispatched excavation scope");
            }
            if (effect.verification() == AgentActionStore.Verification.CONFIRMED) {
                if (!broken.add(cell)) continue;
                confirmedBreaks++;
                pendingBrokenDelta++;
            } else if (effect.verification() == AgentActionStore.Verification.UNKNOWN
                    && !unknownEffects.add(effect.subject())) {
                continue;
            }
            pendingEffects.add(effect);
        }
    }

    private static String subject(TunnelGeometry.Cell cell) {
        return "block:" + cell.dimension() + ":" + cell.x() + "," + cell.y() + "," + cell.z();
    }

    private void waitForObservation(long tick) {
        if (observationWaitStart < 0) observationWaitStart = tick;
        if (tick - observationWaitStart >= limits.observationWaitTicks()) {
            stop(Status.FAILED, StopReason.OBSERVATION_TIMEOUT);
        }
    }

    private TunnelGeometry.Cell nextFeet() { return plan.route().get(routeIndex); }

    public void cancel() { stop(Status.CANCELLED, StopReason.CANCELLED); }

    private void stop(Status intent, StopReason failure) {
        if (terminalIntent != null || status != Status.RUNNING) return;
        terminalIntent = intent;
        reason = failure;
        phase = Phase.RELEASING;
    }

    private void release() {
        try {
            boolean released = port.release();
            recordEffects(port.drainEffects());
            if (released) {
                status = terminalIntent;
                phase = Phase.TERMINAL;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Keep the first terminal intent and the exact attempt. Outer cleanup can retry.
        }
    }

    public List<KnownConstructionAttempt.EffectDelta> drainEffects() {
        List<KnownConstructionAttempt.EffectDelta> result = List.copyOf(pendingEffects);
        pendingEffects.clear();
        return result;
    }

    /** Also drain this after close(), including when close is awaiting release confirmation. */
    public int drainBrokenDelta() {
        int result = pendingBrokenDelta;
        pendingBrokenDelta = 0;
        return result;
    }

    private TickResult result() {
        TickResult result = new TickResult(status, reason, phase, drainBrokenDelta(),
                confirmedBreaks, visited.size(), routeIndex, drainEffects());
        return result;
    }

    @Override public void close() {
        if (status != Status.RUNNING) return;
        cancel();
        release();
        if (status == Status.RUNNING) throw new IllegalStateException("excavation input release unconfirmed");
    }

    public enum Phase { HEAD, FEET, BREAKING_HEAD, BREAKING_FEET, MOVE_READY, MOVING, RELEASING, TERMINAL }
    public enum Status { RUNNING, SUCCEEDED, FAILED, CANCELLED }

    public record Limits(long startTick, long startNanos, long hardDeadlineTick,
            long hardDeadlineNanos, int maxBreaks, int observationWaitTicks) {
        public Limits {
            if (startTick < 0 || startNanos < 0 || hardDeadlineTick <= startTick
                    || hardDeadlineNanos <= startNanos || maxBreaks < 0
                    || observationWaitTicks < 1 || observationWaitTicks > 1200) {
                throw new IllegalArgumentException("invalid excavation limits");
            }
        }
    }

    public record TickResult(Status status, StopReason reason, Phase phase, int brokenDelta,
            int confirmedBreaks, int completedCells, int completedMoves,
            List<KnownConstructionAttempt.EffectDelta> effects) {
        public TickResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(phase, "phase");
            effects = List.copyOf(effects);
        }
    }
}
