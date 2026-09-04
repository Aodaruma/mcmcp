package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.routine.AttackAttempt;
import dev.aod.mcmcp.routine.PredictionEvidence;
import dev.aod.mcmcp.routine.StationaryBreakPort;
import dev.aod.mcmcp.routine.StationaryBreakRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** One bounded normal-input break, successful only after server ACK and authoritative air. */
public final class KnownBlockBreakAttempt implements AutoCloseable {
    private final StationaryBreakPort port;
    private final StationaryBreakRequest request;
    private final AttackAttempt attack;
    private long lastPredictionSequence;
    private boolean inputStopped;
    private boolean closed;
    private boolean authoritativeBreakConfirmed;
    private boolean confirmedEffectRecorded;
    private boolean unknownEffectRecorded;
    private long lastClientTick;
    private long lastWorldRevision;
    private final int inventoryBefore;
    private final ArrayList<EffectDelta> pendingEffects = new ArrayList<>(1);

    public KnownBlockBreakAttempt(
            StationaryBreakPort port,
            StationaryBreakRequest request,
            long clientTick) {
        this.port = Objects.requireNonNull(port, "port");
        this.request = Objects.requireNonNull(request, "request");
        request.validateAdmissionTick(clientTick);
        var before = Objects.requireNonNull(
                port.observe(request), "adapter returned no stationary break frame");
        if (before.clientTick() > clientTick || !before.inventoryServerSynchronized()) {
            port.retire(request);
            throw new IllegalStateException("stationary break inventory baseline unavailable");
        }
        inventoryBefore = before.goalItemCount();
        lastWorldRevision = before.observationRevision();
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
        lastClientTick = clientTick;
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
        lastClientTick = Math.max(lastClientTick, evidence.clientTick());
        lastWorldRevision = Math.max(lastWorldRevision, evidence.observationRevision());
        if (evidence.confirmsBreakFrom(request.expectedSourceState())) {
            authoritativeBreakConfirmed = true;
            if (!inputStopped) {
                port.stopAttackInput(attack);
                inputStopped = true;
            }
        }
        if (authoritativeBreakConfirmed) {
            var frame = Objects.requireNonNull(
                    port.observe(request), "adapter returned no stationary break frame");
            if (frame.clientTick() > clientTick
                    || frame.observationRevision() < lastWorldRevision) {
                throw new IllegalStateException("stationary break evidence contract changed");
            }
            lastClientTick = Math.max(lastClientTick, frame.clientTick());
            lastWorldRevision = Math.max(lastWorldRevision, frame.observationRevision());
            if (frame.goalConfirmed(request.goal())
                    && frame.goalItemCount() > inventoryBefore) {
                recordConfirmedEffect(frame.goalItemCount());
                close();
                return TickResult.SUCCEEDED;
            }
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
            if (authoritativeBreakConfirmed) recordConfirmedEffect(null);
            close();
            return TickResult.SERVER_DENIED_OR_DESYNC;
        }
        return TickResult.RUNNING;
    }

    public boolean active() {
        return !closed;
    }

    public List<EffectDelta> drainEffectDeltas() {
        if (pendingEffects.isEmpty()) return List.of();
        List<EffectDelta> result = List.copyOf(pendingEffects);
        pendingEffects.clear();
        return result;
    }

    @Override
    public void close() {
        if (closed) return;
        if (authoritativeBreakConfirmed) {
            recordConfirmedEffect(null);
        } else {
            recordUnknownEffect();
        }
        try {
            port.releaseAttack(attack);
        } finally {
            port.retire(request);
        }
        // Commit the wrapper terminal state only after every owning adapter confirmed release.
        // A transient close failure must leave the exact AttackAttempt reachable for retry.
        closed = true;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("break attempt is closed");
    }

    private void recordConfirmedEffect(Integer inventoryCount) {
        if (confirmedEffectRecorded) return;
        var after = new LinkedHashMap<String, Object>();
        after.put("block", "minecraft:air");
        after.put("properties", Map.of());
        if (inventoryCount != null) after.put("inventory_count", inventoryCount);
        pendingEffects.add(new EffectDelta(
                sourceObservation(), after, AgentActionStore.Verification.CONFIRMED,
                lastClientTick, lastWorldRevision));
        confirmedEffectRecorded = true;
    }

    private void recordUnknownEffect() {
        if (unknownEffectRecorded) return;
        pendingEffects.add(new EffectDelta(
                sourceObservation(), Map.of(), AgentActionStore.Verification.UNKNOWN,
                lastClientTick, lastWorldRevision));
        unknownEffectRecorded = true;
    }

    private Map<String, Object> sourceObservation() {
        return Map.of(
                "block", request.expectedSourceState().blockId(),
                "properties", request.expectedSourceState().properties(),
                "expected_drop", request.goal().itemId(),
                "minimum_inventory_count", request.goal().minimumInventoryCount());
    }

    public record EffectDelta(
            Map<String, Object> observedBefore,
            Map<String, Object> observedAfter,
            AgentActionStore.Verification verification,
            long clientTick,
            long worldRevision) {
        public EffectDelta {
            observedBefore = Map.copyOf(Objects.requireNonNull(observedBefore, "observedBefore"));
            observedAfter = Map.copyOf(Objects.requireNonNull(observedAfter, "observedAfter"));
            Objects.requireNonNull(verification, "verification");
            if (clientTick < 0 || worldRevision < 0) {
                throw new IllegalArgumentException("effect clocks must be non-negative");
            }
        }
    }

    public enum TickResult { RUNNING, SUCCEEDED, SERVER_DENIED_OR_DESYNC }
}
