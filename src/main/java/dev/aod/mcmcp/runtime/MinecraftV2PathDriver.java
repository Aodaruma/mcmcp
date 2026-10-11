package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.navigation.CoordinateMoveJobExecution;
import dev.aod.mcmcp.agent.navigation.NavCell;
import dev.aod.mcmcp.routine.MinecraftStationaryBreakPort;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.TntBlock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Optional local path edits. Never walks onto an edited cell before the navigation map proves it. */
final class MinecraftV2PathDriver implements CoordinateMoveJobExecution.ObstacleDriver {
    private final Minecraft minecraft;
    private final V2MoveArguments request;
    private final MinecraftV2BreakDriver breaking;
    private final MinecraftV2PlaceDriver placing;
    private final List<Map<String, Object>> changes = new ArrayList<>();
    private NavCell target;
    private boolean place;
    private boolean started;
    private int waitingTicks;
    private String sourceBlock;

    MinecraftV2PathDriver(Minecraft minecraft, Supplier<WorldSessionTracker.Snapshot> sessions,
            AgentObservations observations, ClientReconciliationSignals reconciliation,
            MinecraftStationaryBreakPort port, V2MoveArguments request) {
        if (!request.clearPath()) throw new IllegalArgumentException("clear_path is required");
        this.minecraft = minecraft;
        this.request = request;
        breaking = new MinecraftV2BreakDriver(minecraft, sessions, observations, reconciliation, port, () -> 0.0D);
        placing = new MinecraftV2PlaceDriver(minecraft, sessions, observations, reconciliation,
                ClientPredictionSignals.global(), () -> 0.0D);
    }

    public CoordinateMoveJobExecution.ObstacleStep tick(NavCell current, long tick, BooleanSupplier allowed) {
        if (!allowed.getAsBoolean()) return CoordinateMoveJobExecution.ObstacleStep.FAILED;
        if (target == null) {
            if (changes.size() >= 64) return CoordinateMoveJobExecution.ObstacleStep.FAILED;
            if (!chooseTarget(current)) return CoordinateMoveJobExecution.ObstacleStep.UNAVAILABLE;
        }
        if (!started) {
            var args = new LinkedHashMap<String, Object>();
            args.put("x", target.x()); args.put("y", target.y()); args.put("z", target.z());
            args.put("advance", false); args.put("max_distance", 0.0D);
            V2BlockJobExecution.BeginResult begin;
            if (place) {
                args.put("block", request.bridgeBlock());
                begin = placing.begin(target, V2PlaceArguments.parse(args, target.dimension()), allowed);
            } else {
                args.put("include_blocks", List.of(sourceBlock));
                begin = breaking.begin(target, V2BreakArguments.parse(args, target.dimension()), allowed);
            }
            switch (begin) {
                case STARTED -> { started = true; waitingTicks = 0; }
                case WAITING -> { return ++waitingTicks <= 80 ? CoordinateMoveJobExecution.ObstacleStep.WORKING : CoordinateMoveJobExecution.ObstacleStep.FAILED; }
                // A concurrent world edit is not our confirmed mutation; observe a fresh path.
                case SKIPPED -> { reset(); return CoordinateMoveJobExecution.ObstacleStep.CHANGED; }
                case FAILED -> { return CoordinateMoveJobExecution.ObstacleStep.FAILED; }
            }
        }
        var step = place ? placing.tick(tick, allowed) : breaking.tick(tick, allowed);
        if (step == V2BlockJobExecution.StepResult.RUNNING) return CoordinateMoveJobExecution.ObstacleStep.WORKING;
        if (step != V2BlockJobExecution.StepResult.CONFIRMED) return CoordinateMoveJobExecution.ObstacleStep.FAILED;
        var change = place ? placing.confirmedChange() : breaking.confirmedChange();
        changes.add(Map.of("x", target.x(), "y", target.y(), "z", target.z(),
                "operation", place ? "place" : "break", "before", change.before().blockId(), "after", change.after().blockId()));
        reset();
        return CoordinateMoveJobExecution.ObstacleStep.CHANGED;
    }

    static List<NavCell> candidates(NavCell current, NavCell goal) {
        if (!current.dimension().equals(goal.dimension()) || current.y() != goal.y()) return List.of();
        var cells = new ArrayList<NavCell>();
        for (var offset : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            long x = (long) current.x() + offset[0], z = (long) current.z() + offset[1];
            if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) continue;
            var next = new NavCell(current.dimension(), (int)x, current.y(), (int)z);
            if (next.distanceTo(goal) < current.distanceTo(goal)) cells.add(next);
        }
        cells.sort(Comparator.comparingDouble((NavCell c) -> c.distanceTo(goal)).thenComparing(Comparator.naturalOrder()));
        return List.copyOf(cells);
    }

    private boolean chooseTarget(NavCell current) {
        var level = minecraft.level;
        for (var next : visibleCorridorCandidates(current)) {
            var feet = new BlockPos(next.x(), next.y(), next.z());
            var floor = feet.below();
            if (!level.isLoaded(floor) || !level.isLoaded(feet.above())) continue;
            // Immediate support is a local safety check, not a disclosure of unseen block data.
            boolean supported = level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)
                    && level.getFluidState(floor).isEmpty();
            for (var position : List.of(feet, feet.above())) {
                if (!supported || !V2LocalConditions.visible(minecraft, position, 4.5D)) continue;
                var state = level.getBlockState(position);
                if (state.isAir() || state.getCollisionShape(level, position).isEmpty()) continue;
                var block = state.getBlock();
                if (state.hasBlockEntity() || level.getBlockEntity(position) != null || !state.getFluidState().isEmpty()
                        || state.getDestroySpeed(level, position) < 0 || block instanceof FallingBlock || block instanceof TntBlock
                        || block instanceof BedBlock || block instanceof DoorBlock || block instanceof DoublePlantBlock) continue;
                sourceBlock = BuiltInRegistries.BLOCK.getKey(block).toString();
                if (!sourceBlock.startsWith("minecraft:")) continue;
                target = new NavCell(next.dimension(), position.getX(), position.getY(), position.getZ());
                place = false;
                return true;
            }
            if (!supported && bridgeMaterialSafe(floor)
                    && V2LocalConditions.visible(minecraft, floor, 4.5D)
                    && V2LocalConditions.visible(minecraft, feet, 4.5D)
                    && V2LocalConditions.visible(minecraft, feet.above(), 4.5D)
                    && level.getBlockState(floor).isAir() && level.getBlockState(feet).isAir()
                    && level.getBlockState(feet.above()).isAir()) {
                target = new NavCell(next.dimension(), floor.getX(), floor.getY(), floor.getZ());
                place = true;
                return true;
            }
        }
        return false;
    }

    private List<NavCell> visibleCorridorCandidates(NavCell current) {
        var result = new ArrayList<NavCell>();
        var level = minecraft.level;
        for (var adjacent : candidates(current, request.goal())) {
            int dx = adjacent.x() - current.x(), dz = adjacent.z() - current.z();
            var next = adjacent;
            for (int distance = 1; distance <= 3; distance++) {
                result.add(next);
                var feet = new BlockPos(next.x(), next.y(), next.z());
                var floor = feet.below();
                // Navigation may stop one clear cell before a wall. Probe only through
                // visible, supported, empty standing cells, never through another obstacle.
                if (!level.isLoaded(feet.above()) || !level.isLoaded(floor)
                        || !V2LocalConditions.visible(minecraft, feet, 4.5D)
                        || !V2LocalConditions.visible(minecraft, feet.above(), 4.5D)
                        || !V2LocalConditions.visible(minecraft, floor, 4.5D)
                        || !level.getBlockState(feet).isAir() || !level.getBlockState(feet.above()).isAir()
                        || !level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)
                        || !level.getFluidState(floor).isEmpty()) break;
                long x = (long) next.x() + dx, z = (long) next.z() + dz;
                if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) break;
                var further = new NavCell(next.dimension(), (int)x, next.y(), (int)z);
                if (further.distanceTo(request.goal()) >= next.distanceTo(request.goal())) break;
                next = further;
            }
        }
        return result;
    }

    private boolean bridgeMaterialSafe(BlockPos position) {
        if (request.bridgeBlock() == null) return false;
        var block = BuiltInRegistries.BLOCK.get(Identifier.parse(request.bridgeBlock()));
        if (block.isEmpty()) return false;
        var state = block.orElseThrow().value().defaultBlockState();
        return !state.hasBlockEntity() && !(state.getBlock() instanceof FallingBlock)
                && !(state.getBlock() instanceof TntBlock) && state.getFluidState().isEmpty()
                && state.isCollisionShapeFullBlock(minecraft.level, position);
    }

    public Map<String, Object> result() {
        var result = new LinkedHashMap<String, Object>();
        result.put("path_changes", List.copyOf(changes));
        result.put("path_changed_count", changes.size());
        if (started && target != null) result.put("unconfirmed_path_target", Map.of(
                "x", target.x(), "y", target.y(), "z", target.z(), "operation", place ? "place" : "break"));
        return result;
    }

    private void reset() {
        close(); target = null; started = false; waitingTicks = 0;
    }
    public void close() {
        try { breaking.close(); } finally { placing.close(); }
    }
}
