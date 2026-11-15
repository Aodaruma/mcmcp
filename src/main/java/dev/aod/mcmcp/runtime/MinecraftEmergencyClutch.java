package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.routine.NavigationViewLease;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** A single witnessed clutch use; a missing acknowledgement never causes another use. */
final class MinecraftEmergencyClutch implements AutoCloseable {
    private final Minecraft minecraft;
    private final UUID session, owner = UUID.randomUUID();
    private final String item;
    private V2HeldItemSelection selection;
    private NavigationViewLease view;
    private ClientPredictionSignals.PredictionAttempt prediction;
    private boolean sent, confirmed, mounted;
    private long sentTick;
    private BlockPos placed;
    private String failure;
    private final Set<UUID> existingBoats = new HashSet<>();

    MinecraftEmergencyClutch(Minecraft minecraft, UUID session, String item) {
        this.minecraft = minecraft; this.session = session; this.item = item;
    }
    void tick(long tick, BooleanSupplier allowed) {
        var player = minecraft.player;
        if (!allowed.getAsBoolean() || player == null || player.isDeadOrDying()) return;
        if (sent) {
            if (prediction != null) {
                boolean block = prediction.confirmation(state -> item.equals("minecraft:water_bucket")
                        ? state.getFluidState().is(FluidTags.WATER)
                        : net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(item))
                        .serverConfirmed();
                confirmed |= block && prediction.acknowledgement().acknowledged();
            }
            if (EmergencyRecoveryPolicy.boat(item) && !mounted) {
                var boat = minecraft.level.getEntitiesOfClass(AbstractBoat.class, player.getBoundingBox().inflate(4),
                        entity -> entity.isAlive() && entity.getPassengers().isEmpty() && !existingBoats.contains(entity.getUUID())
                                && player.hasLineOfSight(entity)).stream().findFirst().orElse(null);
                if (boat != null && player.position().distanceTo(boat.position()) <= player.entityInteractionRange()) {
                    mounted = true;
                    minecraft.gameMode.interact(player, boat, new net.minecraft.world.phys.EntityHitResult(boat), InteractionHand.MAIN_HAND);
                }
            }
            confirmed |= mounted && player.isPassenger();
            return;
        }
        if (selection == null) {
            selection = new V2HeldItemSelection(minecraft, session, item);
            if (!selection.begin(tick, false)) { failure = "clutch_item_unavailable"; return; }
        }
        var selected = selection.poll(tick);
        if (selected == V2InventorySwap.Result.WAITING) return;
        if (selected != V2InventorySwap.Result.CONFIRMED || !selection.matchesRequested()) {
            failure = "clutch_item_unconfirmed"; return;
        }
        if (view == null) view = NavigationViewLease.acquire(player, owner);
        if (!view.matches(owner)) { failure = "clutch_view_changed"; return; }
        Vec3 aim = player.position().add(player.getDeltaMovement().x * 2, -4, player.getDeltaMovement().z * 2);
        Vec3 delta = aim.subtract(player.getEyePosition());
        float yaw = delta.horizontalDistance() < 0.01 ? player.getYRot()
                : (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, delta.horizontalDistance()));
        view.turnToward(owner, yaw, pitch);
        var hit = player.pick(player.blockInteractionRange(), 1, true);
        if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK
                || block.getDirection() != net.minecraft.core.Direction.UP) return;
        var level = minecraft.level;
        var support = level.getBlockState(block.getBlockPos());
        // Never activate a bed, anchor, chest or other equipment as a side effect of an emergency placement.
        if (support.getBlock() instanceof BedBlock || support.getBlock() instanceof RespawnAnchorBlock
                || support.hasBlockEntity()) return;
        if (item.equals("minecraft:water_bucket") && level.environmentAttributes().getValue(
                net.minecraft.world.attribute.EnvironmentAttributes.WATER_EVAPORATES, block.getBlockPos())) {
            failure = "water_evaporates"; return;
        }
        if (player.getMainHandItem().getItem() instanceof BlockItem) {
            var context = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, player.getMainHandItem(), block);
            if (!context.canPlace()) return;
            placed = context.getClickedPos();
        } else placed = block.getBlockPos().above();
        if (EmergencyRecoveryPolicy.boat(item))
            minecraft.level.getEntitiesOfClass(AbstractBoat.class, player.getBoundingBox().inflate(8))
                    .forEach(boat -> existingBoats.add(boat.getUUID()));
        prediction = ClientPredictionSignals.global().begin(level, placed, tick);
        int before = prediction.sequenceBeforePrediction();
        sent = true;
        sentTick = tick;
        if (item.equals("minecraft:water_bucket") || EmergencyRecoveryPolicy.boat(item))
            minecraft.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        else minecraft.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, block);
        if (prediction.captureIssuedPredictions() != before + 1) failure = "clutch_prediction_unavailable";
    }
    String failure() { return failure; }
    boolean dispatched() { return sent; }
    boolean evidenceSettled(long tick) { return confirmed || failure != null || sent && tick - sentTick >= 80; }
    int usesDispatched() { return (sent ? 1 : 0) + (mounted ? 1 : 0); }
    Map<String, Object> result() {
        return Map.of("item", item, "dispatched", sent, "effect_confirmed", confirmed,
                "boat_mount_dispatched", mounted, "failure", failure == null ? "none" : failure);
    }
    @Override public void close() {
        if (view != null) { view.close(owner); view = null; }
        if (selection != null) { selection.close(); selection = null; }
        if (prediction != null) { prediction.close(); prediction = null; }
    }
}
