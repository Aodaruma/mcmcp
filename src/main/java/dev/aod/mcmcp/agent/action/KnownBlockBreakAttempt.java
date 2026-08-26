package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.routine.AttackAttempt;
import dev.aod.mcmcp.routine.PredictionEvidence;
import dev.aod.mcmcp.routine.StationaryBreakPort;
import dev.aod.mcmcp.routine.StationaryBreakRequest;

import java.util.Objects;

/** One bounded normal-input break, successful only after server ACK and authoritative air. */
public final class KnownBlockBreakAttempt implements AutoCloseable {
    private final StationaryBreakPort port;
    private final StationaryBreakRequest request;
    private final AttackAttempt attack;
    private long lastPredictionSequence;
    private boolean inputStopped;
    private boolean closed;

    public KnownBlockBreakAttempt(
            StationaryBreakPort port,
            StationaryBreakRequest request,
            long clientTick) {
        this.port = Objects.requireNonNull(port, "port");
        this.request = Objects.requireNonNull(request, "request");
        request.validateAdmissionTick(clientTick);
        long leaseExpiry = Math.min(
                request.hardDeadlineClientTick(),
                clientTick + request.attackLeaseTicks());
        attack = Objects.requireNonNull(
                port.beginAgentAttack(request, leaseExpiry),
                "adapter returned no attack attempt");
        if (!attack.target().equals(request.target())
                || attack.leaseExpiresAtClientTick() <= clientTick
                || attack.leaseExpiresAtClientTick() > leaseExpiry) {
            try {
                port.releaseAttack(attack);
            } finally {
                port.retire(request);
            }
            throw new IllegalStateException("attack adapter contract violation");
        }
        lastPredictionSequence = attack.predictionSequence();
    }

    public TickResult tick(long clientTick, boolean sourceControlled) {
        requireOpen();
        if (clientTick < 0L) {
            throw new IllegalArgumentException("clientTick must be non-negative");
        }
        PredictionEvidence evidence = Objects.requireNonNull(
                port.predictionEvidence(attack), "adapter returned no prediction evidence");
        if (Long.compareUnsigned(evidence.predictionSequence(), lastPredictionSequence) < 0) {
            throw new IllegalStateException("prediction sequence moved backwards");
        }
        lastPredictionSequence = evidence.predictionSequence();
        if (evidence.confirmsBreakFrom(request.expectedSourceState())) {
            close();
            return TickResult.SUCCEEDED;
        }
        if ((!sourceControlled || evidence.serverVerifiedTransition()
                || clientTick >= attack.leaseExpiresAtClientTick()) && !inputStopped) {
            port.stopAttackInput(attack);
            inputStopped = true;
        }
        if (!inputStopped) {
            port.holdAttack(attack);
        }
        if (clientTick >= request.hardDeadlineClientTick()) {
            close();
            return TickResult.SERVER_DENIED_OR_DESYNC;
        }
        return TickResult.RUNNING;
    }

    public boolean active() {
        return !closed;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            port.releaseAttack(attack);
        } finally {
            port.retire(request);
        }
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("break attempt is closed");
    }

    public enum TickResult { RUNNING, SUCCEEDED, SERVER_DENIED_OR_DESYNC }
}
