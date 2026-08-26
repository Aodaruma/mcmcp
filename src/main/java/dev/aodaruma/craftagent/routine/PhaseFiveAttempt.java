package dev.aodaruma.craftagent.routine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Single stable authority retained for the complete Phase 5 operation. */
public record PhaseFiveAttempt(
        UUID attemptId,
        String kind,
        long issuedClientTick,
        long issuedObservationRevision,
        long hardDeadlineClientTick,
        Map<String, Object> dispatchBasis) {
    public PhaseFiveAttempt {
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(kind, "kind");
        if (!PhaseFiveRequest.KINDS.contains(kind)) {
            throw new IllegalArgumentException("invalid Phase 5 attempt kind");
        }
        if (issuedClientTick < 0
                || issuedObservationRevision < 0
                || hardDeadlineClientTick <= issuedClientTick) {
            throw new IllegalArgumentException("invalid attempt watermarks");
        }
        dispatchBasis = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(dispatchBasis, "dispatchBasis")));
    }
}
