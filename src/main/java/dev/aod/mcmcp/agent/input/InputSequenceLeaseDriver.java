package dev.aod.mcmcp.agent.input;

import dev.aod.mcmcp.client.AgentInputState;
import dev.aod.mcmcp.routine.BoundedInputLease;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/** Client-tick adapter for a finite sequence. The runtime must check world/screen safety first. */
public final class InputSequenceLeaseDriver implements AutoCloseable {
    private static final Duration LEASE_HORIZON = Duration.ofSeconds(1);

    private final int totalTicks;
    private final FiniteInputSequence.Cursor cursor;
    private final LeaseFactory factory;
    private BoundedInputLease lease;
    private Set<BoundedInputLease.Input> held = Set.of();
    private long lastClientTick = -1;
    private boolean closed;

    public InputSequenceLeaseDriver(FiniteInputSequence sequence, AgentInputState inputState) {
        this(sequence, minecraftFactory(inputState));
    }

    InputSequenceLeaseDriver(FiniteInputSequence sequence, LeaseFactory factory) {
        totalTicks = Objects.requireNonNull(sequence, "sequence").totalTicks();
        cursor = sequence.cursor();
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    public int totalTicks() {
        return totalTicks;
    }

    public FiniteInputSequence.Frame tick(long clientTick, long nowNanos, boolean stopConditionMet,
                                          boolean cancelled) {
        if (closed) {
            release();
            return cursor.next(false, true);
        }
        if (clientTick < 0 || clientTick <= lastClientTick) {
            var failure = new IllegalArgumentException("input sequence requires a new client tick");
            try {
                close();
            } catch (RuntimeException | LinkageError releaseFailure) {
                failure.addSuppressed(releaseFailure);
            }
            throw failure;
        }
        lastClientTick = clientTick;
        var frame = cursor.next(stopConditionMet, cancelled);
        try {
            if (!frame.inputs().equals(held)) {
                release();
                if (!frame.inputs().isEmpty()) {
                    lease = Objects.requireNonNull(factory.acquire(frame.inputs(), nowNanos), "lease");
                    held = frame.inputs();
                }
            } else if (lease != null && !lease.heartbeat(nowNanos, LEASE_HORIZON)) {
                throw new IllegalStateException("input sequence lease expired");
            }
            return frame;
        } catch (RuntimeException | LinkageError failure) {
            closed = true;
            cursor.next(false, true);
            try {
                release();
            } catch (RuntimeException | LinkageError releaseFailure) {
                failure.addSuppressed(releaseFailure);
            }
            throw failure;
        }
    }

    @Override
    public void close() {
        closed = true;
        cursor.next(false, true);
        release();
    }

    /** A bounded refill wait preserves the sequence cursor but owns no buttons. */
    public void suspend() { release(); }

    private void release() {
        if (lease != null) {
            lease.close();
            lease = null;
            held = Set.of();
        }
    }

    private static LeaseFactory minecraftFactory(AgentInputState inputState) {
        Objects.requireNonNull(inputState, "inputState");
        return (inputs, nowNanos) -> BoundedInputLease.acquire(
                inputState, inputs, nowNanos, LEASE_HORIZON);
    }

    @FunctionalInterface
    interface LeaseFactory {
        BoundedInputLease acquire(Set<BoundedInputLease.Input> inputs, long nowNanos);
    }
}
