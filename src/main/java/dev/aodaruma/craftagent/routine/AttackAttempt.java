package dev.aodaruma.craftagent.routine;

import java.util.Objects;

/** Adapter-owned attack lease and the vanilla prediction sequence it generated. */
public record AttackAttempt(long predictionSequence, BlockTarget target, long leaseExpiresAtClientTick) {
    public AttackAttempt {
        if (predictionSequence < 0 || leaseExpiresAtClientTick < 1) {
            throw new IllegalArgumentException("invalid attack attempt clocks");
        }
        Objects.requireNonNull(target, "target");
    }
}
