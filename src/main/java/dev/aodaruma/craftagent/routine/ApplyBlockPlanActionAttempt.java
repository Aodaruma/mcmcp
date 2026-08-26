package dev.aodaruma.craftagent.routine;

import java.util.Objects;
import java.util.UUID;

/** Internal mutation attempt created only after preparation has live proof. */
public record ApplyBlockPlanActionAttempt(
        UUID attemptId,
        int stepIndex,
        long issuedClientTick,
        long issuedObservationRevision,
        long leaseExpiresAtClientTick) {
    public ApplyBlockPlanActionAttempt {
        Objects.requireNonNull(attemptId, "attemptId");
        if (stepIndex < 0 || issuedClientTick < 0 || issuedObservationRevision < 0
                || leaseExpiresAtClientTick <= issuedClientTick) {
            throw new IllegalArgumentException("invalid action attempt bounds");
        }
    }
}
