package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.observation.DeliveredPolicyEvidenceStore;
import dev.aod.mcmcp.agent.observation.ObservationValues.BlockPosition;
import dev.aod.mcmcp.agent.observation.ObservationValues.ResourceId;

import java.time.Duration;

/** One admission's bounded renderer wait, before any game interaction has been dispatched. */
final class SurfacePreflightRecovery {
    enum Decision { READY, RENDERER_EVIDENCE_MISSING, RENDERER_EVIDENCE_TIMEOUT,
        DELIVERY_EXPIRED, TARGET_NOT_DELIVERED }

    record Target(ActionDsl.Position position, String block) { }

    private ActionDsl.Budget budget;
    private Target target;
    private DeliveredPolicyEvidenceStore.SurfaceLease lease;
    private boolean initialized;
    private volatile boolean waited;
    private long firstWaitTick;
    private long firstWaitNanos;

    SurfacePreflightRecovery(ActionDsl.Budget budget) { this.budget = budget; }

    static Target target(ActionDsl.Node primitive) {
        return switch (primitive) {
            case ActionDsl.ApproachKnownSurface node -> new Target(node.target(), node.expectedBlock());
            case ActionDsl.InspectKnownContainer node -> new Target(node.target(), node.expectedBlock());
            case ActionDsl.TakeKnownContainerStack node -> new Target(node.target(), node.expectedBlock());
            case ActionDsl.StoreKnownContainerStack node -> new Target(node.target(), node.expectedBlock());
            case null, default -> null;
        };
    }

    void capture(ActionDsl.Node primitive, DeliveredPolicyEvidenceStore store) {
        if (initialized) return;
        initialized = true;
        target = target(primitive);
        if (target != null) {
            var position = target.position();
            lease = store.captureSurfaceLease(new BlockPosition(new ResourceId(position.dimension()),
                    position.x(), position.y(), position.z()), new ResourceId(target.block()));
        }
    }

    DeliveredPolicyEvidenceStore.SurfaceLease lease() { return lease; }

    boolean applies(ActionDsl.Node primitive) {
        return target != null && target.equals(target(primitive));
    }

    void effectiveBudget(ActionDsl.Budget effective) { budget = effective; }

    Decision evaluate(DeliveredPolicyEvidenceStore store, long tick, long now, boolean currentFog) {
        if (lease == null) return Decision.READY;
        return evaluate(store.surfaceLeaseStatus(lease), tick, now, currentFog);
    }

    Decision evaluate(DeliveredPolicyEvidenceStore.SurfaceLeaseStatus delivery,
            long tick, long now, boolean currentFog) {
        if (delivery == DeliveredPolicyEvidenceStore.SurfaceLeaseStatus.DELIVERY_EXPIRED) {
            return Decision.DELIVERY_EXPIRED;
        }
        if (delivery != DeliveredPolicyEvidenceStore.SurfaceLeaseStatus.VALID) {
            return Decision.TARGET_NOT_DELIVERED;
        }
        if (waited && (tick < firstWaitTick || now - firstWaitNanos < 0
                || tick - firstWaitTick >= budget.maxTicks()
                || now - firstWaitNanos >= Duration.ofMillis(budget.maxDurationMillis()).toNanos())) {
            return Decision.RENDERER_EVIDENCE_TIMEOUT;
        }
        if (!currentFog) {
            if (!waited) {
                firstWaitTick = tick;
                firstWaitNanos = now;
                waited = true;
            }
            return Decision.RENDERER_EVIDENCE_MISSING;
        }
        return Decision.READY;
    }

    long executionStartNanos(long now) { return waited ? firstWaitNanos : now; }
    long consumedTicks(long tick) { return waited ? Math.max(0L, tick - firstWaitTick) : 0L; }
    boolean hasWaited() { return waited; }
}
