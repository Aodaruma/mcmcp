package dev.aod.mcmcp.agent.navigation;

import dev.aod.mcmcp.agent.safety.Locomotion;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** Internal, single-owner planning state for one coordinate goal; never performs movement.
 * Retain this instance for the whole job, including failed execution and fresh observations.
 */
public final class CoordinateGoalPlanner {
    static final int MAX_EDGES = 4_096;
    static final int MAX_CANDIDATES = 64;
    static final int MAX_EXPANSIONS = DeterministicAStar.MAX_EXPANDED_NODES;
    private static final int MAX_ISSUED_CELLS = 8_192;
    private final UUID session;
    private final NavCell goal;
    private final Predicate<NavCell> cellAllowed;
    private final Set<NavCell> issuedCells = new HashSet<>();
    private Map<TraversabilityEdge.Key, TraversabilityEdge> lastIssuedEvidence;
    private long newestRevision = -1;

    public CoordinateGoalPlanner(UUID session, NavCell goal) {
        this(session, goal, cell -> true);
    }

    public CoordinateGoalPlanner(UUID session, NavCell goal,
            Predicate<NavCell> cellAllowed) {
        this.session = Objects.requireNonNull(session, "session");
        this.goal = Objects.requireNonNull(goal, "goal");
        this.cellAllowed = Objects.requireNonNull(cellAllowed, "cellAllowed");
    }

    public Result plan(KnownTraversabilitySnapshot map, NavCell start, UUID currentSession,
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
        if (start.equals(goal) && candidates.contains(goal) && cellAllowed.test(goal)) {
            return new Result(Status.REACHED_KNOWN_GOAL,
                    Optional.of(RoutePlan.from(map, List.of(start), List.of())), 0, 0);
        }

        // A detour may initially increase distance to the goal. Require fresh evidence after each
        // waypoint and never select a cell already used by this job, even if replanning begins
        // at an older position after an execution failure.
        if (lastIssuedEvidence != null && lastIssuedEvidence.equals(map.edges())) {
            return empty(Status.BLOCKED, 0, 0);
        }
        // Visible but disconnected terrain must not consume the small A* candidate budget.
        // This is only a graph prefilter: A* still enforces route distance and proof budgets.
        var connected = new HashSet<NavCell>();
        var pending = new ArrayDeque<NavCell>();
        connected.add(start);
        pending.add(start);
        while (!pending.isEmpty()) {
            if (stopped(cancelled)) return empty(Status.CANCELLED, 0, 0);
            for (TraversabilityEdge edge : map.outgoing(pending.removeFirst())) {
                if (edge.traversable() && cellAllowed.test(edge.key().to())
                        && DiagonalTraversal.clear(map, edge)
                        && connected.add(edge.key().to())) {
                    pending.addLast(edge.key().to());
                }
            }
        }
        int attempts = 0;
        int expanded = 0;
        var search = new DeterministicAStar();
        for (NavCell candidate : candidates) {
            if (stopped(cancelled)) return empty(Status.CANCELLED, attempts, expanded);
            if (candidate.equals(start) || !cellAllowed.test(candidate)
                    || issuedCells.contains(candidate)
                    || !connected.contains(candidate)) continue;
            if (attempts >= budget.candidates() || expanded >= budget.expansions()) {
                return empty(Status.LIMIT, attempts, expanded);
            }
            attempts++;
            var found = search.findRoute(map, start, candidate, budget.expansions() - expanded,
                    () -> !stopped(cancelled), () -> { }, cellAllowed);
            expanded += found.expandedCells().size();
            if (stopped(cancelled)) return empty(Status.CANCELLED, attempts, expanded);
            if (found.found()) {
                var newlyIssued = new HashSet<>(found.route().orElseThrow().cells());
                newlyIssued.removeAll(issuedCells);
                if (issuedCells.size() + newlyIssued.size() > MAX_ISSUED_CELLS) {
                    return empty(Status.LIMIT, attempts, expanded);
                }
                issuedCells.addAll(newlyIssued);
                lastIssuedEvidence = map.edges();
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

    public enum Status {
        REACHED_KNOWN_GOAL, KNOWN_GOAL_ROUTE, PARTIAL_WAYPOINT,
        BLOCKED, LIMIT, CANCELLED, STALE_MAP, WORLD_MISMATCH
    }

    public record Result(Status status, Optional<RoutePlan> route, int candidates, int expansions) { }

    public record Budget(int edges, int candidates, int expansions) {
        public static final Budget DEFAULT = new Budget(MAX_EDGES, MAX_CANDIDATES, MAX_EXPANSIONS);

        public Budget {
            if (edges < 0 || edges > MAX_EDGES || candidates < 0 || candidates > MAX_CANDIDATES
                    || expansions < 0 || expansions > MAX_EXPANSIONS) {
                throw new IllegalArgumentException("navigation budget exceeds fixed bounds");
            }
        }
    }
}
