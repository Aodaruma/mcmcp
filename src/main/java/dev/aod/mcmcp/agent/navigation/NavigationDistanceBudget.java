package dev.aod.mcmcp.agent.navigation;

import java.util.Objects;

/** Shared Action DSL distance contract for route search and navigation admission. */
public final class NavigationDistanceBudget {
    public static final double MAX_DISTANCE_BLOCKS = 32.0D;
    public static final double TRAJECTORY_FACTOR = 1.5D;
    public static final double VERTICAL_ARC_ALLOWANCE = 1.5D;
    public static final double MAX_HORIZONTAL_POSITION_ERROR = Math.sqrt(0.5D);
    public static final double MAX_VERTICAL_POSITION_ERROR = 1.0D;

    /*
     * The pathfinder only knows the starting feet cell. Reserve the largest difference between
     * its cell-center first edge and a supported live/abstract starting pose: horizontal position
     * plus error is at most sqrt(2), and vertical position plus error is at most 2 blocks.
     */
    public static final double START_POSE_RESERVE_BLOCKS =
            TRAJECTORY_FACTOR * Math.sqrt(6.0D);
    public static final double MAX_ROUTED_DISTANCE_BLOCKS =
            MAX_DISTANCE_BLOCKS - START_POSE_RESERVE_BLOCKS;

    private static final double EPSILON = 1.0e-9D;

    private NavigationDistanceBudget() {
    }

    /** Additive search cost excluding the separately reserved starting-pose uncertainty. */
    public static double edgeCost(TraversabilityEdge edge) {
        Objects.requireNonNull(edge, "edge");
        return edge.key().length() * TRAJECTORY_FACTOR
                + (vertical(edge) ? VERTICAL_ARC_ALLOWANCE : 0.0D);
    }

    public static double centerlineRouteCost(RoutePlan route) {
        Objects.requireNonNull(route, "route");
        double cost = 0.0D;
        for (TraversabilityEdge edge : route.edges()) {
            cost += edgeCost(edge);
        }
        return cost;
    }

    public static boolean searchCostFits(double cost) {
        return Double.isFinite(cost)
                && cost >= 0.0D
                && cost <= MAX_ROUTED_DISTANCE_BLOCKS + EPSILON;
    }

    /** Exact conservative cost used by admission after A* has applied the start reserve. */
    public static double navigationCost(
            RoutePlan route,
            NavCell startCell,
            double x,
            double y,
            double z,
            double horizontalPositionError,
            double yErrorBelow,
            double yErrorAbove) {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(startCell, "startCell");
        if (!route.cells().getFirst().equals(startCell)) {
            throw new IllegalArgumentException("route does not start at the supplied pose cell");
        }
        requireSupportedStartPose(
                startCell, x, y, z, horizontalPositionError, yErrorBelow, yErrorAbove);

        double geometricDistance;
        if (route.edges().isEmpty()) {
            geometricDistance = Math.hypot(
                    x - (startCell.x() + 0.5D),
                    z - (startCell.z() + 0.5D))
                    + horizontalPositionError;
        } else {
            NavCell waypoint = route.cells().get(1);
            double horizontal = Math.hypot(
                    x - (waypoint.x() + 0.5D),
                    z - (waypoint.z() + 0.5D))
                    + horizontalPositionError;
            double vertical = Math.max(
                    Math.abs(y - yErrorBelow - waypoint.y()),
                    Math.abs(y + yErrorAbove - waypoint.y()));
            geometricDistance = route.distanceBlocks()
                    - route.edges().getFirst().key().length()
                    + Math.hypot(horizontal, vertical);
        }
        long verticalEdges = route.edges().stream()
                .filter(NavigationDistanceBudget::vertical)
                .count();
        return geometricDistance * TRAJECTORY_FACTOR
                + verticalEdges * VERTICAL_ARC_ALLOWANCE;
    }

    private static void requireSupportedStartPose(
            NavCell cell,
            double x,
            double y,
            double z,
            double horizontalPositionError,
            double yErrorBelow,
            double yErrorAbove) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Double.isFinite(horizontalPositionError)
                || !Double.isFinite(yErrorBelow) || !Double.isFinite(yErrorAbove)
                || x < cell.x() - EPSILON || x > cell.x() + 1.0D + EPSILON
                || y < cell.y() - EPSILON || y > cell.y() + 1.0D + EPSILON
                || z < cell.z() - EPSILON || z > cell.z() + 1.0D + EPSILON
                || horizontalPositionError < 0.0D
                || horizontalPositionError > MAX_HORIZONTAL_POSITION_ERROR + EPSILON
                || yErrorBelow < 0.0D
                || yErrorBelow > MAX_VERTICAL_POSITION_ERROR + EPSILON
                || yErrorAbove < 0.0D
                || yErrorAbove > MAX_VERTICAL_POSITION_ERROR + EPSILON) {
            throw new IllegalArgumentException(
                    "navigation pose is outside the bounded starting-cell envelope");
        }
    }

    private static boolean vertical(TraversabilityEdge edge) {
        return edge.key().from().y() != edge.key().to().y();
    }
}
