package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.routine.PhaseFiveAttempt;
import dev.aod.mcmcp.routine.PhaseFiveEvidence;
import dev.aod.mcmcp.routine.PhaseFivePort;
import dev.aod.mcmcp.routine.PhaseFiveRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Adapts the existing finite station transaction without weakening its release/evidence contract. */
final class V2ProductionAttempt {
    private final PhaseFivePort port;
    private final PhaseFiveRequest request;
    private final UUID owner = UUID.randomUUID();
    private final long deadline;
    private PhaseFiveAttempt attempt;
    private boolean begun;
    private boolean closed;
    private long revision;
    private String failure;
    private boolean confirmed;
    private Map<String, Object> progress = Map.of();
    private Map<String, Integer> consumed = Map.of();
    private boolean releaseEvidenceUnavailable;

    V2ProductionAttempt(PhaseFivePort port, PhaseFiveRequest request, long deadline) {
        this.port = port;
        this.request = request;
        this.deadline = deadline;
    }

    V2OperationJobExecution.Step tick(long tick) {
        if (failure != null) return V2OperationJobExecution.Step.FAILED;
        if (confirmed) return V2OperationJobExecution.Step.CONFIRMED;
        if (!begun) {
            var frame = port.observe(request);
            if (frame.clientTick() != tick || frame.failure() != null) {
                failure = frame.failure() == null ? "production_clock_changed" : frame.failure().code().toLowerCase(java.util.Locale.ROOT);
                return V2OperationJobExecution.Step.FAILED;
            }
            revision = frame.observationRevision();
            begun = true;
            attempt = port.begin(owner, request, deadline);
            if (!attempt.attemptId().equals(owner) || !attempt.kind().equals(request.kind())
                    || attempt.issuedClientTick() != tick || attempt.issuedObservationRevision() < revision
                    || attempt.hardDeadlineClientTick() != deadline) throw new IllegalStateException("production attempt contract changed");
        }
        capture(tick);
        if (confirmed) return V2OperationJobExecution.Step.CONFIRMED;
        if (failure != null) return V2OperationJobExecution.Step.FAILED;
        port.maintain(attempt);
        return V2OperationJobExecution.Step.RUNNING;
    }

    private void capture(long tick) {
        if (attempt == null) return;
        var evidence = port.evidence(attempt);
        if (!owner.equals(evidence.attemptId()) || evidence.clientTick() < attempt.issuedClientTick()
                || evidence.clientTick() > tick || evidence.observationRevision() < revision) {
            throw new IllegalStateException("production evidence contract changed");
        }
        revision = evidence.observationRevision();
        var safe = new LinkedHashMap<String, Object>();
        for (String key : Set.of("stage", "open_count", "container_clicks", "loaded_readback", "smelt_started",
                "smelt_completed", "fuel_recovered", "result_recovered", "fresh_readback", "release_pending",
                "release_confirmed", "release_fault", "brew_started", "brew_completed")) {
            Object value = evidence.basis().get(key);
            if (value instanceof Boolean || value instanceof Number || value instanceof String) safe.put(key, value);
        }
        progress = Map.copyOf(safe);
        if (evidence instanceof PhaseFiveEvidence.ServerConfirmed done) {
            if (!done.result().goalVerified() || done.result().verifiedUnits() != request.expectedUnits()) {
                failure = "production_quantity_not_confirmed";
            } else {
                confirmed = true;
                if (done.result().basis().get("consumed") instanceof Map<?, ?> materials) {
                    var counts = new LinkedHashMap<String, Integer>();
                    materials.forEach((item, count) -> {
                        if (item instanceof String id && id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                                && count instanceof Integer units && units > 0) counts.put(id, units);
                    });
                    consumed = Map.copyOf(counts);
                }
            }
        } else if (evidence instanceof PhaseFiveEvidence.Failed failed) {
            failure = failed.failure().code().toLowerCase(java.util.Locale.ROOT);
        } else if (evidence instanceof PhaseFiveEvidence.Inconclusive) failure = "production_result_uncertain";
    }

    void close() {
        if (closed) return;
        if (attempt != null) {
            captureDuringRelease();
            try { port.release(attempt); } finally { captureDuringRelease(); }
        }
        port.retire(request);
        closed = true;
    }
    private void captureDuringRelease() {
        try { capture(Long.MAX_VALUE); }
        catch (RuntimeException | LinkageError ignored) {
            // Session teardown may reset evidence clocks; it must never block identity release.
            releaseEvidenceUnavailable = true;
        }
    }
    Map<String, Object> result() {
        var result = new LinkedHashMap<String, Object>();
        result.put("confirmed", confirmed);
        result.put("progress", progress);
        result.put("processing_may_continue", begun && !confirmed);
        if (!consumed.isEmpty()) result.put("consumed", consumed);
        if (releaseEvidenceUnavailable) result.put("release_evidence_unavailable", true);
        if (failure != null) result.put("failure", failure);
        return result;
    }
    UUID owner() { return owner; }
    boolean confirmed() { return confirmed; }
}
