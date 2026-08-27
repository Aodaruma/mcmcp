package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.routine.BlockStateFingerprint;
import dev.aod.mcmcp.routine.BreakBlockRequest;
import dev.aod.mcmcp.routine.InteractBlockRequest;
import dev.aod.mcmcp.routine.PlaceBlockRequest;
import dev.aod.mcmcp.routine.SemanticActionAttempt;
import dev.aod.mcmcp.routine.SemanticActionPort;
import dev.aod.mcmcp.routine.SemanticActionPreparationAttempt;
import dev.aod.mcmcp.routine.SemanticActionRequest;
import dev.aod.mcmcp.routine.UseItemOnBlockRequest;

import java.util.Objects;
import java.util.Locale;

/** One bounded known-block mutation, confirmed by prediction ACK and authoritative state. */
public final class KnownBlockMutationAttempt implements AutoCloseable {
    private final SemanticActionPort port;
    private final SemanticActionRequest request;
    private final BlockStateFingerprint expectedBefore;
    private final BlockStateFingerprint expectedAfter;
    private final long deadlineTick;
    private Phase phase = Phase.PRECHECK;
    private SemanticActionPreparationAttempt preparation;
    private SemanticActionAttempt action;
    private boolean closed;

    public KnownBlockMutationAttempt(
            SemanticActionPort port,
            SemanticActionRequest request,
            long admittedClientTick,
            long deadlineTick) {
        this.port = Objects.requireNonNull(port, "port");
        this.request = Objects.requireNonNull(request, "request");
        request.validateAdmissionTick(admittedClientTick);
        if (deadlineTick <= admittedClientTick) {
            throw new IllegalArgumentException("deadline must follow admission");
        }
        this.deadlineTick = deadlineTick;
        expectedBefore = expectedBefore(request);
        expectedAfter = expectedAfter(request);
    }

    public TickResult tick(long clientTick) {
        requireOpen();
        if (clientTick < 0L) throw new IllegalArgumentException("clientTick must be non-negative");
        if (clientTick >= deadlineTick) return fail("mutation_deadline");

        return switch (phase) {
            case PRECHECK -> precheck(clientTick);
            case PREPARING -> prepare();
            case CONFIRMING -> confirm();
        };
    }

    private TickResult precheck(long clientTick) {
        var frame = Objects.requireNonNull(port.observe(request), "adapter returned no frame");
        if (frame.liveBlockState().filter(expectedAfter::matches).isPresent()) {
            close();
            return TickResult.succeeded();
        }
        if (!frame.universalSafetyClear()
                || frame.liveBlockState().isPresent()
                        && frame.liveBlockState().filter(expectedBefore::matches).isEmpty()) {
            return fail("mutation_precondition_changed");
        }
        preparation = Objects.requireNonNull(
                port.beginPreparation(request, deadlineTick),
                "adapter returned no preparation");
        if (!request.kind().equals(preparation.kind())
                || preparation.issuedClientTick() != clientTick
                || preparation.leaseExpiresAtClientTick() != deadlineTick) {
            return fail("mutation_preparation_contract");
        }
        phase = Phase.PREPARING;
        return TickResult.running();
    }

    private TickResult prepare() {
        var evidence = Objects.requireNonNull(
                port.preparationEvidence(preparation), "adapter returned no preparation evidence");
        if (!preparation.attemptId().equals(evidence.attemptId())) {
            return fail("mutation_preparation_contract");
        }
        if (evidence.failure() != null) {
            return fail(evidence.failure().code().toLowerCase(Locale.ROOT));
        }
        if (!evidence.prepared()) {
            port.maintainPreparation(preparation);
            return TickResult.running();
        }
        var live = evidence.liveBlockState().orElseThrow();
        if (expectedAfter.matches(live)) {
            close();
            return TickResult.succeeded();
        }
        if (!expectedBefore.matches(live)) return fail("mutation_precondition_changed");
        action = Objects.requireNonNull(
                port.dispatchPrepared(request, preparation, deadlineTick),
                "adapter returned no mutation action");
        if (!request.kind().equals(action.kind())
                || action.leaseExpiresAtClientTick() != deadlineTick) {
            return fail("mutation_dispatch_contract");
        }
        port.releasePreparation(preparation);
        preparation = null;
        phase = Phase.CONFIRMING;
        return TickResult.running();
    }

    private TickResult confirm() {
        var evidence = Objects.requireNonNull(port.evidence(action), "adapter returned no evidence");
        if (!action.attemptId().equals(evidence.attemptId())) {
            return fail("mutation_evidence_contract");
        }
        if (evidence.failure() != null) {
            return fail(evidence.failure().code().toLowerCase(Locale.ROOT));
        }
        if (evidence.acknowledged()
                && evidence.serverBlockState().filter(expectedAfter::matches).isPresent()) {
            port.stopInput(action);
            close();
            return TickResult.succeeded();
        }
        port.maintain(action);
        return TickResult.running();
    }

    private TickResult fail(String evidence) {
        close();
        return TickResult.failed(evidence);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            if (action != null) port.release(action);
        } finally {
            try {
                if (preparation != null) port.releasePreparation(preparation);
            } finally {
                port.retire(request);
            }
        }
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("mutation attempt is closed");
    }

    private static BlockStateFingerprint expectedBefore(SemanticActionRequest request) {
        return switch (request) {
            case BreakBlockRequest value -> value.expectedBefore();
            case PlaceBlockRequest value -> value.expectedBefore();
            case UseItemOnBlockRequest value -> value.expectedBefore();
            case InteractBlockRequest value -> value.expectedBefore();
            default -> throw new IllegalArgumentException("request is not a finite block mutation");
        };
    }

    private static BlockStateFingerprint expectedAfter(SemanticActionRequest request) {
        return switch (request) {
            case BreakBlockRequest value -> value.expectedAfter();
            case PlaceBlockRequest value -> value.expectedAfter();
            case UseItemOnBlockRequest value -> value.expectedAfter();
            case InteractBlockRequest value -> value.expectedAfter();
            default -> throw new IllegalArgumentException("request is not a finite block mutation");
        };
    }

    private enum Phase { PRECHECK, PREPARING, CONFIRMING }

    public record TickResult(Status status, String evidence) {
        private static TickResult running() { return new TickResult(Status.RUNNING, null); }
        private static TickResult succeeded() { return new TickResult(Status.SUCCEEDED, null); }
        private static TickResult failed(String evidence) {
            return new TickResult(Status.FAILED, Objects.requireNonNull(evidence, "evidence"));
        }
    }

    public enum Status { RUNNING, SUCCEEDED, FAILED }
}
