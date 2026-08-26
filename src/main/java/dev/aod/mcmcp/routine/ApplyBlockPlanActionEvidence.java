package dev.aod.mcmcp.routine;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Server-derived world-diff evidence for one internal mutation. */
public record ApplyBlockPlanActionEvidence(
        UUID attemptId,
        long clientTick,
        long observationRevision,
        boolean acknowledged,
        boolean worldDiffObserved,
        Optional<BlockStateFingerprint> liveStateAfter,
        Map<String, Object> basis,
        RoutineFailure failure) {
    public ApplyBlockPlanActionEvidence {
        Objects.requireNonNull(attemptId, "attemptId");
        if (clientTick < 0 || observationRevision < 0) {
            throw new IllegalArgumentException("evidence clock must be non-negative");
        }
        Objects.requireNonNull(liveStateAfter, "liveStateAfter");
        basis = Map.copyOf(Objects.requireNonNull(basis, "basis"));
        if (worldDiffObserved && liveStateAfter.isEmpty()) {
            throw new IllegalArgumentException("world diff requires a known after-state");
        }
    }
}
