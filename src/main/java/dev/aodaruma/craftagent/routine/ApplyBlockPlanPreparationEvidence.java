package dev.aodaruma.craftagent.routine;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Live proof that a child is ready without assuming crosshair or selected-slot state. */
public record ApplyBlockPlanPreparationEvidence(
        UUID attemptId,
        long clientTick,
        long observationRevision,
        Optional<BlockStateFingerprint> liveState,
        boolean targetReplaceable,
        boolean safeStandReady,
        boolean aimAligned,
        boolean requiredHotbarItemSelected,
        RoutineFailure failure) {
    public ApplyBlockPlanPreparationEvidence {
        Objects.requireNonNull(attemptId, "attemptId");
        if (clientTick < 0 || observationRevision < 0) {
            throw new IllegalArgumentException("evidence clock must be non-negative");
        }
        Objects.requireNonNull(liveState, "liveState");
    }

    public boolean prepared() {
        return failure == null && liveState.isPresent()
                && safeStandReady && aimAligned && requiredHotbarItemSelected;
    }
}
