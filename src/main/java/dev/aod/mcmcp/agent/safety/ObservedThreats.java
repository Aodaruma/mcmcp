package dev.aod.mcmcp.agent.safety;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import java.util.LinkedHashMap;
import java.util.UUID;

/** Received damage attribution, scoped to this player/world, plus a currently known attack target. */
public final class ObservedThreats {
    private static final History history = new History();
    private static long revision;
    private ObservedThreats() { }

    public static void bindAction(LocalPlayer player, UUID action) {
        history.bind(player, player == null ? null : player.level(), action);
    }

    public static void received(LocalPlayer player, DamageSource source) {
        if (!history.matches(player, player.level())) bindAction(player, null);
        Entity cause = source.getEntity();
        if (cause == null) cause = source.getDirectEntity();
        if (cause instanceof Mob || cause instanceof Player) {
            history.received(cause.getUUID(), player.level().getGameTime(), cause instanceof Player);
            revision++;
        }
    }

    public static boolean attacking(Entity entity, LocalPlayer player) {
        if (entity == player || !entity.isAlive()) return false;
        var target = entity instanceof Mob mob ? mob.getTarget() : null;
        if (!history.matches(player, player.level())) return target == player;
        boolean attackingPose = entity instanceof Mob mob && (mob.isAggressive()
                || mob instanceof net.minecraft.world.entity.monster.illager.Pillager pillager && pillager.isChargingCrossbow());
        boolean facingUs = entity.getLookAngle().dot(player.getEyePosition().subtract(entity.getEyePosition()).normalize()) >= 0.8;
        return history.attacking(entity.getUUID(), player.level().getGameTime(), target != null,
                target == player, attackingPose && facingUs);
    }

    public static long revision() { return revision; }
    public static boolean recentAttack(LocalPlayer player) {
        return history.matches(player, player.level()) && history.recentAttack(player.level().getGameTime());
    }
    public static boolean recentPlayerAttack(LocalPlayer player) { return recentAttack(player) && history.playerAttacker; }

    /** Retain identities across rescue/script children, not unseen positions or blanket hostility by mob type. */
    static final class History {
        private record Attack(long tick, UUID action) { }
        private Object owner, level;
        private UUID action;
        private final LinkedHashMap<UUID, Attack> attackers = new LinkedHashMap<>();
        private long lastAttackTick = Long.MIN_VALUE;
        private boolean playerAttacker;

        void bind(Object player, Object world, UUID currentAction) {
            if (!matches(player, world)) {
                attackers.clear(); lastAttackTick = Long.MIN_VALUE;
                owner = player; level = world;
            }
            action = currentAction;
        }
        boolean matches(Object player, Object world) { return owner == player && level == world; }
        void received(UUID attacker, long tick, boolean fromPlayer) {
            remember(attacker, tick);
            lastAttackTick = tick; playerAttacker = fromPlayer;
        }
        private void remember(UUID attacker, long tick) {
            attackers.remove(attacker);
            attackers.put(attacker, new Attack(tick, action));
            if (attackers.size() > 64) attackers.pollFirstEntry();
        }
        boolean attacking(UUID entity, long tick, boolean targetKnown, boolean targetingUs, boolean aimedAttackPose) {
            if (targetKnown && !targetingUs) return false;
            if (targetingUs) { remember(entity, tick); return true; }
            var attack = attackers.get(entity);
            if (attack == null || tick < attack.tick()) return false;
            return tick - attack.tick() <= 200
                    || action != null && action.equals(attack.action()) && aimedAttackPose;
        }
        boolean recentAttack(long tick) {
            return lastAttackTick != Long.MIN_VALUE && tick >= lastAttackTick && tick - lastAttackTick <= 40;
        }
    }
}
