package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.routine.KnownBrewingRequest;
import dev.aod.mcmcp.routine.PhaseFiveAttempt;
import dev.aod.mcmcp.routine.PhaseFiveEvidence;
import dev.aod.mcmcp.routine.PhaseFivePort;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Drives one bounded brewing menu transaction and releases it before terminal publication. */
public final class KnownBrewingAttempt implements AutoCloseable {
    private final PhaseFivePort port;
    private final KnownBrewingRequest request;
    private final UUID attemptId = UUID.randomUUID();
    private final long admittedClientTick;
    private final long deadlineClientTick;
    private PhaseFiveAttempt attempt;
    private long lastObservationRevision;
    private int recordedInteractions;
    private boolean deadlineInitiatedRelease;
    private boolean closed;

    public KnownBrewingAttempt(
            PhaseFivePort port,
            KnownBrewingRequest request,
            long admittedClientTick,
            long deadlineClientTick) {
        this.port = Objects.requireNonNull(port, "port");
        this.request = Objects.requireNonNull(request, "request");
        request.operation().validateAdmissionTick(admittedClientTick);
        long maximum = admittedClientTick > Long.MAX_VALUE - KnownBrewingRequest.MAX_TICKS
                ? Long.MAX_VALUE : admittedClientTick + KnownBrewingRequest.MAX_TICKS;
        if (admittedClientTick < 0 || deadlineClientTick <= admittedClientTick
                || deadlineClientTick > maximum) {
            throw new IllegalArgumentException("brewing deadline is invalid");
        }
        this.admittedClientTick = admittedClientTick;
        this.deadlineClientTick = deadlineClientTick;
    }

    public TickResult tick(long clientTick) {
        requireOpen();
        if (clientTick < admittedClientTick) {
            throw new IllegalArgumentException("client tick moved before admission");
        }
        if (clientTick >= deadlineClientTick) {
            if (attempt == null) return fail("brewing_deadline", 0);
            PhaseFiveEvidence edge = Objects.requireNonNull(
                    port.evidence(attempt), "adapter returned no brewing evidence");
            if (!validEvidence(edge, clientTick)) {
                return fail("brewing_evidence_contract", 0);
            }
            lastObservationRevision = edge.observationRevision();
            final int delta;
            try {
                delta = recordInteractions(edge.basis());
            } catch (IllegalStateException invalidUsage) {
                return fail("brewing_interaction_contract", 0);
            }
            if (edge instanceof PhaseFiveEvidence.ServerConfirmed confirmed) {
                if (!confirmed.result().goalVerified()
                        || confirmed.result().verifiedUnits()
                                != request.expectedOutput().count()) {
                    return fail("brewing_postcondition_unconfirmed", delta);
                }
                close();
                return TickResult.succeeded(delta, confirmed.result().verifiedUnits());
            }
            if (edge instanceof PhaseFiveEvidence.Inconclusive inconclusive) {
                return fail(inconclusiveEvidence(inconclusive), delta);
            }
            if (edge instanceof PhaseFiveEvidence.Failed failed) {
                return fail("brewing_" + failed.failure().category().name()
                        .toLowerCase(Locale.ROOT), delta);
            }
            if (releaseFault(edge.basis())) {
                return fail("brewing_release_fault", delta);
            }
            if (!releasePending(edge.basis())) {
                deadlineInitiatedRelease = true;
            }
            port.maintain(attempt);
            return TickResult.running(delta);
        }
        var frame = Objects.requireNonNull(
                port.observe(request.operation()), "adapter returned no brewing frame");
        if (frame.clientTick() != clientTick
                || frame.observationRevision() < lastObservationRevision) {
            return fail("brewing_observation_contract", captureInteractionDelta(clientTick));
        }
        lastObservationRevision = frame.observationRevision();
        if (frame.failure() != null) {
            return fail("brewing_" + frame.failure().category().name()
                    .toLowerCase(Locale.ROOT), captureInteractionDelta(clientTick));
        }
        if (attempt == null) {
            attempt = Objects.requireNonNull(
                    port.begin(attemptId, request.operation(), deadlineClientTick),
                    "adapter returned no brewing attempt");
            if (!attemptId.equals(attempt.attemptId())
                    || !request.operation().kind().equals(attempt.kind())
                    || attempt.issuedClientTick() != clientTick
                    || attempt.issuedObservationRevision() < frame.observationRevision()
                    || attempt.hardDeadlineClientTick() != deadlineClientTick) {
                return fail("brewing_begin_contract", 0);
            }
            return TickResult.running(0);
        }

        PhaseFiveEvidence evidence = Objects.requireNonNull(
                port.evidence(attempt), "adapter returned no brewing evidence");
        if (!validEvidence(evidence, clientTick)) {
            return fail("brewing_evidence_contract", 0);
        }
        lastObservationRevision = evidence.observationRevision();
        int delta;
        try {
            delta = recordInteractions(evidence.basis());
        } catch (IllegalStateException invalidUsage) {
            return fail("brewing_interaction_contract", 0);
        }

        return switch (evidence) {
            case PhaseFiveEvidence.Pending ignored -> {
                if (releaseFault(evidence.basis())) {
                    yield fail("brewing_release_fault", delta);
                }
                port.maintain(attempt);
                yield TickResult.running(delta);
            }
            case PhaseFiveEvidence.ServerConfirmed confirmed -> {
                if (!confirmed.result().goalVerified()
                        || confirmed.result().verifiedUnits()
                                != request.expectedOutput().count()) {
                    yield fail("brewing_postcondition_unconfirmed", delta);
                }
                close();
                yield TickResult.succeeded(delta, confirmed.result().verifiedUnits());
            }
            case PhaseFiveEvidence.Inconclusive inconclusive ->
                    fail(inconclusiveEvidence(inconclusive), delta);
            case PhaseFiveEvidence.Failed failed ->
                    fail("brewing_" + failed.failure().category().name()
                            .toLowerCase(Locale.ROOT), delta);
        };
    }

    private String inconclusiveEvidence(PhaseFiveEvidence.Inconclusive inconclusive) {
        return deadlineInitiatedRelease
                ? "brewing_deadline"
                : "brewing_" + inconclusive.certainty().name().toLowerCase(Locale.ROOT);
    }

    private static boolean releasePending(Map<String, Object> basis) {
        return Boolean.TRUE.equals(basis.get("release_pending"));
    }

    private static boolean releaseFault(Map<String, Object> basis) {
        return Boolean.TRUE.equals(basis.get("release_fault"));
    }

    private TickResult fail(String evidence, int delta) {
        // Do not close here. McmcpRuntime first retains this exact failure intent, then its shared
        // terminal release fence repeatedly invokes close() until the port confirms cleanup.
        return TickResult.failed(evidence, delta);
    }

    private int captureInteractionDelta(long maximumClientTick) {
        if (attempt == null) return 0;
        PhaseFiveEvidence evidence = Objects.requireNonNull(
                port.evidence(attempt), "adapter returned no brewing evidence");
        if (!validEvidence(evidence, maximumClientTick)) {
            return 0;
        }
        lastObservationRevision = evidence.observationRevision();
        try {
            return recordInteractions(evidence.basis());
        } catch (IllegalStateException invalidUsage) {
            return 0;
        }
    }

    private boolean validEvidence(PhaseFiveEvidence evidence, long maximumClientTick) {
        return attemptId.equals(evidence.attemptId())
                && evidence.clientTick() >= attempt.issuedClientTick()
                && evidence.clientTick() <= maximumClientTick
                && evidence.observationRevision() >= lastObservationRevision;
    }

    private int recordInteractions(Map<String, Object> basis) {
        int observed = interactions(basis);
        if (observed < recordedInteractions
                || observed > KnownBrewingRequest.MAX_INTERACTIONS) {
            throw new IllegalStateException("brewing interaction contract changed");
        }
        int delta = observed - recordedInteractions;
        recordedInteractions = observed;
        return delta;
    }

    /** Drains cleanup clicks while the runtime still retains the first terminal intent. */
    public int drainReleaseInteractionDelta() {
        return closed ? 0 : captureInteractionDelta(Long.MAX_VALUE);
    }

    /** Distinguishes bounded tick-driven cleanup from a true fail-closed release fault. */
    public ReleaseStatus releaseStatus() {
        if (closed) return ReleaseStatus.CONFIRMED;
        if (attempt == null) return ReleaseStatus.ACTIVE;
        try {
            Map<String, Object> basis = port.evidence(attempt).basis();
            if (releaseFault(basis)) return ReleaseStatus.FAULT;
            if (Boolean.TRUE.equals(basis.get("release_confirmed"))) {
                return ReleaseStatus.CONFIRMED;
            }
            return releasePending(basis) ? ReleaseStatus.PROGRESSING : ReleaseStatus.ACTIVE;
        } catch (RuntimeException | LinkageError invalidEvidence) {
            return ReleaseStatus.FAULT;
        }
    }

    private static int interactions(Map<String, Object> basis) {
        return Math.addExact(nonNegative(basis.get("open_count")),
                nonNegative(basis.get("container_clicks")));
    }

    private static int nonNegative(Object value) {
        if (!(value instanceof Number number) || number.intValue() < 0) {
            throw new IllegalStateException("brewing evidence count is invalid");
        }
        return number.intValue();
    }

    @Override
    public void close() {
        if (closed) return;
        if (attempt != null) port.release(attempt);
        port.retire(request.operation());
        closed = true;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("brewing attempt is closed");
    }

    public enum Status { RUNNING, SUCCEEDED, FAILED }

    public enum ReleaseStatus { ACTIVE, PROGRESSING, CONFIRMED, FAULT }

    public record TickResult(
            Status status,
            String evidence,
            int interactionDelta,
            int verifiedPotions) {
        public TickResult {
            Objects.requireNonNull(status, "status");
            if (interactionDelta < 0
                    || interactionDelta > KnownBrewingRequest.MAX_INTERACTIONS
                    || verifiedPotions < 0 || verifiedPotions > 3) {
                throw new IllegalArgumentException("brewing result is outside bounds");
            }
        }

        private static TickResult running(int delta) {
            return new TickResult(Status.RUNNING, null, delta, 0);
        }

        private static TickResult succeeded(int delta, int count) {
            return new TickResult(Status.SUCCEEDED, null, delta, count);
        }

        private static TickResult failed(String evidence, int delta) {
            return new TickResult(Status.FAILED,
                    Objects.requireNonNull(evidence, "evidence"), delta, 0);
        }
    }
}
