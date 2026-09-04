package dev.aod.mcmcp.routine;

import dev.aod.mcmcp.client.AgentInputState;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Short, renewable ownership for a finite set of normal player inputs.
 *
 * <p>The enclosing semantic Action owns the long duration. This lease never does: it must be
 * revalidated and renewed on every active client tick, and its two-second watchdog makes stale
 * output neutral if the runtime stops ticking. Closing is idempotent and releases every channel
 * acquired by this instance.</p>
 */
public final class BoundedInputLease implements AutoCloseable {
    public static final Duration MAX_HORIZON = Duration.ofSeconds(2);
    private static final long MAX_HORIZON_NANOS = MAX_HORIZON.toNanos();

    private final Control control;
    private final Set<Input> inputs;
    private long deadlineNanos;
    private boolean active;
    private boolean releaseComplete;

    private BoundedInputLease(
            Control control, Set<Input> inputs, long nowNanos, Duration horizon) {
        this.control = Objects.requireNonNull(control, "control");
        if (Objects.requireNonNull(inputs, "inputs").isEmpty()) {
            throw new IllegalArgumentException("bounded input lease requires at least one input");
        }
        this.inputs = Set.copyOf(inputs);
        deadlineNanos = deadline(control.watchdogTime(nowNanos), validatedNanos(horizon));
        active = true;
        try {
            control.publish(this.inputs, deadlineNanos);
        } catch (RuntimeException | LinkageError failure) {
            active = false;
            try {
                control.release(this.inputs);
            } catch (RuntimeException | LinkageError releaseFailure) {
                failure.addSuppressed(releaseFailure);
            }
            throw failure;
        }
    }

    public static BoundedInputLease acquire(
            AgentInputState inputState,
            Set<Input> inputs,
            long nowNanos,
            Duration horizon) {
        return acquire(new AgentInputControl(inputState), inputs, nowNanos, horizon);
    }

    /** Public for deterministic tests. */
    public static BoundedInputLease acquire(
            Control control, Set<Input> inputs, long nowNanos, Duration horizon) {
        return new BoundedInputLease(control, inputs, nowNanos, horizon);
    }

    /** Renews the short watchdog and reasserts the same immutable input set. */
    public boolean heartbeat(long nowNanos, Duration horizon) {
        requireActive();
        long watchdogNow = control.watchdogTime(nowNanos);
        if (watchdogNow >= deadlineNanos) {
            close();
            return false;
        }
        deadlineNanos = deadline(watchdogNow, validatedNanos(horizon));
        try {
            control.publish(inputs, deadlineNanos);
            return true;
        } catch (RuntimeException | LinkageError failure) {
            try {
                close();
            } catch (RuntimeException | LinkageError releaseFailure) {
                failure.addSuppressed(releaseFailure);
            }
            throw failure;
        }
    }

    public boolean active() {
        return active;
    }

    public Set<Input> inputs() {
        return inputs;
    }

    @Override
    public void close() {
        if (!active && releaseComplete) return;
        active = false;
        control.release(inputs);
        releaseComplete = true;
    }

    private void requireActive() {
        if (!active) throw new IllegalStateException("bounded input lease is not active");
    }

    private static long validatedNanos(Duration horizon) {
        Objects.requireNonNull(horizon, "horizon");
        final long nanos;
        try {
            nanos = horizon.toNanos();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("bounded input lease horizon is out of range", failure);
        }
        if (nanos <= 0L || nanos > MAX_HORIZON_NANOS) {
            throw new IllegalArgumentException(
                    "bounded input lease horizon must be within (0, 2 seconds]");
        }
        return nanos;
    }

    private static long deadline(long nowNanos, long horizonNanos) {
        try {
            return Math.addExact(nowNanos, horizonNanos);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    public enum Input { FORWARD, BACK, LEFT, RIGHT, JUMP, SNEAK, ATTACK, USE }

    public interface Control {
        void publish(Set<Input> inputs, long validUntilNanos);

        void release(Set<Input> inputs);

        default long watchdogTime(long nowNanos) {
            return nowNanos;
        }
    }

    private static final class AgentInputControl implements Control {
        private final AgentInputState state;

        private AgentInputControl(AgentInputState state) {
            this.state = Objects.requireNonNull(state, "state");
        }

        @Override
        public void publish(Set<Input> inputs, long validUntilNanos) {
            EnumSet<Input> set = EnumSet.copyOf(inputs);
            if (hasMovement(set)) {
                state.publishMovement(
                        set.contains(Input.FORWARD), set.contains(Input.BACK),
                        set.contains(Input.LEFT), set.contains(Input.RIGHT),
                        set.contains(Input.JUMP), set.contains(Input.SNEAK), validUntilNanos);
            }
            if (set.contains(Input.ATTACK)) state.publishAttack(validUntilNanos);
            if (set.contains(Input.USE)) state.publishUse(validUntilNanos);
        }

        @Override
        public void release(Set<Input> inputs) {
            if (hasMovement(inputs)) state.releaseMovement();
            if (inputs.contains(Input.ATTACK)) state.releaseAttack();
            if (inputs.contains(Input.USE)) state.releaseUse();
        }

        @Override
        public long watchdogTime(long nowNanos) {
            return state.watchdogTime(nowNanos);
        }

        private static boolean hasMovement(Set<Input> inputs) {
            return inputs.contains(Input.FORWARD) || inputs.contains(Input.BACK)
                    || inputs.contains(Input.LEFT) || inputs.contains(Input.RIGHT)
                    || inputs.contains(Input.JUMP) || inputs.contains(Input.SNEAK);
        }
    }
}
