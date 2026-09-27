package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.Code;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.PickupPlan;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.PlanningException;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.Pose;
import dev.aod.mcmcp.agent.action.AgentProgramPlanner.PlanningWork;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.dsl.ActionDslCompiler;
import dev.aod.mcmcp.agent.navigation.DeterministicAStar;
import dev.aod.mcmcp.agent.navigation.KnownTraversabilitySnapshot;
import dev.aod.mcmcp.agent.navigation.NavCell;
import dev.aod.mcmcp.agent.navigation.TraversabilityEdge;
import dev.aod.mcmcp.agent.observation.ObservationFrame;
import dev.aod.mcmcp.agent.observation.ObservationRecord;
import dev.aod.mcmcp.agent.observation.ObservationValues;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 可視itemの鮮度と接触範囲を検証し、単独・batchの回収経路を計画する。 */
final class AgentPickupPlanner {
    private AgentPickupPlanner() {
    }

    private static final double VISIBLE_ITEM_MATCH_RADIUS = 0.75D;

    // Vanilla scans an item against player.getBoundingBox().inflate(1.0, 0.5, 1.0).
    // A route may finish 0.25 blocks from its cell center, so keep the horizontal
    // admission envelope slightly smaller than the nominal 0.3 + 1.0 block reach.
    private static final double PICKUP_HORIZONTAL_REACH = 1.0D;

    private static final double PICKUP_VERTICAL_INFLATE = 0.5D;

    private static final double MIN_NAVIGATING_PLAYER_HEIGHT = 1.5D;

    static List<Pose> analyzeCollectBatch(
            ActionDsl.CollectVisibleItemBatch batch,
            List<Pose> input,
            KnownTraversabilitySnapshot map,
            DeterministicAStar pathfinder,
            Optional<ObservationFrame> latestFrame,
            long visualBarrierWorldRevision,
            Map<String, ActionDslCompiler.Cost> costs,
            Map<TraversabilityEdge.Key, TraversabilityEdge> routeDependencies,
            PlanningWork work) {
        ActionDslCompiler.Cost worst = null;
        var output = new ArrayList<Pose>(input.size());
        for (Pose initial : input) {
            Pose pose = initial;
            ActionDslCompiler.Cost cost = new ActionDslCompiler.Cost(0, 0, 0, 0, 0, 0, 0);
            var boundEntities = new LinkedHashSet<ObservationRecord.VisibleEntity>();
            for (int index = 0; index < batch.targets().size(); index++) {
                work.poseTransition();
                ActionDsl.CollectVisibleItem child = collectBatchChild(batch, index);
                ObservationRecord.VisibleEntity entity;
                try {
                    long frameTick = latestFrame.map(
                            ObservationFrame::frameCompletedTick).orElse(0L);
                    entity = matchingVisibleItems(
                                    map, latestFrame, child,
                                    visualBarrierWorldRevision, frameTick, 0L)
                            .stream()
                            .filter(boundEntities::add)
                            .findFirst()
                            .orElseThrow(() -> new PlanningException(
                                    Code.TARGET_UNKNOWN,
                                    "Collect target requires current matching visible item evidence"));
                } catch (PlanningException failure) {
                    throw new PlanningException(
                            failure.code(),
                            "Collect batch target[" + index
                                    + "] lacks required current evidence: "
                                    + failure.getMessage());
                }
                PickupPlan pickup = requirePickupPlan(
                        map, pathfinder, pose.cell(), entity, work);
                AgentNavigationPlanner.addRouteDependencies(map, pickup.route(), routeDependencies);
                cost = AgentPlannerCosts.addCosts(cost, AgentPlannerCosts.pickupCost(pickup.route(), pose));
                pose = pose.at(pickup.pickupCell(), 0.25D);
            }
            worst = AgentPlannerCosts.maximum(worst, cost);
            output.add(pose);
        }
        AgentPlannerCosts.merge(costs, batch.id(), Objects.requireNonNull(worst, "collect batch cost"));
        return AgentProgramPlanner.distinct(output);
    }

    static ActionDsl.CollectVisibleItem collectBatchChild(
            ActionDsl.CollectVisibleItemBatch batch, int index) {
        Objects.requireNonNull(batch, "batch");
        if (index < 0 || index >= batch.targets().size()) {
            throw new IllegalArgumentException("collect batch index is outside the target list");
        }
        ActionDsl.CollectTarget target = batch.targets().get(index);
        String suffix = "_c" + (index + 1);
        String id = batch.id().substring(
                0, Math.min(batch.id().length(), 32 - suffix.length())) + suffix;
        return new ActionDsl.CollectVisibleItem(id, target.displayedItem(), target.target());
    }

    /** Resolves one currently visible item witness to a reachable, known player-feet cell. */
    static PickupPlan requirePickupPlan(
            KnownTraversabilitySnapshot map,
            DeterministicAStar pathfinder,
            NavCell start,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.CollectVisibleItem target) {
        ObservationRecord.VisibleEntity entity = requireVisibleItem(map, latestFrame, target);
        return requirePickupPlan(
                map, pathfinder, start, entity, new PlanningWork(() -> true));
    }

    /** Runtime variant that rejects an observation frame older than one visual scan cycle. */
    static PickupPlan requirePickupPlan(
            KnownTraversabilitySnapshot map,
            DeterministicAStar pathfinder,
            NavCell start,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.CollectVisibleItem target,
            long currentTick,
            long maxAgeTicks) {
        return requirePickupPlan(
                map, pathfinder, start, latestFrame, target,
                map.worldRevision(), currentTick, maxAgeTicks);
    }

    /** Runtime variant accepting evidence no older than the current visual barrier. */
    static PickupPlan requirePickupPlan(
            KnownTraversabilitySnapshot map,
            DeterministicAStar pathfinder,
            NavCell start,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.CollectVisibleItem target,
            long visualBarrierWorldRevision,
            long currentTick,
            long maxAgeTicks) {
        ObservationRecord.VisibleEntity entity = requireVisibleItem(
                map, latestFrame, target,
                visualBarrierWorldRevision, currentTick, maxAgeTicks);
        return requirePickupPlan(
                map, pathfinder, start, entity, new PlanningWork(() -> true));
    }

    /** True only while the exact policy-visible item/position witness remains current. */
    static boolean visibleItemCurrent(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.CollectVisibleItem target) {
        try {
            requireVisibleItem(map, latestFrame, target);
            return true;
        } catch (PlanningException unavailable) {
            return false;
        }
    }

    /** Runtime freshness check for a moving entity witness. */
    static boolean visibleItemCurrent(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.CollectVisibleItem target,
            long currentTick,
            long maxAgeTicks) {
        return visibleItemCurrent(
                map, latestFrame, target, map.worldRevision(), currentTick, maxAgeTicks);
    }

    /** Runtime freshness check bounded by the most recent visual-invalidating mutation. */
    static boolean visibleItemCurrent(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.CollectVisibleItem target,
            long visualBarrierWorldRevision,
            long currentTick,
            long maxAgeTicks) {
        try {
            requireVisibleItem(
                    map, latestFrame, target,
                    visualBarrierWorldRevision, currentTick, maxAgeTicks);
            return true;
        } catch (PlanningException unavailable) {
            return false;
        }
    }

    /** True only while the planned cell can still contact the freshly observed witness. */
    static boolean visibleItemPickupCellCurrent(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.CollectVisibleItem target,
            NavCell pickupCell,
            long currentTick,
            long maxAgeTicks) {
        return visibleItemPickupCellCurrent(
                map, latestFrame, target, pickupCell,
                map.worldRevision(), currentTick, maxAgeTicks);
    }

    /** Runtime pickup-cell check bounded by the most recent visual-invalidating mutation. */
    static boolean visibleItemPickupCellCurrent(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.CollectVisibleItem target,
            NavCell pickupCell,
            long visualBarrierWorldRevision,
            long currentTick,
            long maxAgeTicks) {
        Objects.requireNonNull(pickupCell, "pickupCell");
        try {
            ObservationRecord.VisibleEntity entity = requireVisibleItem(
                    map, latestFrame, target,
                    visualBarrierWorldRevision, currentTick, maxAgeTicks);
            return pickupCellCanContact(pickupCell, entity.aabb());
        } catch (PlanningException unavailable) {
            return false;
        }
    }

    /** Returns the freshly revalidated item AABB for an exact runtime pickup-area check. */
    static Optional<ObservationValues.Aabb> visibleItemAabb(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.CollectVisibleItem target,
            long currentTick,
            long maxAgeTicks) {
        return visibleItemAabb(
                map, latestFrame, target,
                map.worldRevision(), currentTick, maxAgeTicks);
    }

    /** Runtime item bounds check bounded by the most recent visual-invalidating mutation. */
    static Optional<ObservationValues.Aabb> visibleItemAabb(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.CollectVisibleItem target,
            long visualBarrierWorldRevision,
            long currentTick,
            long maxAgeTicks) {
        try {
            return Optional.of(requireVisibleItem(
                    map, latestFrame, target,
                    visualBarrierWorldRevision, currentTick, maxAgeTicks).aabb());
        } catch (PlanningException unavailable) {
            return Optional.empty();
        }
    }

    /**
     * Resolves only submitted batch witnesses against one fresh delivered frame. One visible
     * record can satisfy at most one listed target; missing or ambiguous suffix entries remain
     * empty and are never discovered from live entities.
     */
    static List<Optional<ObservationValues.Aabb>> visibleBatchItemAabbs(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.CollectVisibleItemBatch batch,
            long visualBarrierWorldRevision,
            long currentTick,
            long maxAgeTicks) {
        Objects.requireNonNull(batch, "batch");
        var used = new LinkedHashSet<ObservationRecord.VisibleEntity>();
        var result = new ArrayList<Optional<ObservationValues.Aabb>>(batch.targets().size());
        for (int index = 0; index < batch.targets().size(); index++) {
            ActionDsl.CollectVisibleItem child = collectBatchChild(batch, index);
            Optional<ObservationRecord.VisibleEntity> matched = matchingVisibleItems(
                            map, latestFrame, child,
                            visualBarrierWorldRevision, currentTick, maxAgeTicks)
                    .stream()
                    .filter(used::add)
                    .findFirst();
            result.add(matched.map(ObservationRecord.VisibleEntity::aabb));
        }
        return List.copyOf(result);
    }

    private static ObservationRecord.VisibleEntity requireVisibleItem(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.CollectVisibleItem target) {
        return requireVisibleItem(map, latestFrame, target, map.worldRevision());
    }

    static ObservationRecord.VisibleEntity requireVisibleItem(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.CollectVisibleItem target,
            long visualBarrierWorldRevision) {
        long frameTick = latestFrame.map(ObservationFrame::frameCompletedTick).orElse(0L);
        return requireVisibleItem(
                map, latestFrame, target,
                visualBarrierWorldRevision, frameTick, 0L);
    }

    private static ObservationRecord.VisibleEntity requireVisibleItem(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.CollectVisibleItem target,
            long currentTick,
            long maxAgeTicks) {
        return requireVisibleItem(
                map, latestFrame, target,
                map.worldRevision(), currentTick, maxAgeTicks);
    }

    private static ObservationRecord.VisibleEntity requireVisibleItem(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.CollectVisibleItem target,
            long visualBarrierWorldRevision,
            long currentTick,
            long maxAgeTicks) {
        return matchingVisibleItems(
                        map, latestFrame, target,
                        visualBarrierWorldRevision, currentTick, maxAgeTicks)
                .stream()
                .findFirst()
                .orElseThrow(() -> new PlanningException(
                        Code.TARGET_UNKNOWN,
                        "Collect target requires current matching visible item evidence"));
    }

    private static List<ObservationRecord.VisibleEntity> matchingVisibleItems(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.CollectVisibleItem target,
            long visualBarrierWorldRevision,
            long currentTick,
            long maxAgeTicks) {
        Objects.requireNonNull(map, "map");
        Objects.requireNonNull(latestFrame, "latestFrame");
        Objects.requireNonNull(target, "target");
        if (currentTick < 0L || maxAgeTicks < 0L) {
            throw new IllegalArgumentException("visible item freshness bounds must be non-negative");
        }
        AgentSurfaceEvidence.requireVisualBarrierWorldRevision(
                map, map.worldRevision(), visualBarrierWorldRevision);
        return latestFrame.stream()
                .filter(frame -> frame.dimension().value().equals(map.dimension()))
                .filter(frame -> frame.frameCompletedTick() <= currentTick
                        && currentTick - frame.frameCompletedTick() <= maxAgeTicks)
                .flatMap(frame -> frame.records().stream()
                        .filter(record -> record.newestObservedTick()
                                == frame.frameCompletedTick()))
                .filter(ObservationRecord.VisibleEntity.class::isInstance)
                .map(ObservationRecord.VisibleEntity.class::cast)
                .filter(entity -> entity.worldRevision() >= visualBarrierWorldRevision
                        && entity.worldRevision() <= map.worldRevision())
                .filter(entity -> "minecraft:item".equals(entity.entityType().value()))
                .filter(entity -> entity.displayedItem() != null
                        && target.displayedItem().equals(entity.displayedItem().value()))
                .filter(entity -> sameVisibleItemPosition(entity.position(), target.target()))
                .sorted(java.util.Comparator.comparingDouble(entity ->
                        visibleItemDistanceSquared(entity.position(), target.target())))
                .toList();
    }

    static PickupPlan requirePickupPlan(
            KnownTraversabilitySnapshot map,
            DeterministicAStar pathfinder,
            NavCell start,
            ObservationRecord.VisibleEntity entity,
            PlanningWork work) {
        var candidates = new java.util.TreeSet<NavCell>();
        candidates.add(start);
        for (TraversabilityEdge edge : map.edges().values()) {
            if (edge.traversable()) {
                candidates.add(edge.key().from());
                candidates.add(edge.key().to());
            }
        }
        var pickupCells = candidates.stream()
                .filter(cell -> pickupCellCanContact(cell, entity.aabb()))
                .toList();
        var reachable = new ArrayList<PickupPlan>(pickupCells.size());
        for (NavCell candidate : pickupCells) {
            var result = pathfinder.findRoute(
                    map, start, candidate, work::canContinue, work::routeExpansion);
            if (result.route().isPresent()) {
                reachable.add(new PickupPlan(result.route().orElseThrow(), candidate));
            }
        }
        if (!reachable.isEmpty()) {
            return reachable.stream()
                    .min(java.util.Comparator
                            .comparingLong((PickupPlan plan) -> plan.route().tickUpperBound())
                            .thenComparingDouble(plan -> plan.route().distanceBlocks())
                            .thenComparingDouble(plan -> pickupDistanceSquared(
                                    plan.pickupCell(), entity.aabb()))
                            .thenComparing(PickupPlan::pickupCell))
                    .orElseThrow();
        }
        throw new PlanningException(
                pickupCells.isEmpty() ? Code.TARGET_UNKNOWN : Code.NO_KNOWN_PATH,
                pickupCells.isEmpty()
                        ? "No known safe pickup cell overlaps the visible item"
                        : "No policy-approved route reaches a known item pickup cell");
    }

    private static boolean pickupCellCanContact(
            NavCell cell, ObservationValues.Aabb item) {
        double centerX = cell.x() + 0.5D;
        double centerZ = cell.z() + 0.5D;
        return item.maxX() > centerX - PICKUP_HORIZONTAL_REACH
                && item.minX() < centerX + PICKUP_HORIZONTAL_REACH
                && item.maxZ() > centerZ - PICKUP_HORIZONTAL_REACH
                && item.minZ() < centerZ + PICKUP_HORIZONTAL_REACH
                && item.maxY() > cell.y() - PICKUP_VERTICAL_INFLATE
                && item.minY() < cell.y()
                        + MIN_NAVIGATING_PLAYER_HEIGHT + PICKUP_VERTICAL_INFLATE;
    }

    private static double pickupDistanceSquared(
            NavCell cell, ObservationValues.Aabb item) {
        double x = cell.x() + 0.5D;
        double z = cell.z() + 0.5D;
        double dx = x < item.minX() ? item.minX() - x
                : x > item.maxX() ? x - item.maxX() : 0.0D;
        double dz = z < item.minZ() ? item.minZ() - z
                : z > item.maxZ() ? z - item.maxZ() : 0.0D;
        double pickupMinY = cell.y() - PICKUP_VERTICAL_INFLATE;
        double pickupMaxY = cell.y()
                + MIN_NAVIGATING_PLAYER_HEIGHT + PICKUP_VERTICAL_INFLATE;
        double dy = pickupMinY > item.maxY() ? pickupMinY - item.maxY()
                : pickupMaxY < item.minY() ? item.minY() - pickupMaxY : 0.0D;
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean sameVisibleItemPosition(
            ObservationValues.WorldPosition observed, ActionDsl.WorldPosition requested) {
        return observed.dimension().value().equals(requested.dimension())
                && visibleItemDistanceSquared(observed, requested)
                        <= VISIBLE_ITEM_MATCH_RADIUS * VISIBLE_ITEM_MATCH_RADIUS;
    }

    private static double visibleItemDistanceSquared(
            ObservationValues.WorldPosition observed, ActionDsl.WorldPosition requested) {
        return AgentPlannerGeometry.square(observed.x() - requested.x())
                + AgentPlannerGeometry.square(observed.y() - requested.y())
                + AgentPlannerGeometry.square(observed.z() - requested.z());
    }
}
