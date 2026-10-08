package dev.aod.mcmcp.runtime;

import net.minecraft.client.Minecraft;
import dev.aod.mcmcp.routine.MovementInputLease;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** One normal sneak pulse after checking vanilla's local dismount location. */
final class MinecraftV2DismountDriver implements V2OperationJobExecution.Driver {
    private final Minecraft minecraft;
    private final UUID owner = UUID.randomUUID();
    private net.minecraft.world.entity.Entity vehicle;
    private net.minecraft.client.player.LocalPlayer player;
    private MovementInputLease inputs;
    private long baseline = -1, started;
    private boolean dispatched, confirmed;
    private String failure;
    MinecraftV2DismountDriver(Minecraft minecraft) { this.minecraft = minecraft; }
    public void begin(long tick, BooleanSupplier allowed) {
        player = minecraft.player; vehicle = player.getVehicle();
        if (!MinecraftV2VehicleDriver.supported(player) || vehicle.getDeltaMovement().lengthSqr() > 0.0025) {
            failure = "dismount_requires_stationary_vehicle"; return;
        }
        if (!landingSafe()) { failure = "dismount_landing_unverified"; return; }
        var evidence = VehiclePassengerSignals.global().snapshot(vehicle);
        baseline = evidence == null ? -1 : evidence.revision();
    }
    private boolean landingSafe() {
        var destination = vehicle.getDismountLocationForPassenger(player);
        var box = player.getBoundingBox().move(destination.subtract(player.position()));
        var pos = net.minecraft.core.BlockPos.containing(destination);
        if (destination.distanceTo(player.position()) > 4 || !minecraft.level.isLoaded(pos)
                || !minecraft.level.isLoaded(pos.below()) || !minecraft.level.noCollision(player, box)
                || !minecraft.level.getFluidState(pos).isEmpty()
                || minecraft.level.getBlockState(pos.below()).getCollisionShape(minecraft.level, pos.below()).isEmpty()) {
            return false;
        }
        for (var cell : net.minecraft.core.BlockPos.betweenClosed(
                net.minecraft.core.BlockPos.containing(box.minX, box.minY - 0.05, box.minZ),
                net.minecraft.core.BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            if (!minecraft.level.isLoaded(cell) || !minecraft.level.getWorldBorder().isWithinBounds(cell)) return false;
            var state = minecraft.level.getBlockState(cell);
            if (!state.getFluidState().isEmpty() || state.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)
                    || state.is(net.minecraft.world.level.block.Blocks.CAMPFIRE) || state.is(net.minecraft.world.level.block.Blocks.SOUL_CAMPFIRE)
                    || state.is(net.minecraft.world.level.block.Blocks.FIRE) || state.is(net.minecraft.world.level.block.Blocks.SOUL_FIRE)
                    || state.is(net.minecraft.world.level.block.Blocks.CACTUS) || state.is(net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH)
                    || state.is(net.minecraft.world.level.block.Blocks.POWDER_SNOW)) return false;
        }
        return true;
    }
    public V2OperationJobExecution.Step tick(long tick, BooleanSupplier allowed) {
        if (failure != null) return V2OperationJobExecution.Step.FAILED;
        if (minecraft.player != player || minecraft.level != vehicle.level()) { failure = "dismount_context_changed"; return V2OperationJobExecution.Step.FAILED; }
        if (!dispatched && allowed.getAsBoolean()) {
            if (player.getVehicle() != vehicle || !MinecraftV2VehicleDriver.supported(player)
                    || vehicle.getDeltaMovement().lengthSqr() > 0.0025 || !landingSafe()) {
                failure = "dismount_context_or_landing_changed";
                return V2OperationJobExecution.Step.FAILED;
            }
            inputs = MovementInputLease.acquire(minecraft, owner, System.nanoTime(), java.time.Duration.ofMillis(500));
            inputs.setDesired(owner, Set.of(MovementInputLease.MovementKey.CROUCH));
            dispatched = true;
            started = tick;
            inputs.heartbeat(owner, System.nanoTime(), java.time.Duration.ofMillis(500));
        }
        if (dispatched && tick - started >= 2) close();
        var evidence = VehiclePassengerSignals.global().snapshot(vehicle);
        confirmed = dispatched && evidence != null && evidence.revision() > baseline
                && !evidence.passengers().contains(player.getId()) && !player.isPassenger();
        return confirmed ? V2OperationJobExecution.Step.CONFIRMED : V2OperationJobExecution.Step.RUNNING;
    }
    public boolean allowsRiding() { return true; }
    public void close() { if (inputs != null) { inputs.close(); inputs = null; } }
    public Map<String, Object> result() {
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("operation", "dismount"); result.put("confirmed", confirmed); result.put("unconfirmed", dispatched && !confirmed);
        if (failure != null) result.put("failure", failure);
        return result;
    }
}
