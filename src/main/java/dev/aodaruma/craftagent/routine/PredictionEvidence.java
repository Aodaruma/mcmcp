package dev.aodaruma.craftagent.routine;

import java.util.Objects;
import java.util.Optional;

/** Vanilla server acknowledgement and authoritative target transition are independent evidence. */
public record PredictionEvidence(
        long predictionSequence,
        boolean acknowledged,
        boolean serverVerifiedTransition,
        Optional<BlockStateFingerprint> transitionedTo,
        long clientTick,
        long observationRevision) {
    public PredictionEvidence {
        if (predictionSequence < 0 || clientTick < 0 || observationRevision < 0) {
            throw new IllegalArgumentException("invalid prediction evidence clocks");
        }
        transitionedTo = Objects.requireNonNull(transitionedTo, "transitionedTo");
        if (serverVerifiedTransition && transitionedTo.isEmpty()) {
            throw new IllegalArgumentException("server-verified transition requires the observed state");
        }
    }

    public boolean confirmsBreakFrom(BlockStateFingerprint sourceState) {
        return acknowledged
                && serverVerifiedTransition
                && transitionedTo.filter(state -> !sourceState.matches(state))
                .filter(state -> "minecraft:air".equals(state.blockId()))
                .isPresent();
    }
}
