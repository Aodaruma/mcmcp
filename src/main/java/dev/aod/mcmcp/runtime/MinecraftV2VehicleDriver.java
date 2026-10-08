package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.MinecraftActionPrimitiveExecutor.*;
import dev.aod.mcmcp.agent.navigation.*;
import dev.aod.mcmcp.routine.MovementInputLease;
import dev.aod.mcmcp.routine.NavigationViewLease;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;
import static dev.aod.mcmcp.routine.MovementInputLease.MovementKey.*;

/** Normal riding inputs with vehicle-volume paths; cancellation releases inputs without pretending inertia vanished. */
final class MinecraftV2VehicleDriver implements CoordinateMoveJobExecution.MovementDriver {
    private final Minecraft minecraft;
    private final V2MoveArguments request;
    private final Entity vehicle;
    private final net.minecraft.client.player.LocalPlayer player;
    private final UUID owner = UUID.randomUUID();
    private MovementInputLease inputs;
    private NavigationViewLease view;
    private List<NavCell> route = List.of();
    private int waypoint, settled, replans, waiting;
    private long lastProgress;
    private Vec3 lastPosition;
    private String failure;

    MinecraftV2VehicleDriver(Minecraft minecraft, V2MoveArguments request) {
        this.minecraft = minecraft; this.request = request;
        player = minecraft.player; vehicle = player.getVehicle();
        if (!supported(player) || request.sneak() || request.clearPath())
            throw new IllegalArgumentException("riding requires a controllable vehicle without sneak or path construction");
        lastPosition = vehicle.position();
    }
    static boolean supported(net.minecraft.client.player.LocalPlayer player) {
        var vehicle = player.getVehicle();
        return vehicle != null && vehicle.isAlive() && (vehicle instanceof Minecart
                && vehicle.getFirstPassenger() == player || (vehicle instanceof AbstractBoat
                || vehicle instanceof AbstractHorse horse && horse.isTamed()) && vehicle.getControllingPassenger() == player);
    }
    public boolean selfPlanning() { return true; }
    public boolean arrived(NavCell goal, double radius, double tolerance) { return false; }
    public boolean active() { return true; }
    public void begin(RoutePlan route, double tolerance) { throw new UnsupportedOperationException(); }
    public TickResult tick(KnownTraversabilitySnapshot map, double remaining, long tick, BooleanSupplier allowed) {
        return tickGoal(request.goal(), map, remaining, tick, allowed);
    }
    public TickResult tickGoal(NavCell goal, KnownTraversabilitySnapshot map, double remaining, long tick, BooleanSupplier allowed) {
        if (minecraft.player != player || player.getVehicle() != vehicle || !supported(player)
                || minecraft.level != vehicle.level() || !allowed.getAsBoolean()) return fail("vehicle_or_control_changed");
        if (lastProgress == 0) lastProgress = tick;
        if (lastPosition.distanceTo(vehicle.position()) > 0.15) { lastPosition = vehicle.position(); lastProgress = tick; }
        if (tick - lastProgress > 200) return fail("vehicle_stalled");
        double distance = Math.hypot(goal.x() + 0.5 - vehicle.getX(), goal.z() + 0.5 - vehicle.getZ());
        double speed = vehicle.getDeltaMovement().horizontalDistance();
        boolean near = distance <= request.arrivalRadius() + Math.max(0.35, request.tolerance())
                && Math.abs(vehicle.getY() - goal.y()) < 1;
        settled = near && speed < 0.025 ? settled + 1 : 0;
        if (settled >= 4) { close(); return new TickResult(Status.SUCCEEDED, Reason.NONE); }
        if (remaining < Math.max(0.25, speed * 12)) return fail("vehicle_stopping_distance_budget");
        // Collision clearance includes the rider. Predict coasting before sending another input pulse.
        if (!volumeSafe(vehicle.getBoundingBox().minmax(player.getBoundingBox())
                .expandTowards(vehicle.getDeltaMovement().scale(12)))) return fail("vehicle_coasting_corridor_unverified");
        var start = cell(map.dimension(), vehicle.position());
        if (vehicle instanceof AbstractMinecart cart) {
            var rail = cart.getCurrentBlockPosOrRailBelow();
            start = new NavCell(map.dimension(), rail.getX(), rail.getY(), rail.getZ());
        }
        if (!near && (route.isEmpty() || waypoint >= route.size())) {
            route = VehicleRoutePlanner.plan(start, goal, this::neighbors);
            waypoint = 1;
            if (route.isEmpty()) {
                neutral();
                if (!request.autoReplan() || ++waiting > 80) return fail("vehicle_no_known_path");
                return running();
            }
            waiting = 0; replans++;
        }
        NavCell target = near ? goal : route.get(waypoint);
        double targetDistance = Math.hypot(target.x() + 0.5 - vehicle.getX(), target.z() + 0.5 - vehicle.getZ());
        if (!near && targetDistance < 0.4 && Math.abs(target.y() - vehicle.getY()) < 1) {
            waypoint++;
            if (waypoint < route.size()) target = route.get(waypoint);
            else { neutral(); return running(); }
        }
        if (!near && !start.equals(target) && !edgeSafe(start, target)) {
            route = List.of(); neutral();
            return request.autoReplan() ? running() : fail("vehicle_path_changed");
        }
        targetDistance = Math.hypot(target.x() + 0.5 - vehicle.getX(), target.z() + 0.5 - vehicle.getZ());
        double dx = target.x() + 0.5 - vehicle.getX(), dz = target.z() + 0.5 - vehicle.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        var desired = EnumSet.noneOf(MovementInputLease.MovementKey.class);
        if (vehicle instanceof AbstractBoat) {
            float turn = Mth.wrapDegrees(yaw - vehicle.getYRot());
            if (!near && Math.abs(turn) > 8) desired.add(turn > 0 ? RIGHT : LEFT);
            double forwardSpeed = vehicle.getDeltaMovement().x * -Math.sin(Math.toRadians(vehicle.getYRot()))
                    + vehicle.getDeltaMovement().z * Math.cos(Math.toRadians(vehicle.getYRot()));
            if ((near || targetDistance < speed * 9 + 0.25) && forwardSpeed > 0.025) desired.add(BACK);
            else if (!near && Math.abs(turn) < 25 && speed < 0.14 && tick % 3 == 0) desired.add(FORWARD);
        } else {
            if (view == null) view = NavigationViewLease.acquire(player, owner);
            if (!view.matches(owner)) return fail("vehicle_view_changed");
            view.turnToward(owner, yaw, 0);
            if (!near && Math.abs(Mth.wrapDegrees(yaw - player.getYRot())) < 15
                    && (!(vehicle instanceof AbstractMinecart) || speed < 0.03)) desired.add(FORWARD);
        }
        if (inputs == null) inputs = MovementInputLease.acquire(minecraft, owner, System.nanoTime(), Duration.ofMillis(500));
        inputs.setDesired(owner, desired);
        if (!inputs.heartbeat(owner, System.nanoTime(), Duration.ofMillis(500))) return fail("vehicle_input_expired");
        return running();
    }

    private List<NavCell> neighbors(NavCell from) {
        var result = new ArrayList<NavCell>();
        if (vehicle instanceof AbstractMinecart) {
            var position = new BlockPos(from.x(), from.y(), from.z());
            if (!minecraft.level.isLoaded(position)) return result;
            var state = minecraft.level.getBlockState(position);
            if (!(state.getBlock() instanceof BaseRailBlock rail)) return result;
            var exits = AbstractMinecart.exits(state.getValue(rail.getShapeProperty()));
            for (var direction : List.of(exits.getFirst(), exits.getSecond())) {
                for (int dy : new int[]{direction.getY(), direction.getY() - 1, direction.getY() + 1}) {
                    var to = new NavCell(from.dimension(), from.x() + direction.getX(), from.y() + dy, from.z() + direction.getZ());
                    if (edgeSafe(from, to)) { result.add(to); break; }
                }
            }
        } else for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
            for (int dy : vehicle instanceof AbstractHorse ? new int[]{0,1,-1} : new int[]{0}) {
                var to = new NavCell(from.dimension(), from.x() + d[0], from.y() + dy, from.z() + d[1]);
                if (edgeSafe(from, to)) { result.add(to); break; }
            }
        }
        return result;
    }
    private boolean edgeSafe(NavCell from, NavCell to) {
        if (from.distanceTo(to) > 1.75 || Math.hypot(to.x() + 0.5 - vehicle.getX(), to.z() + 0.5 - vehicle.getZ()) > 6) return false;
        var body = vehicle.getBoundingBox().minmax(player.getBoundingBox());
        Vec3 a = new Vec3(from.x() + 0.5 - vehicle.getX(), from.y() - Math.floor(vehicle.getY()), from.z() + 0.5 - vehicle.getZ());
        Vec3 b = new Vec3(to.x() + 0.5 - vehicle.getX(), to.y() - Math.floor(vehicle.getY()), to.z() + 0.5 - vehicle.getZ());
        if (!volumeSafe(body.move(a).move(0, Math.max(0, b.y - a.y), 0).minmax(body.move(b)))) return false;
        if (vehicle instanceof AbstractMinecart) {
            var pos = new BlockPos(to.x(), to.y(), to.z());
            if (!minecraft.level.isLoaded(pos)) return false;
            var state = minecraft.level.getBlockState(pos);
            if (!(state.getBlock() instanceof BaseRailBlock rail)) return false;
            var exits = AbstractMinecart.exits(state.getValue(rail.getShapeProperty()));
            return List.of(exits.getFirst(), exits.getSecond()).stream().anyMatch(exit -> to.x() + exit.getX() == from.x()
                    && to.z() + exit.getZ() == from.z() && Math.abs(to.y() + exit.getY() - from.y()) <= 1);
        }
        var base = vehicle.getBoundingBox().move(b);
        for (double x : new double[]{base.minX + 0.05, base.maxX - 0.05}) for (double z : new double[]{base.minZ + 0.05, base.maxZ - 0.05}) {
            var floor = BlockPos.containing(x, base.minY - 0.15, z);
            if (!minecraft.level.isLoaded(floor)) return false;
            var state = minecraft.level.getBlockState(floor);
            if (vehicle instanceof AbstractBoat) {
                if (!state.getFluidState().is(FluidTags.WATER) || state.is(Blocks.BUBBLE_COLUMN)) return false;
            } else if (state.getCollisionShape(minecraft.level, floor).isEmpty() || !state.getFluidState().isEmpty()
                    || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)) return false;
        }
        return true;
    }
    private boolean volumeSafe(AABB body) {
        for (var pos : BlockPos.betweenClosed(BlockPos.containing(body.minX, body.minY, body.minZ),
                BlockPos.containing(body.maxX - 1E-5, body.maxY - 1E-5, body.maxZ - 1E-5))) {
            if (!minecraft.level.isLoaded(pos) || !minecraft.level.getWorldBorder().isWithinBounds(pos)
                    || pos.distToCenterSqr(vehicle.position()) > 100) return false;
            var state = minecraft.level.getBlockState(pos);
            if (state.getFluidState().is(FluidTags.LAVA) || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                    || state.is(Blocks.CACTUS) || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.POWDER_SNOW)) return false;
        }
        return minecraft.level.noCollision(vehicle, body);
    }
    private static NavCell cell(String dimension, Vec3 position) {
        return new NavCell(dimension, Mth.floor(position.x), Mth.floor(position.y), Mth.floor(position.z));
    }
    private void neutral() { if (inputs != null) { inputs.close(); inputs = null; } }
    public void close() { try { neutral(); } finally { if (view != null) { view.close(owner); view = null; } } }
    public Map<String, Object> result() {
        var result = new LinkedHashMap<String, Object>();
        result.put("locomotion", vehicle instanceof AbstractBoat ? "boat" : vehicle instanceof AbstractMinecart ? "minecart" : "horse");
        result.put("path_plans", replans);
        result.put("vehicle_may_continue_moving", vehicle.isAlive() && vehicle.getDeltaMovement().lengthSqr() > 0.000625);
        if (failure != null) result.put("failure", failure);
        return result;
    }
    private TickResult fail(String reason) { failure = reason; close(); return new TickResult(Status.FAILED, Reason.UNVERIFIED_MOVEMENT_VECTOR); }
    private static TickResult running() { return new TickResult(Status.RUNNING, Reason.NONE); }
}
