package dev.aod.mcmcp.routine;

import dev.aod.mcmcp.client.AgentInputState;
import net.minecraft.client.Minecraft;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Renewable, owner-token-bound control of the five agent movement inputs used by navigation. */
public final class MovementInputLease implements AutoCloseable {
    public static final Duration MAX_HORIZON = Duration.ofSeconds(2);
    private static final long MAX_HORIZON_NANOS = MAX_HORIZON.toNanos();

    private final MovementControl control;
    private final UUID ownerId;
    private Set<MovementKey> desired = Set.of();
    private long deadlineNanos;
    private boolean active;
    private boolean releaseComplete;

    private MovementInputLease(
            MovementControl control, UUID ownerId, long nowNanos, Duration horizon) {
        this.control = Objects.requireNonNull(control, "control");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        deadlineNanos = deadline(control.watchdogTime(nowNanos), validatedNanos(horizon));
        active = true;
        try {
            control.apply(Set.of(), deadlineNanos);
        } catch (RuntimeException | LinkageError failure) {
            active = false;
            try {
                control.release();
                releaseComplete = true;
            } catch (RuntimeException | LinkageError releaseFailure) {
                failure.addSuppressed(releaseFailure);
            }
            throw failure;
        }
    }

    public static MovementInputLease acquire(
            Minecraft minecraft, UUID ownerId, long nowNanos, Duration horizon) {
        return acquire(new VanillaMovementControl(minecraft), ownerId, nowNanos, horizon);
    }

    /** Public for deterministic tests and future normal-input adapters. */
    public static MovementInputLease acquire(
            MovementControl control, UUID ownerId, long nowNanos, Duration horizon) {
        return new MovementInputLease(control, ownerId, nowNanos, horizon);
    }

    public void setDesired(UUID owner, Set<MovementKey> keys) {
        requireOwner(owner);
        requireActive();
        Objects.requireNonNull(keys, "keys");
        var copy = keys.isEmpty()
                ? EnumSet.noneOf(MovementKey.class)
                : EnumSet.copyOf(keys);
        if (copy.contains(MovementKey.FORWARD) && copy.contains(MovementKey.BACK)
                || copy.contains(MovementKey.LEFT) && copy.contains(MovementKey.RIGHT)) {
            throw new IllegalArgumentException("opposed movement keys cannot be owned together");
        }
        desired = Set.copyOf(copy);
    }

    /** Renews the watchdog and reasserts only this lease's desired vanilla keys. */
    public boolean heartbeat(UUID owner, long nowNanos, Duration horizon) {
        requireOwner(owner);
        requireActive();
        long extension = validatedNanos(horizon);
        long watchdogNow = control.watchdogTime(nowNanos);
        if (deadlineReached(deadlineNanos, watchdogNow)) {
            close(owner);
            return false;
        }
        deadlineNanos = deadline(watchdogNow, extension);
        try {
            control.apply(desired, deadlineNanos);
            return true;
        } catch (RuntimeException | LinkageError failure) {
            try {
                close(owner);
            } catch (RuntimeException | LinkageError releaseFailure) {
                failure.addSuppressed(releaseFailure);
            }
            throw failure;
        }
    }

    /** Reasserts current keys without extending the watchdog. */
    public boolean maintain(UUID owner, long nowNanos) {
        requireOwner(owner);
        if (!active) {
            return false;
        }
        if (deadlineReached(deadlineNanos, control.watchdogTime(nowNanos))) {
            close(owner);
            return false;
        }
        try {
            control.apply(desired, deadlineNanos);
            return true;
        } catch (RuntimeException | LinkageError failure) {
            try {
                close(owner);
            } catch (RuntimeException | LinkageError releaseFailure) {
                failure.addSuppressed(releaseFailure);
            }
            throw failure;
        }
    }

    public void close(UUID owner) {
        requireOwner(owner);
        if (!active && releaseComplete) {
            return;
        }
        active = false;
        control.release();
        releaseComplete = true;
        desired = Set.of();
    }

    /** Fail-safe capability close for try-with-resources; owner-token checks still guard driving. */
    @Override
    public void close() {
        close(ownerId);
    }

    public UUID ownerId() {
        return ownerId;
    }

    public boolean active() {
        return active;
    }

    public long deadlineNanos() {
        return deadlineNanos;
    }

    private void requireOwner(UUID candidate) {
        if (!ownerId.equals(Objects.requireNonNull(candidate, "owner"))) {
            throw new SecurityException("movement lease owner mismatch");
        }
    }

    private void requireActive() {
        if (!active) {
            throw new IllegalStateException("movement input lease is not active");
        }
    }

    private static long validatedNanos(Duration horizon) {
        Objects.requireNonNull(horizon, "horizon");
        final long nanos;
        try {
            nanos = horizon.toNanos();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("movement lease horizon is out of range", failure);
        }
        if (nanos <= 0 || nanos > MAX_HORIZON_NANOS) {
            throw new IllegalArgumentException("movement lease horizon must be within (0, 2 seconds]");
        }
        return nanos;
    }

    private static long deadline(long nowNanos, long horizonNanos) {
        return nowNanos + horizonNanos;
    }

    private static boolean deadlineReached(long deadlineNanos, long nowNanos) {
        return nowNanos - deadlineNanos >= 0L;
    }

    public enum MovementKey {
        FORWARD,
        BACK,
        LEFT,
        RIGHT,
        JUMP
    }

    public interface MovementControl {
        void apply(Set<MovementKey> keys);

        default void apply(Set<MovementKey> keys, long validUntilNanos) {
            apply(keys);
        }

        void release();

        default long watchdogTime(long nowNanos) {
            return nowNanos;
        }
    }

    private static final class VanillaMovementControl implements MovementControl {
        private final Minecraft minecraft;
        private final AgentInputState inputState = AgentInputState.global();

        private VanillaMovementControl(Minecraft minecraft) {
            this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
        }

        @Override
        public void apply(Set<MovementKey> keys) {
            long now = inputState.watchdogTime(System.nanoTime());
            apply(keys, deadline(now, MAX_HORIZON_NANOS));
        }

        @Override
        public void apply(Set<MovementKey> keys, long validUntilNanos) {
            assertClientThread();
            inputState.publishMovement(
                    keys.contains(MovementKey.FORWARD),
                    keys.contains(MovementKey.BACK),
                    keys.contains(MovementKey.LEFT),
                    keys.contains(MovementKey.RIGHT),
                    keys.contains(MovementKey.JUMP),
                    validUntilNanos);
        }

        @Override
        public void release() {
            assertClientThread();
            inputState.releaseMovement();
        }

        @Override
        public long watchdogTime(long nowNanos) {
            return inputState.watchdogTime(nowNanos);
        }

        private void assertClientThread() {
            if (!minecraft.isSameThread()) {
                throw new IllegalStateException("movement input lease must run on the Minecraft client thread");
            }
        }
    }
}
