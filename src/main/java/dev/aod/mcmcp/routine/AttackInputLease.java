package dev.aod.mcmcp.routine;

import dev.aod.mcmcp.client.AgentInputState;
import net.minecraft.client.Minecraft;

import java.time.Duration;
import java.util.Objects;

/**
 * Renewable, client-thread-only ownership of the dedicated block-breaking action channel.
 *
 * <p>The deadline can never be more than two seconds ahead. A routine renews and maintains the
 * lease every tick; expiry, an input failure, or {@link #close()} releases both the agent channel
 * and the current block-destroy action. Use try-with-resources around all setup which can throw.</p>
 */
public final class AttackInputLease implements AutoCloseable {
    public static final Duration MAX_HORIZON = Duration.ofSeconds(2);
    private static final long MAX_HORIZON_NANOS = MAX_HORIZON.toNanos();

    private final AttackControl control;
    private long deadlineNanos;
    private boolean active;
    private boolean releaseComplete;

    private AttackInputLease(AttackControl control, long nowNanos, Duration horizon) {
        this.control = Objects.requireNonNull(control, "control");
        deadlineNanos = deadline(control.watchdogTime(nowNanos), validatedNanos(horizon));
        active = true;
        try {
            control.press();
        } catch (RuntimeException | LinkageError failure) {
            active = false;
            try {
                control.release();
            } catch (RuntimeException | LinkageError releaseFailure) {
                failure.addSuppressed(releaseFailure);
            }
            throw failure;
        }
    }

    public static AttackInputLease acquire(Minecraft minecraft, long nowNanos, Duration horizon) {
        return acquire(new VanillaAttackControl(minecraft), nowNanos, horizon);
    }

    /** Public for deterministic tests and alternative normal-input adapters. */
    public static AttackInputLease acquire(AttackControl control, long nowNanos, Duration horizon) {
        return new AttackInputLease(control, nowNanos, horizon);
    }

    /**
     * Extends the watchdog horizon without releasing mining progress. An already expired lease
     * is closed and cannot be resurrected.
     */
    public boolean renew(long nowNanos, Duration horizon) {
        requireActive();
        long extension = validatedNanos(horizon);
        long watchdogNow = control.watchdogTime(nowNanos);
        if (watchdogNow >= deadlineNanos) {
            close();
            return false;
        }
        deadlineNanos = deadline(watchdogNow, extension);
        return true;
    }

    /** Atomically performs the per-tick renewal and key reassertion expected by routines. */
    public boolean heartbeat(long nowNanos, Duration horizon) {
        if (!renew(nowNanos, horizon)) {
            return false;
        }
        return maintain(nowNanos);
    }

    /** Reasserts the vanilla key for this tick, or releases it if its watchdog expired. */
    public boolean maintain(long nowNanos) {
        if (!active) {
            return false;
        }
        if (control.watchdogTime(nowNanos) >= deadlineNanos) {
            close();
            return false;
        }
        try {
            control.press();
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

    public long deadlineNanos() {
        return deadlineNanos;
    }

    @Override
    public void close() {
        if (!active && releaseComplete) {
            return;
        }
        active = false;
        control.release();
        releaseComplete = true;
    }

    private void requireActive() {
        if (!active) {
            throw new IllegalStateException("attack input lease is not active");
        }
    }

    private static long validatedNanos(Duration horizon) {
        Objects.requireNonNull(horizon, "horizon");
        long nanos;
        try {
            nanos = horizon.toNanos();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("attack lease horizon is out of range", failure);
        }
        if (nanos <= 0 || nanos > MAX_HORIZON_NANOS) {
            throw new IllegalArgumentException("attack lease horizon must be within (0, 2 seconds]");
        }
        return nanos;
    }

    private static long deadline(long nowNanos, long horizonNanos) {
        try {
            return Math.addExact(nowNanos, horizonNanos);
        } catch (ArithmeticException failure) {
            return Long.MAX_VALUE;
        }
    }

    public interface AttackControl {
        void press();

        void release();

        default long watchdogTime(long nowNanos) {
            return nowNanos;
        }
    }

    private static final class VanillaAttackControl implements AttackControl {
        private final Minecraft minecraft;
        private final AgentInputState inputState = AgentInputState.global();

        private VanillaAttackControl(Minecraft minecraft) {
            this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
        }

        @Override
        public void press() {
            assertClientThread();
            inputState.publishAttack();
        }

        @Override
        public void release() {
            assertClientThread();
            inputState.releaseAttack();
            RuntimeException runtimeFailure = null;
            LinkageError linkageFailure = null;
            if (minecraft.gameMode != null) {
                try {
                    minecraft.gameMode.stopDestroyBlock();
                } catch (RuntimeException failure) {
                    if (runtimeFailure != null) {
                        runtimeFailure.addSuppressed(failure);
                    } else if (linkageFailure != null) {
                        linkageFailure.addSuppressed(failure);
                    } else {
                        runtimeFailure = failure;
                    }
                } catch (LinkageError failure) {
                    if (runtimeFailure != null) {
                        runtimeFailure.addSuppressed(failure);
                    } else if (linkageFailure != null) {
                        linkageFailure.addSuppressed(failure);
                    } else {
                        linkageFailure = failure;
                    }
                }
            }
            if (runtimeFailure != null) {
                throw runtimeFailure;
            }
            if (linkageFailure != null) {
                throw linkageFailure;
            }
        }

        @Override
        public long watchdogTime(long nowNanos) {
            return inputState.watchdogTime(nowNanos);
        }

        private void assertClientThread() {
            if (!minecraft.isSameThread()) {
                throw new IllegalStateException("attack input lease must run on the Minecraft client thread");
            }
        }
    }
}
