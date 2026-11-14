package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.safety.ObservedThreats;
import dev.aod.mcmcp.client.AgentInputState;
import dev.aod.mcmcp.routine.BoundedInputLease;
import dev.aod.mcmcp.routine.NavigationViewLease;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Bounded self-defense against a witnessed attacking mob, never a player or merely nearby animal. */
final class MinecraftEmergencyCombat implements AutoCloseable {
    private static final Duration HORIZON = Duration.ofMillis(500);
    private final Minecraft minecraft;
    private final UUID session, owner = UUID.randomUUID();
    private V2HeldItemSelection selection;
    private NavigationViewLease view;
    private BoundedInputLease bowUse;
    private BoundedInputLease shieldUse;
    private long shieldSince;
    private int shieldUses;
    private long drawingSince, lastAttack = Long.MIN_VALUE;
    private UUID targetId;
    private String weapon;
    private int attacks;
    private boolean cameraBlocked;

    MinecraftEmergencyCombat(Minecraft minecraft, UUID session) { this.minecraft = minecraft; this.session = session; }

    void tick(Mob target, long tick, int attackLimit, double remainingCamera, BooleanSupplier allowed) {
        cameraBlocked = false;
        var player = minecraft.player;
        if (target == null || !allowed.getAsBoolean() || attacks + shieldUses >= attackLimit && shieldUse == null
                || !ObservedThreats.attacking(target, player) || !target.isAlive()
                || !player.hasLineOfSight(target) || player.distanceToSqr(target) > 16 * 16
                || targetId != null && !targetId.equals(target.getUUID())) { close(); return; }
        targetId = target.getUUID();
        if (defendArrow(target, tick, attackLimit, remainingCamera)) return;
        boolean arrows = false;
        for (int slot = 0; slot < 36; slot++) {
            var stack = player.getInventory().getItem(slot);
            arrows |= stack.is(Items.ARROW) || stack.is(Items.SPECTRAL_ARROW) || stack.is(Items.TIPPED_ARROW);
        }
        boolean rangedClear = !(target instanceof EnderMan) && player.distanceToSqr(target) >= 16
                && clearShot(target, target.getBoundingBox().getCenter(), true);
        int priority = Integer.MAX_VALUE;
        String preferred = null;
        for (int slot = 0; slot < 36; slot++) {
            var stack = player.getInventory().getItem(slot);
            if (stack.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            int score = EmergencyRecoveryPolicy.weaponPriority(id, arrows, rangedClear);
            if (score < priority) { priority = score; preferred = id; }
        }
        if (preferred == null) { close(); return; }
        // A bystander can leave the shot corridor after we first selected a melee tool.
        if (selection != null && !preferred.equals(weapon)) {
            releaseBow(false); selection.close(); selection = null;
        }
        if (selection == null) {
            weapon = preferred;
            selection = new V2HeldItemSelection(minecraft, session, weapon);
            if (!selection.begin(tick, false)) { close(); return; }
        }
        if (selection.poll(tick) != V2InventorySwap.Result.CONFIRMED || !selection.matchesRequested()) return;
        boolean bow = weapon.equals("minecraft:bow");
        if (bow && !rangedClear) { close(); return; }
        Vec3 aim = target.getBoundingBox().getCenter();
        if (bow) {
            double flight = player.getEyePosition().distanceTo(aim) / 3;
            aim = aim.add(target.getDeltaMovement().scale(flight)).add(0, 0.025 * flight * flight, 0);
        }
        Vec3 direction = aim.subtract(player.getEyePosition());
        float yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(direction.y, direction.horizontalDistance()));
        if (view == null) view = NavigationViewLease.acquire(player, owner);
        if (!view.matches(owner)) { close(); return; }
        if (!view.turnTowardWithinBudget(owner, yaw, pitch, remainingCamera)) {
            cameraBlocked = true;
            close();
            return;
        }
        boolean aimed = Math.abs(net.minecraft.util.Mth.wrapDegrees(player.getYRot() - yaw)) < 2
                && Math.abs(player.getXRot() - pitch) < 2;
        if (!aimed || !clearShot(target, aim, bow)) {
            releaseBow(false);
            return;
        }
        if (bow) {
            if (bowUse == null) {
                if (lastAttack != Long.MIN_VALUE && tick - lastAttack < 10) return;
                minecraft.gameMode.useItem(player, InteractionHand.MAIN_HAND);
                if (!player.isUsingItem()) return;
                bowUse = BoundedInputLease.acquire(AgentInputState.global(), Set.of(BoundedInputLease.Input.USE),
                        System.nanoTime(), HORIZON);
                AgentInputState.global().suppressUseRestart();
                drawingSince = tick;
            } else if (tick - drawingSince >= 20) {
                releaseBow(true); attacks++; lastAttack = tick;
            } else if (!bowUse.heartbeat(System.nanoTime(), HORIZON)) close();
        } else if (player.getAttackStrengthScale(0) >= 0.95
                && player.getEyePosition().distanceTo(target.getBoundingBox().getCenter()) <= player.entityInteractionRange()
                && (lastAttack == Long.MIN_VALUE || tick - lastAttack >= 10)) {
            minecraft.gameMode.attack(player, target);
            player.swing(InteractionHand.MAIN_HAND);
            attacks++; lastAttack = tick;
        }
    }

    private boolean defendArrow(Mob target, long tick, int limit, double camera) {
        var player = minecraft.player;
        var shield = player.getOffhandItem();
        if (!shield.is(Items.SHIELD) || !shield.has(net.minecraft.core.component.DataComponents.BLOCKS_ATTACKS)
                || player.getCooldowns().isOnCooldown(shield) || !player.onGround()
                || player.isFallFlying() || player.isInWater() || player.isInLava()) {
            releaseShield(); return false;
        }
        var incoming = minecraft.level.getEntitiesOfClass(net.minecraft.world.entity.projectile.arrow.AbstractArrow.class,
                player.getBoundingBox().inflate(16), arrow ->
                        (arrow.getType() == net.minecraft.world.entity.EntityTypes.ARROW
                            || arrow.getType() == net.minecraft.world.entity.EntityTypes.SPECTRAL_ARROW)
                        && arrow.getOwner() == target && arrow.getPierceLevel() == 0 && arrow.isAlive()
                        && player.hasLineOfSight(arrow) && approachingArrow(player.getBoundingBox(),
                            arrow.position(), arrow.getDeltaMovement())).stream()
                .min(java.util.Comparator.comparingDouble(player::distanceToSqr)).orElse(null);
        if (incoming == null || shieldUse != null && tick - shieldSince >= 32) {
            releaseShield(); return false;
        }
        releaseBow(false);
        if (selection != null) { selection.close(); selection = null; }
        var direction = incoming.position().subtract(player.getEyePosition());
        float yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
        if (view == null) view = NavigationViewLease.acquire(player, owner);
        if (!view.matches(owner) || !view.turnTowardWithinBudget(owner, yaw, 0, camera)) {
            cameraBlocked = true; releaseShield(); return true;
        }
        if (shieldUse == null) {
            if (attacks + shieldUses >= limit || player.isUsingItem()) return true;
            shieldUses++;
            minecraft.gameMode.useItem(player, InteractionHand.OFF_HAND);
            if (!player.isUsingItem() || player.getUsedItemHand() != InteractionHand.OFF_HAND) return true;
            shieldUse = BoundedInputLease.acquire(AgentInputState.global(), Set.of(BoundedInputLease.Input.USE),
                    System.nanoTime(), HORIZON);
            AgentInputState.global().suppressUseRestart();
            shieldSince = tick;
        } else if (!shieldUse.heartbeat(System.nanoTime(), HORIZON)) releaseShield();
        return true;
    }

    static boolean approachingArrow(AABB body, Vec3 position, Vec3 velocity) {
        if (velocity.lengthSqr() < .01) return false;
        // Native, non-piercing arrows only; this does not classify unknown MOD projectiles.
        for (int i = 0; i < 12; i++) {
            var next = position.add(velocity);
            if (body.inflate(.3).clip(position, next).isPresent()) return true;
            position = next;
            velocity = velocity.scale(.99).add(0, -.05, 0);
        }
        return false;
    }

    private void releaseShield() {
        if (shieldUse == null) return;
        shieldUse.close(); shieldUse = null;
        if (minecraft.player != null && minecraft.player.isUsingItem()
                && minecraft.player.getUsedItemHand() == InteractionHand.OFF_HAND)
            minecraft.gameMode.releaseUsingItem(minecraft.player);
    }

    private boolean clearShot(Mob target, Vec3 aim, boolean ranged) {
        var player = minecraft.player;
        Vec3 start = player.getEyePosition();
        if (minecraft.level.clip(new ClipContext(start, aim, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, player)).getType() != HitResult.Type.MISS) return false;
        // Do not shoot across another body or a player near the target/backdrop; Vanilla arrows have spread.
        Vec3 end = ranged ? aim.add(aim.subtract(start).normalize().scale(24)) : aim;
        AABB corridor = new AABB(start, end).inflate(ranged ? 2 : 0.3);
        return minecraft.level.getEntities(player, corridor, entity -> entity instanceof LivingEntity
                && entity != target && entity.isAlive()).isEmpty();
    }
    private void releaseBow(boolean fire) {
        if (bowUse != null) {
            bowUse.close(); bowUse = null;
            if (minecraft.player != null && minecraft.player.isUsingItem()) {
                if (fire) minecraft.gameMode.releaseUsingItem(minecraft.player);
                else {
                    // Releasing a drawn bow fires it. A normal hotbar change cancels use without shooting.
                    int slot = minecraft.player.getInventory().getSelectedSlot();
                    minecraft.getConnection().send(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket((slot + 1) % 9));
                    minecraft.getConnection().send(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(slot));
                    minecraft.player.stopUsingItem();
                }
            }
        }
    }
    Map<String, Object> result() {
        return Map.of("attacks_dispatched", attacks, "shield_uses_dispatched", shieldUses,
                "shield_active", shieldUse != null, "weapon", weapon == null ? "none" : weapon,
                "target", targetId == null ? "none" : targetId.toString(), "camera_budget_blocked", cameraBlocked);
    }
    int attacksDispatched() { return attacks; }
    int interactionsDispatched() { return attacks + shieldUses; }
    boolean readyToFight() { return shieldUse != null || targetId != null && selection != null && selection.matchesRequested(); }
    boolean cameraBlocked() { return cameraBlocked; }
    @Override public void close() {
        releaseBow(false);
        releaseShield();
        if (view != null) { view.close(owner); view = null; }
        if (selection != null) { selection.close(); selection = null; }
        targetId = null;
    }
}
