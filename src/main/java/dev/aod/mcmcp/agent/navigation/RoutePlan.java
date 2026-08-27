package dev.aod.mcmcp.agent.navigation;

import dev.aod.mcmcp.agent.dsl.ActionDslCompiler;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable route, including explicit probe use and conservative execution bounds. */
public record RoutePlan(
        UUID worldSessionId,
        String dimension,
        long worldRevision,
        List<NavCell> cells,
        List<TraversabilityEdge> edges,
        double distanceBlocks,
        int probeEdgeCount,
        long tickUpperBound,
        long durationMillisUpperBound) {
    public static final int BASE_SETTLE_TICKS = 20;
    public static final int TICKS_PER_TRANSITION = 16;
    public static final int EXTRA_TICKS_PER_PROBE = 20;
    private static final int MILLIS_PER_ACTIVE_TICK = 50;

    public RoutePlan {
        Objects.requireNonNull(worldSessionId, "worldSessionId");
        Objects.requireNonNull(dimension, "dimension");
        if (dimension.isBlank() || worldRevision < 0) {
            throw new IllegalArgumentException("route boundary is invalid");
        }
        cells = List.copyOf(Objects.requireNonNull(cells, "cells"));
        edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
        if (cells.isEmpty() || edges.size() + 1 != cells.size()) {
            throw new IllegalArgumentException("route cells and edges are inconsistent");
        }
        if (!Double.isFinite(distanceBlocks) || distanceBlocks < 0
                || probeEdgeCount < 0 || tickUpperBound < 0 || durationMillisUpperBound < 0) {
            throw new IllegalArgumentException("route bounds must be finite and non-negative");
        }
        int actualProbes = 0;
        double actualDistance = 0;
        for (NavCell cell : cells) {
            if (!dimension.equals(cell.dimension())) {
                throw new IllegalArgumentException("route cell belongs to another dimension");
            }
        }
        for (int index = 0; index < edges.size(); index++) {
            TraversabilityEdge edge = edges.get(index);
            if (!worldSessionId.equals(edge.worldSessionId())
                    || edge.worldRevision() > worldRevision
                    || !edge.key().from().equals(cells.get(index))
                    || !edge.key().to().equals(cells.get(index + 1))
                    || !edge.traversable()) {
                throw new IllegalArgumentException("route contains a disconnected or forbidden edge");
            }
            actualDistance += edge.key().length();
            if (edge.requiresProbe()) actualProbes++;
        }
        if (Math.abs(actualDistance - distanceBlocks) > 1.0e-9 || actualProbes != probeEdgeCount) {
            throw new IllegalArgumentException("route summary does not match its edges");
        }
        long expectedTicks = executionTicks(edges.size(), actualProbes);
        long expectedDuration = Math.multiplyExact(expectedTicks, MILLIS_PER_ACTIVE_TICK);
        if (tickUpperBound != expectedTicks || durationMillisUpperBound != expectedDuration) {
            throw new IllegalArgumentException("route execution bounds do not match its edges");
        }
    }

    static RoutePlan from(
            KnownTraversabilitySnapshot snapshot,
            List<NavCell> cells,
            List<TraversabilityEdge> edges) {
        Objects.requireNonNull(snapshot, "snapshot");
        double distance = 0;
        int probes = 0;
        for (TraversabilityEdge edge : edges) {
            distance += edge.key().length();
            if (edge.requiresProbe()) probes++;
        }
        long ticks = executionTicks(edges.size(), probes);
        return new RoutePlan(
                snapshot.worldSessionId(),
                snapshot.dimension(),
                snapshot.worldRevision(),
                cells,
                edges,
                distance,
                probes,
                ticks,
                Math.multiplyExact(ticks, MILLIS_PER_ACTIVE_TICK));
    }

    private static long executionTicks(int edgeCount, int probes) {
        long transitions = Math.max(1L, edgeCount);
        return Math.addExact(
                BASE_SETTLE_TICKS,
                Math.addExact(
                        Math.multiplyExact(transitions, TICKS_PER_TRANSITION),
                        Math.multiplyExact((long) probes, EXTRA_TICKS_PER_PROBE)));
    }

    public boolean usesProbeAllowed() {
        return probeEdgeCount > 0;
    }

    /** Direct adapter value for {@link ActionDslCompiler.PrimitiveCostModel}. */
    public ActionDslCompiler.Cost toDslPrimitiveCost() {
        return new ActionDslCompiler.Cost(
                durationMillisUpperBound,
                tickUpperBound,
                distanceBlocks,
                0,
                0,
                0,
                0);
    }
}
