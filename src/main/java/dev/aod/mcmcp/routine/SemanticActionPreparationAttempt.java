package dev.aod.mcmcp.routine;

import java.util.Objects;
import java.util.UUID;

/** Adapter-owned bounded aim/hotbar preparation handle for one finite block action. */
public record SemanticActionPreparationAttempt(
        UUID attemptId,
        String kind,
        long issuedClientTick,
        long issuedObservationRevision,
        long leaseExpiresAtClientTick,
        long positionCorrectionRevisionAtStart) {
    public SemanticActionPreparationAttempt {
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(kind, "kind");
        if (!kind.matches("[a-z][a-z0-9_]{0,63}")
                || issuedClientTick < 0 || issuedObservationRevision < 0
                || leaseExpiresAtClientTick <= issuedClientTick
                || positionCorrectionRevisionAtStart < 0) {
            throw new IllegalArgumentException("invalid preparation attempt");
        }
    }
}
