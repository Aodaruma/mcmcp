package dev.aodaruma.craftagent.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Retains failed client-thread finalization work until a later tick can retry it.
 *
 * <p>Only a bounded number of routines can be retained by the owning routine manager, and this
 * queue is cleared at the same world-session fences. Throwable instances are deliberately not
 * retained: the local log receives the exception while the retry carries only a normalized
 * diagnostic code into the eventual routine outcome.</p>
 */
final class FinalizationRetryQueue {
    /** Bounds repeated global input/voice cleanup; record-only probes continue at the capped delay. */
    static final int MAX_AUTOMATIC_ATTEMPTS = 8;
    private final Map<UUID, Incident> pending = new LinkedHashMap<>();

    <T, F> Attempt<T, F> attempt(
            UUID routineId,
            long clientTick,
            Finalizer<T> finalizer,
            Supplier<F> emergencyRelease) {
        Objects.requireNonNull(routineId, "routineId");
        Objects.requireNonNull(finalizer, "finalizer");
        Objects.requireNonNull(emergencyRelease, "emergencyRelease");

        Incident priorIncident = pending.get(routineId);
        try {
            T value = Objects.requireNonNull(
                    finalizer.finalizeRoutine(priorIncident),
                    "finalizer returned no result");
            pending.remove(routineId);
            return Attempt.success(value);
        }
        catch (RuntimeException | LinkageError failure) {
            var incident = pending.getOrDefault(routineId, Incident.empty());
            if (incident.boundaryFailureCode() == null) {
                incident = incident.withBoundaryFailure(incidentCode(failure), clientTick);
            }
            else {
                incident = incident.afterFailedAttempt(clientTick);
            }
            pending.put(routineId, incident);

            F fallback = null;
            boolean fallbackAttempted = !incident.cleanupObserved();
            if (fallbackAttempted) {
                try {
                    fallback = emergencyRelease.get();
                }
                catch (RuntimeException | LinkageError fallbackFailure) {
                    failure.addSuppressed(fallbackFailure);
                }
            }
            return Attempt.failure(
                    fallback,
                    fallbackAttempted,
                    failure,
                    incident);
        }
    }

    void rememberCleanupOutcome(
            UUID routineId,
            boolean inputsReleased,
            boolean voiceRestored,
            String voiceFailureCode) {
        var incident = pending.getOrDefault(routineId, Incident.empty());
        pending.put(
                routineId,
                incident.withCleanupOutcome(
                        inputsReleased,
                        voiceRestored,
                        voiceFailureCode));
    }

    List<UUID> pendingRoutineIds(long clientTick) {
        var ready = new ArrayList<UUID>();
        for (var entry : pending.entrySet()) {
            if (entry.getValue().nextRetryClientTick() <= clientTick) {
                ready.add(entry.getKey());
            }
        }
        return List.copyOf(ready);
    }

    boolean hasPending() {
        return !pending.isEmpty();
    }

    int pendingCount() {
        return pending.size();
    }

    boolean contains(UUID routineId) {
        return pending.containsKey(routineId);
    }

    void forget(UUID routineId) {
        pending.remove(routineId);
    }

    void clear() {
        pending.clear();
    }

    private static String incidentCode(Throwable failure) {
        String simpleName = failure.getClass().getSimpleName();
        if (simpleName.isBlank()) {
            simpleName = "Throwable";
        }
        return ("finalization_record_exception_" + simpleName)
                .replaceAll("[^A-Za-z0-9_]+", "_");
    }

    @FunctionalInterface
    interface Finalizer<T> {
        T finalizeRoutine(Incident priorIncident);
    }

    record Incident(
            String boundaryFailureCode,
            boolean cleanupObserved,
            boolean inputsReleased,
            boolean voiceRestored,
            boolean previousInputReleaseFailure,
            String previousVoiceFailureCode,
            int failedAttempts,
            long nextRetryClientTick) {
        private static Incident empty() {
            return new Incident(null, false, false, false, false, null, 0, 0);
        }

        private Incident withBoundaryFailure(String failureCode, long clientTick) {
            return new Incident(
                    Objects.requireNonNull(failureCode, "failureCode"),
                    cleanupObserved,
                    inputsReleased,
                    voiceRestored,
                    previousInputReleaseFailure,
                    previousVoiceFailureCode,
                    1,
                    nextRetryTick(clientTick, 1));
        }

        private Incident afterFailedAttempt(long clientTick) {
            int attempts = failedAttempts == Integer.MAX_VALUE
                    ? Integer.MAX_VALUE
                    : failedAttempts + 1;
            return new Incident(
                    boundaryFailureCode,
                    cleanupObserved,
                    inputsReleased,
                    voiceRestored,
                    previousInputReleaseFailure,
                    previousVoiceFailureCode,
                    attempts,
                    nextRetryTick(clientTick, attempts));
        }

        private Incident withCleanupOutcome(
                boolean currentInputsReleased,
                boolean currentVoiceRestored,
                String voiceFailureCode) {
            return new Incident(
                    boundaryFailureCode,
                    true,
                    inputsReleased || currentInputsReleased,
                    voiceRestored || currentVoiceRestored,
                    previousInputReleaseFailure || !currentInputsReleased,
                    previousVoiceFailureCode != null
                            ? previousVoiceFailureCode
                            : currentVoiceRestored ? null : voiceFailureCode,
                    failedAttempts,
                    nextRetryClientTick);
        }

        private static long nextRetryTick(long clientTick, int attempts) {
            int shift = Math.min(5, Math.max(0, attempts - 1));
            long delay = 20L << shift;
            return clientTick > Long.MAX_VALUE - delay
                    ? Long.MAX_VALUE
                    : clientTick + delay;
        }
    }

    record Attempt<T, F>(
            boolean success,
            T value,
            F emergencyRelease,
            boolean emergencyReleaseAttempted,
            Throwable failure,
            Incident incident) {
        String incidentCode() {
            return incident == null ? null : incident.boundaryFailureCode();
        }

        private static <T, F> Attempt<T, F> success(T value) {
            return new Attempt<>(true, value, null, false, null, null);
        }

        private static <T, F> Attempt<T, F> failure(
                F emergencyRelease,
                boolean emergencyReleaseAttempted,
                Throwable failure,
                Incident incident) {
            return new Attempt<>(
                    false,
                    null,
                    emergencyRelease,
                    emergencyReleaseAttempted,
                    Objects.requireNonNull(failure, "failure"),
                    Objects.requireNonNull(incident, "incident"));
        }
    }
}
