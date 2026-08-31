package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.routine.KnownPillarUpRequest;
import dev.aod.mcmcp.routine.PillarUpPort;

import java.util.Objects;

/** Tick state machine which can dispatch exactly one jump and one placement. */
public final class KnownPillarUpAttempt implements AutoCloseable {
    public static final int MAX_TICKS = 300;

    private final PillarUpPort port;
    private final KnownPillarUpRequest request;
    private final long deadlineTick;
    private Phase phase = Phase.BEGIN;
    private PillarUpPort.Handle handle;
    private long lastTick;
    private boolean closed;

    public KnownPillarUpAttempt(
            PillarUpPort port,
            KnownPillarUpRequest request,
            long admittedClientTick,
            long deadlineTick) {
        this.port = Objects.requireNonNull(port, "port");
        this.request = Objects.requireNonNull(request, "request");
        if (admittedClientTick < 0L
                || deadlineTick <= admittedClientTick
                || deadlineTick > admittedClientTick + MAX_TICKS) {
            throw new IllegalArgumentException("pillar deadline is outside its fixed bound");
        }
        this.deadlineTick = deadlineTick;
        lastTick = admittedClientTick;
    }

    public TickResult tick(long clientTick) {
        requireOpen();
        if (clientTick < lastTick) return fail("pillar_non_monotonic_tick");
        lastTick = clientTick;
        if (clientTick >= deadlineTick) return fail("pillar_deadline");
        try {
            if (phase == Phase.BEGIN) {
                handle = Objects.requireNonNull(
                        port.begin(request, deadlineTick), "adapter returned no pillar handle");
                if (handle.issuedClientTick() != clientTick
                        || handle.leaseExpiresAtClientTick() != deadlineTick) {
                    return fail("pillar_adapter_contract");
                }
                phase = Phase.PREPARING;
                return running("pillar_preparing");
            }
            port.maintain(handle);
            PillarUpPort.Evidence evidence = Objects.requireNonNull(
                    port.evidence(handle), "adapter returned no pillar evidence");
            if (!handle.attemptId().equals(evidence.attemptId())
                    || evidence.clientTick() != clientTick) {
                return fail("pillar_adapter_contract");
            }
            if (evidence.failure() != null) return fail(evidence.failure());
            if (phase == Phase.PREPARING) {
                if (!evidence.prepared()) return running("pillar_preparing");
                port.startJump(handle);
                phase = Phase.JUMPING;
                return running("pillar_jumping");
            }
            if (phase == Phase.JUMPING) {
                if (!evidence.targetCleared()) return running("pillar_jumping");
                port.placeOnce(handle);
                phase = Phase.CONFIRMING;
                return running("pillar_confirming");
            }
            if (evidence.blockConfirmed()
                    && evidence.inventoryConfirmed()
                    && evidence.yConfirmed()) {
                TickResult result = new TickResult(Status.SUCCEEDED, "pillar_complete", 1);
                close();
                return result;
            }
            return running("pillar_confirming");
        } catch (RuntimeException | LinkageError failure) {
            return fail("pillar_adapter_failed");
        }
    }

    private TickResult running(String evidence) {
        return new TickResult(Status.RUNNING, evidence, 0);
    }

    private TickResult fail(String evidence) {
        TickResult result = new TickResult(Status.FAILED, evidence, 0);
        close();
        return result;
    }

    @Override
    public void close() {
        if (closed) return;
        if (handle != null) port.release(handle);
        closed = true;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("pillar attempt is closed");
    }

    private enum Phase { BEGIN, PREPARING, JUMPING, CONFIRMING }

    public record TickResult(Status status, String evidence, int placedDelta) {
        public TickResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(evidence, "evidence");
            if (placedDelta < 0 || placedDelta > 1) {
                throw new IllegalArgumentException("placedDelta must be 0..1");
            }
        }
    }

    public enum Status { RUNNING, SUCCEEDED, FAILED }
}
