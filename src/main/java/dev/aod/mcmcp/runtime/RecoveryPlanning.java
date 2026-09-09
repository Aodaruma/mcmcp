package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner;
import dev.aod.mcmcp.agent.action.MinecraftActionPrimitiveExecutor;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.navigation.KnownTraversabilitySnapshot;
import dev.aod.mcmcp.agent.navigation.RoutePlan;
import dev.aod.mcmcp.agent.observation.ObservationRecord;
import dev.aod.mcmcp.agent.safety.LocalObservationVolume;
import dev.aod.mcmcp.agent.safety.MinecraftRecoveryGovernor;
import dev.aod.mcmcp.client.AgentInputState;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.world.phys.Vec3;

/** 局所安全スナップショットから有限の回復候補と期限を計算する。 */
final class RecoveryPlanning {
    private RecoveryPlanning() {}

    static long agentReplanWindowTicks(ActionDsl.Node primitive) {
        return KnownBreakSafety.isKnownBreak(primitive)
                ? AgentPrimitivePlanner.BREAK_REOBSERVATION_TICKS
                : 20L;
    }

    static long agentReplanDeadlineTick(
            ActionDsl.Node primitive,
            long actionTick,
            long occurrenceStartTick,
            long occurrenceTickLimit) {
        long observationDeadline = Math.addExact(actionTick, agentReplanWindowTicks(primitive));
        if (!(primitive instanceof ActionDsl.NavigateToKnown)
                && !(primitive instanceof ActionDsl.ApproachKnownSurface)
                && !(primitive instanceof ActionDsl.ApproachKnownPlacement)
                && !(primitive instanceof ActionDsl.CollectVisibleItem)) {
            return observationDeadline;
        }
        long admittedNavigationDeadline = Math.addExact(
                occurrenceStartTick, Math.addExact(occurrenceTickLimit, 1L));
        return Math.max(observationDeadline, admittedNavigationDeadline);
    }

    static long recoveryEvidenceClientTick(WorldSessionTracker.Snapshot session) {
        Objects.requireNonNull(session, "session");
        if (!session.worldReady() || session.clientTick() < 0L) {
            throw new IllegalStateException("recovery evidence requires a ready world clock");
        }
        return session.clientTick();
    }

    static RecoveryHazards recoveryHazards(
            dev.aod.mcmcp.agent.safety.ObservationRecord.Fluid fluid,
            dev.aod.mcmcp.agent.safety.ObservationRecord.Hazard hazard,
            boolean playerInLava,
            boolean onGround,
            double verticalVelocity,
            double descentSinceGround) {
        boolean observedDangerousFall = hazard
                == dev.aod.mcmcp.agent.safety.ObservationRecord.Hazard.FALL;
        return new RecoveryHazards(
                playerInLava
                        || fluid == dev.aod.mcmcp.agent.safety.ObservationRecord.Fluid.LAVA,
                onGround && !observedDangerousFall,
                observedDangerousFall ? Math.min(-0.081D, verticalVelocity) : verticalVelocity,
                observedDangerousFall
                        ? Math.max(Math.nextUp(3.0D), descentSinceGround)
                        : Math.max(0.0D, descentSinceGround));
    }

    record RecoveryHazards(
            boolean inLava,
            boolean onGround,
            double verticalVelocity,
            double descentSinceGround) {
    }

    static final class RecoveryDescentTracker {
        private Object playerIdentity;
        private Object levelIdentity;
        private UUID worldSessionId;
        private double lastY;
        private double descent;
        private long correctionRevision;

        double update(
                Object player,
                Object level,
                UUID sessionId,
                double y,
                boolean onGround,
                boolean safeWater,
                long currentCorrectionRevision) {
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(sessionId, "sessionId");
            if (!Double.isFinite(y) || currentCorrectionRevision < 0L) {
                throw new IllegalArgumentException("descent evidence must be finite");
            }
            if (playerIdentity != player
                    || levelIdentity != level
                    || !sessionId.equals(worldSessionId)) {
                playerIdentity = player;
                levelIdentity = level;
                worldSessionId = sessionId;
                lastY = y;
                descent = 0.0D;
                correctionRevision = currentCorrectionRevision;
            } else {
                if (currentCorrectionRevision == correctionRevision) {
                    descent += Math.max(0.0D, lastY - y);
                }
                lastY = y;
                correctionRevision = currentCorrectionRevision;
            }
            if (onGround || safeWater) {
                descent = 0.0D;
            }
            return descent;
        }

        double current(Object player, Object level, UUID sessionId) {
            return playerIdentity == player
                            && levelIdentity == level
                            && Objects.equals(worldSessionId, sessionId)
                    ? descent : 0.0D;
        }

        void reset() {
            playerIdentity = null;
            levelIdentity = null;
            worldSessionId = null;
            lastY = 0.0D;
            descent = 0.0D;
            correctionRevision = 0L;
        }
    }

    static int effectDuration(
            net.minecraft.client.player.LocalPlayer player,
            Holder<net.minecraft.world.effect.MobEffect> effect) {
        var instance = player.getEffect(effect);
        return instance == null ? 0
                : instance.isInfiniteDuration()
                ? Integer.MAX_VALUE
                : Math.max(0, instance.getDuration());
    }

    static List<MinecraftRecoveryGovernor.Candidate> recoveryCandidates(
            Minecraft minecraft,
            net.minecraft.client.player.LocalPlayer player,
            KnownTraversabilitySnapshot map,
            MinecraftRecoveryGovernor.Evidence evidence,
            boolean continuingRecovery) {
        var candidates = new ArrayList<MinecraftRecoveryGovernor.Candidate>();
        var threat = recoveryThreat(player);
        var currentHazard = LocalObservationVolume.global().latestFor(player)
                .map(snapshot -> snapshot.current().hazard())
                .orElse(dev.aod.mcmcp.agent.safety.ObservationRecord.Hazard.UNKNOWN);
        var currentCenter = player.getBoundingBox().getCenter();
        boolean lavaRecovery = evidence.inLava()
                || continuingRecovery
                        && !evidence.onGround()
                        && evidence.landing() == MinecraftRecoveryGovernor.Landing.KNOWN_LAVA;
        boolean continuingLavaEscape = lavaRecovery && !evidence.inLava();
        for (var option : LocalObservationVolume.global()
                .recoveryOptions(player, map.worldRevision())) {
            var endpoint = option.endpoint();
            var target = new net.minecraft.world.phys.Vec3(
                    option.target().x(), option.target().y(), option.target().z());
            var movement = EnumSet.noneOf(dev.aod.mcmcp.routine.MovementInputLease.MovementKey.class);
            movement.addAll(MinecraftActionPrimitiveExecutor.steering(
                    player.getX(),
                    player.getZ(),
                    player.getYRot(),
                    target.x,
                    target.z,
                    0.05D));
            if (requiresRecoveryJump(currentCenter.y, target)) {
                movement.add(dev.aod.mcmcp.routine.MovementInputLease.MovementKey.JUMP);
            }
            if (movement.isEmpty()) continue;
            boolean dryStable = recoveryDryStable(endpoint);
            boolean waterStable = endpoint.loaded()
                    == dev.aod.mcmcp.agent.safety.ObservationRecord.LoadedState.LOADED
                    && endpoint.clearance()
                    == dev.aod.mcmcp.agent.safety.ObservationRecord.Clearance.CLEAR
                    && endpoint.fluid()
                    == dev.aod.mcmcp.agent.safety.ObservationRecord.Fluid.WATER
                    && !endpoint.suffocation()
                    && endpoint.hazard()
                    == dev.aod.mcmcp.agent.safety.ObservationRecord.Hazard.NONE;
            boolean avoidsNewDamage = LocalObservationVolume.avoidsNewDamageHazard(
                    currentHazard, option.path().hazard(), endpoint.hazard());
            if (evidence.suffocating() && (dryStable || waterStable)) {
                addRecoveryCandidate(
                        candidates,
                        player,
                        map,
                        recoveryCandidateId("free", target),
                        MinecraftRecoveryGovernor.CandidateKind.BACK_TO_FREE_AABB,
                        AgentInputState.RecoveryMode.ESCAPE_SUFFOCATION,
                        target,
                        null,
                        movement,
                        true,
                        true);
            }
            if ((currentHazard
                            == dev.aod.mcmcp.agent.safety.ObservationRecord.Hazard.FIRE_DAMAGE
                    || currentHazard
                            == dev.aod.mcmcp.agent.safety.ObservationRecord.Hazard.CONTACT_DAMAGE
                    || currentHazard
                            == dev.aod.mcmcp.agent.safety.ObservationRecord.Hazard.FREEZING)
                    && dryStable) {
                addRecoveryCandidate(
                        candidates,
                        player,
                        map,
                        recoveryCandidateId("surface-exit", target),
                        MinecraftRecoveryGovernor.CandidateKind.RETREAT_TO_KNOWN_SAFE,
                        AgentInputState.RecoveryMode.EXIT_DAMAGE_SURFACE,
                        target,
                        null,
                        movement,
                        true,
                        true);
            }
            if (lavaRecovery && avoidsNewDamage && (dryStable || waterStable)
                    && endpoint.fluid()
                    != dev.aod.mcmcp.agent.safety.ObservationRecord.Fluid.LAVA) {
                addRecoveryCandidate(
                        candidates,
                        player,
                        map,
                        recoveryCandidateId("lava-exit", target),
                        MinecraftRecoveryGovernor.CandidateKind.EXIT_HAZARDOUS_FLUID,
                        continuingLavaEscape
                                ? AgentInputState.RecoveryMode.CONTINUE_LAVA_ESCAPE
                                : AgentInputState.RecoveryMode.EXIT_LAVA,
                        target,
                        null,
                        movement,
                        true,
                        true);
            }
            if (lavaRecovery
                    && !continuingLavaEscape
                    && target.y > currentCenter.y
                    && endpoint.loaded()
                    == dev.aod.mcmcp.agent.safety.ObservationRecord.LoadedState.LOADED
                    && endpoint.clearance()
                    == dev.aod.mcmcp.agent.safety.ObservationRecord.Clearance.CLEAR
                    && endpoint.fluid()
                    == dev.aod.mcmcp.agent.safety.ObservationRecord.Fluid.LAVA
                    && !endpoint.suffocation()) {
                addRecoveryCandidate(
                        candidates,
                        player,
                        map,
                        recoveryCandidateId("lava-progress", target),
                        MinecraftRecoveryGovernor.CandidateKind.EXIT_HAZARDOUS_FLUID,
                        AgentInputState.RecoveryMode.EXIT_LAVA,
                        target,
                        null,
                        movement,
                        false,
                        false);
            }
            if (evidence.underwater()
                    && endpoint.loaded()
                    == dev.aod.mcmcp.agent.safety.ObservationRecord.LoadedState.LOADED
                    && endpoint.fluid()
                    != dev.aod.mcmcp.agent.safety.ObservationRecord.Fluid.LAVA
                    && endpoint.fluid()
                    != dev.aod.mcmcp.agent.safety.ObservationRecord.Fluid.UNKNOWN
                    && !endpoint.suffocation()
                    && endpoint.hazard()
                    == dev.aod.mcmcp.agent.safety.ObservationRecord.Hazard.NONE) {
                boolean reachesAir = endpoint.fluid()
                        == dev.aod.mcmcp.agent.safety.ObservationRecord.Fluid.NONE;
                if (reachesAir || target.y > currentCenter.y) {
                    addRecoveryCandidate(
                            candidates,
                            player,
                            map,
                            recoveryCandidateId("air", target),
                            MinecraftRecoveryGovernor.CandidateKind.REACH_BREATHING_SPACE,
                            AgentInputState.RecoveryMode.REACH_BREATHING_SPACE,
                            target,
                            null,
                            movement,
                            reachesAir,
                            reachesAir);
                }
            }
            if (evidence.onFire() && waterStable && avoidsNewDamage) {
                addRecoveryCandidate(
                        candidates,
                        player,
                        map,
                        recoveryCandidateId("water", target),
                        MinecraftRecoveryGovernor.CandidateKind.EXIT_HAZARDOUS_FLUID,
                        AgentInputState.RecoveryMode.ENTER_WATER,
                        target,
                        null,
                        movement,
                        true,
                        true);
            }
            if (!evidence.onGround()
                    && evidence.verticalVelocity() < -0.08D
                    && option.landing()
                    && dryStable) {
                addRecoveryCandidate(
                        candidates,
                        player,
                        map,
                        recoveryCandidateId("landing", target),
                        MinecraftRecoveryGovernor.CandidateKind.STEER_TO_KNOWN_LANDING,
                        AgentInputState.RecoveryMode.STEER_TO_LANDING,
                        target,
                        null,
                        movement,
                        true,
                        true);
            }
            if (threat != null && dryStable
                    && fartherFromThreat(player.position(), target, threat)) {
                addRecoveryCandidate(
                        candidates,
                        player,
                        map,
                        recoveryCandidateId("retreat", target),
                        MinecraftRecoveryGovernor.CandidateKind.RETREAT_FROM_THREAT,
                        AgentInputState.RecoveryMode.RETREAT_FROM_THREAT,
                        target,
                        threat,
                        movement,
                        false,
                        true);
            }
        }
        return List.copyOf(candidates);
    }

    static void addRecoveryCandidate(
            List<MinecraftRecoveryGovernor.Candidate> candidates,
            net.minecraft.client.player.LocalPlayer player,
            KnownTraversabilitySnapshot map,
            String id,
            MinecraftRecoveryGovernor.CandidateKind kind,
            AgentInputState.RecoveryMode mode,
            net.minecraft.world.phys.Vec3 target,
            net.minecraft.world.phys.Vec3 threat,
            Set<dev.aod.mcmcp.routine.MovementInputLease.MovementKey> movement,
            boolean preventsFatalHarm,
            boolean reachesStableState) {
        var currentCenter = player.getBoundingBox().getCenter();
        double distance = recoveryDistance(currentCenter, target);
        if (distance <= 0.0D || movement.isEmpty()) return;
        candidates.add(new MinecraftRecoveryGovernor.Candidate(
                id,
                map.worldSessionId(),
                map.dimension(),
                map.worldRevision(),
                kind,
                movement,
                new AgentInputState.RecoveryIntent(mode, target, threat),
                true,
                true,
                preventsFatalHarm,
                true,
                reachesStableState,
                Math.max(1, (int) Math.ceil(distance * RoutePlan.TICKS_PER_TRANSITION)),
                distance,
                distance));
    }

    static boolean requiresRecoveryJump(double currentCenterY, net.minecraft.world.phys.Vec3 target) {
        return target.y > currentCenterY + 0.1D;
    }

    static double recoveryDistance(
            net.minecraft.world.phys.Vec3 currentCenter,
            net.minecraft.world.phys.Vec3 target) {
        return Math.hypot(target.x - currentCenter.x, target.z - currentCenter.z)
                + Math.max(0.0D, target.y - currentCenter.y);
    }

    static String recoveryCandidateId(String prefix, net.minecraft.world.phys.Vec3 target) {
        return prefix + "-"
                + recoveryTargetCoordinate(target.x) + "_"
                + recoveryTargetCoordinate(target.y) + "_"
                + recoveryTargetCoordinate(target.z);
    }

    static long recoveryTargetCoordinate(double coordinate) {
        return (long) Math.floor(coordinate * 4.0D);
    }

    static boolean recoveryDryStable(LocalObservationVolume.EndpointSafety endpoint) {
        return endpoint.loaded()
                        == dev.aod.mcmcp.agent.safety.ObservationRecord.LoadedState.LOADED
                && endpoint.clearance()
                        == dev.aod.mcmcp.agent.safety.ObservationRecord.Clearance.CLEAR
                && endpoint.support()
                        == dev.aod.mcmcp.agent.safety.ObservationRecord.Support.PRESENT
                && endpoint.fluid()
                        == dev.aod.mcmcp.agent.safety.ObservationRecord.Fluid.NONE
                && !endpoint.suffocation()
                && endpoint.hazard()
                        == dev.aod.mcmcp.agent.safety.ObservationRecord.Hazard.NONE;
    }

    static net.minecraft.world.phys.Vec3 recoveryThreat(
            net.minecraft.client.player.LocalPlayer player) {
        var source = player.getLastDamageSource();
        if (source == null) return null;
        var raw = source.sourcePositionRaw();
        if (raw != null) return raw;
        var causing = source.getEntity();
        if (causing != null && !causing.isRemoved()) return causing.position();
        var direct = source.getDirectEntity();
        return direct == null || direct.isRemoved()
                || direct.position().distanceToSqr(player.position()) <= 1.0E-6D
                ? null : direct.position();
    }

    static boolean fartherFromThreat(
            net.minecraft.world.phys.Vec3 current,
            net.minecraft.world.phys.Vec3 target,
            net.minecraft.world.phys.Vec3 threat) {
        double currentDistance = Math.hypot(current.x - threat.x, current.z - threat.z);
        double targetDistance = Math.hypot(target.x - threat.x, target.z - threat.z);
        return targetDistance > currentDistance + 1.0E-7D;
    }
}
