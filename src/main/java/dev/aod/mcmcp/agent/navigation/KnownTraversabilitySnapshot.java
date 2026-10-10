package dev.aod.mcmcp.agent.navigation;

import dev.aod.mcmcp.agent.safety.Locomotion;

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
    private final Map<TraversabilityEdge.Key, Double> fallDamage;
    private final double fallDamageBudget;

    KnownTraversabilitySnapshot(
            UUID worldSessionId,
            String dimension,
            long worldRevision,
            Map<TraversabilityEdge.Key, TraversabilityEdge> source) {
        this(worldSessionId, dimension, worldRevision, source, Map.of(), 0);
    }

    private KnownTraversabilitySnapshot(UUID worldSessionId, String dimension, long worldRevision,
            Map<TraversabilityEdge.Key, TraversabilityEdge> source,
            Map<TraversabilityEdge.Key, Double> fallDamage, double fallDamageBudget) {
        this.fallDamage = Map.copyOf(fallDamage);
        this.fallDamageBudget = fallDamageBudget;
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

    /** An action-local view; accepted damage permission never enters the shared session map. */
    public KnownTraversabilitySnapshot withGroundTransfers(List<TraversabilityEdge> additions,
            Map<TraversabilityEdge.Key, Double> damage, double budget) {
        if (!Double.isFinite(budget) || budget < 0 || damage.values().stream()
                .anyMatch(value -> !Double.isFinite(value) || value < 0))
            throw new IllegalArgumentException("invalid fall damage budget");
        var keys = additions.stream().map(TraversabilityEdge::key).collect(java.util.stream.Collectors.toSet());
        if (additions.size() > 256 || !damage.keySet().equals(keys)
                || edges.values().stream().anyMatch(edge -> edge.locomotion().ballistic()))
            throw new IllegalArgumentException("ground transfers require explicit bounded per-edge costs");
        var merged = new TreeMap<>(edges);
        for (var edge : additions) {
            if (!edge.locomotion().ballistic() || edge.worldRevision() != worldRevision
                    || !edge.worldSessionId().equals(worldSessionId) || !edge.key().from().dimension().equals(dimension))
                throw new IllegalArgumentException("ground transfer must have current action evidence");
            merged.put(edge.key(), edge);
        }
        if (!additions.isEmpty() && merged.size() > KnownTraversabilityMap.MAX_EDGES) {
            var observer = additions.getFirst().observerPosition();
            var distant = merged.values().stream().filter(edge -> !keys.contains(edge.key()))
                    .sorted(java.util.Comparator.comparingDouble((TraversabilityEdge edge) -> edge.key().from().distanceTo(observer))
                            .reversed().thenComparing(TraversabilityEdge::key)).iterator();
            while (merged.size() > KnownTraversabilityMap.MAX_EDGES && distant.hasNext()) merged.remove(distant.next().key());
        }
        return new KnownTraversabilitySnapshot(worldSessionId, dimension, worldRevision, merged, damage, budget);
    }

    public boolean hasFallDamage() { return fallDamage.values().stream().anyMatch(value -> value > 0); }
    public double fallDamage(TraversabilityEdge.Key edge) { return fallDamage.getOrDefault(edge, 0.0); }
    public double fallDamageBudget() { return fallDamageBudget; }

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

    /** True only for a delivered safe endpoint, never for an unsupported ladder rung. */
    public boolean containsDestination(NavCell cell) {
        Objects.requireNonNull(cell, "cell");
        for (TraversabilityEdge edge : edges.values()) {
            if (edge.destination()
                    && (edge.key().to().equals(cell)
                            || (edge.locomotion() == Locomotion.GROUND || edge.locomotion().ballistic() || edge.locomotion().aerial())
                                    && edge.key().from().equals(cell))) {
                return true;
            }
        }
        return false;
    }

    /** Internal segment handoff only; an unsupported rung is never a job destination. */
    boolean containsLadderTransit(NavCell cell) {
        return edges.values().stream().anyMatch(edge -> edge.traversable()
                && edge.locomotion() == Locomotion.LADDER
                && edge.targetSupport() == TraversabilityEdge.TargetSupport.ABSENT
                && edge.key().to().equals(cell));
    }
}
