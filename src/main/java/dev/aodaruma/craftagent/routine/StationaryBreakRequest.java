package dev.aodaruma.craftagent.routine;

import java.util.Objects;

/** Immutable, equality-stable arguments used directly by the idempotency registry. */
public record StationaryBreakRequest(
        BlockTarget target,
        BlockStateFingerprint expectedSourceState,
        StationaryBreakGoal goal,
        long hardDeadlineClientTick,
        int attackLeaseTicks,
        int regenerationWaitTicks) {
    public static final int MAX_ATTACK_LEASE_TICKS = 40;
    public static final int MAX_DURATION_TICKS = 6_000;

    public StationaryBreakRequest {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(expectedSourceState, "expectedSourceState");
        Objects.requireNonNull(goal, "goal");
        if (hardDeadlineClientTick < 1) {
            throw new IllegalArgumentException("hard deadline must be positive");
        }
        if (attackLeaseTicks < 1 || attackLeaseTicks > MAX_ATTACK_LEASE_TICKS) {
            throw new IllegalArgumentException("attack lease must be in 1..40 ticks");
        }
        if (regenerationWaitTicks < 1 || regenerationWaitTicks > 200) {
            throw new IllegalArgumentException("regeneration wait must be in 1..200 ticks");
        }
    }

    public void validateAdmissionTick(long admittedClientTick) {
        if (admittedClientTick < 0
                || hardDeadlineClientTick <= admittedClientTick
                || hardDeadlineClientTick - admittedClientTick > MAX_DURATION_TICKS) {
            throw new IllegalArgumentException("deadline must be within 1..6000 future client ticks");
        }
    }
}
