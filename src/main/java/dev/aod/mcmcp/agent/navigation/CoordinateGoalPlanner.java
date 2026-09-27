package dev.aod.mcmcp.agent.navigation;

import dev.aod.mcmcp.agent.safety.Locomotion;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Internal, single-owner planning state for one coordinate goal; never performs movement.
 * Retain this instance for the whole job, including failed execution and fresh observations.
 */
final class CoordinateGoalPlanner {
    static final int MAX_EDGES = 4_096;
    static final int MAX_CANDIDATES = 64;
    static final int MAX_EXPANSIONS = DeterministicAStar.MAX_EXPANDED_NODES;
    private final UUID session;
    private final NavCell goal;
    private double issuedDistance = Double.POSITIVE_INFINITY;
    private long newestRevision = -1;

    CoordinateGoalPlanner(UUID session, NavCell goal) {
        this.session = Objects.requireNonNull(session, "session");
        this.goal = Objects.requireNonNull(goal, "goal");
    }

    Result plan(KnownTraversabilitySnapshot map, NavCell start, UUID currentSession,
            long currentRevision, Budget budget, BooleanSupplier cancelled) {
        Objects.requireNonNull(map, "map");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(cancelled, "cancelled");
        if (stopped(cancelled)) return empty(Status.CANCELLED, 0, 0);
        if (!session.equals(currentSession) || !session.equals(map.worldSessionId())
                || !goal.dimension().equals(map.dimension())
                || !goal.dimension().equals(start.dimension())) {
            return empty(Status.WORLD_MISMATCH, 0, 0);
        }
        if (currentRevision != map.worldRevision() || currentRevision < newestRevision) {
            return empty(Status.STALE_MAP, 0, 0);
        }
        newestRevision = currentRevision;
        if (map.edges().size() > budget.edges()) return empty(Status.LIMIT, 0, 0);

        var candidates = new TreeSet<NavCell>(Comparator
                .comparingDouble((NavCell cell) -> cell.distanceTo(goal))
                .thenComparing(Comparator.naturalOrder()));
        for (TraversabilityEdge edge : map.edges().values()) {
            if (stopped(cancelled)) return empty(Status.CANCELLED, 0, 0);
            if (!session.equals(edge.worldSessionId())
                    || !map.dimension().equals(edge.key().from().dimension())) {
                return empty(Status.WORLD_MISMATCH, 0, 0);
            }
            // Older unaffected evidence is valid under KnownTraversabilityMap's invalidation contract.
            if (edge.worldRevision() > currentRevision) return empty(Status.STALE_MAP, 0, 0);
            if (edge.destination()) {
                candidates.add(edge.key().to());
                if (edge.locomotion() == Locomotion.GROUND) candidates.add(edge.key().from());
            }
        }
        if (stopped(cancelled)) return empty(Status.CANCELLED, 0, 0);
        if (start.equals(goal) && candidates.contains(goal)) {
            return new Result(Status.REACHED_KNOWN_GOAL,
                    Optional.of(RoutePlan.from(map, List.of(start), List.of())), 0, 0);
        }

        double threshold = Math.min(start.distanceTo(goal), issuedDistance);
        int attempts = 0;
        int expanded = 0;
        var search = new DeterministicAStar();
        for (NavCell candidate : candidates) {
            if (stopped(cancelled)) return empty(Status.CANCELLED, attempts, expanded);
            // Strict progress prevents reissuing a failed waypoint or cycling on unchanged evidence.
            if (!(candidate.distanceTo(goal) < threshold)) continue;
            if (attempts >= budget.candidates() || expanded >= budget.expansions()) {
                return empty(Status.LIMIT, attempts, expanded);
            }
            attempts++;
            var found = search.findRoute(map, start, candidate, budget.expansions() - expanded,
                    () -> !stopped(cancelled), () -> { });
            expanded += found.expandedCells().size();
            if (stopped(cancelled)) return empty(Status.CANCELLED, attempts, expanded);
            if (found.found()) {
                issuedDistance = candidate.distanceTo(goal);
                return new Result(candidate.equals(goal) ? Status.KNOWN_GOAL_ROUTE : Status.PARTIAL_WAYPOINT,
                        found.route(), attempts, expanded);
            }
            if (found.failure().orElseThrow() == DeterministicAStar.FailureReason.SEARCH_CANCELLED) {
                return empty(Status.CANCELLED, attempts, expanded);
            }
            if (found.failure().orElseThrow() == DeterministicAStar.FailureReason.SEARCH_LIMIT) {
                return empty(Status.LIMIT, attempts, expanded);
            }
        }
        return empty(Status.BLOCKED, attempts, expanded);
    }

    private static boolean stopped(BooleanSupplier cancelled) {
        return Thread.currentThread().isInterrupted() || cancelled.getAsBoolean();
    }

    private static Result empty(Status status, int candidates, int expansions) {
        return new Result(status, Optional.empty(), candidates, expansions);
    }

    enum Status {
        REACHED_KNOWN_GOAL, KNOWN_GOAL_ROUTE, PARTIAL_WAYPOINT,
        BLOCKED, LIMIT, CANCELLED, STALE_MAP, WORLD_MISMATCH
    }

    record Result(Status status, Optional<RoutePlan> route, int candidates, int expansions) { }

    record Budget(int edges, int candidates, int expansions) {
        static final Budget DEFAULT = new Budget(MAX_EDGES, MAX_CANDIDATES, MAX_EXPANSIONS);

        Budget {
            if (edges < 0 || edges > MAX_EDGES || candidates < 0 || candidates > MAX_CANDIDATES
                    || expansions < 0 || expansions > MAX_EXPANSIONS) {
                throw new IllegalArgumentException("navigation budget exceeds fixed bounds");
            }
        }
    }
}
