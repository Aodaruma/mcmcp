package dev.aodaruma.craftagent.routine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Adapter-owned action handle plus immutable raw dispatch watermarks.
 *  The lease expiry is the absolute action ceiling; adapters renew a shorter input watchdog.
 */
public record SemanticActionAttempt(
        UUID attemptId,
        String kind,
        long issuedClientTick,
        long issuedObservationRevision,
        long leaseExpiresAtClientTick,
        long positionCorrectionRevisionAtDispatch,
        Map<String, Object> dispatchBasis) {
    public SemanticActionAttempt {
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(kind, "kind");
        if (!kind.matches("[a-z][a-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("invalid attempt kind");
        }
        if (issuedClientTick < 0 || issuedObservationRevision < 0
                || leaseExpiresAtClientTick <= issuedClientTick
                || positionCorrectionRevisionAtDispatch < 0) {
            throw new IllegalArgumentException("invalid attempt watermarks");
        }
        Objects.requireNonNull(dispatchBasis, "dispatchBasis");
        dispatchBasis = Collections.unmodifiableMap(new LinkedHashMap<>(dispatchBasis));
    }
}
