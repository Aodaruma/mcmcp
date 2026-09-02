package dev.aod.mcmcp.agent.navigation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.function.BooleanSupplier;

/** Deterministic A* over an immutable Known Traversability Map snapshot. */
public final class DeterministicAStar {
    /** Largest possible flat centerline route after reserving the first-pose uncertainty. */
    public static final double MAX_ROUTE_DISTANCE_BLOCKS =
            NavigationDistanceBudget.MAX_ROUTED_DISTANCE_BLOCKS
                    / NavigationDistanceBudget.TRAJECTORY_FACTOR;
    public static final int MAX_EXPANDED_NODES = 2_048;
    private static final double EPSILON = 1.0e-9;

    private static final Comparator<OpenNode> OPEN_ORDER = Comparator
            .comparingDouble(OpenNode::estimatedTotal)
            .thenComparingDouble(OpenNode::heuristic)
            .thenComparingInt(OpenNode::probeEdges)
            .thenComparing(OpenNode::cell)
            .thenComparingDouble(OpenNode::distance);

    public SearchResult findRoute(
            KnownTraversabilitySnapshot snapshot, NavCell start, NavCell target) {
        return findRoute(
                snapshot, start, target, MAX_EXPANDED_NODES, () -> true, () -> { });
    }

    public SearchResult findRoute(
            KnownTraversabilitySnapshot snapshot,
            NavCell start,
            NavCell target,
            BooleanSupplier canContinue,
            Runnable onExpansion) {
        return findRoute(
                snapshot, start, target, MAX_EXPANDED_NODES, canContinue, onExpansion);
    }

    SearchResult findRoute(
            KnownTraversabilitySnapshot snapshot,
            NavCell start,
            NavCell target,
            int expansionLimit) {
        return findRoute(
                snapshot, start, target, expansionLimit, () -> true, () -> { });
    }

    SearchResult findRoute(
            KnownTraversabilitySnapshot snapshot,
            NavCell start,
            NavCell target,
            int expansionLimit,
            BooleanSupplier canContinue,
            Runnable onExpansion) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(canContinue, "canContinue");
        Objects.requireNonNull(onExpansion, "onExpansion");
        if (expansionLimit < 1 || expansionLimit > MAX_EXPANDED_NODES) {
            throw new IllegalArgumentException(
                    "expansionLimit must be within 1.." + MAX_EXPANDED_NODES);
        }
        if (!snapshot.dimension().equals(start.dimension())
                || !snapshot.dimension().equals(target.dimension())) {
            return SearchResult.failure(FailureReason.DIMENSION_MISMATCH, List.of());
        }
        if (!NavigationDistanceBudget.searchCostFits(
                start.distanceTo(target) * NavigationDistanceBudget.TRAJECTORY_FACTOR)) {
            return SearchResult.failure(FailureReason.TARGET_TOO_FAR, List.of());
        }
        if (!snapshot.containsDestination(target)) {
            return SearchResult.failure(FailureReason.TARGET_UNKNOWN, List.of());
        }
        if (start.equals(target)) {
            return SearchResult.found(
                    RoutePlan.from(snapshot, List.of(start), List.of()), List.of(start));
        }

        var open = new PriorityQueue<>(OPEN_ORDER);
        var best = new HashMap<NavCell, Score>();
        var predecessor = new HashMap<NavCell, TraversabilityEdge>();
        var closed = new HashSet<NavCell>();
        var expanded = new ArrayList<NavCell>();
        double initialHeuristic = start.distanceTo(target)
                * NavigationDistanceBudget.TRAJECTORY_FACTOR;
        best.put(start, new Score(0, 0));
        open.add(new OpenNode(start, 0, initialHeuristic, 0));

        while (!open.isEmpty()) {
            if (!canContinue.getAsBoolean()) {
                return SearchResult.failure(FailureReason.SEARCH_CANCELLED, expanded);
            }
            OpenNode current = open.remove();
            Score currentBest = best.get(current.cell());
            if (currentBest == null || !current.matches(currentBest) || closed.contains(current.cell())) {
                continue;
            }
            if (expanded.size() >= expansionLimit) {
                return SearchResult.failure(FailureReason.SEARCH_LIMIT, expanded);
            }
            closed.add(current.cell());
            expanded.add(current.cell());
            onExpansion.run();
            if (current.cell().equals(target)) {
                return SearchResult.found(
                        reconstruct(snapshot, start, target, predecessor), expanded);
            }

            for (TraversabilityEdge edge : snapshot.outgoing(current.cell())) {
                if (!edge.traversable() || !cornerClear(snapshot, edge)) continue;
                NavCell next = edge.key().to();
                if (closed.contains(next)) continue;
                double candidateDistance = current.distance()
                        + NavigationDistanceBudget.edgeCost(edge);
                if (!NavigationDistanceBudget.searchCostFits(candidateDistance)) {
                    continue;
                }
                int candidateProbes = current.probeEdges() + (edge.requiresProbe() ? 1 : 0);
                Score known = best.get(next);
                if (known != null && !better(candidateDistance, candidateProbes, known)) continue;

                best.put(next, new Score(candidateDistance, candidateProbes));
                predecessor.put(next, edge);
                double heuristic = next.distanceTo(target)
                        * NavigationDistanceBudget.TRAJECTORY_FACTOR;
                open.add(new OpenNode(next, candidateDistance, heuristic, candidateProbes));
            }
        }
        return SearchResult.failure(FailureReason.NO_PATH, expanded);
    }

    private static boolean better(double distance, int probes, Score known) {
        if (distance < known.distance() - EPSILON) return true;
        return Math.abs(distance - known.distance()) <= EPSILON && probes < known.probeEdges();
    }

    private static boolean cornerClear(
            KnownTraversabilitySnapshot snapshot, TraversabilityEdge edge) {
        NavCell from = edge.key().from();
        NavCell to = edge.key().to();
        if (!from.horizontallyDiagonalTo(to)) return true;

        NavCell xSide = new NavCell(from.dimension(), to.x(), from.y(), from.z());
        NavCell zSide = new NavCell(from.dimension(), from.x(), from.y(), to.z());
        return confirmed(snapshot, new TraversabilityEdge.Key(from, xSide))
                && confirmed(snapshot, new TraversabilityEdge.Key(from, zSide));
    }

    private static boolean confirmed(
            KnownTraversabilitySnapshot snapshot, TraversabilityEdge.Key key) {
        return snapshot.edge(key)
                .map(edge -> edge.status() == TraversabilityEdge.Status.CONFIRMED)
                .orElse(false);
    }

    private static RoutePlan reconstruct(
            KnownTraversabilitySnapshot snapshot,
            NavCell start,
            NavCell target,
            Map<NavCell, TraversabilityEdge> predecessor) {
        var reversedCells = new ArrayList<NavCell>();
        var reversedEdges = new ArrayList<TraversabilityEdge>();
        NavCell cursor = target;
        reversedCells.add(cursor);
        while (!cursor.equals(start)) {
            TraversabilityEdge edge = predecessor.get(cursor);
            if (edge == null) {
                throw new IllegalStateException("A* predecessor chain is incomplete");
            }
            reversedEdges.add(edge);
            cursor = edge.key().from();
            reversedCells.add(cursor);
        }
        Collections.reverse(reversedCells);
        Collections.reverse(reversedEdges);
        return RoutePlan.from(snapshot, reversedCells, reversedEdges);
    }

    public enum FailureReason {
        DIMENSION_MISMATCH,
        TARGET_TOO_FAR,
        TARGET_UNKNOWN,
        NO_PATH,
        SEARCH_LIMIT,
        SEARCH_CANCELLED
    }

    public record SearchResult(
            Optional<RoutePlan> route,
            Optional<FailureReason> failure,
            List<NavCell> expandedCells) {
        public SearchResult {
            route = Objects.requireNonNull(route, "route");
            failure = Objects.requireNonNull(failure, "failure");
            expandedCells = List.copyOf(Objects.requireNonNull(expandedCells, "expandedCells"));
            if (route.isPresent() == failure.isPresent()) {
                throw new IllegalArgumentException("exactly one of route or failure is required");
            }
        }

        static SearchResult found(RoutePlan route, List<NavCell> expanded) {
            return new SearchResult(Optional.of(route), Optional.empty(), expanded);
        }

        static SearchResult failure(FailureReason reason, List<NavCell> expanded) {
            return new SearchResult(Optional.empty(), Optional.of(reason), expanded);
        }

        public boolean found() {
            return route.isPresent();
        }
    }

    private record Score(double distance, int probeEdges) {
    }

    private record OpenNode(
            NavCell cell, double distance, double heuristic, int probeEdges) {
        double estimatedTotal() {
            return distance + heuristic;
        }

        boolean matches(Score score) {
            return Math.abs(distance - score.distance()) <= EPSILON
                    && probeEdges == score.probeEdges();
        }
    }
}
