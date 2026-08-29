package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.routine.PhaseFiveAttempt;
import dev.aod.mcmcp.routine.PhaseFiveEvidence;
import dev.aod.mcmcp.routine.PhaseFivePort;
import dev.aod.mcmcp.routine.PhaseFiveRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Drives one bounded container operation without admitting a second public routine. */
public final class KnownContainerAttempt implements AutoCloseable {
    private final PhaseFivePort port;
    private final PhaseFiveRequest request;
    private final UUID attemptId = UUID.randomUUID();
    private final long admittedClientTick;
    private final long deadlineClientTick;
    private PhaseFiveAttempt attempt;
    private long lastObservationRevision;
    private int recordedInteractions;
    private boolean closed;

    public KnownContainerAttempt(
            PhaseFivePort port,
            PhaseFiveRequest request,
            long admittedClientTick,
            long deadlineClientTick) {
        this.port = Objects.requireNonNull(port, "port");
        this.request = Objects.requireNonNull(request, "request");
        request.validateAdmissionTick(admittedClientTick);
        if (deadlineClientTick <= admittedClientTick) {
            throw new IllegalArgumentException("deadline must follow admission");
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
            return fail("container_deadline", 0);
        }
        var frame = Objects.requireNonNull(port.observe(request), "adapter returned no frame");
        if (frame.clientTick() != clientTick
                || frame.observationRevision() < lastObservationRevision) {
            return fail("container_observation_contract", 0);
        }
        lastObservationRevision = frame.observationRevision();
        if (frame.failure() != null) {
            return fail(frame.failure().code().toLowerCase(Locale.ROOT), 0);
        }

        if (attempt == null) {
            attempt = Objects.requireNonNull(
                    port.begin(attemptId, request, deadlineClientTick),
                    "adapter returned no attempt");
            if (!attemptId.equals(attempt.attemptId())
                    || !request.kind().equals(attempt.kind())
                    || attempt.issuedClientTick() != clientTick
                    || attempt.issuedObservationRevision() < frame.observationRevision()
                    || attempt.hardDeadlineClientTick() != deadlineClientTick) {
                return fail("container_begin_contract", 0);
            }
            return TickResult.running(0);
        }

        PhaseFiveEvidence evidence = Objects.requireNonNull(
                port.evidence(attempt), "adapter returned no evidence");
        if (!attemptId.equals(evidence.attemptId())
                || evidence.clientTick() < attempt.issuedClientTick()
                || evidence.clientTick() > clientTick
                || evidence.observationRevision() < lastObservationRevision) {
            return fail("container_evidence_contract", 0);
        }
        lastObservationRevision = evidence.observationRevision();
        int interactions = interactionCount(evidence.basis());
        if (interactions < recordedInteractions) {
            return fail("container_interaction_contract", 0);
        }
        int delta = interactions - recordedInteractions;
        recordedInteractions = interactions;

        return switch (evidence) {
            case PhaseFiveEvidence.Pending ignored -> {
                port.maintain(attempt);
                yield TickResult.running(delta);
            }
            case PhaseFiveEvidence.ServerConfirmed confirmed -> {
                if (!confirmed.result().goalVerified()
                        || confirmed.result().verifiedUnits() < request.expectedUnits()) {
                    yield fail("container_postcondition_unconfirmed", delta);
                }
                List<ItemCount> items = itemCounts(confirmed.result().basis());
                close();
                yield TickResult.succeeded(delta, items);
            }
            case PhaseFiveEvidence.Inconclusive inconclusive ->
                    fail("container_" + inconclusive.certainty().name()
                            .toLowerCase(Locale.ROOT), delta);
            case PhaseFiveEvidence.Failed failed ->
                    fail(failed.failure().code().toLowerCase(Locale.ROOT), delta);
        };
    }

    private TickResult fail(String evidence, int interactionDelta) {
        close();
        return TickResult.failed(evidence, interactionDelta);
    }

    @Override
    public void close() {
        if (closed) return;
        try {
            if (attempt != null) port.release(attempt);
        } finally {
            port.retire(request);
        }
        // Router/delegate ownership stays retryable until both release and retirement succeed.
        closed = true;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("container attempt is closed");
    }

    private static int interactionCount(Map<String, Object> basis) {
        return Math.addExact(nonNegativeInt(basis.get("open_count")),
                nonNegativeInt(basis.get("container_clicks")));
    }

    private static int nonNegativeInt(Object value) {
        if (!(value instanceof Number number) || number.intValue() < 0) {
            throw new IllegalStateException("container evidence count is invalid");
        }
        return number.intValue();
    }

    private static List<ItemCount> itemCounts(Map<String, Object> basis) {
        Object raw = basis.get("available_source_items");
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list) || list.size() > 27) {
            throw new IllegalStateException("container item evidence is invalid");
        }
        var output = new ArrayList<ItemCount>(list.size());
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> map)
                    || !(map.get("item") instanceof String item)
                    || !(map.get("count") instanceof Number count)) {
                throw new IllegalStateException("container item evidence is invalid");
            }
            output.add(new ItemCount(item, count.intValue()));
        }
        return List.copyOf(output);
    }

    public enum Status { RUNNING, SUCCEEDED, FAILED }

    public record ItemCount(String item, int count) {
        public ItemCount {
            Objects.requireNonNull(item, "item");
            if (count < 1 || count > 2_304) {
                throw new IllegalArgumentException("item count is outside the bounded range");
            }
        }
    }

    public record TickResult(
            Status status,
            String evidence,
            int interactionDelta,
            List<ItemCount> items) {
        public TickResult {
            Objects.requireNonNull(status, "status");
            if (interactionDelta < 0 || interactionDelta > 8) {
                throw new IllegalArgumentException("interaction delta is outside the action bound");
            }
            items = List.copyOf(Objects.requireNonNull(items, "items"));
        }

        private static TickResult running(int delta) {
            return new TickResult(Status.RUNNING, null, delta, List.of());
        }

        private static TickResult succeeded(int delta, List<ItemCount> items) {
            return new TickResult(Status.SUCCEEDED, null, delta, items);
        }

        private static TickResult failed(String evidence, int delta) {
            return new TickResult(Status.FAILED,
                    Objects.requireNonNull(evidence, "evidence"), delta, List.of());
        }
    }
}
