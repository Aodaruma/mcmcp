package dev.aod.mcmcp.routine;

import java.util.Objects;
import java.util.Optional;

/** Fresh raw client-tick facts consumed by Phase 3 domain state machines. */
public record SemanticActionFrame(
        long clientTick,
        long observationRevision,
        boolean worldReady,
        boolean clientFocused,
        boolean playerAlive,
        boolean healthSafe,
        boolean visibleThreatClear,
        boolean screenClear,
        Optional<BlockStateFingerprint> liveBlockState,
        boolean blockInReach,
        boolean crosshairOnBlock,
        boolean entityResolved,
        Optional<String> entityType,
        boolean entityVisible,
        boolean entityLineOfSight,
        boolean entityInReach,
        boolean crosshairOnEntity,
        int goalItemCount,
        boolean inventoryServerSynchronized,
        double playerX,
        double playerY,
        double playerZ,
        double playerHorizontalVelocitySquared,
        boolean onGround,
        boolean routeSafe,
        String routeCheckReason,
        long positionCorrectionRevision,
        boolean safeToRetry) {
    static final String PROBE_NOT_CURRENTLY_VISIBLE = "probe_not_currently_visible";
    static final String VISIBLE_ROUTE_OCCUPIED = "visible_route_occupied";
    static final String ROUTE_REOBSERVATION_WAIT = "route_reobservation_wait";
    static final String ROUTE_OCCUPANCY_WAIT = "route_occupancy_wait";
    static final String STATIONARY_NAVIGATION = "stationary_navigation_no_lookahead";

    public SemanticActionFrame {
        if (clientTick < 0 || observationRevision < 0 || positionCorrectionRevision < 0) {
            throw new IllegalArgumentException("frame revisions must be non-negative");
        }
        Objects.requireNonNull(liveBlockState, "liveBlockState");
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(routeCheckReason, "routeCheckReason");
        if (goalItemCount < 0) {
            throw new IllegalArgumentException("goal item count must be non-negative");
        }
        if (!Double.isFinite(playerX) || !Double.isFinite(playerY) || !Double.isFinite(playerZ)
                || !Double.isFinite(playerHorizontalVelocitySquared)
                || playerHorizontalVelocitySquared < 0) {
            throw new IllegalArgumentException("player motion facts must be finite and non-negative");
        }
        if (!entityResolved && entityType.isPresent()) {
            throw new IllegalArgumentException("an unresolved entity cannot have a current type");
        }
    }

    public boolean universalSafetyClear() {
        return worldReady && clientFocused && playerAlive && healthSafe
                && visibleThreatClear && screenClear;
    }

    public boolean routeNeedsReobservation() {
        return PROBE_NOT_CURRENTLY_VISIBLE.equals(routeCheckReason)
                || ROUTE_REOBSERVATION_WAIT.equals(routeCheckReason);
    }

    public boolean routeTemporarilyOccupied() {
        return VISIBLE_ROUTE_OCCUPIED.equals(routeCheckReason)
                || ROUTE_OCCUPANCY_WAIT.equals(routeCheckReason);
    }

    public boolean routeTransientWait() {
        return routeSafe && (ROUTE_REOBSERVATION_WAIT.equals(routeCheckReason)
                || ROUTE_OCCUPANCY_WAIT.equals(routeCheckReason));
    }

    public boolean stationaryNavigation() {
        return routeSafe && STATIONARY_NAVIGATION.equals(routeCheckReason);
    }
}
