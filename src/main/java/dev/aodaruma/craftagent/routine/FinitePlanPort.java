package dev.aodaruma.craftagent.routine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Minecraft-independent boundary used by the parent-only finite-plan interpreter. */
public interface FinitePlanPort {
    Frame observe(FinitePlanRequest request);

    ConditionEvidence evaluate(FinitePlanRequest.Condition condition);

    ActionAttempt begin(
            UUID parentRoutineId,
            FinitePlanRequest.Action action,
            long hardDeadlineClientTick);

    void maintain(ActionAttempt attempt);

    ActionEvidence evidence(ActionAttempt attempt);

    /** Cancels any pending work and releases all action-scoped ownership. */
    void release(ActionAttempt attempt);

    /** Drops plan-scoped observations after every terminal outcome. */
    void retire(FinitePlanRequest request);

    record Frame(long clientTick, long observationRevision, RoutineFailure failure) {
        public Frame {
            if (clientTick < 0 || observationRevision < 0) {
                throw new IllegalArgumentException("frame clocks must be non-negative");
            }
            rejectFinalizationFailure(failure);
        }
    }

    enum ConditionStatus {
        SATISFIED,
        UNSATISFIED,
        UNKNOWN
    }

    record ConditionEvidence(
            long clientTick,
            long observationRevision,
            ConditionStatus status,
            Map<String, Object> basis) {
        public ConditionEvidence {
            if (clientTick < 0 || observationRevision < 0) {
                throw new IllegalArgumentException("condition clocks must be non-negative");
            }
            Objects.requireNonNull(status, "status");
            basis = immutableMap(basis, "basis");
        }
    }

    record ActionAttempt(
            UUID attemptId,
            UUID parentRoutineId,
            String stepId,
            long issuedClientTick,
            long issuedObservationRevision,
            long hardDeadlineClientTick) {
        public ActionAttempt {
            Objects.requireNonNull(attemptId, "attemptId");
            Objects.requireNonNull(parentRoutineId, "parentRoutineId");
            Objects.requireNonNull(stepId, "stepId");
            if (!stepId.matches("[a-z][a-z0-9_.-]{0,63}")) {
                throw new IllegalArgumentException("invalid step id");
            }
            if (issuedClientTick < 0 || issuedObservationRevision < 0
                    || hardDeadlineClientTick <= issuedClientTick) {
                throw new IllegalArgumentException("invalid action attempt clocks");
            }
        }
    }

    record ActionEvidence(
            UUID attemptId,
            long clientTick,
            long observationRevision,
            boolean confirmed,
            RoutineFailure failure,
            Map<String, Object> basis) {
        public ActionEvidence {
            Objects.requireNonNull(attemptId, "attemptId");
            if (clientTick < 0 || observationRevision < 0) {
                throw new IllegalArgumentException("action evidence clocks must be non-negative");
            }
            rejectFinalizationFailure(failure);
            if (confirmed && failure != null) {
                throw new IllegalArgumentException("failed action evidence cannot be confirmed");
            }
            basis = immutableMap(basis, "basis");
        }
    }

    private static void rejectFinalizationFailure(RoutineFailure failure) {
        if (failure != null && failure.scope() == RoutineFailure.Scope.FINALIZATION) {
            throw new IllegalArgumentException("plan port cannot report a finalization failure");
        }
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source, String name) {
        Objects.requireNonNull(source, name);
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
