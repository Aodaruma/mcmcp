package dev.aod.mcmcp.agent.navigation;

import java.util.List;

/** Shared admission and execution rule; side-floor support is not corridor clearance. */
public final class DiagonalTraversal {
    private DiagonalTraversal() { }

    public static boolean directProofCurrent(KnownTraversabilitySnapshot map, TraversabilityEdge edge) {
        return edge.supportedDiagonal() && edge.traversable()
                && edge.worldSessionId().equals(map.worldSessionId())
                && edge.worldRevision() == map.worldRevision();
    }

    public static List<TraversabilityEdge.Key> requiredSides(
            KnownTraversabilitySnapshot map, TraversabilityEdge edge) {
        var from = edge.key().from();
        var to = edge.key().to();
        if (!from.horizontallyDiagonalTo(to) || directProofCurrent(map, edge)) return List.of();
        return List.of(new TraversabilityEdge.Key(from,
                        new NavCell(from.dimension(), to.x(), from.y(), from.z())),
                new TraversabilityEdge.Key(from,
                        new NavCell(from.dimension(), from.x(), from.y(), to.z())));
    }

    public static boolean clear(KnownTraversabilitySnapshot map, TraversabilityEdge edge) {
        return requiredSides(map, edge).stream().allMatch(key -> map.edge(key)
                .map(side -> side.status() == TraversabilityEdge.Status.CONFIRMED).orElse(false));
    }
}
