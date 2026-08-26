package dev.aod.mcmcp.routine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Raw server/reconciliation evidence; semantic postconditions remain domain-owned. */
public record SemanticActionEvidence(
        UUID attemptId,
        long clientTick,
        long observationRevision,
        boolean acknowledged,
        Optional<BlockStateFingerprint> serverBlockState,
        boolean inventoryUpdateObserved,
        boolean inventoryServerSynchronized,
        int goalItemCount,
        RoutineFailure failure,
        boolean safeToRetry,
        Map<String, Object> basis) {
    public SemanticActionEvidence {
        Objects.requireNonNull(attemptId, "attemptId");
        if (clientTick < 0 || observationRevision < 0 || goalItemCount < 0) {
            throw new IllegalArgumentException("evidence clocks/counts must be non-negative");
        }
        Objects.requireNonNull(serverBlockState, "serverBlockState");
        Objects.requireNonNull(basis, "basis");
        basis = Collections.unmodifiableMap(new LinkedHashMap<>(basis));
        if (failure != null && failure.scope() == RoutineFailure.Scope.FINALIZATION) {
            throw new IllegalArgumentException("action evidence cannot carry a finalization failure");
        }
    }
}
