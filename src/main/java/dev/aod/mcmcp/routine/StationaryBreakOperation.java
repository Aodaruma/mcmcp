package dev.aod.mcmcp.routine;

import java.util.Objects;
import java.util.UUID;

/**
 * Finite Action-DSL adapter around the existing stationary-break state machine. It deliberately
 * exposes no raw input primitive: attack ownership remains inside {@link StationaryBreakRoutine}
 * and its {@link AttackInputLease}-backed port.
 */
public final class StationaryBreakOperation implements AutoCloseable {
    public static final int MAX_BREAKS = 64;
    public static final int MAX_DURATION_TICKS = 36_000;

    private static final int EVENT_CAPACITY = 64;

    private final StationaryBreakRoutine routine;
    private final int maxBreaks;
    private boolean closed;

    public StationaryBreakOperation(
            StationaryBreakPort port,
            StationaryBreakRequest request,
            int maxBreaks,
            long admittedClientTick) {
        Objects.requireNonNull(port, "port");
        Objects.requireNonNull(request, "request");
        if (maxBreaks < 1 || maxBreaks > MAX_BREAKS) {
            throw new IllegalArgumentException("maxBreaks must be in 1..64");
        }
        long duration = request.hardDeadlineClientTick() - admittedClientTick;
        if (admittedClientTick < 0L || duration < 1L || duration > MAX_DURATION_TICKS) {
            throw new IllegalArgumentException(
                    "deadline must be within 1..36000 future client ticks");
        }
        this.maxBreaks = maxBreaks;
        routine = new StationaryBreakRoutine(
                UUID.randomUUID(), request, port, EVENT_CAPACITY, admittedClientTick);
    }

    public TickResult tick() {
        requireOpen();
        RoutineSnapshot before = snapshot();
        if (!before.state().terminal() && before.checkpoint().seq() >= maxBreaks) {
            routine.cancel("max_breaks_reached");
            return new TickResult(Status.MAX_BREAKS_REACHED, snapshot());
        }
        routine.tick();
        if (routine.state() == RoutineState.FINALIZING) {
            routine.completeFinalization(null);
        }
        RoutineSnapshot after = snapshot();
        if (!after.state().terminal() && after.checkpoint().seq() >= maxBreaks) {
            routine.cancel("max_breaks_reached");
            return new TickResult(Status.MAX_BREAKS_REACHED, snapshot());
        }
        Status status = switch (after.state()) {
            case SUCCEEDED -> Status.SUCCEEDED;
            case FAILED, CANCELLED -> Status.FAILED;
            default -> Status.RUNNING;
        };
        return new TickResult(status, after);
    }

    public RoutineSnapshot snapshot() {
        return routine.snapshot(Long.MAX_VALUE, 1);
    }

    @Override
    public void close() {
        if (closed) return;
        if (!routine.state().terminal()) {
            routine.cancel("action_closed");
        }
        routine.retire();
        closed = true;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("stationary break operation is closed");
    }

    public record TickResult(Status status, RoutineSnapshot snapshot) {
        public TickResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    public enum Status { RUNNING, SUCCEEDED, MAX_BREAKS_REACHED, FAILED }
}
