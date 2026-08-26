package dev.aod.mcmcp.agent.navigation;

import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/** Session-local mutable owner of policy-filtered traversability evidence. */
public final class KnownTraversabilityMap {
    public static final int MAX_EDGES = 8_192;
    private static final Comparator<TraversabilityEdge> EVIDENCE_ORDER = Comparator
            .comparingLong(TraversabilityEdge::worldRevision)
            .thenComparingLong(TraversabilityEdge::observedTick)
            .thenComparingInt(edge -> provenanceRank(edge.provenance()))
            .thenComparingInt(edge -> statusRank(edge.status()))
            .thenComparingInt(edge -> edge.targetSupport().ordinal())
            .thenComparingInt(edge -> edge.clearance().ordinal())
            .thenComparingInt(edge -> edge.transition().ordinal())
            .thenComparingInt(edge -> edge.fluid().ordinal())
            .thenComparingInt(edge -> edge.hazard().ordinal())
            .thenComparing(TraversabilityEdge::observerPosition);
    private static final Comparator<TraversabilityEdge> EVICTION_ORDER = Comparator
            .comparingLong(TraversabilityEdge::observedTick)
            .thenComparing(TraversabilityEdge::key);

    private final TreeMap<TraversabilityEdge.Key, TraversabilityEdge> edges = new TreeMap<>();
    private final NavigableSet<TraversabilityEdge> evictionOrder = new TreeSet<>(EVICTION_ORDER);

    private UUID worldSessionId;
    private String dimension;
    private long worldRevision;
    private KnownTraversabilitySnapshot snapshotCache;

    /** Starts a new boundary even if the supplied identity equals the previous one. */
    public synchronized void startSession(
            UUID newWorldSessionId, String newDimension, long initialWorldRevision) {
        Objects.requireNonNull(newWorldSessionId, "newWorldSessionId");
        Objects.requireNonNull(newDimension, "newDimension");
        if (newDimension.isBlank() || initialWorldRevision < 0) {
            throw new IllegalArgumentException("dimension/revision is invalid");
        }
        clearWorld();
        worldSessionId = newWorldSessionId;
        dimension = newDimension;
        worldRevision = initialWorldRevision;
    }

    public synchronized void clearWorld() {
        edges.clear();
        evictionOrder.clear();
        worldSessionId = null;
        dimension = null;
        worldRevision = 0;
        snapshotCache = null;
    }

    /**
     * Adds or replaces one edge only from Local Observation Volume or actual contact evidence.
     * Older evidence cannot overwrite a newer decision.
     */
    public synchronized boolean observe(TraversabilityEdge evidence) {
        requireSession();
        Objects.requireNonNull(evidence, "evidence");
        if (!evidence.provenance().mayUpdateTraversability()) {
            throw new IllegalArgumentException(
                    "visual and sound evidence cannot update the traversability map");
        }
        if (evidence.status() == TraversabilityEdge.Status.STALE) {
            throw new IllegalArgumentException("STALE is produced only by map invalidation");
        }
        requireCurrentBoundary(evidence);
        if (evidence.worldRevision() != worldRevision) {
            return false;
        }

        TraversabilityEdge accepted = evidence;
        TraversabilityEdge previous = edges.get(evidence.key());
        if (previous != null
                && previous.status() == TraversabilityEdge.Status.CONFIRMED
                && previous.provenance() == TraversabilityEdge.Provenance.CONTACT
                && accepted.status() == TraversabilityEdge.Status.PROBE_ALLOWED
                && accepted.provenance() == TraversabilityEdge.Provenance.LOCAL_VOLUME) {
            return false;
        }
        if (previous != null
                && previous.status() != TraversabilityEdge.Status.STALE
                && EVIDENCE_ORDER.compare(accepted, previous) <= 0) {
            return false;
        }
        if (previous != null
                && previous.status() == TraversabilityEdge.Status.STALE
                && accepted.status() == TraversabilityEdge.Status.STALE
                && EVIDENCE_ORDER.compare(accepted, previous) <= 0) {
            return false;
        }
        if (previous == null && edges.size() == MAX_EDGES) {
            TraversabilityEdge oldest = evictionOrder.pollFirst();
            edges.remove(Objects.requireNonNull(oldest, "eviction index").key());
        }
        if (previous != null) evictionOrder.remove(previous);
        edges.put(accepted.key(), accepted);
        evictionOrder.add(accepted);
        snapshotCache = null;
        return true;
    }

    /** Marks only explicitly affected cells and edges stale while advancing the global revision. */
    public synchronized int advanceWorldRevision(
            long newWorldRevision,
            Collection<NavCell> affectedCells,
            Collection<TraversabilityEdge.Key> affectedEdges) {
        requireSession();
        if (newWorldRevision < worldRevision) {
            throw new IllegalArgumentException("world revision cannot decrease");
        }
        Objects.requireNonNull(affectedCells, "affectedCells");
        Objects.requireNonNull(affectedEdges, "affectedEdges");
        affectedCells.forEach(cell ->
                requireCurrentDimension(Objects.requireNonNull(cell, "affected cell")));
        affectedEdges.forEach(key ->
                requireCurrentDimension(Objects.requireNonNull(key, "affected edge").from()));
        boolean revisionChanged = newWorldRevision != worldRevision;
        worldRevision = newWorldRevision;

        int changed = 0;
        for (Map.Entry<TraversabilityEdge.Key, TraversabilityEdge> entry : edges.entrySet()) {
            if (isAffected(entry.getKey(), affectedCells, affectedEdges)
                    && entry.getValue().status() != TraversabilityEdge.Status.STALE) {
                TraversabilityEdge prior = entry.getValue();
                TraversabilityEdge stale = prior.stale();
                evictionOrder.remove(prior);
                entry.setValue(stale);
                evictionOrder.add(stale);
                changed++;
            }
        }
        if (revisionChanged || changed > 0) snapshotCache = null;
        return changed;
    }

    public synchronized Optional<KnownTraversabilitySnapshot> snapshot() {
        if (worldSessionId == null) return Optional.empty();
        if (snapshotCache == null) {
            snapshotCache = new KnownTraversabilitySnapshot(
                    worldSessionId, dimension, worldRevision, edges);
        }
        return Optional.of(snapshotCache);
    }

    public synchronized int size() {
        return edges.size();
    }

    private static boolean isAffected(
            TraversabilityEdge.Key key,
            Collection<NavCell> affectedCells,
            Collection<TraversabilityEdge.Key> affectedEdges) {
        return affectedEdges.contains(key)
                || affectedCells.contains(key.from())
                || affectedCells.contains(key.to());
    }

    private void requireCurrentBoundary(TraversabilityEdge evidence) {
        if (!worldSessionId.equals(evidence.worldSessionId())) {
            throw new IllegalArgumentException("edge evidence belongs to another world session");
        }
        requireCurrentDimension(evidence.key().from());
    }

    private void requireCurrentDimension(NavCell cell) {
        if (!dimension.equals(cell.dimension())) {
            throw new IllegalArgumentException("cell belongs to another dimension");
        }
    }

    private void requireSession() {
        if (worldSessionId == null) {
            throw new IllegalStateException("no world session is bound");
        }
    }

    private static int provenanceRank(TraversabilityEdge.Provenance provenance) {
        return switch (provenance) {
            case SOUND, OMNIDIRECTIONAL_VISUAL -> 0;
            case LOCAL_VOLUME -> 1;
            case CONTACT -> 2;
        };
    }

    private static int statusRank(TraversabilityEdge.Status status) {
        return switch (status) {
            case STALE -> 0;
            case PROBE_ALLOWED -> 1;
            case CONFIRMED -> 2;
            case BLOCKED -> 3;
        };
    }
}
