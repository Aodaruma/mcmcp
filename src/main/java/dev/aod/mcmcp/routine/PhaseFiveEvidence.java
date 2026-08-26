package dev.aod.mcmcp.routine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Closed evidence states prevent an incomplete or ambiguous observation from becoming success. */
public sealed interface PhaseFiveEvidence permits PhaseFiveEvidence.Pending,
        PhaseFiveEvidence.ServerConfirmed, PhaseFiveEvidence.Inconclusive,
        PhaseFiveEvidence.Failed {
    UUID attemptId();

    long clientTick();

    long observationRevision();

    Map<String, Object> basis();

    record Pending(
            UUID attemptId,
            long clientTick,
            long observationRevision,
            Map<String, Object> basis) implements PhaseFiveEvidence {
        public Pending {
            validate(attemptId, clientTick, observationRevision);
            basis = immutableBasis(basis);
        }
    }

    record ServerConfirmed(
            UUID attemptId,
            long clientTick,
            long observationRevision,
            PhaseFiveResult result,
            Map<String, Object> basis) implements PhaseFiveEvidence {
        public ServerConfirmed {
            validate(attemptId, clientTick, observationRevision);
            Objects.requireNonNull(result, "result");
            basis = immutableBasis(basis);
        }
    }

    record Inconclusive(
            UUID attemptId,
            long clientTick,
            long observationRevision,
            Certainty certainty,
            String reason,
            Map<String, Object> basis) implements PhaseFiveEvidence {
        public Inconclusive {
            validate(attemptId, clientTick, observationRevision);
            Objects.requireNonNull(certainty, "certainty");
            Objects.requireNonNull(reason, "reason");
            if (reason.isBlank() || reason.length() > 160) {
                throw new IllegalArgumentException("inconclusive reason must be 1..160 characters");
            }
            basis = immutableBasis(basis);
        }
    }

    record Failed(
            UUID attemptId,
            long clientTick,
            long observationRevision,
            RoutineFailure failure,
            Map<String, Object> basis) implements PhaseFiveEvidence {
        public Failed {
            validate(attemptId, clientTick, observationRevision);
            Objects.requireNonNull(failure, "failure");
            if (failure.scope() == RoutineFailure.Scope.FINALIZATION) {
                throw new IllegalArgumentException("action failure cannot use FINALIZATION scope");
            }
            basis = immutableBasis(basis);
        }
    }

    enum Certainty {
        UNKNOWN,
        AMBIGUOUS
    }

    private static void validate(UUID attemptId, long clientTick, long observationRevision) {
        Objects.requireNonNull(attemptId, "attemptId");
        if (clientTick < 0 || observationRevision < 0) {
            throw new IllegalArgumentException("evidence clocks must be non-negative");
        }
    }

    private static Map<String, Object> immutableBasis(Map<String, Object> basis) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(basis, "basis")));
    }
}
