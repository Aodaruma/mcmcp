package dev.aodaruma.craftagent.routine;

import java.util.Objects;
import java.util.UUID;

/** Bounded ownership of aim and hotbar selection from an already-safe current stand. */
public record ApplyBlockPlanPreparationAttempt(
        UUID attemptId,
        int stepIndex,
        long issuedClientTick,
        long issuedObservationRevision,
        long leaseExpiresAtClientTick) {
    public ApplyBlockPlanPreparationAttempt {
        Objects.requireNonNull(attemptId, "attemptId");
        if (stepIndex < 0 || issuedClientTick < 0 || issuedObservationRevision < 0
                || leaseExpiresAtClientTick <= issuedClientTick) {
            throw new IllegalArgumentException("invalid preparation attempt bounds");
        }
    }
}
