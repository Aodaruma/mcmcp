package dev.aod.mcmcp.agent.navigation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/** Immutable, deterministically ordered view consumed by the pure pathfinder. */
public final class KnownTraversabilitySnapshot {
    private final UUID worldSessionId;
    private final String dimension;
    private final long worldRevision;
    private final NavigableMap<TraversabilityEdge.Key, TraversabilityEdge> edges;
    private final NavigableMap<NavCell, List<TraversabilityEdge>> outgoing;

    KnownTraversabilitySnapshot(
            UUID worldSessionId,
            String dimension,
            long worldRevision,
            Map<TraversabilityEdge.Key, TraversabilityEdge> source) {
        this.worldSessionId = Objects.requireNonNull(worldSessionId, "worldSessionId");
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        if (worldRevision < 0) {
            throw new IllegalArgumentException("worldRevision must be non-negative");
        }
        this.worldRevision = worldRevision;

        var edgeCopy = new TreeMap<TraversabilityEdge.Key, TraversabilityEdge>();
        edgeCopy.putAll(Objects.requireNonNull(source, "source"));
        edges = Collections.unmodifiableNavigableMap(edgeCopy);

        var adjacency = new TreeMap<NavCell, List<TraversabilityEdge>>();
        for (TraversabilityEdge edge : edgeCopy.values()) {
            adjacency.computeIfAbsent(edge.key().from(), ignored -> new ArrayList<>()).add(edge);
        }
        var immutableAdjacency = new TreeMap<NavCell, List<TraversabilityEdge>>();
        adjacency.forEach((cell, values) -> immutableAdjacency.put(cell, List.copyOf(values)));
        outgoing = Collections.unmodifiableNavigableMap(immutableAdjacency);
    }

    public UUID worldSessionId() {
        return worldSessionId;
    }

    public String dimension() {
        return dimension;
    }

    public long worldRevision() {
        return worldRevision;
    }

    public NavigableMap<TraversabilityEdge.Key, TraversabilityEdge> edges() {
        return edges;
    }

    public Optional<TraversabilityEdge> edge(TraversabilityEdge.Key key) {
        return Optional.ofNullable(edges.get(Objects.requireNonNull(key, "key")));
    }

    public List<TraversabilityEdge> outgoing(NavCell from) {
        return outgoing.getOrDefault(Objects.requireNonNull(from, "from"), List.of());
    }

    public boolean containsCell(NavCell cell) {
        Objects.requireNonNull(cell, "cell");
        for (TraversabilityEdge edge : edges.values()) {
            if (edge.status() != TraversabilityEdge.Status.STALE
                    && (edge.key().from().equals(cell) || edge.key().to().equals(cell))) {
                return true;
            }
        }
        return false;
    }
}
