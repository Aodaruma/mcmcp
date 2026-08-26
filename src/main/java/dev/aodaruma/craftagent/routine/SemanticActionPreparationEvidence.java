package dev.aodaruma.craftagent.routine;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Live proof that a finite block action is aimed, reachable and hotbar-ready. */
public record SemanticActionPreparationEvidence(
        UUID attemptId,
        long clientTick,
        long observationRevision,
        Optional<BlockStateFingerprint> liveBlockState,
        boolean blockInReach,
        boolean crosshairOnBlock,
        boolean requiredHotbarSlotSelected,
        RoutineFailure failure) {
    public SemanticActionPreparationEvidence {
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(liveBlockState, "liveBlockState");
        if (clientTick < 0 || observationRevision < 0) {
            throw new IllegalArgumentException("preparation evidence clocks must be non-negative");
        }
    }

    public boolean prepared() {
        return failure == null && liveBlockState.isPresent()
                && blockInReach && crosshairOnBlock && requiredHotbarSlotSelected;
    }
}
