package dev.aod.mcmcp.agent.navigation;

import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** Bounded A* labels retain both distance and cumulative fall cost at each cell. */
final class DamageBudgetPathfinder {
    private static final double EPSILON = 1.0E-6;

    static DeterministicAStar.SearchResult find(KnownTraversabilitySnapshot map, NavCell start,
            NavCell target, int limit, BooleanSupplier allowed, Runnable expandedCallback,
            Predicate<NavCell> canEnter) {
        var queue = new PriorityQueue<Label>(Comparator
                .comparingDouble((Label label) -> label.distance + label.cell.distanceTo(target)
                        * NavigationDistanceBudget.TRAJECTORY_FACTOR)
                .thenComparingDouble(label -> label.damage).thenComparing(label -> label.cell)
                .thenComparingDouble(label -> label.distance));
        var labels = new HashMap<NavCell, List<Label>>();
        var expanded = new ArrayList<NavCell>();
        var first = new Label(start, 0, 0, null, null);
        queue.add(first); labels.put(start, new ArrayList<>(List.of(first)));
        while (!queue.isEmpty()) {
            if (!allowed.getAsBoolean()) return failure(DeterministicAStar.FailureReason.SEARCH_CANCELLED, expanded);
            var current = queue.remove();
            if (!labels.get(current.cell).contains(current)) continue;
            if (expanded.size() >= limit) return failure(DeterministicAStar.FailureReason.SEARCH_LIMIT, expanded);
            expanded.add(current.cell); expandedCallback.run();
            if (current.cell.equals(target)) {
                var cells = new ArrayList<NavCell>(); var edges = new ArrayList<TraversabilityEdge>();
                for (var cursor = current; cursor != null; cursor = cursor.previous) {
                    cells.add(cursor.cell); if (cursor.edge != null) edges.add(cursor.edge);
                }
                Collections.reverse(cells); Collections.reverse(edges);
                return DeterministicAStar.SearchResult.found(RoutePlan.from(map, cells, edges), expanded);
            }
            for (var edge : map.outgoing(current.cell)) {
                if (!edge.traversable() || !DiagonalTraversal.clear(map, edge) || !canEnter.test(edge.key().to())) continue;
                double distance = current.distance + NavigationDistanceBudget.edgeCost(edge);
                double damage = current.damage + map.fallDamage(edge.key());
                if (!NavigationDistanceBudget.searchCostFits(distance) || damage > map.fallDamageBudget() + EPSILON) continue;
                var candidates = labels.computeIfAbsent(edge.key().to(), ignored -> new ArrayList<>());
                if (candidates.stream().anyMatch(label -> label.distance <= distance + EPSILON && label.damage <= damage + EPSILON)) continue;
                candidates.removeIf(label -> distance <= label.distance + EPSILON && damage <= label.damage + EPSILON);
                var next = new Label(edge.key().to(), distance, damage, current, edge);
                candidates.add(next); queue.add(next);
            }
        }
        return failure(DeterministicAStar.FailureReason.NO_PATH, expanded);
    }

    private static DeterministicAStar.SearchResult failure(DeterministicAStar.FailureReason reason, List<NavCell> expanded) {
        return DeterministicAStar.SearchResult.failure(reason, expanded);
    }

    private static final class Label {
        final NavCell cell;
        final double distance, damage;
        final Label previous;
        final TraversabilityEdge edge;
        Label(NavCell cell, double distance, double damage, Label previous, TraversabilityEdge edge) {
            this.cell = cell; this.distance = distance; this.damage = damage; this.previous = previous; this.edge = edge;
        }
    }
}
