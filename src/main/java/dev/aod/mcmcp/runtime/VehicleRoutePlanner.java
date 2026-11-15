package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.navigation.NavCell;
import java.util.*;
import java.util.function.Function;

/** Local bounded search over edges already checked with the actual vehicle and passenger volume. */
final class VehicleRoutePlanner {
    static List<NavCell> plan(NavCell start, NavCell goal, Function<NavCell, List<NavCell>> neighbors) {
        var open = new PriorityQueue<NavCell>(Comparator.comparingDouble(cell -> cell.distanceTo(goal)));
        var parents = new LinkedHashMap<NavCell, NavCell>();
        parents.put(start, null);
        open.add(start);
        NavCell best = start;
        int expanded = 0;
        while (!open.isEmpty() && expanded++ < 128) {
            var current = open.remove();
            if (current.distanceTo(goal) < best.distanceTo(goal)) best = current;
            if (current.equals(goal)) break;
            for (var next : neighbors.apply(current)) {
                if (next.distanceTo(start) > 5 || parents.containsKey(next)) continue;
                parents.put(next, current);
                open.add(next);
            }
        }
        if (best.equals(start)) return List.of();
        var route = new ArrayList<NavCell>();
        for (var at = best; at != null; at = parents.get(at)) route.add(at);
        Collections.reverse(route);
        return List.copyOf(route);
    }
}
