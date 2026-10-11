package dev.aod.mcmcp.agent.safety;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.Optional;

/** Immutable, bounded physical corridor for one one-cell gap or a drop to a known landing. */
public record GroundTraversal(AABB start, AABB landing, Locomotion mode, double damageAllowance) {
    public static final int MAX_DROP = 5;
    public static final double JUMP_APEX = 1.3;
    private static final double EPSILON = 1.0E-6;

    public GroundTraversal {
        if (start == null || landing == null || mode == null || !mode.ballistic()
                || !Double.isFinite(damageAllowance) || damageAllowance < 0)
            throw new IllegalArgumentException("invalid ground traversal");
        double dx = Math.abs(landing.getCenter().x - start.getCenter().x);
        double dz = Math.abs(landing.getCenter().z - start.getCenter().z);
        double drop = start.minY - landing.minY;
        if (Math.min(dx, dz) > 0.51 || Math.max(dx, dz) > (mode == Locomotion.GAP_JUMP ? 2.51 : 1.51)
                || Math.max(dx, dz) < (mode == Locomotion.GAP_JUMP ? 1.49 : 0.49)
                || mode == Locomotion.GAP_JUMP && Math.abs(drop) > EPSILON
                || mode == Locomotion.DROP && (drop <= EPSILON || drop > MAX_DROP + EPSILON))
            throw new IllegalArgumentException("ground traversal exceeds bounded geometry");
    }

    public static Optional<GroundTraversal> discover(LocalPlayer player, AABB start,
            int targetX, int targetZ, Locomotion mode, double allowance) {
        var level = (ClientLevel) player.level();
        var origin = point(player.getBoundingBox().getCenter());
        if (!physicsSupported(player, mode)) return Optional.empty();
        if (!LocalObservationVolume.stableLanding(player, level, origin, start)
                || LocalObservationVolume.bouncySupport(level, player, start)) return Optional.empty();
        var over = start.move(targetX + 0.5 - start.getCenter().x, 0, targetZ + 0.5 - start.getCenter().z);
        AABB landing = over;
        if (mode == Locomotion.DROP) {
            // Query only the loaded local volume; an unknown landing never becomes an edge.
            var region = over.expandTowards(0, -MAX_DROP, 0);
            if (!LocalObservationVolume.loadedGroundRegion(level, origin, region)) return Optional.empty();
            var down = new Vec3(0, -MAX_DROP, 0);
            var delta = Entity.collideBoundingBox(player, down, over, level, level.getEntityCollisions(player, region));
            if (delta.y >= -EPSILON || delta.y < -MAX_DROP) return Optional.empty();
            landing = over.move(delta);
        } else if (mode != Locomotion.GAP_JUMP) return Optional.empty();
        else {
            var middle = start.move((over.getCenter().x - start.getCenter().x) / 2, 0,
                    (over.getCenter().z - start.getCenter().z) / 2);
            if (!LocalObservationVolume.loadedGroundRegion(level, origin, middle.expandTowards(0, -0.1, 0))
                    || LocalObservationVolume.stableLanding(player, level, origin, middle)) return Optional.empty();
        }
        GroundTraversal plan;
        try { plan = new GroundTraversal(start, landing, mode, allowance); }
        catch (IllegalArgumentException invalid) { return Optional.empty(); }
        return plan.corridorSafe(player) ? Optional.of(plan) : Optional.empty();
    }

    public double predictedDamage(LocalPlayer player) {
        return FallDamageEstimate.predict(player, mode == Locomotion.GAP_JUMP ? JUMP_APEX : start.minY - landing.minY);
    }

    public boolean corridorSafe(LocalPlayer player) {
        return physicsSupported(player, mode) && corridorSafe(player, start);
    }

    private boolean corridorSafe(LocalPlayer player, AABB from) {
        var level = (ClientLevel) player.level();
        var over = landing.move(0, from.minY - landing.minY, 0);
        List<AABB> regions = mode == Locomotion.GAP_JUMP
                ? List.of(start.minmax(over).expandTowards(0, JUMP_APEX, 0))
                : List.of(from.minmax(over), over.minmax(landing));
        double damage = predictedDamage(player);
        return damage <= damageAllowance + EPSILON
                && damage < player.getHealth() + player.getAbsorptionAmount() - EPSILON
                && LocalObservationVolume.dryGroundCorridor(player, level, point(player.getBoundingBox().getCenter()), regions, landing);
    }

    public boolean currentSafe(LocalPlayer player) {
        return physicsSupported(player, mode) && contains(player.getBoundingBox())
                && corridorSafe(player, mode == Locomotion.DROP ? player.getBoundingBox() : start)
                && LocalObservationVolume.dryGroundCorridor(player, (ClientLevel) player.level(),
                        point(player.getBoundingBox().getCenter()), List.of(player.getBoundingBox()), landing);
    }

    public boolean resolvedSafe(LocalPlayer player, Vec3 intended, Vec3 resolved) {
        var before = player.getBoundingBox();
        var after = before.move(resolved);
        if (!currentSafe(player) || !contains(after)) return false;
        if (Math.abs(intended.x - resolved.x) > EPSILON || Math.abs(intended.z - resolved.z) > EPSILON) return false;
        var target = landing.getCenter();
        double previous = horizontalDistance(before.getCenter(), target);
        double next = horizontalDistance(after.getCenter(), target);
        if (next > Math.max(previous, 0.25) + EPSILON) return false;
        return LocalObservationVolume.dryGroundCorridor(player, (ClientLevel) player.level(),
                point(before.getCenter()), List.of(before.minmax(after)), landing);
    }

    public boolean contains(AABB body) {
        var a = start.getCenter(); var b = landing.getCenter(); var c = body.getCenter();
        return c.x >= Math.min(a.x, b.x) - 0.25 && c.x <= Math.max(a.x, b.x) + 0.25
                && c.z >= Math.min(a.z, b.z) - 0.25 && c.z <= Math.max(a.z, b.z) + 0.25
                && body.minY >= landing.minY - EPSILON
                && body.minY <= start.minY + (mode == Locomotion.GAP_JUMP ? JUMP_APEX : 0) + EPSILON;
    }

    public boolean landed(LocalPlayer player, double tolerance) {
        return player.onGround() && Math.abs(player.getY() - landing.minY) <= 0.05
                && horizontalDistance(player.getBoundingBox().getCenter(), landing.getCenter()) <= tolerance;
    }

    private static double horizontalDistance(Vec3 a, Vec3 b) { return Math.hypot(a.x - b.x, a.z - b.z); }
    private static boolean physicsSupported(LocalPlayer player, Locomotion mode) {
        if (player.isInWater() || player.isPassenger() || player.isFallFlying() || player.isCrouching()
                || Locomotion.aerialMode(player).aerial()
                || player.hasEffect(net.minecraft.world.effect.MobEffects.LEVITATION)
                || player.hasEffect(net.minecraft.world.effect.MobEffects.SLOW_FALLING)) return false;
        if (mode != Locomotion.GAP_JUMP) return true;
        return !player.hasEffect(net.minecraft.world.effect.MobEffects.JUMP_BOOST)
                && Math.abs(player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.GRAVITY) - 0.08) < 1.0E-5
                && Math.abs(player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.JUMP_STRENGTH) - 0.42) < 1.0E-5
                && player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED) >= 0.099;
    }
    private static ObservationRecord.Point point(Vec3 p) { return new ObservationRecord.Point(p.x, p.y, p.z); }
}
