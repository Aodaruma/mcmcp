package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.observation.ObservationFrameStore;

import dev.aod.mcmcp.agent.action.AgentActionStore;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.observation.ObservationFrame;
import dev.aod.mcmcp.agent.observation.ObservationRecord;
import dev.aod.mcmcp.client.AgentInputState;
import dev.aod.mcmcp.client.AgentScreenPolicy;
import dev.aod.mcmcp.observation.MinecraftObservationService;
import dev.aod.mcmcp.runtime.KillZoneSafety.AttackProfile;
import dev.aod.mcmcp.runtime.RuntimeFailures.RuntimeInvocationException;
import dev.aod.mcmcp.safety.ScopedEntityAttackConsentStore;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;

/** 消費済み同意scope、攻撃ACKと再送禁止集合を一つのAction内で所有する。 */
final class KillZoneExecution {
    private static final float MIN_SAFE_STAY_HEALTH = 6.0F;
    private final UUID actionId;
    private final AgentActionStore agentActions;
    private final ObservationFrameStore frames;
    private final MinecraftObservationService observations;
    private final ClientReconciliationSignals reconciliationSignals;
    private static final long EFFECT_DEADLINE_TICKS = 10L;

    private final ActionDsl.OperateKillZone operation;
    private final ScopedEntityAttackConsentStore.Scope scope;
    private final long startedAtClientTick;
    private final float healthBaseline;
    private final float absorptionBaseline;
    private final float effectiveHealthBaseline;
    private float lastHealth;
    private float lastAbsorption;
    private float lastEffectiveHealth;
    private final Set<UUID> noRetryEntityIds = new LinkedHashSet<>();
    private long lastDispatchTick = Long.MIN_VALUE;
    private int dispatchedAttacks;
    private int confirmedAttacks;
    private int unknownAttacks;
    private KillZoneAttackAttempt pending;

    KillZoneExecution(
            ActionDsl.OperateKillZone operation,
            ScopedEntityAttackConsentStore.Scope scope,
            long startedAtClientTick,
            float healthBaseline,
            float absorptionBaseline, UUID actionId, AgentActionStore agentActions,
            ObservationFrameStore frames, MinecraftObservationService observations,
            ClientReconciliationSignals reconciliationSignals) {
        this.actionId = actionId;
        this.agentActions = agentActions;
        this.frames = frames;
        this.observations = observations;
        this.reconciliationSignals = reconciliationSignals;
        this.operation = Objects.requireNonNull(operation, "operation");
        this.scope = Objects.requireNonNull(scope, "scope");
        if (startedAtClientTick < 0L || !Float.isFinite(healthBaseline)
                || !Float.isFinite(absorptionBaseline)) {
            throw new IllegalArgumentException("Invalid kill-zone execution baseline");
        }
        this.startedAtClientTick = startedAtClientTick;
        this.healthBaseline = healthBaseline;
        this.absorptionBaseline = absorptionBaseline;
        effectiveHealthBaseline = healthBaseline + absorptionBaseline;
        lastHealth = healthBaseline;
        lastAbsorption = absorptionBaseline;
        lastEffectiveHealth = effectiveHealthBaseline;
    }
    PrimitiveOutcome tickKillZone(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            long latestWorldRevision,
            boolean actionHardDeadlineReached,
            boolean newDispatchBudgetReached) {
        var player = Objects.requireNonNull(minecraft.player, "player");
        var level = Objects.requireNonNull(minecraft.level, "level");
        long tick = session.clientTick();

        String hazard = killZoneHardHazard(minecraft, player);
        if (hazard != null) {
            return safetyInterruptKillZone(minecraft, session, latestWorldRevision, hazard);
        }

        AttackProfile currentProfile;
        try {
            currentProfile = KillZoneSafety.requireKnownAttackProfile(player.getMainHandItem());
        } catch (RuntimeInvocationException changed) {
            return safetyInterruptKillZone(
                    minecraft, session, latestWorldRevision, "attack_profile_changed");
        }
        if (!this.scope.mainHandItem().equals(
                        BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).toString())
                || !this.scope.attackProfileFingerprint().equals(currentProfile.fingerprint())
                || this.scope.attackSideEffectProfile() != currentProfile.sideEffects()) {
            return safetyInterruptKillZone(minecraft, session, latestWorldRevision, "attack_profile_changed");
        }

        if (this.pending != null) {
            KillZoneAttackAttempt attempt = this.pending;
            LivingEntity target = attempt.target;
            boolean armorStandHit = armorStandHitConfirmed(attempt);
            boolean confirmed = armorStandHit || target.getHealth() < attempt.healthBefore
                    || (!target.isAlive() && target.getHealth() <= 0.0F);
            if (confirmed) {
                this.confirmedAttacks++;
                recordKillZoneAttackEffect(
                        session, attempt, AgentActionStore.Verification.CONFIRMED,
                        target.getHealth(), armorStandHit ? "armor_stand_hit_event"
                                : target.isAlive() ? "health_decreased" : "dead");
                this.pending = null;
            } else if (target.isRemoved() || KillZoneSafety.killZonePendingMustClose(
                    tick, attempt.effectDeadlineTick, actionHardDeadlineReached)) {
                this.unknownAttacks++;
                this.noRetryEntityIds.add(target.getUUID());
                recordKillZoneAttackEffect(
                        session, attempt, AgentActionStore.Verification.UNKNOWN,
                        target.getHealth(), target.isRemoved()
                                ? "despawned_or_unloaded"
                                : actionHardDeadlineReached
                                        ? "action_budget_deadline" : "effect_timeout");
                this.pending = null;
            } else {
                return PrimitiveOutcome.running();
            }
        }

        boolean durationComplete = actionHardDeadlineReached || newDispatchBudgetReached
                || tick - this.startedAtClientTick
                >= this.scope.maxOperationDurationTicks();
        boolean countComplete = this.dispatchedAttacks >= this.scope.maxAttacks();
        if (durationComplete || countComplete) {
            return finishKillZone(minecraft, session, latestWorldRevision,
                    actionHardDeadlineReached ? "action_budget_reached"
                            : countComplete ? "attack_limit_reached"
                            : newDispatchBudgetReached ? "dispatch_budget_reached"
                            : "duration_reached");
        }
        if (this.lastDispatchTick != Long.MIN_VALUE
                && tick - this.lastDispatchTick < this.scope.minimumIntervalTicks()) {
            return PrimitiveOutcome.running();
        }
        if (player.getAttackStrengthScale(0.0F) < 0.99F) {
            return PrimitiveOutcome.running();
        }

        KillZoneTarget target = currentKillZoneTarget(minecraft, session);
        if (target == null) return PrimitiveOutcome.running();
        if (!KillZoneSafety.killZoneStructureFingerprint(
                        level,
                        this.scope.playerStationBounds(),
                        this.scope.targetKillZoneBounds())
                .equals(this.scope.structureFingerprint())) {
            return safetyInterruptKillZone(minecraft, session, latestWorldRevision, "structure_changed");
        }
        if (!KillZoneSafety.killZoneCollateralSafe(level, player, target.entity(), this.scope)) {
            return safetyInterruptKillZone(minecraft, session, latestWorldRevision, "collateral_not_proved");
        }

        // Reserve before semantic dispatch. Unknown outcomes and exceptions never return this slot.
        this.dispatchedAttacks++;
        this.lastDispatchTick = tick;
        float healthBefore = target.entity().getHealth();
        long armorStandLastHitBefore = KillZoneSafety.armorStandLastHit(target.entity());
        try {
            minecraft.gameMode.attack(player, target.entity());
            player.swing(InteractionHand.MAIN_HAND);
            agentActions.recordInteraction(actionId);
        } catch (RuntimeException | LinkageError dispatchFailure) {
            this.unknownAttacks++;
            this.noRetryEntityIds.add(target.entity().getUUID());
            var synthetic = new KillZoneAttackAttempt(
                    target.entity(), target.entityRef(), healthBefore,
                    armorStandLastHitBefore, tick);
            recordKillZoneAttackEffect(
                    session, synthetic, AgentActionStore.Verification.UNKNOWN,
                    target.entity().getHealth(), "dispatch_exception");
            throw dispatchFailure;
        }
        this.pending = new KillZoneAttackAttempt(
                target.entity(), target.entityRef(), healthBefore,
                armorStandLastHitBefore, tick);
        return PrimitiveOutcome.running();
    }

    private String killZoneHardHazard(
            Minecraft minecraft, Player player) {
        float health = player.getHealth();
        float effectiveHealth = KillZoneSafety.effectiveHealth(player);
        if (!player.isAlive() || player.isCreative() || player.isSpectator()) return "player_mode_or_life";
        if (effectiveHealth < this.lastEffectiveHealth) return "health_decreased";
        if (health < MIN_SAFE_STAY_HEALTH) return "health_floor";
        if (player.hurtTime > 0) return "active_damage";
        if (player.isOnFire()) return "on_fire";
        if (player.fallDistance > 0.0F || !player.onGround()) return "fall_or_support";
        if (player.getAirSupply() < player.getMaxAirSupply()) return "air_loss";
        if (player.isPassenger() || player.isInWater() || player.isInLava()
                || player.isFallFlying() || player.getAbilities().flying) return "unsupported_locomotion";
        if (!AgentScreenPolicy.allowsWorldInput(minecraft.gui.screen())) return "screen_open";
        AABB box = player.getBoundingBox();
        if (!this.scope.playerStationBounds().contains(
                box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)) return "station_departed";
        if (!minecraft.level.noCollision(player, box.deflate(1.0e-5D))) return "player_collision";
        if (player.getDeltaMovement().lengthSqr() > 0.01D) return "unexpected_motion";
        AABB safety = box.inflate(8.0D);
        if (!minecraft.level.getEntities(player, safety,
                entity -> entity instanceof Projectile && entity.isAlive()).isEmpty()) {
            return "projectile_present";
        }
        if (!minecraft.level.getEntities(player, box.inflate(0.125D),
                entity -> entity instanceof LivingEntity && entity.isAlive()).isEmpty()) {
            return "living_contact";
        }
        for (Entity entity : minecraft.level.getEntities(player, safety,
                entity -> entity.isAlive() && (entity instanceof Enemy
                        || entity instanceof Mob mob && mob.getTarget() == player))) {
            if (!(entity instanceof LivingEntity living)
                    || !this.scope.entityTypeAllowlist().contains(KillZoneSafety.entityType(entity))
                    || !KillZoneSafety.wholeBoxInside(this.scope.targetKillZoneBounds(), living.getBoundingBox())) {
                return "hostile_outside_policy";
            }
            if (living.hasLineOfSight(player)) return "hostile_has_player_los";
        }
        return null;
    }

    private KillZoneTarget currentKillZoneTarget(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session) {
        if (!(minecraft.hitResult instanceof EntityHitResult hit)
                || !(hit.getEntity() instanceof LivingEntity target)
                || target instanceof Player
                || !target.isAlive()
                || this.noRetryEntityIds.contains(target.getUUID())
                || !this.scope.entityTypeAllowlist().contains(KillZoneSafety.entityType(target))
                || !KillZoneSafety.wholeBoxInside(this.scope.targetKillZoneBounds(), target.getBoundingBox())
                || target.getBoundingBox().getYsize() <= 1.0D
                || !KillZoneSafety.clearKillZoneCrosshairRay(minecraft, hit)
                || target instanceof Mob mob
                        && (mob instanceof Enemy || mob.getTarget() == minecraft.player)
                        && target.hasLineOfSight(minecraft.player)
                || !minecraft.player.isWithinEntityInteractionRange(target, 0.0D)) {
            return null;
        }
        Optional<ObservationFrame> frame = frames.latestFrame();
        if (frame.isEmpty() || frame.orElseThrow().visibleEntitiesTruncated()
                || !session.dimension().equals(frame.orElseThrow().dimension().value())
                || session.clientTick() < frame.orElseThrow().frameCompletedTick()
                || session.clientTick() - frame.orElseThrow().frameCompletedTick() > 2L) {
            return null;
        }
        for (ObservationRecord record : frame.orElseThrow().records()) {
            if (!(record instanceof ObservationRecord.VisibleEntity visible)
                    || visible.entityRef() == null
                    || visible.observedTick() + 2L < session.clientTick()
                    || !visible.entityType().value().equals(KillZoneSafety.entityType(target))) continue;
            Optional<Entity> resolved = observations.resolveLoadedEntityRefIdentity(
                    minecraft, session.clientTick(), session.worldSessionId(), session.dimension(),
                    visible.entityRef(), minecraft.player.entityInteractionRange() + 1.0D);
            if (resolved.orElse(null) == target) {
                return new KillZoneTarget(target, visible.entityRef());
            }
        }
        return null;
    }

    private void recordKillZoneAttackEffect(
            WorldSessionTracker.Snapshot session,
            KillZoneAttackAttempt attempt,
            AgentActionStore.Verification verification,
            float healthAfter,
            String outcome) {
        long revision = reconciliationSignals.bindAndSnapshot(
                Objects.requireNonNull(Minecraft.getInstance().level, "level"),
                session.worldSessionId()).worldRevision();
        agentActions.recordEffect(
                actionId,
                "entity_attack",
                "refhash:" + RoutineIdentity.sha256Identity(new StringBuilder(attempt.entityRef))
                        .substring("sha256:".length()),
                Map.of("entity_type", KillZoneSafety.entityType(attempt.target), "health", attempt.healthBefore),
                Map.of("health", healthAfter, "outcome", outcome),
                verification,
                session.clientTick(),
                revision);
    }

    private PrimitiveOutcome finishKillZone(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            long latestWorldRevision,
            String reason) {
        if (this.confirmedAttacks < 1) {
            return PrimitiveOutcome.failed(
                    AgentActionStore.FailureCode.CONDITION_TIMEOUT,
                    true,
                    "kill_zone_no_confirmed_attack");
        }
        long revision = reconciliationSignals.bindAndSnapshot(
                Objects.requireNonNull(minecraft.level, "level"),
                session.worldSessionId()).worldRevision();
        agentActions.recordEffect(
                actionId, "kill_zone_summary", "operation",
                Map.of("max_attacks", this.scope.maxAttacks()),
                Map.of(
                        "dispatched_attacks", this.dispatchedAttacks,
                        "confirmed_attacks", this.confirmedAttacks,
                        "unknown_attacks", this.unknownAttacks,
                        "completion_reason", reason),
                AgentActionStore.Verification.CONFIRMED,
                session.clientTick(), revision);
        return PrimitiveOutcome.succeeded();
    }

    PrimitiveOutcome safetyInterruptKillZone(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            long latestWorldRevision,
            String reason) {
        AgentInputState.global().releaseAttack();
        closePendingEffect(session, latestWorldRevision);
        float current = minecraft.player == null ? 0.0F : minecraft.player.getHealth();
        float currentAbsorption = minecraft.player == null
                ? 0.0F : minecraft.player.getAbsorptionAmount();
        float currentEffective = current + currentAbsorption;
        long revision = minecraft.level == null ? 0L : reconciliationSignals.bindAndSnapshot(
                minecraft.level, session.worldSessionId()).worldRevision();
        agentActions.recordEffect(
                actionId, "safety_interrupted", "player",
                Map.of(
                        "health_before", this.lastHealth,
                        "absorption_before", this.lastAbsorption,
                        "effective_health_before", this.lastEffectiveHealth,
                        "effective_health_previous", this.lastEffectiveHealth),
                Map.of(
                        "health_current", current,
                        "absorption_current", currentAbsorption,
                        "effective_health_current", currentEffective,
                        "health_delta", currentEffective - this.lastEffectiveHealth,
                        "dispatched_attacks", this.dispatchedAttacks,
                        "confirmed_attacks", this.confirmedAttacks,
                        "unknown_attacks", this.unknownAttacks,
                        "reason", reason),
                AgentActionStore.Verification.CONFIRMED,
                session.clientTick(), revision);
        return PrimitiveOutcome.failed(
                AgentActionStore.FailureCode.SAFETY_INTERRUPTED,
                false,
                reason);
    }

    private static boolean armorStandHitConfirmed(KillZoneAttackAttempt attempt) {
        return KillZoneSafety.armorStandHitEventAdvanced(
                attempt.armorStandLastHitBefore, KillZoneSafety.armorStandLastHit(attempt.target));
    }

    boolean healthDecreased(Player player) {
        float currentHealth = player.getHealth();
        float currentAbsorption = player.getAbsorptionAmount();
        if (KillZoneSafety.healthDecreased(
                this.lastHealth,
                this.lastAbsorption,
                currentHealth,
                currentAbsorption)) {
            return true;
        }
        this.lastHealth = currentHealth;
        this.lastAbsorption = currentAbsorption;
        this.lastEffectiveHealth = currentHealth + currentAbsorption;
        return false;
    }

    void closePendingEffect(WorldSessionTracker.Snapshot session, long latestWorldRevision) {
        if (pending == null) return;
        KillZoneAttackAttempt attempt = this.pending;
        this.pending = null;
        boolean armorStandHit = armorStandHitConfirmed(attempt);
        boolean confirmed = armorStandHit || attempt.target.getHealth() < attempt.healthBefore
                || (!attempt.target.isAlive() && attempt.target.getHealth() <= 0.0F);
        if (confirmed) this.confirmedAttacks++;
        else {
            this.unknownAttacks++;
            this.noRetryEntityIds.add(attempt.target.getUUID());
        }
        agentActions.recordEffect(
                actionId,
                "entity_attack",
                "refhash:" + RoutineIdentity.sha256Identity(new StringBuilder(attempt.entityRef))
                        .substring("sha256:".length()),
                Map.of("entity_type", KillZoneSafety.entityType(attempt.target), "health", attempt.healthBefore),
                Map.of(
                        "health", attempt.target.getHealth(),
                        "outcome", armorStandHit ? "armor_stand_hit_event"
                                : confirmed ? "terminal_confirmed" : "terminal_unknown"),
                confirmed ? AgentActionStore.Verification.CONFIRMED
                        : AgentActionStore.Verification.UNKNOWN,
                Math.max(0L, session.clientTick()),
                Math.max(0L, latestWorldRevision));
    }

    private static final class KillZoneAttackAttempt {
        private final LivingEntity target;
        private final String entityRef;
        private final float healthBefore;
        private final long armorStandLastHitBefore;
        private final long effectDeadlineTick;

        private KillZoneAttackAttempt(
                LivingEntity target,
                String entityRef,
                float healthBefore,
                long armorStandLastHitBefore,
                long dispatchTick) {
            this.target = Objects.requireNonNull(target, "target");
            this.entityRef = Objects.requireNonNull(entityRef, "entityRef");
            this.healthBefore = healthBefore;
            this.armorStandLastHitBefore = armorStandLastHitBefore;
            effectDeadlineTick = Math.addExact(
                    dispatchTick, KillZoneExecution.EFFECT_DEADLINE_TICKS);
        }
    }

    private record KillZoneTarget(LivingEntity entity, String entityRef) {
        private KillZoneTarget {
            Objects.requireNonNull(entity, "entity");
            Objects.requireNonNull(entityRef, "entityRef");
        }
    }

}
